package com.zmanlab.diditake.usecase

import com.zmanlab.diditake.data.DidITakeDatabase
import kotlin.time.Clock

/**
 * Cancels an active judgment, app only, after the user confirmed (record-rules.md section 6.1).
 * The row is kept with `active = NULL` so history survives and the window can be recorded again.
 */
class VoidDose(
    private val db: DidITakeDatabase,
    private val clock: Clock,
) {
    suspend operator fun invoke(recordId: String, reason: String = VoidReasons.USER_CANCEL): VoidResult {
        val changed = db.doseRecordDao().voidActive(recordId, clock.now().toEpochMilliseconds(), reason)
        return if (changed == 1) VoidResult.Voided else VoidResult.NotActive
    }
}

sealed interface VoidResult {
    data object Voided : VoidResult

    /** No such row, or it was already voided. Nothing changed. */
    data object NotActive : VoidResult
}
