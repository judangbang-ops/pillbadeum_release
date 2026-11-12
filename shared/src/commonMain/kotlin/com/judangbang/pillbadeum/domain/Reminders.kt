package com.judangbang.pillbadeum.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Common reminder settings, one set for every item (record-rules-supplement.md N-1). Quiet hours are
 * `[quietStart, quietEnd)` in the base zone and wrap past midnight; equal ends mean no quiet hours (N-4).
 */
data class ReminderSettings(
    val interval: Duration = DEFAULT_INTERVAL,
    val maxPerDay: Int = DEFAULT_MAX_PER_DAY,
    val quietStart: LocalTime = DEFAULT_QUIET_START,
    val quietEnd: LocalTime = DEFAULT_QUIET_END,
) {
    /** The values the settings screen offers (supplement N-4, Claude default). */
    val isValid: Boolean
        get() = interval in INTERVALS && maxPerDay in MAX_PER_DAY_RANGE &&
            quietStart.onHalfHour() && quietEnd.onHalfHour()

    fun isQuiet(time: LocalTime): Boolean = when {
        quietStart == quietEnd -> false
        quietStart < quietEnd -> time >= quietStart && time < quietEnd
        else -> time >= quietStart || time < quietEnd
    }

    companion object {
        val DEFAULT_INTERVAL: Duration = 60.minutes
        const val DEFAULT_MAX_PER_DAY: Int = 3
        val DEFAULT_QUIET_START: LocalTime = LocalTime(22, 0)
        val DEFAULT_QUIET_END: LocalTime = LocalTime(7, 0)

        /** Claude default (supplement N-4): 30, 60, 90 or 120 minutes. */
        val INTERVALS: List<Duration> = listOf(30.minutes, 60.minutes, 90.minutes, 120.minutes)

        /** Claude default (supplement N-4): 1 to 5 reminders a day, counted before quiet hours drop any. */
        val MAX_PER_DAY_RANGE: IntRange = 1..5

        private fun LocalTime.onHalfHour(): Boolean = minute % 30 == 0 && second == 0 && nanosecond == 0
    }
}

/** First reminder time of an item without a `reminder_rule` row, from its time-of-day label (supplement N-3). */
fun defaultReminderStart(label: SlotLabel?): LocalTime = when (label) {
    SlotLabel.MORNING, null -> LocalTime(8, 0)
    SlotLabel.NOON -> LocalTime(12, 0)
    SlotLabel.EVENING -> LocalTime(18, 0)
    SlotLabel.BEDTIME -> LocalTime(21, 0)
}

/** One grid slot of a window (supplement N-4). [index] 0 is the first reminder and always rings. */
data class ReminderSlot(val at: Instant, val index: Int)

/**
 * The reminder grid of [window] (supplement N-4, N-5): on the window's first logical day D, slot 0 is the
 * first [start] after D's day start (a start before the day start falls on the next date), and slot k is
 * slot 0 + k × interval for k < maxPerDay. Slots at or after the end of D are dropped (for a daily window
 * that is the window end; a weekly window rings on its first day D only), and so are slots in quiet hours
 * except slot 0. All in the base zone.
 */
fun reminderSlots(
    window: Window,
    start: LocalTime,
    settings: WindowSettings,
    reminders: ReminderSettings,
): List<ReminderSlot> {
    val day = LocalDate.parse(window.periodKey)
    val firstDay = dailyWindowOn(day, settings)
    val date = if (start >= settings.dayStart) day else day.plus(1, DateTimeUnit.DAY)
    val first = LocalDateTime(date, start).toInstant(settings.baseZone)
    return (0 until reminders.maxPerDay).mapNotNull { k ->
        val at = first + reminders.interval * k
        val quiet = reminders.isQuiet(at.toLocalDateTime(settings.baseZone).time)
        ReminderSlot(at, k).takeIf { at in window && at in firstDay && (k == 0 || !quiet) }
    }
}

/** What to tell the user when saving reminder settings whose repeats fall into quiet hours (N-4, REQ-077). */
sealed interface QuietNotice {
    /** "방해 금지 시간이라 첫 알림만 울리고 다시 알림은 없어요" */
    data object FirstOnly : QuietNotice

    /** "방해 금지 시간(22:00~07:00)과 겹쳐 다시 알림이 [dropped]번 줄어요" */
    data class Reduced(val dropped: Int) : QuietNotice
}

/**
 * The save-time notice of supplement N-4 for an item starting at [start], or null when quiet hours drop
 * nothing. Counted on wall-clock minutes of one logical day, the day [reminderSlots] rings on for daily and
 * weekly items alike; slots past that day are not counted as quiet. So it does not depend on today's date or DST.
 */
fun quietNotice(
    start: LocalTime,
    settings: WindowSettings,
    reminders: ReminderSettings,
): QuietNotice? {
    val repeats = repeats(start, settings, reminders)
    return when {
        repeats.dropped == 0 -> null
        repeats.kept == 0 && reminders.isQuiet(start) -> QuietNotice.FirstOnly
        else -> QuietNotice.Reduced(repeats.dropped)
    }
}

/**
 * How many repeats of an item starting at [start] quiet hours drop in one logical day, counted as [quietNotice]
 * counts them: 0 when it gives no notice. The settings screen adds these up over the items with reminders on
 * ("다시 알림이 모두 4번 줄어요", SCR-009, REQ-077).
 */
fun quietDrops(
    start: LocalTime,
    settings: WindowSettings,
    reminders: ReminderSettings,
): Int = repeats(start, settings, reminders).dropped

/** The repeats (slots 1 and on) of one logical day that quiet hours drop and keep (supplement N-4). */
private class Repeats(val dropped: Int, val kept: Int)

private fun repeats(start: LocalTime, settings: WindowSettings, reminders: ReminderSettings): Repeats {
    val day = MINUTES_PER_DAY
    val dayStart = settings.dayStart.minuteOfDay()
    val offset0 = (start.minuteOfDay() - dayStart + day) % day
    val step = reminders.interval.inWholeMinutes.toInt()
    var dropped = 0
    var kept = 0
    for (k in 1 until reminders.maxPerDay) {
        val offset = offset0 + k * step
        if (offset >= day) break
        val minute = (dayStart + offset) % day
        if (reminders.isQuiet(LocalTime(minute / 60, minute % 60))) dropped++ else kept++
    }
    return Repeats(dropped, kept)
}

private const val MINUTES_PER_DAY: Int = 24 * 60

private fun LocalTime.minuteOfDay(): Int = hour * 60 + minute
