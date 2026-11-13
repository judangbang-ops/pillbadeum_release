package com.judangbang.pillbadeum.ui

import com.judangbang.pillbadeum.status.JudgeCell
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.ClockFlagResult
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.VoidResult
import com.judangbang.pillbadeum.usecase.WindowWriteResult
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** What the app does after a write on SCR-007 (screens.md SCR-007 tables, TASK-018). */
internal sealed interface JudgeAfter {
    /**
     * Stay on the cell and read it again, with [message] in the band (null: the tile itself shows the result). [wrote]:
     * the database changed, so the widgets and reminders are redone too.
     */
    data class Stay(val message: String?, val wrote: Boolean) : JudgeAfter

    /** A time edit moved the dose to another cell (supplement P-6): that cell is shown. */
    data class Move(val versionId: String, val periodKey: String, val message: String?) : JudgeAfter

    /** SCR-007#gone: the first screen, with [message]. */
    data class Home(val message: String) : JudgeAfter

    /**
     * SCR-007#stale: what the screen showed is out of date. The app closes to the first screen when the screen was opened
     * on the current window and that window has ended, and otherwise reads the cell again.
     */
    data object Stale : JudgeAfter
}

internal object JudgeOutcomes {
    /** `[지금 먹었어]`, the same write as the first screen's `[먹었어]` (RecordDose). */
    fun of(result: RecordResult, now: Instant, zone: TimeZone): JudgeAfter = when (result) {
        is RecordResult.Recorded -> JudgeAfter.Stay(null, wrote = true)
        is RecordResult.Already -> JudgeAfter.Stay(Labels.judgeAlready(result.existing, dated = false, now, zone), wrote = false)
        is RecordResult.Stale -> JudgeAfter.Stale
        RecordResult.Gone -> JudgeAfter.Home(Labels.GONE)
        RecordResult.Locked -> JudgeAfter.Stay(Labels.LOCKED, wrote = false)
        is RecordResult.Error -> JudgeAfter.Stay(Labels.TRY_AGAIN, wrote = false)
    }

    /**
     * A picked time, a skip or a skip turned into a dose ([edit] false), or a time edit ([edit] true). A dose whose new time
     * left the cell's window now lives in another cell, which is shown next.
     */
    fun of(result: WindowWriteResult, cell: JudgeCell, edit: Boolean, now: Instant, zone: TimeZone): JudgeAfter = when (result) {
        is WindowWriteResult.Recorded -> {
            val record = result.record
            val message = if (edit) Labels.TIME_EDITED else null
            if (Instant.fromEpochMilliseconds(record.takenAt) in cell.window) {
                JudgeAfter.Stay(message, wrote = true)
            } else {
                JudgeAfter.Move(record.scheduleVersionId, record.periodKey, message)
            }
        }
        is WindowWriteResult.Already -> JudgeAfter.Stay(Labels.judgeAlready(result.existing, dated = edit, now, zone), wrote = false)
        WindowWriteResult.Unchanged -> JudgeAfter.Stay(Labels.UNCHANGED, wrote = false)
        WindowWriteResult.Locked -> JudgeAfter.Stay(Labels.LOCKED, wrote = false)
        is WindowWriteResult.InvalidTime -> JudgeAfter.Stay(Labels.invalidTime(result.reason), wrote = false)
        WindowWriteResult.Stale -> JudgeAfter.Stale
        WindowWriteResult.Gone -> JudgeAfter.Home(Labels.GONE)
        is WindowWriteResult.Error -> JudgeAfter.Stay(Labels.TRY_AGAIN, wrote = false)
    }

    /** `[기록 취소]` or `[건너뜀 취소]` after the question (VoidDose). */
    fun of(result: VoidResult, cell: JudgeCell): JudgeAfter = when (result) {
        VoidResult.Voided -> JudgeAfter.Stay(Labels.voided(cell.intakeName, skip = cell.isSkipped), wrote = true)
        VoidResult.NotActive -> JudgeAfter.Stay(Labels.ALREADY_VOIDED, wrote = false)
        VoidResult.Locked -> JudgeAfter.Stay(Labels.LOCKED, wrote = false)
    }

    /** `[그대로 두기]` of a clock warning: the band goes away. */
    fun ofKeep(result: ClockFlagResult): JudgeAfter = JudgeAfter.Stay(null, wrote = result == ClockFlagResult.Resolved)

    /** `[기록 취소]` of a clock warning after the question: voided like `[기록 취소]`. */
    fun ofCancel(result: ClockFlagResult, cell: JudgeCell): JudgeAfter = when (result) {
        ClockFlagResult.Resolved -> JudgeAfter.Stay(Labels.voided(cell.intakeName, skip = cell.isSkipped), wrote = true)
        ClockFlagResult.NotFlagged -> JudgeAfter.Stay(null, wrote = false)
        ClockFlagResult.Locked -> JudgeAfter.Stay(Labels.LOCKED, wrote = false)
    }
}
