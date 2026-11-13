package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.ArchiveIntake
import com.judangbang.pillbadeum.usecase.InvalidTimeReason
import com.judangbang.pillbadeum.usecase.ManageSettings
import com.judangbang.pillbadeum.usecase.RecordInWindow
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.WindowCommand
import com.judangbang.pillbadeum.usecase.WindowWriteResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** SCR-007's cell, its wording and the time picker's range (TASK-018). Seen on Thursday 11/12 at 18:30 in Seoul. */
class JudgeCellTest {
    private val fx = DbFixture(start = seoul("2026-11-12T18:30"))
    private val load = LoadJudgeCell(fx.db, fx.clock)
    private val window = RecordInWindow(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)

    @AfterTest
    fun tearDown() = fx.close()

    private fun nb(text: String) = text.replace(' ', ' ')

    private suspend fun versionOf(intakeId: String): String = fx.db.scheduleVersionDao().listByIntake(intakeId).single().id

    private suspend fun cell(intakeId: String, periodKey: String): JudgeCell =
        assertIs<JudgeLoad.Ready>(load(intakeId, versionOf(intakeId), periodKey)).cell

    private suspend fun write(intakeId: String, periodKey: String, kind: DoseKind, at: String? = null) {
        val result = window.record(
            WindowCommand(intakeId, versionOf(intakeId), periodKey, kind, at?.let(::seoul), SEOUL.id, renderedAt = fx.clock.now()),
        )
        assertIs<WindowWriteResult.Recorded>(result)
    }

    @Test
    fun the_current_window_is_today_with_the_window_and_what_can_be_picked() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-02T04:00"))
        val today = cell(intake, "2026-11-12")

