package com.judangbang.pillbadeum.ui

import com.judangbang.pillbadeum.status.Labels
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
