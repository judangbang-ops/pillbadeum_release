package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.ClockFlagEntity
import com.judangbang.pillbadeum.data.DidITakeDatabase
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Leaves a flag on every active judgment taken more than [MARGIN] after now (record-rules-supplement.md
 * C-1, REQ-009). Called on clock, time zone and boot broadcasts and whenever the app or widget reads
 * judgments. A flag that was already resolved is never raised again for the same judgment.
 */
class FlagClockMismatch(
    private val db: DidITakeDatabase,
    private val clock: Clock,
) {
    /** Returns how many judgments were newly flagged. */
    suspend operator fun invoke(): Int =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val now = clock.now()
                val dao = db.clockFlagDao()
                dao.findUnflaggedTakenAfter((now + MARGIN).toEpochMilliseconds()).count { record ->
                    dao.insertOrIgnore(ClockFlagEntity(doseId = record.id, flaggedAt = now.toEpochMilliseconds())) != -1L
                }
            }
        }

    companion object {
        /**
         * Claude default (supplement C-1, review B9): automatic time sync moving the clock by a minute or two
         * right after a tap must not leave a warning (RR-88).
         */
        val MARGIN: Duration = 5.minutes
    }
}

/** The two answers to a clock warning (supplement C-3, SCR-004#future, SCR-007). */
class ResolveClockFlag(
    private val db: DidITakeDatabase,
    private val clock: Clock,
) {
    /** [그대로 두기]: the judgment stays, the warning goes (RR-66). */
    suspend fun keep(doseId: String): ClockFlagResult =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val now = clock.now().toEpochMilliseconds()
                if (db.clockFlagDao().resolve(doseId, now) == 1) ClockFlagResult.Resolved else ClockFlagResult.NotFlagged
            }
        }

    /**
     * [기록 취소]: voids the judgment (reason `USER_CANCEL`) and resolves the flag in one transaction (RR-65),
     * with the same rules as the home screen's cancel ([VoidDose]): a judgment whose cell ended before the
     * last settings change is not voided (P-5), and [그대로 두기] stays available. An open window becomes
     * pending again; its reminder is re-planned by the caller (P-7, TASK-027).
     */
    suspend fun cancel(doseId: String): ClockFlagResult =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val now = clock.now()
                val flag = db.clockFlagDao().find(doseId)
                val record = db.doseRecordDao().findById(doseId)
                when {
                    flag == null || flag.resolvedAt != null || record == null || !record.isActive ->
                        ClockFlagResult.NotFlagged
                    db.isLockedJudgment(record, now) -> ClockFlagResult.Locked
                    db.voidJudgment(doseId, now, VoidReasons.USER_CANCEL) == 1 -> ClockFlagResult.Resolved
                    else -> ClockFlagResult.NotFlagged
                }
            }
        }
}

sealed interface ClockFlagResult {
    data object Resolved : ClockFlagResult

    /** No open flag for that judgment (already answered, or voided elsewhere). Nothing changed. */
    data object NotFlagged : ClockFlagResult

    /** [기록 취소] on a judgment whose cell ended before the last settings change (supplement P-5). Nothing changed. */
    data object Locked : ClockFlagResult
}
