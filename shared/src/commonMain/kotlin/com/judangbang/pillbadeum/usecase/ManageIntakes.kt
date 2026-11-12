package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.IntakeEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.ScheduleVersionEntity
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.domain.LatePolicy
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.domain.UuidV7
import com.judangbang.pillbadeum.domain.firstVersionStart
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What the registration screen submits.
 *
 * @property slotLabel display only, meaningful for daily items (아침/점심/저녁/자기 전).
 */
data class IntakeDraft(
    val personId: String,
    val name: String,
    val widgetAlias: String? = null,
    val rule: ScheduleRule = ScheduleRule.Daily,
    val slotLabel: SlotLabel? = null,
) {
    /** Trimmed copy; a blank alias becomes null, weekly items carry no slot. */
    fun normalized(): IntakeDraft = copy(
        name = name.trim(),
        widgetAlias = widgetAlias?.trim()?.takeIf { it.isNotEmpty() },
        slotLabel = if (rule is ScheduleRule.Daily) slotLabel else null,
    )
}

sealed interface SaveIntakeResult {
    data class Saved(val intakeId: String, val newScheduleVersion: Boolean) : SaveIntakeResult

    /** Nothing written. [reason] is a code from [Invalid.Companion]. */
    data class Invalid(val reason: String) : SaveIntakeResult {
        companion object {
            const val BLANK_NAME: String = "BLANK_NAME"
            const val UNKNOWN_PERSON: String = "UNKNOWN_PERSON"
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
 * - Every new version stores `late_policy` = [NEW_VERSION_LATE_POLICY] (KEEP): the 1st release never
 *   moves a weekly anchor after a late dose (record-rules-supplement.md W-1, decision Q15, TASK-004).
 */
class SaveIntake(
    private val db: DidITakeDatabase,
    private val clock: Clock,
    private val ids: UuidV7,
    private val deviceId: String,
) {
    suspend fun create(draft: IntakeDraft): SaveIntakeResult {
        val d = draft.normalized()
        validate(d)?.let { return it }
        return db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
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
                SaveIntakeResult.Saved(intakeId, newScheduleVersion = true)
            }
        }
    }

    suspend fun update(intakeId: String, draft: IntakeDraft): SaveIntakeResult {
        val d = draft.normalized()
        validate(d)?.let { return it }
        return db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val now = clock.now().toEpochMilliseconds()
                val intakeDao = db.intakeDao()
                if (intakeDao.updateDetails(intakeId, d.personId, d.name, d.widgetAlias, d.slotLabel, now) != 1) {
                    return@immediateTransaction SaveIntakeResult.Gone
                }
                val versions = db.scheduleVersionDao()
                val current = versions.findEffective(intakeId, now)
                val changed = current == null || ScheduleParams.toRule(current) != d.rule
                if (changed) {
                    if (current != null) versions.close(current.id, effectiveTo = now, now = now)
                    // A changed schedule applies from now; the window holding now follows the new
                    // version (supplement P-8). `current == null` only happens when the clock is
                    // before the first version's start; TASK-005 handles that case.
                    insertVersion(intakeId, d.rule, now, effectiveFrom = now)
                }
                SaveIntakeResult.Saved(intakeId, newScheduleVersion = changed)
            }
        }
    }

    private suspend fun validate(d: IntakeDraft): SaveIntakeResult.Invalid? {
        if (d.name.isEmpty()) return SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.BLANK_NAME)
        val person = db.personDao().findById(d.personId)
        if (person == null || person.archivedAt != null) {
            return SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.UNKNOWN_PERSON)
        }
        return null
    }

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
    suspend operator fun invoke(intakeId: String): Boolean =
        db.intakeDao().archive(intakeId, clock.now().toEpochMilliseconds()) == 1
}
