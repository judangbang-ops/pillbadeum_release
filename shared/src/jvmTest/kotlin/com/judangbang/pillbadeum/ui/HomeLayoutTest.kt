package com.judangbang.pillbadeum.ui

import com.judangbang.pillbadeum.status.Labels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SCR-004 tiles a row (09 BoxHome: two at 100%, one at 200%). Sizes are dp; 14sp is 14 dp at 100%. */
class HomeLayoutTest {
    @Test
    fun two_tiles_a_row_until_the_weekly_date_no_longer_fits() {
        // Android 14 grows text nonlinearly: 14sp is 16.4 dp at 115%, 18.8 dp at 130% and 26 dp at 200%.
        assertEquals(2, HomeLayout.columns(width = 360f, bodySm = 14f), "100%")
        assertEquals(2, HomeLayout.columns(width = 360f, bodySm = 16.4f), "115%")
        assertEquals(1, HomeLayout.columns(width = 360f, bodySm = 18.8f), "130%: the bold weekly date needs 138 of 136 dp")
        assertEquals(1, HomeLayout.columns(width = 360f, bodySm = 26f), "200%")
        assertEquals(2, HomeLayout.columns(width = 320f, bodySm = 14f), "a small phone at 100%")
        assertEquals(2, HomeLayout.columns(width = 600f, bodySm = 26f), "a tablet at 200%")
    }

    @Test
    fun the_longest_piece_is_a_weekly_last_dose_that_cannot_break() {
        assertTrue(Labels.NBSP in HomeLayout.LONGEST_PIECE)
        assertEquals("11/02(월)${Labels.NBSP}08:10", HomeLayout.LONGEST_PIECE)
    }

    @Test
    fun the_week_box_names_go_above_when_one_does_not_fit_its_column() {
        // 09 BoxHome's names fit the 64dp column at 100%; the TASK-030 emulator run cut 종합비타민 to 종합비타….
        assertFalse(HomeLayout.weekNamesAbove(columns = 2, names = listOf("아침약", "비타민D", "혈압약"), bodySm = 14f))
        assertFalse(HomeLayout.weekNamesAbove(columns = 2, names = listOf("유산균", "오메가3"), bodySm = 14f))
        assertTrue(HomeLayout.weekNamesAbove(columns = 2, names = listOf("유산균", "종합비타민"), bodySm = 14f))
        assertTrue(HomeLayout.weekNamesAbove(columns = 2, names = listOf("글루코사민 콘드로이친"), bodySm = 14f))
        assertTrue(HomeLayout.weekNamesAbove(columns = 1, names = listOf("아침약"), bodySm = 26f), "200%: always above")
    }
}
