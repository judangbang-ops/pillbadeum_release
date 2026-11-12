package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import kotlin.time.Clock

/**
 * Cancels an active judgment, app only, after the user confirmed (record-rules.md section 6.1).
 * The row is kept with `active = NULL` so history survives and the window can be recorded again.
 * Its clock flag, if any, is resolved in the same transaction (supplement C-3, RR-89).
 */
class VoidDose(
    private val db: DidITakeDatabase,
    private val clock: Clock,
) {
    suspend operator fun invoke(recordId: String, reason: String = VoidReasons.USER_CANCEL): VoidResult =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val now = clock.now()
                val record = db.doseRecordDao().findById(recordId)
                when {
                    record == null || !record.isActive -> VoidResult.NotActive
                    // A cell that ended before the last settings change cannot be changed (supplement P-5).
                    db.isLockedJudgment(record, now) -> VoidResult.Locked
                    db.voidJudgment(recordId, now, reason) == 1 -> VoidResult.Voided
                    else -> VoidResult.NotActive
                }
            }
        }
}

sealed interface VoidResult {
    data object Voided : VoidResult

    /** No such row, or it was already voided. Nothing changed. */
    data object NotActive : VoidResult

    /** Its cell ended before the last day-start or base-zone change (supplement P-5). Nothing changed. */
    data object Locked : VoidResult
}
