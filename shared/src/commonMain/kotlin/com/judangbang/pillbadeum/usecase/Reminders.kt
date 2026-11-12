package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.IntakeEntity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.data.ReminderRuleEntity
import com.judangbang.pillbadeum.data.ReminderSnoozeEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.loadReminderAlarmAt
import com.judangbang.pillbadeum.data.loadReminderLastRunAt
import com.judangbang.pillbadeum.data.loadReminderSettings
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.data.saveReminderAlarmAt
import com.judangbang.pillbadeum.data.saveReminderLastRunAt
import com.judangbang.pillbadeum.data.saveReminderSettings
import com.judangbang.pillbadeum.domain.QuietNotice
import com.judangbang.pillbadeum.domain.ReminderSettings
import com.judangbang.pillbadeum.domain.ReminderSlot
import com.judangbang.pillbadeum.domain.Window
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.defaultReminderStart
import com.judangbang.pillbadeum.domain.quietNotice
import com.judangbang.pillbadeum.domain.reminderSlots
import com.judangbang.pillbadeum.domain.windowOf
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.LocalTime

/** One reminder to show, one per item (supplement N-8, N-13). */
data class ReminderNotice(
    val intakeId: String,
    val intakeName: String,
    val person: PersonEntity,
    /** The item's current window; its period key goes into the notification's record button. */
    val window: Window,
    /** The grid slot or snooze time being shown (the latest one that was due, N-12). */
    val dueAt: Instant,
    /** [dueAt] is the item's [30분 뒤] time rather than a grid slot. */
    val snoozed: Boolean,
    /** Whether the notification offers [30분 뒤]: only while now + 30 minutes is before the window end (N-7). */
    val canSnooze: Boolean,
)

/** Result of a reminder run: what to show now and when the one alarm should ring next (N-8). */
data class ReminderRun(val notices: List<ReminderNotice>, val nextAlarmAt: Instant?)

/**
 * The reminder plan (record-rules-supplement.md N-4~N-8, N-12; TASK-011). Computed from the settings and
 * the clock alone: no reminder count is stored. One alarm serves every item (N-8).
 *
 * - [nextAlarm]: after any change of N-9. Only finds the next alarm; overdue slots are not shown and
 *   `reminder_last_run_at` is only repaired when missing or in the future (N-12). The one exception keeps
 *   an alarm from being lost: when the alarm planned last (`reminder_alarm_at`) is already due but [fire]
 *   has not run since, that time is returned as it is, at or before now, so the app rings at once.
 * - [fire]: when the alarm rings, and on the first run after boot, clock or time-zone change (N-10). Shows,
 *   per item, the latest slot due in (`reminder_last_run_at`, now] unless it is more than one interval old,
 *   then sets `reminder_last_run_at` to now and finds the next alarm.
 *
 * The app arms its one alarm with whatever either call returns, even a time already past, and cancels it on
 * null. Every call also stores that time in `reminder_alarm_at`. The first call ever sets
 * `reminder_last_run_at` to now, so nothing before it is shown.
 *
 * An item is reminded only while its current window has no active judgment (N-6), checked here, at the
 * moment of showing. Slots before the item's reminders were switched on or its start time changed are not
 * shown (RR-79). Snoozes that no longer apply (judged window, new period key, item or person archived,
 * reminders switched off) are deleted on the way (N-7).
 */
