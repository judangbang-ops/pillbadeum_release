package com.judangbang.pillbadeum.domain

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * One judgment window `[start, end)` and its period key (record-rules.md sections 2-3).
 *
 * @property periodKey local date (`YYYY-MM-DD`, base zone) on which the window starts.
 *   Never an ISO week number and never a relative cycle index (record-rules.md section 3).
 */
data class Window(
    val start: Instant,
    val end: Instant,
    val periodKey: String,
) {
    operator fun contains(instant: Instant): Boolean = instant >= start && instant < end
}

/**
 * Window of [rule] that contains [at].
 *
 * - Daily: [D T0, D+1 T0) where D is the local date of (at - T0) in the base zone.
 * - Weekly: [most recent anchor weekday at T0, +7 days).
 */
fun windowOf(rule: ScheduleRule, at: Instant, settings: WindowSettings): Window {
    val local = at.toLocalDateTime(settings.baseZone)
    // Before T0 the instant still belongs to the previous day (00:30 -> yesterday's window).
    val logicalDay = if (local.time >= settings.dayStart) local.date else local.date.minus(1, DateTimeUnit.DAY)

    var startDate = when (rule) {
        ScheduleRule.Daily -> logicalDay
        is ScheduleRule.Weekly -> logicalDay.minus(daysSince(logicalDay.dayOfWeek, rule.anchor), DateTimeUnit.DAY)
    }

    // The wall-clock guess above can be one period off only when T0 falls into a DST gap or
    // overlap of the base zone. Re-check against the real boundary instants so that windows
    // stay contiguous and always contain `at`.
    val step = rule.periodDays
    if (at < boundaryOf(startDate, settings)) {
        startDate = startDate.minus(step, DateTimeUnit.DAY)
    } else if (at >= boundaryOf(startDate.plus(step, DateTimeUnit.DAY), settings)) {
        startDate = startDate.plus(step, DateTimeUnit.DAY)
    }

    return Window(
        start = boundaryOf(startDate, settings),
        end = boundaryOf(startDate.plus(step, DateTimeUnit.DAY), settings),
        periodKey = periodKeyOf(startDate),
    )
}

/**
 * Effective start of an intake's first schedule version (record-rules-supplement.md P-4): the start
 * of the window, under [rule], that contains the registration instant. Registering in the afternoon
 * still lets the user record "this morning at 08:00"; anything earlier stays out of reach.
 */
fun firstVersionStart(rule: ScheduleRule, registeredAt: Instant, settings: WindowSettings): Instant =
    windowOf(rule, registeredAt, settings).start

/** Period key format: the window start date as `YYYY-MM-DD`. */
fun periodKeyOf(windowStartDate: LocalDate): String = windowStartDate.toString()

/** The instant at which the logical day [date] begins (T0 in the base zone). */
private fun boundaryOf(date: LocalDate, settings: WindowSettings): Instant =
    LocalDateTime(date, settings.dayStart).toInstant(settings.baseZone)

/** Days elapsed since the most recent [anchor] weekday, 0 when [day] is the anchor itself. */
private fun daysSince(day: DayOfWeek, anchor: DayOfWeek): Int =
    (day.isoDayNumber - anchor.isoDayNumber + 7) % 7
