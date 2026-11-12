package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.IntakeEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.ScheduleVersionEntity
import com.judangbang.pillbadeum.domain.LatePolicy
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.domain.UuidV7
import kotlin.time.Clock

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
 * - Create: intake row + first schedule_version, effective from now, in one transaction.
 * - Edit: display fields are updated in place; a changed schedule closes the current version at
 *   now (`effective_to`) and inserts a new one. Versions are never overwritten (data-model.md 2).
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
                insertVersion(intakeId, d.rule, now)
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
                    insertVersion(intakeId, d.rule, now)
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

    private suspend fun insertVersion(intakeId: String, rule: ScheduleRule, now: Long) {
        db.scheduleVersionDao().insert(
            ScheduleVersionEntity(
                id = ids.next(),
                intakeId = intakeId,
                type = ScheduleParams.typeOf(rule),
                paramsJson = ScheduleParams.encode(rule),
                latePolicy = LatePolicy.ASK,
                effectiveFrom = now,
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
