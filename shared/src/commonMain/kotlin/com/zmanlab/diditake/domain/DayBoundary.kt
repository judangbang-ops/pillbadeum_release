package com.zmanlab.diditake.domain

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * The logical date [at] belongs to: its daily window start date in the base zone.
 * Before the day start (default 04:00) this is still the previous date. Used for the widget
 * header (`11/11 (수)`) and the week grid.
 */
fun logicalDateOf(at: Instant, settings: WindowSettings): LocalDate =
    LocalDate.parse(windowOf(ScheduleRule.Daily, at, settings).periodKey)

/**
 * The next day boundary strictly after [at] (the end of the current daily window). The widget is
 * refreshed at this instant (sequences.md section 4).
 */
fun nextDayBoundary(at: Instant, settings: WindowSettings): Instant =
    windowOf(ScheduleRule.Daily, at, settings).end

/** The daily window of the logical date [date] (it starts at the day start of [date]). */
fun dailyWindowOn(date: LocalDate, settings: WindowSettings): Window =
    windowOf(ScheduleRule.Daily, LocalDateTime(date, settings.dayStart).toInstant(settings.baseZone), settings)

/** Monday..Sunday of the calendar week containing [date] (display order of the week grid). */
fun mondayToSunday(date: LocalDate): List<LocalDate> {
    val monday = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    return (0 until 7).map { monday.plus(it, DateTimeUnit.DAY) }
}
