package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.ManagePeople
import com.judangbang.pillbadeum.usecase.PersonChange
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.SaveIntake
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Skip display and counting (TASK-013, supplement S-1~S-4), the weekly last dose (TASK-041, W-2·W-3) and times
 * in the phone's zone (TASK-043, REQ-082). Tests named rrNN follow the supplement's counterexample rows; do not
 * edit their expectations to make them pass. `\u00a0` is the no-break space between a date and its time.
 */
class LabelsTest {
    private val fx = DbFixture(start = seoul("2026-11-01T12:00"))
    private val load = LoadStatusBoard(fx.db, fx.clock)
    private val people = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val save = SaveIntake(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)

    @AfterTest
    fun tearDown() = fx.close()

    /** An item of the one default person "나", registered on 11/01. */
    private suspend fun mine(name: String, rule: ScheduleRule = ScheduleRule.Daily): String {
        val me = people.ensureDefault().first()
        return assertIs<SaveIntakeResult.Saved>(save.create(IntakeDraft(me.id, name, rule = rule))).intakeId
    }

    /** Records at [at] in the window of that time, as a tap in the app would. */
    private suspend fun recordAt(intakeId: String, at: String, kind: DoseKind = DoseKind.TAKEN) {
        val keep = fx.clock.now
        fx.at(at)
        val key = load().items.single { it.intakeId == intakeId }.window.periodKey
        assertIs<RecordResult.Recorded>(fx.record(intakeId, renderedKey = key, source = DoseSource.APP, kind = kind))
        fx.clock.now = keep
    }

    private suspend fun button(intakeId: String, zone: TimeZone = SEOUL): WidgetButton =
        WidgetModel.from(load(), zone).buttons.single { it.intakeId == intakeId }

    @Test
    fun rr35_a_skip_is_counted_apart_drawn_grey_and_a_tap_changes_nothing() = runTest {
        val vitamin = mine("비타민D")
        recordAt(vitamin, "2026-11-11T09:10", DoseKind.SKIPPED)
        fx.at("2026-11-11T09:30")

        val model = WidgetModel.from(load(), SEOUL)
        assertEquals("0 / 1 완료 · 건너뜀 1", model.countLabel)
        assertEquals("0 / 1" to "완료 · 건너뜀 1", model.countDone to model.countRest, "the header's bold part and the rest")
        val tile = model.buttons.single()
        assertEquals(TileState.SKIPPED, tile.state)
        assertEquals(listOf("건너뜀"), tile.lines)
        assertEquals("비타민D, 건너뜀", tile.description, "one person: the person is not read (S-4)")

        // S-3: a tap records nothing and says where to change it; the judgment row stays as it was.
        val tap = fx.record(vitamin, renderedKey = tile.periodKey)
        assertEquals("비타민D: 건너뜀으로 기록됨 (09:10). 바꾸려면 앱에서", Labels.recordMessage(tap, "비타민D", fx.clock.now(), SEOUL))
        assertEquals(listOf(DoseKind.SKIPPED), fx.activeRows(vitamin).map { it.kind })
        assertEquals(RowText("매일 · 건너뜀 09:10"), Labels.appRow(load().items.single(), load(), SEOUL))
    }

    @Test
    fun the_header_counts_doses_only() = runTest {
        val morning = mine("아침약")
        mine("저녁약")
        val vitamin = mine("비타민D")
        recordAt(morning, "2026-11-11T08:02")
        recordAt(vitamin, "2026-11-11T09:10", DoseKind.SKIPPED)
        fx.at("2026-11-11T10:00")
        val board = load()
        assertEquals(1, board.doneCount)
        assertEquals(1, board.skippedCount)
        assertEquals("1 / 3 완료 · 건너뜀 1", Labels.count(board))
        assertTrue(board.items.single { it.intakeId == vitamin }.isDone, "a skip still makes the window judged")

        fx.at("2026-11-12T10:00")
        assertEquals("0 / 3 완료", Labels.count(load()), "no skip today: no skip part")
    }

    @Test
    fun rr36_the_last_dose_and_a_later_skip_on_two_lines() = runTest {
        val pressure = mine("혈압약")
        recordAt(pressure, "2026-11-10T22:00")
        recordAt(pressure, "2026-11-11T09:10", DoseKind.SKIPPED)
        fx.at("2026-11-12T10:00") // Thursday

        val tile = button(pressure)
        assertEquals(TileState.PENDING, tile.state)
        assertEquals(listOf("마지막 복용 11/10(화)\u00a022:00", "어제 건너뜀"), tile.lines, "'그저께' is not used")
        assertEquals("혈압약, 미완료, 마지막 복용 11월 10일 화요일 22:00, 어제 건너뜀", tile.description)
        assertEquals(
            RowText("매일 · 마지막 복용 11/10(화)\u00a022:00", skipLine = "어제 건너뜀"),
            Labels.appRow(load().items.single(), load(), SEOUL),
        )
    }

