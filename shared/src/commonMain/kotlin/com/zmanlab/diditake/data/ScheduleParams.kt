package com.zmanlab.diditake.data

import com.zmanlab.diditake.domain.ScheduleRule
import com.zmanlab.diditake.domain.ScheduleType
import kotlinx.datetime.DayOfWeek

/**
 * Maps `schedule_version (type, params_json)` to and from [ScheduleRule].
 *
 * MVP params are tiny (`{}` and `{"anchorWeekday":"MON"}`, data-model.md section 2), so this is a
 * deliberately small hand-written codec. Switch to kotlinx-serialization when extension types
 * (EVERY_N_DAYS, DAILY_SLOTS, PRN) arrive.
 */
object ScheduleParams {

    fun typeOf(rule: ScheduleRule): ScheduleType = when (rule) {
        ScheduleRule.Daily -> ScheduleType.DAILY
        is ScheduleRule.Weekly -> ScheduleType.WEEKLY
    }

    fun encode(rule: ScheduleRule): String = when (rule) {
        ScheduleRule.Daily -> "{}"
        is ScheduleRule.Weekly -> """{"anchorWeekday":"${weekdayCode(rule.anchor)}"}"""
    }

    /** @throws IllegalArgumentException when params do not match [type]. */
    fun decode(type: ScheduleType, paramsJson: String): ScheduleRule = when (type) {
        ScheduleType.DAILY -> ScheduleRule.Daily
        ScheduleType.WEEKLY -> {
            val code = ANCHOR_WEEKDAY.find(paramsJson)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("WEEKLY params without anchorWeekday: $paramsJson")
            ScheduleRule.Weekly(weekdayOf(code))
        }
    }

    fun toRule(version: ScheduleVersionEntity): ScheduleRule = decode(version.type, version.paramsJson)

    private val ANCHOR_WEEKDAY = Regex(""""anchorWeekday"\s*:\s*"([A-Z]{3})"""")

    private val CODES: Map<DayOfWeek, String> = mapOf(
        DayOfWeek.MONDAY to "MON",
        DayOfWeek.TUESDAY to "TUE",
        DayOfWeek.WEDNESDAY to "WED",
        DayOfWeek.THURSDAY to "THU",
        DayOfWeek.FRIDAY to "FRI",
        DayOfWeek.SATURDAY to "SAT",
        DayOfWeek.SUNDAY to "SUN",
    )

    private fun weekdayCode(day: DayOfWeek): String = CODES.getValue(day)

    private fun weekdayOf(code: String): DayOfWeek =
        CODES.entries.firstOrNull { it.value == code }?.key
            ?: throw IllegalArgumentException("unknown anchorWeekday: $code")
}
