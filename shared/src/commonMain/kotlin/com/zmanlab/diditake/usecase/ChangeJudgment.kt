package com.zmanlab.diditake.usecase

import androidx.room3.TransactionScope
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.zmanlab.diditake.data.DidITakeDatabase
import com.zmanlab.diditake.domain.UuidV7
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Replaces the current window's judgment with another kind, app only (record-rules.md section 6.3),
 * e.g. SKIPPED -> TAKEN. Void and insert happen in ONE immediate transaction; if the insert does
 * not land, the void is rolled back so the window is never left without its judgment.
 *
 * Results: [RecordResult.Recorded] with the new row; [RecordResult.Already] when the active
 * judgment already has the requested kind; [RecordResult.Stale]/[RecordResult.Gone] as in
 * [RecordDose]; [RecordResult.Error] on an inconsistent state (nothing changed).
 * With no active judgment in the window this simply records the requested kind.
 */
class ChangeJudgment(
    private val db: DidITakeDatabase,
    private val clock: Clock,
    private val ids: UuidV7,
    private val deviceId: String,
) {
    suspend operator fun invoke(command: RecordCommand, reason: String = VoidReasons.CHANGED): RecordResult =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                change(command, reason, clock.now())
            }
        }

    private suspend fun TransactionScope<RecordResult>.change(
        command: RecordCommand,
        reason: String,
        now: Instant,
    ): RecordResult {
        val open = when (val lookup = db.lookUpWindow(command.intakeId, command.renderedPeriodKey, now)) {
            is WindowLookup.Rejected -> return lookup.result
            is WindowLookup.Open -> lookup
        }

        val dao = db.doseRecordDao()
        val current = dao.findActiveIn(open.intake.id, open.window)
        if (current != null) {
            if (current.kind == command.kind) return RecordResult.Already(current)
            if (dao.voidActive(current.id, now.toEpochMilliseconds(), reason) != 1) {
                rollback(RecordResult.Error("judgment ${current.id} could not be voided"))
            }
        }

        val replacement = open.newJudgment(command, now, ids, deviceId)
        if (dao.insertOrIgnore(replacement) == -1L) {
            rollback(RecordResult.Error("replacement judgment was ignored (intake ${open.intake.id})"))
        }
        return RecordResult.Recorded(replacement)
    }
}
