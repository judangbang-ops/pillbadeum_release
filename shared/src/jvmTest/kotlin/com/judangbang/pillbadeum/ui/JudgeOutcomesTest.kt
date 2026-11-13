package com.judangbang.pillbadeum.ui

import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.windowWithKey
import com.judangbang.pillbadeum.status.JudgeCell
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.ClockFlagResult
import com.judangbang.pillbadeum.usecase.InvalidTimeReason
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.VoidResult
import com.judangbang.pillbadeum.usecase.WindowWriteResult
import kotlin.test.Test
import kotlin.test.assertEquals

/** What SCR-007 does after each write result (screens.md SCR-007 tables, TASK-018). */
class JudgeOutcomesTest {
    private val settings = WindowSettings()
    private val now = seoul("2026-11-12T09:00")

    private fun dose(key: String, at: String, kind: DoseKind = DoseKind.TAKEN, version: String = "v1") = DoseRecordEntity(
        id = "d-$key-$at", intakeId = "i", scheduleVersionId = version, periodKey = key, kind = kind,
        takenAt = seoul(at).toEpochMilliseconds(), zoneId = SEOUL.id, recordedAt = now.toEpochMilliseconds(),
        source = DoseSource.APP, createdAt = 0, updatedAt = 0, deviceId = TEST_DEVICE_ID,
    )

    private fun cell(key: String, judgment: DoseRecordEntity? = null) = JudgeCell(
        intakeId = "i", intakeName = "아침약", slotLabel = null,
        person = PersonEntity(id = "p", name = "나", color = "#2B59C3", createdAt = 0, updatedAt = 0, deviceId = TEST_DEVICE_ID),
        scheduleVersionId = "v1", rule = ScheduleRule.Daily, window = checkNotNull(windowWithKey(ScheduleRule.Daily, key, settings)),
        judgment = judgment, clockWarning = false, locked = false, cut = false, earliest = seoul("2026-11-01T04:00"),
        now = now, settings = settings, showPeople = false,
    )

    @Test
    fun a_record_stays_and_a_time_edit_out_of_the_window_follows_the_dose() {
        val yesterday = cell("2026-11-11", dose("2026-11-11", "2026-11-11T08:10"))
        val same = dose("2026-11-11", "2026-11-11T09:00")
        assertEquals(JudgeAfter.Stay(null, wrote = true), JudgeOutcomes.of(WindowWriteResult.Recorded(same), yesterday, edit = false, now, SEOUL))
        assertEquals(
            JudgeAfter.Stay(Labels.TIME_EDITED, wrote = true),
            JudgeOutcomes.of(WindowWriteResult.Recorded(same), yesterday, edit = true, now, SEOUL),
        )
        // P-6: 11/12 01:30 is still the 11/11 window; 11/12 05:00 is the 11/12 window, shown next.
        val night = dose("2026-11-11", "2026-11-12T01:30")
        assertEquals(JudgeAfter.Stay(Labels.TIME_EDITED, wrote = true), JudgeOutcomes.of(WindowWriteResult.Recorded(night), yesterday, edit = true, now, SEOUL))
        val moved = dose("2026-11-12", "2026-11-12T05:00", version = "v2")
        assertEquals(
            JudgeAfter.Move("v2", "2026-11-12", Labels.TIME_EDITED),
            JudgeOutcomes.of(WindowWriteResult.Recorded(moved), yesterday, edit = true, now, SEOUL),
        )
    }