class PlanReminders(
    private val db: DidITakeDatabase,
    private val clock: Clock,
) {
    suspend fun nextAlarm(): Instant? =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val now = clock.now()
                val settings = db.settingDao()
                val lastRun = settings.loadReminderLastRunAt(now)
                // The planned alarm is late (Doze) or its receiver has not run yet: keep it so fire() collects it.
                val planned = settings.loadReminderAlarmAt()
                if (planned != null && planned > lastRun && planned <= now) return@immediateTransaction planned
                nextAlarmAt(loadItems(now), now).also { settings.saveReminderAlarmAt(it) }
            }
        }

    suspend fun fire(): ReminderRun =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val now = clock.now()
                val settings = db.settingDao()
                val lastRun = settings.loadReminderLastRunAt(now)
                val items = loadItems(now)
                val notices = items.mapNotNull { item -> item.due(lastRun, now) }
                for (item in items) {
                    val snooze = item.snooze ?: continue
                    if (Instant.fromEpochMilliseconds(snooze.fireAt) <= now) db.reminderDao().deleteSnooze(item.intake.id)
                }
                settings.saveReminderLastRunAt(now)
                // A snooze that was just shown is at or before now, so it is no longer a next time.
                val next = nextAlarmAt(items, now)
                settings.saveReminderAlarmAt(next)
                ReminderRun(notices, next)
            }
        }

    private fun nextAlarmAt(items: List<Item>, now: Instant): Instant? = items.mapNotNull { it.next(now) }.minOrNull()

    private suspend fun loadItems(now: Instant): List<Item> {
        val settings = db.settingDao().loadWindowSettings()
        val reminders = db.settingDao().loadReminderSettings()
        val rules = db.reminderDao().listEnabledRules()
        val enabled = rules.mapTo(HashSet()) { it.intakeId }
        for (snooze in db.reminderDao().listSnoozes()) {
            if (snooze.intakeId !in enabled) db.reminderDao().deleteSnooze(snooze.intakeId)
        }
        return rules.mapNotNull { rule -> loadItem(rule, now, settings, reminders) }
    }

    private suspend fun loadItem(
        rule: ReminderRuleEntity,
        now: Instant,
        settings: WindowSettings,
        reminders: ReminderSettings,
    ): Item? {
        val intake = db.intakeDao().findById(rule.intakeId) ?: return null
        val person = db.personDao().findById(intake.personId) ?: return null
        val versions = db.scheduleVersionDao()
        val version = versions.findEffective(intake.id, now.toEpochMilliseconds()) ?: return null
        val window = windowOf(ScheduleParams.toRule(version), now, settings)
        val judged = db.doseRecordDao().findActiveIn(intake.id, window) != null
        var snooze = db.reminderDao().findSnooze(intake.id)
        if (snooze != null && (judged || snooze.periodKey != window.periodKey)) {
            db.reminderDao().deleteSnooze(intake.id)
            snooze = null
        }
        val start = LocalTime.parse(rule.startTime)
        val nextRule = versions.findEffective(intake.id, window.end.toEpochMilliseconds())
            ?.let(ScheduleParams::toRule) ?: ScheduleParams.toRule(version)
        val nextWindow = windowOf(nextRule, window.end, settings)
        // A rule saved with the clock ahead does not hide slots (Claude's choice, like reminder_last_run_at).
        val since = Instant.fromEpochMilliseconds(rule.updatedAt).takeIf { it <= now }
        return Item(
            intake = intake,
            person = person,
            window = window,
            judged = judged,
            since = since,
            snooze = snooze,
            slots = reminderSlots(window, start, settings, reminders),
            nextWindowSlots = reminderSlots(nextWindow, start, settings, reminders),
            interval = reminders.interval,
        )
    }

    private class Item(
        val intake: IntakeEntity,
        val person: PersonEntity,
        val window: Window,
        val judged: Boolean,
        /** When the item's reminders were switched on or its start time changed; slots before it are not shown. */
        val since: Instant?,
        val snooze: ReminderSnoozeEntity?,
        val slots: List<ReminderSlot>,
        val nextWindowSlots: List<ReminderSlot>,
        val interval: Duration,
    ) {
        private val snoozeAt: Instant? = snooze?.fireAt?.let(Instant::fromEpochMilliseconds)

        /** Grid slots of this window that a pending snooze does not hold back (N-7). */
        private val gridTimes: List<Instant> = slots.map { it.at }.filter { snoozeAt == null || it >= snoozeAt }

        /** N-12: the latest due time in (lastRun, now], shown only when at most one interval old. */
        fun due(lastRun: Instant, now: Instant): ReminderNotice? {
            if (judged) return null
            val grid = gridTimes.filter { it > lastRun && it <= now && (since == null || it > since) }
            val snoozed = snoozeAt?.takeIf { it <= now }
            val latest = (grid + listOfNotNull(snoozed)).maxOrNull() ?: return null
            if (latest < now - interval) return null
            return ReminderNotice(
                intakeId = intake.id,
                intakeName = intake.name,
                person = person,
                window = window,
                dueAt = latest,
                snoozed = latest == snoozed,
                canSnooze = now + SNOOZE < window.end,
            )
        }

        /** The item's next due time after [now]: in this window unless judged, else the next window's first. */
        fun next(now: Instant): Instant? {
            if (!judged) {
                val inWindow = (gridTimes + listOfNotNull(snoozeAt)).filter { it > now }.minOrNull()
                if (inWindow != null) return inWindow
            }
            return nextWindowSlots.map { it.at }.filter { it > now }.minOrNull()
        }
    }

    companion object {
        /** [30분 뒤] (supplement N-7). */
        val SNOOZE: Duration = 30.minutes
    }
}

/** An item's reminder switch and first time, with the N-3 defaults when it has no row yet. */
data class ReminderRule(val intakeId: String, val enabled: Boolean, val start: LocalTime)

sealed interface SaveReminderResult {
    /** Saved; [notice] is the quiet-hours message to show, if any (N-4, REQ-077). Saving is never refused for it. */
    data class Saved(val notice: QuietNotice?) : SaveReminderResult

    /** The time has seconds. Nothing written. */
    data object Invalid : SaveReminderResult

    /** The item or its person is missing or archived. Nothing written. */
    data object Gone : SaveReminderResult
}

sealed interface SaveReminderSettingsResult {
    /** Saved; [notices] holds the quiet-hours message per item with reminders on, by intake id (N-4, REQ-077). */
    data class Saved(val notices: Map<String, QuietNotice>) : SaveReminderSettingsResult

    /** Not among the offered values (N-4). Nothing written. */
    data object Invalid : SaveReminderSettingsResult
}

