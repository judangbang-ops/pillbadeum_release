package com.zmanlab.diditake.usecase

import com.zmanlab.diditake.data.DoseRecordEntity
import com.zmanlab.diditake.domain.DoseKind
import com.zmanlab.diditake.domain.DoseSource

/**
 * One judgment request from the widget, the app or a notification button.
 *
 * @property renderedPeriodKey period key the tapped widget or notification was rendered with.
 *   `null` only when the app records directly from fresh data (record-rules.md section 7).
 * @property deviceZoneId device time zone at input time, stored as `zone_id`. Windows are always
 *   computed in the base zone, never in this zone.
 */
data class RecordCommand(
    val intakeId: String,
    val kind: DoseKind = DoseKind.TAKEN,
    val source: DoseSource,
    val renderedPeriodKey: String?,
    val deviceZoneId: String,
)

/** Outcome of a write (record-rules.md section 6). Only [Recorded] wrote a new active judgment. */
sealed interface RecordResult {
    /** A new active judgment was stored. */
    data class Recorded(val record: DoseRecordEntity) : RecordResult

    /** The window already has an active judgment; nothing changed ("already recorded at 08:02"). */
    data class Already(val existing: DoseRecordEntity) : RecordResult

    /** The caller was rendered for another window; nothing written, caller must refresh. */
    data class Stale(val currentPeriodKey: String) : RecordResult

    /** The intake is missing or archived; nothing written, caller must refresh. */
    data object Gone : RecordResult

    /** Inconsistent state. Reported as an error, never disguised as a duplicate. */
    data class Error(val message: String) : RecordResult
}

/** `void_reason` codes written by this app. */
object VoidReasons {
    /** The user cancelled a judgment in the app (record-rules.md section 6.1). */
    const val USER_CANCEL: String = "USER_CANCEL"

    /** Replaced by another judgment for the same window (record-rules.md section 6.3). */
    const val CHANGED: String = "CHANGED"
}