    @Test
    fun rr37_no_dose_yet_and_a_skip_yesterday() = runTest {
        val pressure = mine("혈압약")
        recordAt(pressure, "2026-11-11T09:10", DoseKind.SKIPPED)
        fx.at("2026-11-12T10:00")

        assertEquals(listOf("기록 없음", "어제 건너뜀"), button(pressure).lines)
        assertEquals("혈압약, 미완료, 기록 없음, 어제 건너뜀", button(pressure).description)
    }

    @Test
    fun a_skip_before_the_last_dose_is_not_shown() = runTest {
        val pressure = mine("혈압약")
        recordAt(pressure, "2026-11-10T09:00", DoseKind.SKIPPED)
        recordAt(pressure, "2026-11-11T21:40")
        fx.at("2026-11-12T10:00")

        assertEquals(listOf("마지막 복용 어제\u00a021:40"), button(pressure).lines)
    }

    @Test
    fun rr76_today_and_yesterday_count_calendar_dates_not_windows() = runTest {
        val pressure = mine("혈압약")
        recordAt(pressure, "2026-11-09T03:00") // Monday 03:00: the judgment of Sunday's window
        fx.at("2026-11-09T10:00")

        val tile = button(pressure)
        assertEquals(TileState.PENDING, tile.state, "Monday's window is not judged")
        assertEquals(listOf("마지막 복용 오늘\u00a003:00"), tile.lines)
    }

    /** The reminder body (R3, PR #20) reads [Labels.lastLabel] until it moves to [Labels.lastTaken]: same output. */
    @Test
    fun the_old_last_label_keeps_its_wording_for_the_reminder_body() = runTest {
        val pressure = mine("혈압약")
        recordAt(pressure, "2026-11-04T09:10")
        suspend fun last() = load().items.single().lastBefore
        fx.at("2026-11-10T10:00")
        assertEquals("마지막 11/04 (수) 09:10", Labels.lastLabel(last(), fx.clock.now(), SEOUL))
        recordAt(pressure, "2026-11-09T21:40", DoseKind.SKIPPED)
        assertEquals("마지막 어제 21:40", Labels.lastLabel(last(), fx.clock.now(), SEOUL), "either kind, plain space")
        assertEquals(Labels.NO_RECORD, Labels.lastLabel(null, fx.clock.now(), SEOUL))
    }

    @Test
    fun rr58_times_follow_the_phone_zone_while_windows_and_the_base_date_stay() = runTest {
        val london = TimeZone.of("Europe/London")
        val morning = mine("아침약")
        recordAt(morning, "2026-11-11T08:02") // 11/10 23:02 in London
        fx.at("2026-11-11T09:00") // 11/11 00:00 in London

        val model = WidgetModel.from(load(), london)
        assertEquals("11/11 (수)", model.dateLabel, "the base date is Seoul's")
        val tile = model.buttons.single()
        assertEquals("2026-11-11", tile.periodKey)
        assertEquals(listOf("23:02 먹음"), tile.lines)
        assertEquals("아침약, 복용함, 어제 23:02", tile.description, "yesterday by London's calendar")
        assertEquals(listOf("08:02 먹음"), button(morning, SEOUL).lines)

        fx.at("2026-11-12T10:00") // 11/12 01:00 in London
        assertEquals(listOf("마지막 복용 11/10(화)\u00a023:02"), button(morning, london).lines)
        assertEquals(listOf("마지막 복용 어제\u00a008:02"), button(morning, SEOUL).lines)
    }

    @Test
    fun rr59_a_weekly_last_dose_with_its_weekday_at_the_names_size() = runTest {
        val bone = mine("골다공증약", ScheduleRule.Weekly(DayOfWeek.MONDAY))
        recordAt(bone, "2026-11-08T22:00") // Sunday, the window of Monday 11/02
        fx.at("2026-11-09T08:00") // Monday

        val tile = button(bone)
        assertEquals(TileState.PENDING, tile.state)
        assertEquals(listOf("마지막 복용 어제(일)\u00a022:00"), tile.lines)
        assertTrue(tile.largeFirstLine)
        assertEquals("골다공증약, 이번 주 미완료, 마지막 복용 어제 일요일 22:00", tile.description)
        val item = load().items.single()
        assertEquals("마지막 복용 어제(일)\u00a022:00", Labels.lastTaken(item, fx.clock.now(), SEOUL), "the reminder body line (W-4)")
        // The app tile (09 BoxHome): the schedule, then the same line in bold (W-2, REQ-075).
        assertEquals(RowText("주 1회 월요일", boldLine = "마지막 복용 어제(일)\u00a022:00"), Labels.appRow(item, load(), SEOUL))
    }