sealed interface SnoozeResult {
    data class Snoozed(val fireAt: Instant) : SnoozeResult

    /** Now + 30 minutes is at or after the window end, so nothing is written (N-7, RR-85). */
    data object TooLate : SnoozeResult

    /** The window already has a judgment or the item's reminders are off. Nothing written. */
    data object NotNeeded : SnoozeResult

    /** The notification was for an earlier window; nothing written. */
    data object Stale : SnoozeResult

    data object Gone : SnoozeResult

    data class Error(val message: String) : SnoozeResult
}

/**
 * Reminder settings of items and the common settings, and [30분 뒤] (supplement N-1, N-3, N-4, N-7).
 * Callers re-plan with [PlanReminders.nextAlarm] after each change (N-9).
 */
class ManageReminders(
    private val db: DidITakeDatabase,
    private val clock: Clock,
    private val deviceId: String,
) {
    /** The item's rule; an item without a row reads as off with the default time of its label (N-3). */
    suspend fun rule(intakeId: String): ReminderRule? {
        val intake = db.intakeDao().findById(intakeId) ?: return null
        val row = db.reminderDao().findRule(intakeId)
        return ReminderRule(
            intakeId = intakeId,
            enabled = row?.enabled ?: false,
            start = row?.startTime?.let(LocalTime::parse) ?: defaultReminderStart(intake.slotLabel),
        )
    }

    suspend fun saveRule(intakeId: String, enabled: Boolean, start: LocalTime): SaveReminderResult =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (start.second != 0 || start.nanosecond != 0) return@immediateTransaction SaveReminderResult.Invalid
                val now = clock.now()
                val intake = db.intakeDao().findById(intakeId)
                val person = intake?.let { db.personDao().findById(it.personId) }
                if (intake == null || intake.archivedAt != null || person == null || person.archivedAt != null) {
                    return@immediateTransaction SaveReminderResult.Gone
                }
                val dao = db.reminderDao()
                val old = dao.findRule(intakeId)
                val nowMillis = now.toEpochMilliseconds()
                // Saving the same values writes nothing, so updated_at keeps when the rule last really changed.
                if (old == null || old.enabled != enabled || old.startTime != start.toString()) {
                    dao.upsertRule(
                        ReminderRuleEntity(
                            intakeId = intakeId,
                            enabled = enabled,
                            startTime = start.toString(),
                            createdAt = old?.createdAt ?: nowMillis,
                            updatedAt = nowMillis,
                            version = (old?.version ?: 0) + 1,
                            deviceId = deviceId,
                        ),
                    )
                }
                if (!enabled) dao.deleteSnooze(intakeId)
                val notice = if (enabled) {
                    quietNotice(start, db.settingDao().loadWindowSettings(), db.settingDao().loadReminderSettings())
                } else {
                    null
                }
                SaveReminderResult.Saved(notice)
            }
        }

    suspend fun settings(): ReminderSettings = db.settingDao().loadReminderSettings()

    /** Saves the common settings (N-1, N-4). */
    suspend fun saveSettings(settings: ReminderSettings): SaveReminderSettingsResult =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (!settings.isValid) return@immediateTransaction SaveReminderSettingsResult.Invalid
                db.settingDao().saveReminderSettings(settings)
                val windowSettings = db.settingDao().loadWindowSettings()
                val notices = db.reminderDao().listEnabledRules().mapNotNull { row ->
                    quietNotice(LocalTime.parse(row.startTime), windowSettings, settings)?.let { row.intakeId to it }
                }.toMap()
                SaveReminderSettingsResult.Saved(notices)
            }
        }

    /** [30분 뒤] from a notification for [renderedPeriodKey] (supplement N-7). Only the last snooze is kept. */
    suspend fun snooze(intakeId: String, renderedPeriodKey: String?): SnoozeResult =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val now = clock.now()
                val open = when (val lookup = db.lookUpWindow(intakeId, renderedPeriodKey, now)) {
                    is WindowLookup.Open -> lookup
                    is WindowLookup.Rejected -> return@immediateTransaction when (val result = lookup.result) {
                        RecordResult.Gone -> SnoozeResult.Gone
                        is RecordResult.Stale -> SnoozeResult.Stale
                        is RecordResult.Error -> SnoozeResult.Error(result.message)
                        else -> SnoozeResult.Error("unexpected lookup result $result")
                    }
                }
                val rule = db.reminderDao().findRule(intakeId)
                if (rule == null || !rule.enabled || db.doseRecordDao().findActiveIn(intakeId, open.window) != null) {
                    return@immediateTransaction SnoozeResult.NotNeeded
                }
                val fireAt = now + PlanReminders.SNOOZE
                if (fireAt >= open.window.end) return@immediateTransaction SnoozeResult.TooLate
                db.reminderDao().upsertSnooze(ReminderSnoozeEntity(intakeId, open.window.periodKey, fireAt.toEpochMilliseconds()))
                SnoozeResult.Snoozed(fireAt)
            }
        }
}
