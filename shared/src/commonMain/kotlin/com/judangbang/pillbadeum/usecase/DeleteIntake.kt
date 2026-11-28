package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.SettingKeys
import kotlin.time.Clock

sealed interface DeleteIntakeResult {
    /** The intake, its schedule versions, judgments, clock flags, reminder and medication rows are gone. */
    data object Deleted : DeleteIntakeResult

    /** The intake is still active; archive it first. Nothing deleted. */
    data object NotArchived : DeleteIntakeResult

    /** No such intake. */
    data object Gone : DeleteIntakeResult
}

/**
 * Permanent delete of an archived intake after the user confirmed twice (REQ-015, TASK-010,
 * data-model.md section 3). Everything goes in one transaction, children first because foreign
 * keys are NO ACTION. A widget button left for it afterwards gets [RecordResult.Gone].
 */
class DeleteArchivedIntake(private val db: DidITakeDatabase, private val clock: Clock) {
    suspend operator fun invoke(intakeId: String): DeleteIntakeResult = db.useWriterConnection { transactor ->
        transactor.immediateTransaction {
            val intake = db.intakeDao().findById(intakeId) ?: return@immediateTransaction DeleteIntakeResult.Gone
            if (intake.archivedAt == null) return@immediateTransaction DeleteIntakeResult.NotArchived
            val purge = db.intakePurgeDao()
            purge.deleteClockFlags(intakeId)
            purge.deleteJudgments(intakeId)
            purge.deleteSnooze(intakeId)
            purge.deleteReminderRule(intakeId)
            val rows = db.intakeMedicationDao().listByIntake(intakeId)
            purge.deleteIntakeMedications(intakeId)
            for (row in rows) db.medicationDao().deleteUnlinked(row.medicationId)
            purge.deleteScheduleVersions(intakeId)
            check(purge.deleteArchivedIntake(intakeId) == 1) { "archived intake $intakeId was not deleted" }
            val deviceId = db.settingDao().get(SettingKeys.DEVICE_ID) ?: intake.deviceId
            db.markHealthCardEdited(intake.personId, clock.now().toEpochMilliseconds(), deviceId)
            DeleteIntakeResult.Deleted
        }
    }
}