    @Test
    fun rr60_a_tap_after_a_late_weekly_dose_still_records_and_keeps_the_anchor() = runTest {
        val bone = mine("골다공증약", ScheduleRule.Weekly(DayOfWeek.MONDAY))
        recordAt(bone, "2026-11-08T22:00")
        fx.at("2026-11-09T08:10")

        assertIs<RecordResult.Recorded>(fx.record(bone, renderedKey = button(bone).periodKey))
        fx.at("2026-11-16T08:00")
        assertEquals("2026-11-16", load().items.single().window.periodKey, "still Monday")
    }

    @Test
    fun rr61_a_weekly_item_never_taken() = runTest {
        val bone = mine("골다공증약", ScheduleRule.Weekly(DayOfWeek.MONDAY))
        fx.at("2026-11-09T08:00")
        assertEquals(listOf("기록 없음"), button(bone).lines)
        assertEquals("골다공증약, 이번 주 미완료, 기록 없음", button(bone).description)
    }

    @Test
    fun rr62_a_dose_on_another_day_of_the_window_is_the_last_dose() = runTest {
        val bone = mine("골다공증약", ScheduleRule.Weekly(DayOfWeek.MONDAY))
        recordAt(bone, "2026-11-10T08:00") // Tuesday, the window of Monday 11/09
        fx.at("2026-11-16T08:00")

        assertEquals("2026-11-16", load().items.single().window.periodKey)
        assertEquals(listOf("마지막 복용 11/10(화)\u00a008:00"), button(bone).lines)
    }

    @Test
    fun rr63_a_daily_last_dose_keeps_the_small_line() = runTest {
        val pressure = mine("혈압약")
        recordAt(pressure, "2026-11-10T22:00")
        fx.at("2026-11-11T08:00")

        val tile = button(pressure)
        assertEquals(listOf("마지막 복용 어제\u00a022:00"), tile.lines)
        assertFalse(tile.largeFirstLine)
    }

    @Test
    fun weekly_skips_say_last_week_or_their_date() = runTest {
        val bone = mine("골다공증약", ScheduleRule.Weekly(DayOfWeek.MONDAY))
        recordAt(bone, "2026-11-02T08:00")
        recordAt(bone, "2026-11-09T09:00", DoseKind.SKIPPED)

        fx.at("2026-11-16T10:00")
        assertEquals(listOf("마지막 복용 11/02(월)\u00a008:00", "지난주 건너뜀"), button(bone).lines)
        assertEquals("골다공증약, 이번 주 미완료, 마지막 복용 11월 2일 월요일 08:00, 지난주 건너뜀", button(bone).description)

        fx.at("2026-11-23T10:00")
        assertEquals(listOf("마지막 복용 11/02(월)\u00a008:00", "11/09(월) 건너뜀"), button(bone).lines)
        assertEquals("골다공증약, 이번 주 미완료, 마지막 복용 11월 2일 월요일 08:00, 11월 9일 월요일 건너뜀", button(bone).description)
    }

    @Test
    fun the_person_is_read_and_badged_only_with_two_people() = runTest {
        val morning = mine("아침약")
        recordAt(morning, "2026-11-11T08:02")
        fx.at("2026-11-11T09:00")
        assertFalse(WidgetModel.from(load(), SEOUL).showPeople)
        assertEquals("아침약, 복용함, 오늘 08:02", button(morning).description)

        people.add("엄마", "#C2501A")
        assertTrue(WidgetModel.from(load(), SEOUL).showPeople)
        assertEquals("나 아침약, 복용함, 오늘 08:02", button(morning).description)
    }

