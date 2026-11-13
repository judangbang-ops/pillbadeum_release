package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.design.Tokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TASK-014: the widget's arrangement for a size and font scale. The numbers come from the TASK-044 drawing
 * (`docs/05-ui/states/README.md` section 2): a real 4×2 is about 350×213dp on a Pixel 5 in portrait.
 */
class WidgetLayoutTest {
    private val nb = Labels.NBSP

    /** Android 14's 200%: 14sp → 26dp, 16sp → 28dp (the drawing's `.fs200`). */
    private val largest = WidgetLayout.Fonts(body = 28f, bodySm = 26f)

    private fun button(
        id: String,
        title: String,
        who: String?,
        lines: List<String>,
        state: TileState = TileState.PENDING,
        weekly: Boolean = false,
    ) = WidgetButton(
        intakeId = id,
        periodKey = "2026-11-12",
        title = title,
        who = who,
        palette = Tokens.PEOPLE[0],
        state = state,
        lines = lines,
        largeFirstLine = weekly && state == TileState.PENDING,
        description = title,
    )

    private fun model(buttons: List<WidgetButton>, restore: String? = null) = WidgetModel(
        title = Labels.APP_NAME,
        dateLabel = "11/12 (목)",
        countLabel = "1 / ${buttons.size} 완료 · 건너뜀 1",
        weekday = "목",
        headerDate = "${Labels.APP_NAME} · 11/12",
        countDone = "1 / ${buttons.size}",
        countRest = "완료 · 건너뜀 1",
        buttons = buttons,
        restoreLine = restore,
        showPeople = buttons.any { it.who != null },
    )

    /** The drawing's four items with the longest copy: a dose, a skip, a last dose with a later skip, a weekly item. */
    private val longest = listOf(
        button("a", "아침약", "나", listOf("08:02 먹음"), TileState.TAKEN),
        button("b", "비타민D", "나", listOf("건너뜀"), TileState.SKIPPED),
        button("c", "혈압약", "엄마", listOf("마지막 복용 11/10(화)${nb}21:40", "어제 건너뜀")),
        button("d", "골다공증약", "엄마", listOf("마지막 복용 11/02(월)${nb}08:10"), weekly = true),
    )

    private fun simple(count: Int) = (1..count).map { button("i$it", "약$it", null, listOf("마지막 복용 어제${nb}08:00")) }

    @Test
    fun the_drawing_at_100_percent_has_two_a_row_and_the_arrow() {
        val plan = WidgetLayout.plan(model(longest), 350f, 213f, WidgetLayout.Fonts.DEFAULT, message = null)
        assertEquals(2, plan.columns)
        assertFalse(plan.headerWraps)
        assertEquals(emptySet(), plan.whoAbove, "엄마 골다공증약 fits on the name line, as drawn")
        assertTrue(plan.overflow, "the second row is cut by about 8dp in the drawing")
    }

    @Test
    fun the_drawing_at_200_percent_has_one_a_row_a_two_line_header_and_the_arrow() {
        val plan = WidgetLayout.plan(model(longest), 350f, 213f, largest, message = null)
        assertEquals(1, plan.columns)
        assertTrue(plan.headerWraps)
        assertTrue(plan.overflow)
    }

    @Test
    fun four_short_items_fit_without_an_arrow_and_six_do_not() {
        // REQ-078 acceptance: 6 items on a 4×2 show the arrow; items that all fit show none.
        assertFalse(WidgetLayout.plan(model(simple(4)), 350f, 213f, WidgetLayout.Fonts.DEFAULT, null).overflow)
        assertTrue(WidgetLayout.plan(model(simple(6)), 350f, 213f, WidgetLayout.Fonts.DEFAULT, null).overflow)
        assertFalse(WidgetLayout.plan(model(emptyList()), 350f, 213f, WidgetLayout.Fonts.DEFAULT, null).overflow)
    }

