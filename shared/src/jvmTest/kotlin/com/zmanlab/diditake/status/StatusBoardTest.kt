package com.zmanlab.diditake.status

import com.zmanlab.diditake.domain.ScheduleRule
import com.zmanlab.diditake.testing.DbFixture
import com.zmanlab.diditake.testing.SEOUL
import com.zmanlab.diditake.testing.TEST_DEVICE_ID
import com.zmanlab.diditake.testing.seoul
import com.zmanlab.diditake.usecase.IntakeDraft
import com.zmanlab.diditake.usecase.ManagePeople
import com.zmanlab.diditake.usecase.RecordResult
import com.zmanlab.diditake.usecase.SaveIntake
import com.zmanlab.diditake.usecase.SaveIntakeResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

class StatusBoardTest {
    private val fx = DbFixture()
    private val load = LoadStatusBoard(fx.db, fx.clock)

    @AfterTest
    fun tearDown() = fx.close()

    @Test
    fun empty_database_gives_empty_board_and_widget() = runTest {
        val board = load()
        assertTrue(board.items.isEmpty())
        val model = WidgetModel.from(board)
        assertEquals("11/11 (수)", model.dateLabel)
        assertEquals("0 / 0 완료", model.countLabel)
        assertTrue(model.buttons.isEmpty())
    }

    @Test
    fun normal_day_counts_done_and_pending_with_labels() = runTest {
        val morning = fx.newIntake()
        val evening = fx.newIntake()
        fx.at("2026-11-10T21:40")
        assertIs<RecordResult.Recorded>(fx.record(evening, renderedKey = "2026-11-10"))
        fx.at("2026-11-11T08:02")
        assertIs<RecordResult.Recorded>(fx.record(morning, renderedKey = "2026-11-11"))
        fx.at("2026-11-11T08:12")

        val board = load()
        assertEquals(LocalDate(2026, 11, 11), board.baseDate)
        assertEquals(1, board.doneCount)
        assertEquals(2, board.totalCount)

        val model = WidgetModel.from(board)
        assertEquals("1 / 2 완료", model.countLabel)
        val done = model.buttons.single { it.intakeId == morning }
        val pending = model.buttons.single { it.intakeId == evening }
        assertTrue(done.done)
        assertEquals("08:02", done.subtitle)
        assertEquals("2026-11-11", done.periodKey)
        assertEquals("T", done.personInitial)
        assertFalse(pending.done)
        assertEquals("마지막 어제 21:40", pending.subtitle)
        assertEquals("Test person Morning dose, 미완료", pending.description)
    }

    @Test
    fun never_recorded_item_says_no_record() = runTest {
        fx.newIntake()
        val model = WidgetModel.from(load())
        assertEquals(Labels.NO_RECORD, model.buttons.single().subtitle)
    }

