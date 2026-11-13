package com.judangbang.pillbadeum.ui

import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.dailyWindowOn
import com.judangbang.pillbadeum.status.CellState
import com.judangbang.pillbadeum.status.HistoryCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** SCR-008 cells a row (09 History: two at 100%) and the weekly cell's own row. Sizes are dp; 14sp is 14 dp at 100%. */
class HistoryLayoutTest {
    @Test
    fun two_cells_a_row_until_the_longest_name_no_longer_fits() {
        // Android 14 grows text nonlinearly: 14sp is 16.4 dp at 115%, 18.8 dp at 130% and 26 dp at 200% (20sp is 34 dp).
        assertEquals(2, HistoryLayout.columns(width = 360f, bodySm = 14f, titleMd = 20f), "100%")
        assertEquals(2, HistoryLayout.columns(width = 360f, bodySm = 16.4f, titleMd = 23f), "115%")
        assertEquals(2, HistoryLayout.columns(width = 360f, bodySm = 18.8f, titleMd = 26f), "130%: 87 of 109 dp")
        assertEquals(1, HistoryLayout.columns(width = 360f, bodySm = 26f, titleMd = 34f), "200%: 120 of 107 dp")
        assertEquals(2, HistoryLayout.columns(width = 320f, bodySm = 14f, titleMd = 20f), "a small phone at 100%")
        assertEquals(2, HistoryLayout.columns(width = 600f, bodySm = 26f, titleMd = 34f), "a tablet at 200%")
    }

    @Test
    fun the_date_column_grows_only_when_today_does_not_fit() {
        assertEquals(44f, HistoryLayout.dateWidth(bodySm = 14f, titleMd = 20f), "09's 44 dp at 100%")
        assertEquals(44f, HistoryLayout.dateWidth(bodySm = 18.8f, titleMd = 26f), "130%")
        assertEquals(47.84f, HistoryLayout.dateWidth(bodySm = 26f, titleMd = 34f), 0.01f, "200%: `오늘` in bold")
    }

    @Test
    fun a_weekly_cell_takes_a_row_of_its_own() {
        val a = cell("a", ScheduleRule.Daily)
        val b = cell("b", ScheduleRule.Daily)
        val c = cell("c", ScheduleRule.Daily)
        val week = cell("week", ScheduleRule.Weekly(DayOfWeek.MONDAY))
        // 09 History's Monday line: two, then one with an empty place, then the weekly cell across.
        assertEquals(listOf(listOf(a, b), listOf(c), listOf(week)), HistoryLayout.rows(listOf(a, b, c, week), columns = 2))
        assertEquals(listOf(listOf(week), listOf(a, b), listOf(c)), HistoryLayout.rows(listOf(week, a, b, c), columns = 2))
        assertEquals(listOf(listOf(a), listOf(week), listOf(b)), HistoryLayout.rows(listOf(a, week, b), columns = 1))
    }

    private fun cell(name: String, rule: ScheduleRule): HistoryCell {
        val person = PersonEntity(id = "p", name = "나", color = "#2B59C3", createdAt = 0, updatedAt = 0, deviceId = "d")
        val window = dailyWindowOn(LocalDate(2026, 11, 9), WindowSettings())
        return HistoryCell(name, name, person, "v", rule, window, CellState.MISSED, judgment = null)
    }
}
