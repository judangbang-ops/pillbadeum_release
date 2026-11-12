package com.judangbang.pillbadeum.domain

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant

/**
 * One schedule version's span, for writing to and drawing past windows (record-rules-supplement.md
 * P-1, P-4, P-8). [to] is null while the version is current.
 */
data class ScheduleSpan(
    val versionId: String,
    val rule: ScheduleRule,
    val from: Instant,
    val to: Instant?,
) {
    /**
     * True when [window], a window of [rule], is one of this version's cells (supplement P-8): it ends
     * after the version starts, and a closed version keeps only the windows that ended by its end.
     * The window that holds a schedule change therefore belongs to the new version, the same as the
     * current-window lookup (`findEffective` at now).
     */
    fun owns(window: Window): Boolean = window.end > from && (to == null || window.end <= to)
}

/**
 * The window of [rule] whose period key is [periodKey] under [settings], or null when [periodKey] is
 * not a window start of [rule] (not a date, or a weekly key on another weekday).
 */
fun windowWithKey(rule: ScheduleRule, periodKey: String, settings: WindowSettings): Window? {
    val date = runCatching { LocalDate.parse(periodKey) }.getOrNull() ?: return null
    val start = LocalDateTime(date, settings.dayStart).toInstant(settings.baseZone)
    return windowOf(rule, start, settings).takeIf { it.periodKey == periodKey }
}

/**
 * The cell that holds [at] for a time edit (supplement P-4, P-6): the window of [preferred] (the
 * edited judgment's own version) when that version owns it, then the version in effect at [at], then
 * any other version, newest first. Null when no version owns the window holding [at], e.g. before
 * the first version started (P-4).
 */
fun cellHolding(at: Instant, spans: List<ScheduleSpan>, preferred: ScheduleSpan?, settings: WindowSettings): Pair<ScheduleSpan, Window>? {
    val inEffect = spans.filter { it.from <= at && (it.to == null || at < it.to) }
    val order = listOfNotNull(preferred) + inEffect + spans.sortedByDescending { it.from }
    for (span in order) {
        val window = windowOf(span.rule, at, settings)
        if (span.owns(window)) return span to window
    }
    return null
}
