package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.IntakeEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.ScheduleVersionEntity
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.data.loadReminderSettings
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.domain.LatePolicy
import com.judangbang.pillbadeum.domain.QuietNotice
import com.judangbang.pillbadeum.domain.ReminderSettings
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.domain.UuidV7
import com.judangbang.pillbadeum.domain.firstVersionStart
import com.judangbang.pillbadeum.domain.quietNotice
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalTime

/** An item's reminder switch and first time, as the item screen shows and saves them (SCR-006, supplement N-3). */
data class ReminderChoice(val enabled: Boolean, val start: LocalTime)

/**
 * What the registration screen submits.
 *
 * @property slotLabel display only, meaningful for daily items (아침/점심/저녁/자기 전).
 * @property reminder the item screen's reminder part (TASK-023); null leaves the item's reminder as it is.
 * @property medications null leaves saved rows alone; an empty list removes them (SPEC-048).
 */
data class IntakeDraft(
    val personId: String,
    val name: String,
    val widgetAlias: String? = null,
    val rule: ScheduleRule = ScheduleRule.Daily,
    val slotLabel: SlotLabel? = null,
    val reminder: ReminderChoice? = null,
    val medications: List<IntakeMedicationDraft>? = null,
) {
    /** Trimmed copy; a blank alias becomes null, weekly items carry no slot, a reminder time is kept to the minute. */
    fun normalized(): IntakeDraft = copy(
        name = name.trim(),
        widgetAlias = widgetAlias?.trim()?.takeIf { it.isNotEmpty() },
        slotLabel = if (rule is ScheduleRule.Daily) slotLabel else null,
        reminder = reminder?.let { it.copy(start = LocalTime(it.start.hour, it.start.minute)) },
        medications = medications?.map { it.copy(name = it.name.trim(), quantity = it.quantity.trim()) },
    )

    companion object {
        const val MAX_NAME_CHARACTERS = 30
    }
}

/** SCR-006#quiet: what quiet hours take from the reminder just saved, counted with [settings] (REQ-077, supplement N-4). */
data class SavedQuiet(val notice: QuietNotice, val settings: ReminderSettings)

sealed interface SaveIntakeResult {
    /**
     * Saved. [quiet] is set when the item's reminder was switched on or its time changed in this save and quiet hours
     * drop some of its repeats; saving is never refused for it (REQ-077).
     */
    data class Saved(val intakeId: String, val newScheduleVersion: Boolean, val quiet: SavedQuiet? = null) : SaveIntakeResult

    /** Nothing written. [reason] is a code from [Invalid.Companion]. */
    data class Invalid(val reason: String) : SaveIntakeResult {
        companion object {
            const val BLANK_NAME: String = "BLANK_NAME"
            const val BLANK_ALIAS: String = "BLANK_ALIAS"
            const val NAME_LIMIT: String = "NAME_LIMIT"
            const val UNKNOWN_PERSON: String = "UNKNOWN_PERSON"
            const val BLANK_MEDICATION: String = "BLANK_MEDICATION"
            const val MEDICATION_LIMIT: String = "MEDICATION_LIMIT"
            const val UNKNOWN_MEDICATION_ROW: String = "UNKNOWN_MEDICATION_ROW"
        }
    }

    /** The intake is missing or archived. */
    data object Gone : SaveIntakeResult
}

/**
 * Creates or edits an intake (flows.md section 1).
 *
 * - Create: intake row + first schedule_version in one transaction. The first version starts at the
 *   start of the window that holds the registration moment, so "this morning at 08:00" can still be
 *   recorded after registering in the afternoon (record-rules-supplement.md P-4, RR-41).
 * - Edit: display fields are updated in place; a changed schedule closes the current version at
 *   now (`effective_to`) and inserts a new one. Versions are never overwritten (data-model.md 2).
 * - Both write the draft's reminder and medication rows in the same transaction (SCR-006).
 * - Every new version stores `late_policy` = [NEW_VERSION_LATE_POLICY] (KEEP): the 1st release never
 *   moves a weekly anchor after a late dose (record-rules-supplement.md W-1, decision Q15, TASK-004).
 */