    /**
     * SPEC-027 (RR-57): '나' and '엄마', then '나' is archived. 엄마 is left alone, so every place drops the person
     * again: the board, the widget tile and its TalkBack sentence, and the history screen's person picker (TASK-015).
     * History still shows 나's past cells.
     */
    @Test
    fun rr57_archiving_me_leaves_mom_without_badge_name_or_picker() = runTest {
        val history = LoadHistory(fx.db, fx.clock)
        val morning = mine("아침약")
        val me = people.list().single()
        val mom = people.add("엄마", "#C2501A")
        val pressure = assertIs<SaveIntakeResult.Saved>(save.create(IntakeDraft(mom.id, "혈압약"))).intakeId
        recordAt(pressure, "2026-11-10T21:40")
        fx.at("2026-11-11T09:00")
        assertTrue(load().showPeople)
        assertEquals("엄마", button(pressure).who)
        assertEquals("엄마 혈압약, 미완료, 마지막 복용 어제 21:40", button(pressure).description)
        assertTrue(history(LocalDate(2026, 11, 1)).showPeople)

        assertEquals(PersonChange.Saved, people.archive(me.id))
        val board = load()
        assertFalse(board.showPeople)
        assertEquals(listOf(pressure), board.items.map { it.intakeId }, "나's item left the board with her")
        assertEquals(null, button(pressure).who)
        assertEquals("혈압약, 미완료, 마지막 복용 어제 21:40", button(pressure).description)
        val month = history(LocalDate(2026, 11, 1))
        assertFalse(month.showPeople, "one active person: no picker on SCR-008 (REQ-018)")
        assertTrue(month.days.any { day -> day.cells.any { it.intakeId == morning } }, "나's past cells stay in history")
    }

    @Test
    fun weekly_doses_show_their_weekday() = runTest {
        val bone = mine("골다공증약", ScheduleRule.Weekly(DayOfWeek.MONDAY))
        recordAt(bone, "2026-11-10T08:00")
        fx.at("2026-11-11T09:00")

        val tile = button(bone)
        assertEquals(TileState.TAKEN, tile.state)
        assertEquals(listOf("화\u00a008:00 먹음"), tile.lines)
        assertEquals("골다공증약, 복용함, 어제 화요일 08:00", tile.description)
        assertFalse(tile.largeFirstLine)
    }

    @Test
    fun the_first_screen_reads_the_date_and_the_day_start_in_words() = runTest {
        assertEquals("11월 12일 목요일", Labels.longDate(LocalDate(2026, 11, 12)))
        assertEquals("1월 5일 월요일", Labels.longDate(LocalDate(2026, 1, 5)))
        mine("아침약")
        fx.at("2026-11-12T09:30")
        assertEquals("하루 시작 04:00", Labels.dayStart(load()))
    }

    /** 09 BoxHome's row labels: days in a row with one state are read together, later days are left out. */
    @Test
    fun the_week_box_reads_each_row_as_one_sentence() = runTest {
        val pressure = mine("혈압약")
        val vitamin = mine("비타민D")
        recordAt(pressure, "2026-11-09T21:00")
        recordAt(pressure, "2026-11-10T21:00")
        for (day in listOf("09", "10", "11")) recordAt(vitamin, "2026-11-${day}T09:00")
        recordAt(vitamin, "2026-11-12T09:10", DoseKind.SKIPPED)
        fx.at("2026-11-12T09:30") // Thursday

        val board = load()
        val row = { id: String -> board.items.single { it.intakeId == id } }
        assertEquals(
            listOf(DayState.DONE, DayState.DONE, DayState.DONE, DayState.SKIPPED, DayState.FUTURE),
            row(vitamin).weekCells.take(5).map { it.state },
        )
        assertEquals("혈압약 이번 주: 월 화 복용, 수 빠짐, 목 아직", Labels.weekSpoken(row(pressure), withPerson = false))
        assertEquals("비타민D 이번 주: 월 화 수 복용, 목 건너뜀", Labels.weekSpoken(row(vitamin), withPerson = false))
        assertEquals("나 비타민D 이번 주: 월 화 수 복용, 목 건너뜀", Labels.weekSpoken(row(vitamin), withPerson = true))
    }

    @Test
    fun a_clock_warning_is_read_as_a_clock_check_in_the_app() = runTest {
        val morning = mine("아침약")
        recordAt(morning, "2026-11-12T09:00")
        fx.at("2026-11-12T08:00") // the clock went back an hour: the dose is now ahead of it
        val board = load()
        val item = board.items.single()
        assertTrue(item.clockWarning)
        assertEquals("아침약, 복용함, 오늘 09:00, 앱에서 확인", Labels.spoken(item, board.now, SEOUL, withPerson = false))
        assertEquals(
            "아침약, 복용함, 오늘 09:00, 시계 확인 필요",
            Labels.spoken(item, board.now, SEOUL, withPerson = false, name = item.name, inApp = true),
        )
        assertEquals(listOf("아침약"), board.clockWarnings.map { it.intakeName })
    }

    @Test
    fun the_archive_question_takes_the_particle_of_the_name() {
        assertEquals("아침약을", Labels.withObject("아침약"))
        assertEquals("유산균을", Labels.withObject("유산균"))
        assertEquals("철분제를", Labels.withObject("철분제"))
        assertEquals("비타민D을(를)", Labels.withObject("비타민D"))
        assertEquals("Vitamin을(를)", Labels.withObject("Vitamin"))
        assertEquals("을(를)", Labels.withObject(""))
    }
}
