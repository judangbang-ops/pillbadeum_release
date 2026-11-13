package com.judangbang.pillbadeum.reminder

import android.content.Context
import android.content.Intent
import android.util.Log
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.container
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.status.ReminderContent
import com.judangbang.pillbadeum.status.ReminderLabels
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.ReminderNotice
import com.judangbang.pillbadeum.usecase.ShownReminder
import com.judangbang.pillbadeum.usecase.SnoozeResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the one reminder alarm and the reminder notifications in line with the database (TASK-024, TASK-025,
 * TASK-027, TASK-040). The order the app keeps (supplement N-13 '구현'): `nextAlarm` after every change of N-9,
 * `fire` when the alarm rings and on the first run after a boot, clock or time zone change (N-10, N-12), and
 * the alarm armed with whatever came back, even a past time; null cancels it.
 *
 * Each step runs under one lock, so an alarm planned from older data is never armed after a newer one. A
 * failure is logged and never thrown at the caller: a reminder problem must not break a record.
 */
object Reminders {
    private const val TAG = "Reminders"

    /** How long "이미 기록됨 (08:02)" stays before it closes (SCR-003#already; Claude default). */
    private val RESULT_TIMEOUT = 10.seconds

    private val lock = Mutex()

    /** N-9 changes that come with no write of their own (process start, exact-alarm permission change). */
    suspend fun replan(context: Context) = step(context, "re-plan") {
        replanLocked(context, it)
    }

    /**
     * After every write (`AppContainer.afterWrite`) and the day boundary: the next alarm (N-9) and the
     * notifications on screen (N-13 judged, ended, archived or switched off; SPEC-034 names on the lock
     * screen; R-3 the restore notice after [확인했어요]).
     */
    suspend fun afterWrite(context: Context) = step(context, "after write") {
        replanLocked(context, it)
        syncLocked(context, it)
    }

    /** The alarm rang, or the first run after a boot, clock or time zone change: overdue slots, one per item (N-12). */
    suspend fun collect(context: Context) = step(context, "collect") { core ->
        val run = core.planReminders.fire()
        ReminderAlarm.arm(context, run.nextAlarmAt)
        if (run.notices.isNotEmpty()) show(context, core, run.notices)
        syncLocked(context, core)
    }

    /**
     * A restore was detected (R-3, TASK-040): no dose reminder until [확인했어요], only the one restore notice,
     * shown now and not again. `nextAlarm` returns null while the notice is pending, so the alarm goes too.
     */
    suspend fun restored(context: Context) = step(context, "restore") { core ->
        ReminderAlarm.arm(context, core.planReminders.nextAlarm())
        ReminderNotifications.cancelAllDoses(context)
        ReminderNotifications.showRestoreNotice(context)
    }

    /** A button of an item's reminder (REQ-045, REQ-088, SPEC-011, SPEC-012), then [afterWrite] through the container. */
    suspend fun onButton(context: Context, intent: Intent) {
        val content = ReminderNotifications.contentOf(intent) ?: return
        step(context, "button ${intent.action}") { core ->
            when (intent.action) {
                ReminderReceiver.ACTION_TAKE -> record(context, core, content, DoseKind.TAKEN)
                ReminderReceiver.ACTION_SKIP -> record(context, core, content, DoseKind.SKIPPED)
                ReminderReceiver.ACTION_SNOOZE -> snooze(context, core, content)
            }
        }
        // Widgets, the next alarm and the other notifications follow the write (REQ-043).
        context.container.afterWrite()
    }

    /** The person swiped a reminder away: it rings again at the next slot (REQ-042); the lock notice may go (N-13). */
    suspend fun onDismissed(context: Context) = step(context, "dismissed") {
        syncLocked(context, it)
    }

    private suspend fun replanLocked(context: Context, core: DidITakeCore) {
        ReminderAlarm.arm(context, core.planReminders.nextAlarm())
    }