    @Test
    fun refused_writes_say_why_and_change_nothing() {
        val shown = cell("2026-11-11")
        val other = dose("2026-11-10", "2026-11-10T21:30")
        assertEquals(
            JudgeAfter.Stay("11/10에 이미 기록됨 (21:30)", wrote = false),
            JudgeOutcomes.of(WindowWriteResult.Already(other), shown, edit = true, now, SEOUL),
            "REQ-031",
        )
        assertEquals(
            JudgeAfter.Stay("이미 기록됨 (수\u00a021:30)", wrote = false),
            JudgeOutcomes.of(WindowWriteResult.Already(dose("2026-11-11", "2026-11-11T21:30")), shown, edit = false, now, SEOUL),
            "SCR-007#already: recorded elsewhere first",
        )
        assertEquals(JudgeAfter.Stay(Labels.UNCHANGED, wrote = false), JudgeOutcomes.of(WindowWriteResult.Unchanged, shown, edit = true, now, SEOUL))
        assertEquals(JudgeAfter.Stay(Labels.LOCKED, wrote = false), JudgeOutcomes.of(WindowWriteResult.Locked, shown, edit = true, now, SEOUL))
        assertEquals(
            JudgeAfter.Stay(Labels.invalidTime(InvalidTimeReason.BEFORE_SCHEDULE), wrote = false),
            JudgeOutcomes.of(WindowWriteResult.InvalidTime(InvalidTimeReason.BEFORE_SCHEDULE), shown, edit = true, now, SEOUL),
        )
        assertEquals(JudgeAfter.Stale, JudgeOutcomes.of(WindowWriteResult.Stale, shown, edit = false, now, SEOUL))
        assertEquals(JudgeAfter.Home(Labels.GONE), JudgeOutcomes.of(WindowWriteResult.Gone, shown, edit = false, now, SEOUL))
        assertEquals(JudgeAfter.Stay(Labels.TRY_AGAIN, wrote = false), JudgeOutcomes.of(WindowWriteResult.Error("x"), shown, edit = false, now, SEOUL))
    }

    @Test
    fun record_now_follows_the_first_screen() {
        val today = dose("2026-11-12", "2026-11-12T08:02")
        assertEquals(JudgeAfter.Stay(null, wrote = true), JudgeOutcomes.of(RecordResult.Recorded(today), now, SEOUL))
        assertEquals(JudgeAfter.Stay("이미 기록됨 (08:02)", wrote = false), JudgeOutcomes.of(RecordResult.Already(today), now, SEOUL))
        assertEquals(JudgeAfter.Stale, JudgeOutcomes.of(RecordResult.Stale("2026-11-13"), now, SEOUL))
        assertEquals(JudgeAfter.Home(Labels.GONE), JudgeOutcomes.of(RecordResult.Gone, now, SEOUL))
        assertEquals(JudgeAfter.Stay(Labels.TRY_AGAIN, wrote = false), JudgeOutcomes.of(RecordResult.Error("x"), now, SEOUL))
    }

    @Test
    fun cancels_and_clock_answers() {
        val skipped = cell("2026-11-11", dose("2026-11-11", "2026-11-11T04:00", DoseKind.SKIPPED))
        val taken = cell("2026-11-11", dose("2026-11-11", "2026-11-11T08:10"))
        assertEquals(JudgeAfter.Stay("아침약 건너뜀을 취소했어요. 기록은 무효로 남아요", wrote = true), JudgeOutcomes.of(VoidResult.Voided, skipped))
        assertEquals(JudgeAfter.Stay("아침약 기록을 취소했어요. 기록은 무효로 남아요", wrote = true), JudgeOutcomes.of(VoidResult.Voided, taken))
        assertEquals(JudgeAfter.Stay(Labels.ALREADY_VOIDED, wrote = false), JudgeOutcomes.of(VoidResult.NotActive, taken))
        assertEquals(JudgeAfter.Stay(Labels.LOCKED, wrote = false), JudgeOutcomes.of(VoidResult.Locked, taken))
        assertEquals(JudgeAfter.Stay(null, wrote = true), JudgeOutcomes.ofKeep(ClockFlagResult.Resolved))
        assertEquals(JudgeAfter.Stay(null, wrote = false), JudgeOutcomes.ofKeep(ClockFlagResult.NotFlagged))
        assertEquals(JudgeAfter.Stay("아침약 기록을 취소했어요. 기록은 무효로 남아요", wrote = true), JudgeOutcomes.ofCancel(ClockFlagResult.Resolved, taken))
        assertEquals(JudgeAfter.Stay(Labels.LOCKED, wrote = false), JudgeOutcomes.ofCancel(ClockFlagResult.Locked, taken))
    }
}