        assertTrue(today.current)
        assertEquals("오늘 기록", Labels.judgeTitle(today))
        assertEquals("매일", Labels.judgeCycle(today.rule, today.slotLabel))
        assertEquals("${nb("11/12 (목) 04:00")} ~ ${nb("11/13 (금) 04:00")}", Labels.judgeWindow(today, SEOUL))
        assertEquals("11월 12일 목요일 04:00부터 11월 13일 금요일 04:00까지", Labels.judgeWindowSpoken(today, SEOUL))
        assertEquals("아직 기록이 없어요", Labels.judgeStatus(today, SEOUL))
        assertEquals(
            "먹은 시각은 ${nb("오늘 04:00")}부터 지금(18:30)까지 고를 수 있어요. 다른 날은 지난 기록에서 열어요.",
            Labels.judgeNote(today, SEOUL),
        )
        assertFalse(today.locked)
        assertFalse(today.showPeople, "one person")
    }

    /** TASK-044 drawings: a past daily window, empty and then with a dose. */
    @Test
    fun a_past_daily_window_is_titled_with_its_day() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-02T04:00"))
        val empty = cell(intake, "2026-11-11")
        assertFalse(empty.current)
        assertEquals(nb("11/11 (수)") + " " + nb("기록 고치기"), Labels.judgeTitle(empty))
        assertEquals("기록이 없어요", Labels.judgeStatus(empty, SEOUL))
        assertEquals("먹은 시각은 ${nb("11/11 (수) 04:00")}부터 ${nb("11/12 (목) 04:00")} 전까지 고를 수 있어요.", Labels.judgeNote(empty, SEOUL))

        write(intake, "2026-11-11", DoseKind.TAKEN, "2026-11-11T08:10")
        val taken = cell(intake, "2026-11-11")
        assertEquals("08:10에 먹음", Labels.judgeStatus(taken, SEOUL))
        assertEquals("기록을 취소해도 지워지지 않고 무효로 남아요.", Labels.judgeNote(taken, SEOUL))

        // A dose after midnight carries its date (Claude's choice).
        write(intake, "2026-11-10", DoseKind.TAKEN, "2026-11-11T01:30")
        assertEquals("${nb("11/11 (수) 01:30")}에 먹음", Labels.judgeStatus(cell(intake, "2026-11-10"), SEOUL))
    }

    /** TASK-044 drawings: last week's skip of a weekly item, and the current window's skip. */
    @Test
    fun a_weekly_skip_is_titled_with_the_week_and_turns_into_a_dose_with_a_picked_time() = runTest {
        val intake = fx.newIntake(rule = ScheduleRule.Weekly(DayOfWeek.MONDAY), effectiveFrom = seoul("2026-11-02T04:00"))
        write(intake, "2026-11-02", DoseKind.SKIPPED)
        val skipped = cell(intake, "2026-11-02")

        assertEquals(nb("11/02 (월)") + " 주 " + nb("기록 고치기"), Labels.judgeTitle(skipped))
        assertEquals("주 1회 · 월요일", Labels.judgeCycle(skipped.rule, skipped.slotLabel))
        assertEquals("${nb("11/02 (월)")} ~ ${nb("11/08 (일)")}", Labels.judgeWindow(skipped, SEOUL))
        assertEquals("건너뜀", Labels.judgeStatus(skipped, SEOUL), "a past skip stores the window start, which is not shown (P-3)")
        assertEquals(
            "먹었어로 바꾸면 먹은 시각을 먼저 골라요. ${nb("11/02 (월) 04:00")}부터 ${nb("11/09 (월) 04:00")} 전까지예요.",
            Labels.judgeNote(skipped, SEOUL),
        )

        write(intake, "2026-11-09", DoseKind.SKIPPED)
        val now = cell(intake, "2026-11-09")
        assertTrue(now.current)
        assertEquals("오늘 기록", Labels.judgeTitle(now))
        assertEquals("${nb("11/12 (목) 18:30")}에 건너뜀", Labels.judgeStatus(now, SEOUL))
        assertEquals("먹었어로 바꾸면 지금 시각으로 기록해요.", Labels.judgeNote(now, SEOUL))
    }

    /** SCR-007#locked (P-5, RR-44): a window that ended before the day start changed. */
    @Test
    fun a_window_that_ended_before_a_settings_change_is_locked() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-02T04:00"))
        write(intake, "2026-11-10", DoseKind.TAKEN, "2026-11-10T08:30")
        write(intake, "2026-11-09", DoseKind.SKIPPED)
        fx.at("2026-11-11T12:00")
        ManageSettings(fx.db, fx.clock).saveWindowSettings(WindowSettings(dayStart = LocalTime(5, 0)))
        fx.at("2026-11-12T18:30")

        val old = cell(intake, "2026-11-10")
        assertTrue(old.locked)
        assertEquals(Labels.LOCKED_NOTE, Labels.judgeNote(old, SEOUL))
        assertEquals(nb("11/10 (화)"), Labels.judgeWindow(old, SEOUL), "only the date: its hours were the old day start's")
        assertEquals("11월 10일 화요일", Labels.judgeWindowSpoken(old, SEOUL))
        assertEquals("08:30에 먹음", Labels.judgeStatus(old, SEOUL))
        // The skip stored 11/09 04:00, the window start then; the window now starts at 05:00 (P-3).
        assertEquals("건너뜀", Labels.judgeStatus(cell(intake, "2026-11-09"), SEOUL))
        assertFalse(cell(intake, "2026-11-11").locked, "the window holding the change ends after it")
        assertFalse(cell(intake, "2026-11-12").locked)
    }

    @Test
    fun an_archived_item_is_gone_and_an_unknown_cell_is_missing() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-02T04:00"))
        val version = versionOf(intake)
        assertEquals(JudgeLoad.Missing, load(intake, version, "2026-11-13"), "not started")
        assertEquals(JudgeLoad.Missing, load(intake, "no-such-version", "2026-11-11"))
        assertEquals(JudgeLoad.Missing, load(intake, version, "2026-10-30"), "before the first window")
        assertEquals(JudgeLoad.Missing, load(intake, version, "not-a-date"))
        assertTrue(ArchiveIntake(fx.db, fx.clock)(intake))
        assertEquals(JudgeLoad.Gone, load(intake, version, "2026-11-11"))
        assertEquals(JudgeLoad.Gone, load("no-such-intake", version, "2026-11-11"))
    }

    /** SCR-007#future: a dose saved while the clock was ahead keeps its warning in the cell (supplement C-2). */
    @Test
    fun a_dose_from_a_clock_ahead_shows_its_warning() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-02T04:00"))
        fx.at("2026-11-12T21:00")
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = null, source = DoseSource.APP))
        fx.at("2026-11-12T18:30")
        val today = cell(intake, "2026-11-12")
        assertTrue(today.clockWarning)
        assertEquals("21:00에 먹음", Labels.judgeStatus(today, SEOUL))
    }

    /** The picker offers five-minute times in the window, up to now (P-2); a time edit reaches a day around it (P-6). */
    @Test
    fun the_picker_offers_the_window_up_to_now() = runTest {
        val intake = fx.newIntake(effectiveFrom = seoul("2026-11-11T10:00"))
        val now = seoul("2026-11-12T18:32")

        val past = cell(intake, "2026-11-11").recordRange(now, SEOUL)
        assertEquals(listOf(LocalDate(2026, 11, 11), LocalDate(2026, 11, 12)), past.dates)
        assertEquals((4..23).toList(), past.hours(LocalDate(2026, 11, 11)))
        assertEquals((0..3).toList(), past.hours(LocalDate(2026, 11, 12)))
        assertEquals(PickRange.MINUTES, past.minutes(LocalDate(2026, 11, 12), 3), "03:55 is the last time of the window")
        assertNull(past.at(LocalDate(2026, 11, 12), 4, 0), "the next window")
        assertEquals(seoul("2026-11-11T04:00"), past.first)
        assertEquals(seoul("2026-11-12T03:55"), past.last)
        assertEquals("${nb("11/11 (수) 04:00")}부터 ${nb("11/12 (목) 03:55")}까지 고를 수 있어요.", Labels.pickNote(past, edit = false))

        val today = cell(intake, "2026-11-12").recordRange(now, SEOUL)
        assertEquals(listOf(LocalDate(2026, 11, 12)), today.dates)
        assertEquals((0..30 step 5).toList(), today.minutes(LocalDate(2026, 11, 12), 18), "not after now")
        assertEquals((4..18).toList(), today.hours(LocalDate(2026, 11, 12)))

        // A time edit: a day before the window, but not before the item's first window (registered 11/11 10:00, P-4).
        val edit = cell(intake, "2026-11-12").editRange(now, SEOUL)
        assertEquals(seoul("2026-11-11T04:00"), edit.first)
        assertEquals(seoul("2026-11-12T18:30"), edit.last)
        assertEquals(seoul("2026-11-12T08:00"), edit.floor(seoul("2026-11-12T08:02")))
        assertTrue(Labels.pickNote(edit, edit = true).endsWith("다른 날의 시각이면 그날 기록으로 옮겨요."))
    }

    /** A time the phone's clock skips (a daylight-saving gap) is never offered. */
    @Test
    fun the_picker_skips_a_daylight_saving_gap() {
        val york = TimeZone.of("America/New_York")
        fun ny(text: String): Instant = LocalDateTime.parse(text).toInstant(york)
        val range = PickRange(ny("2026-03-08T00:00"), ny("2026-03-08T06:00"), york)
        assertEquals(listOf(0, 1, 3, 4, 5, 6), range.hours(LocalDate(2026, 3, 8)), "02:00–02:59 does not exist that night")
        assertNull(range.at(LocalDate(2026, 3, 8), 2, 30))
        assertEquals(listOf(0), range.minutes(LocalDate(2026, 3, 8), 6))
    }

    @Test
    fun messages_after_a_refused_write() {
        val kept = Instant.fromEpochMilliseconds(0)
        val record = com.judangbang.pillbadeum.data.DoseRecordEntity(
            id = "a", intakeId = "i", scheduleVersionId = "v", periodKey = "2026-11-11", kind = DoseKind.TAKEN,
            takenAt = seoul("2026-11-11T21:30").toEpochMilliseconds(), zoneId = SEOUL.id,
            recordedAt = kept.toEpochMilliseconds(), source = DoseSource.APP, active = 1, createdAt = 0, updatedAt = 0,
            deviceId = TEST_DEVICE_ID,
        )
        val now = seoul("2026-11-11T22:00")
        assertEquals("이미 기록됨 (21:30)", Labels.judgeAlready(record, dated = false, now, SEOUL))
        assertEquals("11/11에 이미 기록됨 (21:30)", Labels.judgeAlready(record, dated = true, now, SEOUL), "REQ-031")
        assertEquals("이미 건너뜀으로 기록됨", Labels.judgeAlready(record.copy(kind = DoseKind.SKIPPED), dated = false, now, SEOUL))
        assertEquals("지금보다 뒤 시각은 고를 수 없어요", Labels.invalidTime(InvalidTimeReason.FUTURE))
        assertEquals("아침약 건너뜀을 취소했어요. 기록은 무효로 남아요", Labels.voided("아침약", skip = true))
    }
}