    @Test
    fun voided_judgment_turns_the_item_back_to_pending() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:02")
        val recorded = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))
        fx.voidDose(recorded.record.id)

        val item = load().items.single()
        assertFalse(item.isDone)
        assertNull(item.lastBefore, "voided rows are not a last record")
    }

    @Test
    fun day_start_boundary_0400_switches_base_date_and_period_key() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T03:50")
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-10"))

        fx.at("2026-11-11T03:59:59")
        val before = load()
        assertEquals(LocalDate(2026, 11, 10), before.baseDate)
        assertEquals("11/10 (화)", WidgetModel.from(before).dateLabel)
        assertTrue(before.items.single().isDone)

        fx.at("2026-11-11T04:00")
        val after = load()
        assertEquals("11/11 (수)", WidgetModel.from(after).dateLabel)
        val button = WidgetModel.from(after).buttons.single()
        assertFalse(button.done)
        assertEquals("2026-11-11", button.periodKey)
        assertEquals("마지막 오늘 03:50", button.subtitle, "a dose 10 minutes ago stays visible")

        // A widget still rendered before 04:00 sends the old key: nothing is recorded.
        assertEquals(RecordResult.Stale("2026-11-11"), fx.record(intake, renderedKey = before.items.single().window.periodKey))
        assertEquals(1, fx.history(intake).size)
    }

    @Test
    fun weekly_window_edge_and_next_window() = runTest {
        val intake = fx.newIntake(rule = ScheduleRule.Weekly(DayOfWeek.MONDAY))
        fx.at("2026-11-09T08:30")
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-09"))

        fx.at("2026-11-16T03:59")
        val lastMinute = load()
        val item = lastMinute.items.single()
        assertTrue(item.isDone)
        assertEquals(seoul("2026-11-16T04:00"), item.nextWindowStart)
        assertEquals("월 08:30", Labels.widgetSubtitle(item, lastMinute.now, SEOUL))
        assertTrue(item.weekCells.isEmpty(), "week grid is for daily items only")
        assertEquals(
            "주 1회 · 월 · 이번 창 11/09 (월)~ · 월 08:30 완료 · 다음 창 11/16 (월)",
            Labels.appDetail(item, lastMinute),
        )

        fx.at("2026-11-16T04:00")
        val nextWindow = load()
        val pending = nextWindow.items.single()
        assertFalse(pending.isDone)
        assertEquals("2026-11-16", pending.window.periodKey)
        assertEquals(seoul("2026-11-23T04:00"), pending.nextWindowStart)
        assertEquals("마지막 11/09 (월) 08:30", Labels.widgetSubtitle(pending, nextWindow.now, SEOUL))
    }

    @Test
    fun week_grid_marks_done_missed_today_future_and_unscheduled() = runTest {
        val old = fx.newIntake()
        fx.at("2026-11-09T08:00")
        fx.record(old, renderedKey = "2026-11-09")
        fx.at("2026-11-11T02:00") // still Tuesday's window
        fx.record(old, renderedKey = "2026-11-10")

        fx.at("2026-11-11T08:00")
        val me = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID).ensureDefault().first().id
        val fresh = assertIs<SaveIntakeResult.Saved>(
            SaveIntake(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID).create(IntakeDraft(me, "새 항목")),
        ).intakeId

        val board = load()
        val oldCells = board.items.first { it.intakeId == old }.weekCells
        assertEquals(
            listOf(
                DayState.DONE, DayState.DONE, DayState.TODAY_PENDING,
                DayState.FUTURE, DayState.FUTURE, DayState.FUTURE, DayState.FUTURE,
            ),
            oldCells.map { it.state },
        )
        assertEquals(listOf(false, false, true, false, false, false, false), oldCells.map { it.isToday })

        val freshCells = board.items.first { it.intakeId == fresh }.weekCells
        assertEquals(
            listOf(DayState.NOT_SCHEDULED, DayState.NOT_SCHEDULED, DayState.TODAY_PENDING),
            freshCells.take(3).map { it.state },
        )

        fx.at("2026-11-13T08:00") // Friday: Wed/Thu without records are missed
        val later = load().items.first { it.intakeId == old }.weekCells
        assertEquals(DayState.EMPTY, later[2].state)
        assertEquals(DayState.EMPTY, later[3].state)
        assertEquals(DayState.TODAY_PENDING, later[4].state)
    }

    @Test
    fun record_messages_match_record_rules_table() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:02")
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))
        fx.at("2026-11-11T08:15")
        val already = fx.record(intake, renderedKey = "2026-11-11")
        val now = seoul("2026-11-11T08:15")

        assertEquals("아침약: 이미 기록됨 (08:02). 변경 없음", Labels.recordMessage(already, "아침약", now, SEOUL))
        assertEquals(Labels.STALE, Labels.recordMessage(RecordResult.Stale("2026-11-12"), "아침약", now, SEOUL))
        assertNull(Labels.recordMessage(RecordResult.Gone, "아침약", now, SEOUL))
        assertEquals(Labels.ERROR, Labels.recordMessage(RecordResult.Error("x"), "아침약", now, SEOUL))
        // An older judgment (weekly) shows its weekday.
        assertEquals(
            "주사: 이미 기록됨 (월 08:30). 변경 없음",
            Labels.recordMessage(
                RecordResult.Already((already as RecordResult.Already).existing.copy(takenAt = seoul("2026-11-09T08:30").toEpochMilliseconds())),
                "주사", now, SEOUL,
            ),
        )
    }

    @Test
    fun alias_is_shown_with_person_badge() = runTest {
        val me = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID).ensureDefault().first().id
        SaveIntake(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID).create(IntakeDraft(me, "아침에 먹는 약", widgetAlias = "아침약"))
        val button = WidgetModel.from(load()).buttons.single()
        assertEquals("아침약", button.title)
        assertEquals("나", button.personInitial)
        assertEquals("#3F7D58", button.personColor)
    }
}
