package com.judangbang.pillbadeum.domain

import kotlinx.datetime.DayOfWeek

/**
 * How often one intake must be judged (record-rules.md section 2, MVP rows only).
 *
 * Extension rules (every N days, slots, PRN) are added as new subclasses later.
 */
sealed interface ScheduleRule {
    /** Length of one window in calendar days. */
    val periodDays: Int

    /** Once per day: window [D T0, D+1 T0). */
    data object Daily : ScheduleRule {
        override val periodDays: Int = 1
    }

    /** Once per week: window [most recent [anchor] at T0, +7 days). */
    data class Weekly(val anchor: DayOfWeek) : ScheduleRule {
        override val periodDays: Int = 7
    }
}
