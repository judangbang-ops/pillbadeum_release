package com.judangbang.pillbadeum.data

import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.ScheduleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.datetime.DayOfWeek

class ScheduleParamsTest {
    @Test
    fun encodes_documented_examples() {
        assertEquals("{}", ScheduleParams.encode(ScheduleRule.Daily))
        assertEquals("""{"anchorWeekday":"MON"}""", ScheduleParams.encode(ScheduleRule.Weekly(DayOfWeek.MONDAY)))
        assertEquals(ScheduleType.WEEKLY, ScheduleParams.typeOf(ScheduleRule.Weekly(DayOfWeek.MONDAY)))
    }

    @Test
    fun round_trips_every_weekday() {
        DayOfWeek.entries.forEach { day ->
            val rule = ScheduleRule.Weekly(day)
            assertEquals(rule, ScheduleParams.decode(ScheduleType.WEEKLY, ScheduleParams.encode(rule)))
        }
        assertEquals(ScheduleRule.Daily, ScheduleParams.decode(ScheduleType.DAILY, "{}"))
    }

    @Test
    fun tolerates_whitespace() {
        assertEquals(
            ScheduleRule.Weekly(DayOfWeek.FRIDAY),
            ScheduleParams.decode(ScheduleType.WEEKLY, """{ "anchorWeekday" : "FRI" }"""),
        )
    }

    @Test
    fun rejects_missing_or_unknown_anchor() {
        assertFailsWith<IllegalArgumentException> { ScheduleParams.decode(ScheduleType.WEEKLY, "{}") }
        assertFailsWith<IllegalArgumentException> {
            ScheduleParams.decode(ScheduleType.WEEKLY, """{"anchorWeekday":"XYZ"}""")
        }
    }
}