    private suspend fun show(context: Context, core: DidITakeCore, notices: List<ReminderNotice>) {
        if (!ReminderNotifications.canPostDoses(context)) {
            // SCR-003#permission (notifications refused or off, or the dose channel off): nothing shows, not even the
            // lock-screen notice on the other channel; the app's guidance says why (REQ-089, TASK-026).
            Log.i(TAG, "dose reminders are off: ${notices.size} reminder(s) not shown")
            return
        }
        val board = core.loadStatusBoard()
        val contents = notices.mapNotNull { ReminderLabels.content(board, it) }
        ReminderNotifications.postDoses(context, contents, core.settings.showNamesOnLock(), board.now)
        Log.i(TAG, "${contents.size} reminder(s) shown")
    }

    private suspend fun syncLocked(context: Context, core: DidITakeCore) {
        val posted = ReminderNotifications.shownDoses(context)
        val review = core.planReminders.review(posted.map { ShownReminder(it.content.intakeId, it.content.periodKey) })
        val cleared = review.clear.mapTo(HashSet()) { it.intakeId }
        for (intakeId in cleared) ReminderNotifications.cancelDose(context, intakeId)
        if (!review.restorePending) ReminderNotifications.cancelRestoreNotice(context)
        val showNames = core.settings.showNamesOnLock()
        val now = core.clock.now()
        val remaining = posted.filter { it.content.intakeId !in cleared }
        for (dose in remaining) {
            if (dose.visibility != ReminderNotifications.visibilityFor(showNames)) {
                ReminderNotifications.repost(context, dose.content, showNames, now)
            }
        }
        ReminderNotifications.syncLockNotice(context, remaining.map { it.content }, showNames, now)
    }

    private suspend fun record(context: Context, core: DidITakeCore, content: ReminderContent, kind: DoseKind) {
        val result = core.record(content.intakeId, DoseSource.NOTIFICATION, content.periodKey, kind)
        val showNames = core.settings.showNamesOnLock()
        when (result) {
            is RecordResult.Recorded, RecordResult.Gone -> ReminderNotifications.cancelDose(context, content.intakeId)
            is RecordResult.Already -> {
                val text = ReminderLabels.already(result.existing, core.clock.now(), core.windowSettings().baseZone)
                ReminderNotifications.showResult(context, content, text, showNames, RESULT_TIMEOUT)
            }
            is RecordResult.Stale -> ReminderNotifications.showResult(context, content, ReminderLabels.STALE, showNames, null)
            RecordResult.Locked, is RecordResult.Error -> {
                Log.w(TAG, "notification record failed: $result")
                ReminderNotifications.showResult(context, content, ReminderLabels.ERROR, showNames, null)
            }
        }
    }

    private suspend fun snooze(context: Context, core: DidITakeCore, content: ReminderContent) {
        val showNames = core.settings.showNamesOnLock()
        when (val result = core.reminders.snooze(content.intakeId, content.periodKey)) {
            // The reminder closes and rings once more 30 minutes later; the re-plan in afterWrite arms it (N-7).
            is SnoozeResult.Snoozed, SnoozeResult.NotNeeded, SnoozeResult.Gone ->
                ReminderNotifications.cancelDose(context, content.intakeId)
            // Less than 30 minutes left in the window: nothing is written and the button goes (RR-85).
            SnoozeResult.TooLate ->
                ReminderNotifications.repost(context, content.copy(canSnooze = false), showNames, core.clock.now())
            SnoozeResult.Stale -> ReminderNotifications.showResult(context, content, ReminderLabels.STALE, showNames, null)
            is SnoozeResult.Error -> {
                Log.w(TAG, "snooze failed: ${result.message}")
                ReminderNotifications.showResult(context, content, ReminderLabels.ERROR, showNames, null)
            }
        }
    }

    private suspend fun step(context: Context, name: String, block: suspend (DidITakeCore) -> Unit) {
        try {
            lock.withLock { block(context.container.core()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$name failed", e)
        }
    }
}