class SaveIntake(
    private val db: DidITakeDatabase,
    private val clock: Clock,
    private val ids: UuidV7,
    private val deviceId: String,
) {
    suspend fun loadMedications(intakeId: String): List<IntakeMedicationDraft> =
        db.intakeMedicationDao().listFields(intakeId).map { IntakeMedicationDraft(it.name, it.quantity.orEmpty(), it.id) }

    suspend fun create(draft: IntakeDraft): SaveIntakeResult {
        val d = draft.normalized()
        validateNames(d.name, draft.widgetAlias)?.let { return it }
        validateMedications(d)?.let { return it }
        if (d.medications.orEmpty().any { it.id != null }) {
            return SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.UNKNOWN_MEDICATION_ROW)
        }
        return db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                if (!activePerson(d.personId)) return@immediateTransaction SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.UNKNOWN_PERSON)
                val now = clock.now().toEpochMilliseconds()
                val intakeId = ids.next()
                db.intakeDao().insert(
                    IntakeEntity(
                        id = intakeId,
                        personId = d.personId,
                        name = d.name,
                        widgetAlias = d.widgetAlias,
                        slotLabel = d.slotLabel,
                        sortOrder = db.intakeDao().maxSortOrder() + 1,
                        createdAt = now,
                        updatedAt = now,
                        deviceId = deviceId,
                    ),
                )
                val settings = db.settingDao().loadWindowSettings()
                val from = firstVersionStart(d.rule, Instant.fromEpochMilliseconds(now), settings).toEpochMilliseconds()
                insertVersion(intakeId, d.rule, now, effectiveFrom = from)
                d.medications?.let { db.writeIntakeMedications(intakeId, it, emptyMap(), now, ids, deviceId) }
                db.markHealthCardEdited(d.personId, now, deviceId)
                SaveIntakeResult.Saved(intakeId, newScheduleVersion = true, quiet = d.reminder?.let { saveReminder(intakeId, it, now) })
            }
        }
    }

    suspend fun update(intakeId: String, draft: IntakeDraft): SaveIntakeResult {
        val normalized = draft.normalized()
        validateMedications(normalized)?.let { return it }
        return db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val intakeDao = db.intakeDao()
                val previous = intakeDao.findById(intakeId)?.takeIf { it.archivedAt == null }
                    ?: return@immediateTransaction SaveIntakeResult.Gone
                // SPEC-023: only changed names are normalized and validated; legacy values stay intact.
                val nameChanged = draft.name != previous.name
                val aliasChanged = draft.widgetAlias != previous.widgetAlias
                validateNames(normalized.name.takeIf { nameChanged }, draft.widgetAlias.takeIf { aliasChanged })
                    ?.let { return@immediateTransaction it }
                val d = normalized.copy(
                    name = if (nameChanged) normalized.name else previous.name,
                    widgetAlias = if (aliasChanged) normalized.widgetAlias else previous.widgetAlias,
                )
                if (!activePerson(d.personId)) return@immediateTransaction SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.UNKNOWN_PERSON)
                val now = clock.now().toEpochMilliseconds()
                val existing = if (d.medications == null) emptyMap() else db.intakeMedicationDao().listByIntake(intakeId).associateBy { it.id }
                val draftIds = d.medications.orEmpty().mapNotNull { it.id }
                if (draftIds.size != draftIds.toSet().size || draftIds.any { it !in existing }) {
                    return@immediateTransaction SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.UNKNOWN_MEDICATION_ROW)
                }
                if (intakeDao.updateDetails(intakeId, d.personId, d.name, d.widgetAlias, d.slotLabel, now) != 1) {
                    return@immediateTransaction SaveIntakeResult.Gone
                }
                val versions = db.scheduleVersionDao()
                // The open version, not the one in effect at now: with the clock set back, "now" can
                // fall inside an older closed version or before the first one (TASK-005).
                val current = versions.findOpen(intakeId)
                val changed = current == null || ScheduleParams.toRule(current) != d.rule
                if (changed) {
                    // A changed schedule applies from now; the window holding now follows the new
                    // version (supplement P-8). If the clock is behind the open version's start, the
                    // change applies from that start instead, which leaves the open version empty
                    // (from = to) and never two open versions.
                    val from = maxOf(now, current?.effectiveFrom ?: now)
                    if (current != null) {
                        check(versions.close(current.id, effectiveTo = from, now = now) == 1) {
                            "open schedule version ${current.id} could not be closed"
                        }
                    }
                    insertVersion(intakeId, d.rule, now, effectiveFrom = from)
                }
                val medicationsChanged = d.medications?.let { db.writeIntakeMedications(intakeId, it, existing, now, ids, deviceId) } == true
                val personChanged = previous.personId != d.personId
                if (personChanged || changed || medicationsChanged || previous.name != d.name || previous.slotLabel != d.slotLabel) {
                    // SPEC-049: moving an item to someone else changes both cards.
                    db.markHealthCardEdited(setOf(d.personId, previous.personId), now, deviceId)
                }
                SaveIntakeResult.Saved(intakeId, newScheduleVersion = changed, quiet = d.reminder?.let { saveReminder(intakeId, it, now) })
            }
        }
    }

    /**
     * The item screen's reminder part (TASK-023). An item that never had a rule gets none while its reminder stays off,
     * so its time keeps following its label (supplement N-3). The quiet-hours notice comes only with a rule written
     * switched on: saving an item whose reminder did not change does not say it again (Claude's choice).
     */
    private suspend fun saveReminder(intakeId: String, choice: ReminderChoice, now: Long): SavedQuiet? {
        if (!choice.enabled && db.reminderDao().findRule(intakeId) == null) return null
        val written = db.writeReminderRule(intakeId, choice.enabled, choice.start, now, deviceId)
        if (!written || !choice.enabled) return null
        val settings = db.settingDao().loadReminderSettings()
        return quietNotice(choice.start, db.settingDao().loadWindowSettings(), settings)?.let { SavedQuiet(it, settings) }
    }

    private fun validateNames(name: String?, alias: String?): SaveIntakeResult.Invalid? {
        val trimmedAlias = alias?.trim()
        val reason = when {
            name == "" -> SaveIntakeResult.Invalid.BLANK_NAME
            trimmedAlias == "" && !alias.isNullOrEmpty() -> SaveIntakeResult.Invalid.BLANK_ALIAS
            (name?.codePointCount() ?: 0) > IntakeDraft.MAX_NAME_CHARACTERS ||
                (trimmedAlias?.codePointCount() ?: 0) > IntakeDraft.MAX_NAME_CHARACTERS -> SaveIntakeResult.Invalid.NAME_LIMIT
            else -> return null
        }
        return SaveIntakeResult.Invalid(reason)
    }

    private fun validateMedications(d: IntakeDraft): SaveIntakeResult.Invalid? {
        val rows = d.medications.orEmpty()
        if (rows.any { it.name.isBlank() }) return SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.BLANK_MEDICATION)
        if (rows.size > IntakeMedicationDraft.MAX_ROWS || rows.any { row ->
                row.name != row.name.takeMedicationCodePoints(IntakeMedicationDraft.MAX_NAME) ||
                    row.quantity != row.quantity.takeMedicationCodePoints(IntakeMedicationDraft.MAX_QUANTITY)
            }
        ) {
            return SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.MEDICATION_LIMIT)
        }
        return null
    }

    private suspend fun activePerson(personId: String): Boolean =
        db.personDao().findById(personId)?.let { it.archivedAt == null } == true

    companion object {
        /**
         * `late_policy` of every schedule version this app writes. KEEP, not the data model's ASK:
         * the 1st release has no "move the anchor?" question (decision Q15, 1st-release method).
         * If that question is added later, change this together with supplement section 6 and N-5.
         */
        val NEW_VERSION_LATE_POLICY: LatePolicy = LatePolicy.KEEP
    }

    private suspend fun insertVersion(intakeId: String, rule: ScheduleRule, now: Long, effectiveFrom: Long) {
        db.scheduleVersionDao().insert(
            ScheduleVersionEntity(
                id = ids.next(),
                intakeId = intakeId,
                type = ScheduleParams.typeOf(rule),
                paramsJson = ScheduleParams.encode(rule),
                latePolicy = NEW_VERSION_LATE_POLICY,
                effectiveFrom = effectiveFrom,
                createdAt = now,
                updatedAt = now,
                deviceId = deviceId,
            ),
        )
    }
}

/** Delete = archive (data-model.md section 3). Records and schedule versions are kept. */
class ArchiveIntake(
    private val db: DidITakeDatabase,
    private val clock: Clock,
) {
    /** True when the intake was active and is now archived. */
    suspend operator fun invoke(intakeId: String): Boolean = db.useWriterConnection { writer ->
        writer.immediateTransaction {
            val intake = db.intakeDao().findById(intakeId) ?: return@immediateTransaction false
            val now = clock.now().toEpochMilliseconds()
            if (db.intakeDao().archive(intakeId, now) != 1) return@immediateTransaction false
            val deviceId = db.settingDao().get(SettingKeys.DEVICE_ID) ?: intake.deviceId
            db.markHealthCardEdited(intake.personId, now, deviceId)
            true
        }
    }
}