    @Test
    fun one_tile_a_row_from_about_130_percent_on_a_350dp_widget() {
        // README decision 1: two a row up to 115%, one from 130% (name size × 7.1 against the tile text width 140dp).
        assertEquals(140f, WidgetLayout.tileTextWidth(350f - 2 * WidgetLayout.PAD_H, 2))
        assertEquals(2, WidgetLayout.plan(model(simple(2)), 350f, 213f, WidgetLayout.Fonts(18.4f, 16.1f), null).columns)
        assertEquals(1, WidgetLayout.plan(model(simple(2)), 350f, 213f, WidgetLayout.Fonts(20.8f, 18.2f), null).columns)
        assertEquals(1, WidgetLayout.plan(model(simple(2)), 262f, 213f, WidgetLayout.Fonts.DEFAULT, null).columns, "3 cells wide")
    }

    @Test
    fun a_long_person_and_item_name_put_the_person_above() {
        val items = listOf(
            button("a", "골다공증약", "할머니", listOf("기록 없음")),
            button("b", "혈압약", "엄마", listOf("기록 없음")),
        )
        assertEquals(setOf("a"), WidgetLayout.plan(model(items), 350f, 213f, WidgetLayout.Fonts.DEFAULT, null).whoAbove)
    }

    @Test
    fun the_message_takes_the_header_place_and_can_bring_the_arrow() {
        val message = "비타민D: 건너뜀으로 기록됨 (09:10). 바꾸려면 앱에서"
        // Two lines of message (about 53dp) instead of the 28dp header: four short tiles (134dp) no longer fit.
        assertFalse(WidgetLayout.plan(model(simple(4)), 350f, 213f, WidgetLayout.Fonts.DEFAULT, null).overflow)
        assertTrue(WidgetLayout.plan(model(simple(4)), 350f, 213f, WidgetLayout.Fonts.DEFAULT, message).overflow)
        assertFalse(WidgetLayout.plan(model(simple(2)), 350f, 213f, WidgetLayout.Fonts.DEFAULT, message).overflow)
    }

    @Test
    fun the_restore_band_shortens_the_list() {
        val restore = "복원됨 · 마지막 기록 11/09 21:40 · 앱에서 확인"
        assertFalse(WidgetLayout.plan(model(simple(4)), 350f, 213f, WidgetLayout.Fonts.DEFAULT, null).overflow)
        assertTrue(WidgetLayout.plan(model(simple(4), restore), 350f, 213f, WidgetLayout.Fonts.DEFAULT, null).overflow)
    }

    @Test
    fun korean_words_are_joined_so_lines_break_only_at_spaces() {
        val wj = WidgetLayout.WORD_JOINER
        assertEquals("바${wj}꾸${wj}려${wj}면 앱${wj}에${wj}서", WidgetLayout.keepWords("바꾸려면 앱에서"))
        assertEquals("비${wj}타${wj}민${wj}D: (09:10)", WidgetLayout.keepWords("비타민D: (09:10)"))
        assertEquals("어${wj}제${nb}08:00", WidgetLayout.keepWords("어제${nb}08:00"), "nothing next to a no-break space")
        assertEquals("DidITake · 11/12", WidgetLayout.keepWords("DidITake · 11/12"))
        // The joiners take no width, so the estimate is the same with or without them.
        val message = "비타민D: 건너뜀으로 기록됨 (09:10). 바꾸려면 앱에서"
        assertEquals(WidgetLayout.width(message, 14f), WidgetLayout.width(WidgetLayout.keepWords(message), 14f))
        assertEquals(WidgetLayout.lines(message, 302f, 14f), WidgetLayout.lines(WidgetLayout.keepWords(message), 302f, 14f))
    }

    @Test
    fun lines_break_at_spaces_and_keep_a_date_and_time_together() {
        assertEquals(1, WidgetLayout.lines("08:02 먹음", 140f, 14f))
        assertEquals(2, WidgetLayout.lines("마지막 복용 11/10(화)${nb}21:40", 140f, 14f), "마지막 복용 / 11/10(화) 21:40")
        assertEquals(2, WidgetLayout.lines("마지막 복용 11/02(월)${nb}08:10", 140f, 16f))
        // The joined piece is about 7.05em: one line from 7.05 × 16 = 113dp.
        assertEquals(1, WidgetLayout.lines("11/02(월)${nb}08:10", 114f, 16f))
        assertEquals(2, WidgetLayout.lines("11/02(월)${nb}08:10", 100f, 16f), "too wide for any line: cut")
    }
}
