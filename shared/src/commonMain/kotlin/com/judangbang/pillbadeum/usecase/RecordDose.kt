package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.domain.UuidV7
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The single write path for judgments (record-rules.md section 6). The widget, the app and
 * notification buttons all call this; it records `TAKEN` or `SKIPPED` (section 6.2).
 *
 * Everything runs inside ONE immediate write transaction, so concurrent callers queue on the
 * writer connection, and the UNIQUE(intake_id, period_key, active) index is the final guard.
 */
class RecordDose(
    private val db: DidITakeDatabase,
    private val clock: Clock,
    private val ids: UuidV7,
    private val deviceId: String,
) {
    suspend operator fun invoke(command: RecordCommand): RecordResult =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                // Read the clock after the writer lock is held: the window is the one at write time.
                record(command, clock.now())
            }
        }

    private suspend fun record(command: RecordCommand, now: Instant): RecordResult {
        val open = when (val lookup = db.lookUpWindow(command.intakeId, command.renderedPeriodKey, now)) {
            is WindowLookup.Rejected -> return lookup.result
            is WindowLookup.Open -> lookup
        }

        val dao = db.doseRecordDao()
        dao.findActiveIn(open.intake.id, open.window)?.let { return RecordResult.Already(it) }

        val record = open.newJudgment(command, now, ids, deviceId)
        if (dao.insertOrIgnore(record) == IGNORED) {
            // 0 rows inserted is not proof of a duplicate: OR IGNORE also swallows primary key and
            // NOT NULL violations. Only an active row found by the judgment query counts.
            val existing = dao.findActiveIn(open.intake.id, open.window)
                ?: return RecordResult.Error("insert ignored without an active judgment (intake ${open.intake.id})")
            return RecordResult.Already(existing)
        }
        return RecordResult.Recorded(record)
    }

    private companion object {
        const val IGNORED: Long = -1L
    }
}
