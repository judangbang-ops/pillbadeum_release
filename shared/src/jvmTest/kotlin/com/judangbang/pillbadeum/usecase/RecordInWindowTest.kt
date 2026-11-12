package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.UuidV7
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.ZeroRandom
import com.judangbang.pillbadeum.testing.seoul
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/**
 * App-only writes to past and current windows (TASK-005, REQ-030~034, record-rules-supplement.md I-1,
 * I-2, P-1~P-6). Tests named rrNN follow the supplement's counterexample rows; do not edit their
 * expectations to make them pass, fix the code or the supplement first.
 */
class RecordInWindowTest {
    private val fx = DbFixture()
    private val inWindow = RecordInWindow(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val saveIntake = SaveIntake(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val people = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val settings = ManageSettings(fx.db, fx.clock)

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun versionIds(intakeId: String): List<String> =
        fx.db.scheduleVersionDao().listByIntake(intakeId).map { it.id }

    /** A cell command of the intake's only (or [version]) schedule version, rendered now. */
    private suspend fun cell(
        intakeId: String,
        periodKey: String,
        kind: DoseKind,
        takenAt: Instant? = null,
        replacing: String? = null,
        version: String? = null,
        renderedAt: Instant = fx.clock.now(),
    ) = WindowCommand(
        intakeId = intakeId,
        scheduleVersionId = version ?: versionIds(intakeId).single(),
        periodKey = periodKey,
        kind = kind,
        takenAt = takenAt,
        deviceZoneId = SEOUL.id,
        renderedAt = renderedAt,
        replacing = replacing,
    )

    private fun edit(doseId: String, to: Instant, renderedAt: Instant = fx.clock.now()) =
        TimeEditCommand(doseId, to, SEOUL.id, renderedAt)

    private fun ms(localDateTime: String): Long = seoul(localDateTime).toEpochMilliseconds()

    @Test
    fun rr38_skip_in_yesterdays_missed_cell_stores_the_window_start_and_leaves_today() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T10:00")
        val skip = assertIs<WindowWriteResult.Recorded>(inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED))).record

        assertEquals("2026-11-10", skip.periodKey)
        assertEquals(DoseKind.SKIPPED, skip.kind)
        assertEquals(ms("2026-11-10T04:00"), skip.takenAt, "P-3: the window start")
        assertEquals(ms("2026-11-11T10:00"), skip.recordedAt)
        assertEquals(DoseSource.APP, skip.source)
        // Today's window is untouched: the widget still records it.
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))
    }

    @Test
    fun rr39_picked_time_in_yesterdays_cell_fills_yesterday_only() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T10:00")
        val dose = assertIs<WindowWriteResult.Recorded>(
            inWindow.record(cell(intake, "2026-11-10", DoseKind.TAKEN, takenAt = seoul("2026-11-10T21:00"))),
        ).record

        assertEquals("2026-11-10", dose.periodKey)
        assertEquals(ms("2026-11-10T21:00"), dose.takenAt)
        assertEquals(listOf(dose.id), fx.activeRows(intake).map { it.id }, "today is still pending")
    }

    @Test
    fun rr40_yesterdays_cell_rejects_a_time_outside_its_window() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T10:00")
        for (outside in listOf("2026-11-11T05:00", "2026-11-11T04:00", "2026-11-10T03:59")) {
            assertEquals(
                WindowWriteResult.InvalidTime(InvalidTimeReason.OUTSIDE_WINDOW),
                inWindow.record(cell(intake, "2026-11-10", DoseKind.TAKEN, takenAt = seoul(outside))),
                outside,
            )
        }
        assertEquals(emptyList(), fx.history(intake))
    }

    @Test
    fun rr41_registered_in_the_afternoon_this_mornings_dose_can_be_entered() = runTest {
        fx.at("2026-11-11T15:00")
        val person = people.ensureDefault().single().id
        val intake = assertIs<SaveIntakeResult.Saved>(saveIntake.create(IntakeDraft(person, "아침약"))).intakeId

        val dose = assertIs<WindowWriteResult.Recorded>(
            inWindow.record(cell(intake, "2026-11-11", DoseKind.TAKEN, takenAt = seoul("2026-11-11T08:00"))),
        ).record
        assertEquals("2026-11-11", dose.periodKey)

        // Nothing before the registration window (P-4).
        assertEquals(WindowWriteResult.Stale, inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED)))
        assertEquals(
            WindowWriteResult.InvalidTime(InvalidTimeReason.BEFORE_SCHEDULE),
            inWindow.editTime(edit(dose.id, seoul("2026-11-11T03:59"))),
        )
        assertEquals(listOf(dose.id), fx.activeRows(intake).map { it.id })
    }

    @Test
    fun rr42_moving_yesterdays_dose_into_today_with_a_dose_is_refused() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-10T08:00")
        val yesterday = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-10")).record
        fx.at("2026-11-11T07:30")
        val today = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11")).record

        fx.at("2026-11-11T09:00")
        val result = inWindow.editTime(edit(yesterday.id, seoul("2026-11-11T07:00")))

        assertEquals(WindowWriteResult.Already(today), result, "\"오늘 이미 기록됨 (07:30)\"")
        assertEquals(listOf(yesterday, today), fx.history(intake), "nothing changed")
    }

    @Test
    fun rr43_moving_todays_dose_to_last_night_voids_it_and_empties_today() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:00")
        val today = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11")).record

        fx.at("2026-11-11T09:00")
        val moved = assertIs<WindowWriteResult.Recorded>(inWindow.editTime(edit(today.id, seoul("2026-11-10T23:00"))))

        assertEquals(today, moved.replaced)
        assertEquals("2026-11-10", moved.record.periodKey)
        assertEquals(ms("2026-11-10T23:00"), moved.record.takenAt)
        assertEquals(ms("2026-11-11T09:00"), moved.record.recordedAt)
        val old = fx.history(intake).single { it.id == today.id }
        assertNull(old.active)
        assertEquals(VoidReasons.TIME_EDIT, old.voidReason)
        assertEquals(listOf(moved.record.id), fx.activeRows(intake).map { it.id })
        // Today's window is empty again (its reminder is re-planned by TASK-027).
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))
    }

    @Test
    fun rr44_cells_that_ended_before_a_day_start_change_are_locked() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-09T08:00")
        val dayBefore = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-09")).record
        fx.at("2026-11-10T12:00")
        assertEquals(SaveSettingsResult.Changed, settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(6, 0))))

        fx.at("2026-11-11T10:00")
        assertEquals(WindowWriteResult.Locked, inWindow.record(cell(intake, "2026-11-09", DoseKind.SKIPPED, replacing = dayBefore.id)))
        assertEquals(WindowWriteResult.Locked, inWindow.editTime(edit(dayBefore.id, seoul("2026-11-09T09:00"))))
        // The window open at the change (11-10 06:00 ~ 11-11 06:00) can still be written.
        assertIs<WindowWriteResult.Recorded>(inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED)))
        // Moving a dose into a locked cell is refused too.
        fx.at("2026-11-11T10:05")
        val today = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11")).record
        assertEquals(WindowWriteResult.Locked, inWindow.editTime(edit(today.id, seoul("2026-11-09T22:00"))))
    }

    @Test
    fun a_screen_drawn_before_a_settings_change_is_stale() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T09:00")
        val drawnAt = fx.clock.now()
        fx.at("2026-11-11T09:01")
        settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(5, 0)))
        fx.at("2026-11-11T09:02")

        assertEquals(WindowWriteResult.Stale, inWindow.record(cell(intake, "2026-11-11", DoseKind.TAKEN, renderedAt = drawnAt)))
        assertIs<WindowWriteResult.Recorded>(inWindow.record(cell(intake, "2026-11-11", DoseKind.TAKEN)))
    }

    @Test
    fun windows_that_have_not_started_and_future_times_are_refused() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T10:00")
        assertEquals(
            WindowWriteResult.InvalidTime(InvalidTimeReason.NOT_STARTED),
            inWindow.record(cell(intake, "2026-11-12", DoseKind.SKIPPED)),
        )
        assertEquals(
            WindowWriteResult.InvalidTime(InvalidTimeReason.FUTURE),
            inWindow.record(cell(intake, "2026-11-11", DoseKind.TAKEN, takenAt = seoul("2026-11-11T10:01"))),
        )
        val dose = assertIs<WindowWriteResult.Recorded>(
            inWindow.record(cell(intake, "2026-11-11", DoseKind.TAKEN, takenAt = seoul("2026-11-11T10:00"))),
        ).record
        assertEquals(
            WindowWriteResult.InvalidTime(InvalidTimeReason.FUTURE),
            inWindow.editTime(edit(dose.id, seoul("2026-11-11T10:30"))),
        )
        assertEquals(listOf(dose.id), fx.history(intake).map { it.id })
    }

    @Test
    fun now_button_works_only_in_the_current_window() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T10:00")
        assertEquals(WindowWriteResult.Stale, inWindow.record(cell(intake, "2026-11-10", DoseKind.TAKEN)))
        val now = assertIs<WindowWriteResult.Recorded>(inWindow.record(cell(intake, "2026-11-11", DoseKind.TAKEN))).record
        assertEquals(ms("2026-11-11T10:00"), now.takenAt)
        assertEquals("2026-11-11", now.periodKey)
        // A skip in the current window keeps "now" as its time, like the widget path.
        fx.at("2026-11-12T09:00")
        val skip = assertIs<WindowWriteResult.Recorded>(inWindow.record(cell(intake, "2026-11-12", DoseKind.SKIPPED))).record
        assertEquals(ms("2026-11-12T09:00"), skip.takenAt)
    }

    @Test
    fun a_skip_turns_into_a_dose_in_one_step_and_only_with_replacing() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T10:00")
        val skip = assertIs<WindowWriteResult.Recorded>(inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED))).record

        val dose = cell(intake, "2026-11-10", DoseKind.TAKEN, takenAt = seoul("2026-11-10T21:00"))
        assertEquals(WindowWriteResult.Already(skip), inWindow.record(dose), "an occupied cell needs `replacing`")
        assertEquals(WindowWriteResult.Already(skip), inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED, replacing = skip.id)))

        val changed = assertIs<WindowWriteResult.Recorded>(inWindow.record(dose.copy(replacing = skip.id)))
        assertEquals(skip, changed.replaced)
        assertEquals(DoseKind.TAKEN, changed.record.kind)
        val voided = fx.history(intake).single { it.id == skip.id }
        assertNull(voided.active)
        assertEquals(VoidReasons.CHANGED, voided.voidReason)
        assertEquals(listOf(changed.record.id), fx.activeRows(intake).map { it.id })

        // The voided skip can no longer be replaced: the screen is out of date.
        assertEquals(WindowWriteResult.Stale, inWindow.record(dose.copy(replacing = skip.id)))
    }

    @Test
    fun a_dose_moves_within_its_own_cell_and_the_same_time_changes_nothing() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:00")
        val dose = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11")).record
        fx.at("2026-11-11T09:00")

        assertEquals(WindowWriteResult.Unchanged, inWindow.editTime(edit(dose.id, seoul("2026-11-11T08:00"))))
        val moved = assertIs<WindowWriteResult.Recorded>(inWindow.editTime(edit(dose.id, seoul("2026-11-11T07:10")))).record
        assertEquals("2026-11-11", moved.periodKey)
        assertEquals(dose.scheduleVersionId, moved.scheduleVersionId)
        assertEquals(ms("2026-11-11T07:10"), moved.takenAt)
        assertEquals(2, fx.history(intake).size)

        // Only active doses have a time to edit.
        assertEquals(WindowWriteResult.Stale, inWindow.editTime(edit(dose.id, seoul("2026-11-11T07:20"))))
        val skip = assertIs<WindowWriteResult.Recorded>(inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED))).record
        assertIs<WindowWriteResult.Error>(inWindow.editTime(edit(skip.id, seoul("2026-11-10T20:00"))))
    }

    @Test
    fun a_weekly_cell_is_named_by_its_anchor_day_only() = runTest {
        val intake = fx.newIntake(ScheduleRule.Weekly(DayOfWeek.MONDAY))
        fx.at("2026-11-11T10:00") // Wednesday
        assertEquals(WindowWriteResult.Stale, inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED)), "Tuesday")
        assertEquals(WindowWriteResult.Stale, inWindow.record(cell(intake, "11/09", DoseKind.SKIPPED)), "not a date")

        val last = assertIs<WindowWriteResult.Recorded>(
            inWindow.record(cell(intake, "2026-11-02", DoseKind.TAKEN, takenAt = seoul("2026-11-08T20:00"))),
        ).record
        assertEquals("2026-11-02", last.periodKey, "Sunday evening belongs to the week that started on Monday 11-02")
        assertEquals(
            WindowWriteResult.InvalidTime(InvalidTimeReason.OUTSIDE_WINDOW),
            inWindow.record(cell(intake, "2026-11-09", DoseKind.TAKEN, takenAt = seoul("2026-11-09T03:59"))),
        )
    }

    @Test
    fun archived_intakes_and_people_are_gone() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:00")
        val dose = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11")).record
        val person = fx.db.intakeDao().findById(intake)!!.personId

        fx.at("2026-11-11T09:00")
        fx.db.personDao().archive(person, ms("2026-11-11T09:00"))
        assertEquals(WindowWriteResult.Gone, inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED)))
        assertEquals(WindowWriteResult.Gone, inWindow.editTime(edit(dose.id, seoul("2026-11-11T07:00"))))

        fx.db.personDao().restore(person, ms("2026-11-11T09:00"))
        ArchiveIntake(fx.db, fx.clock)(intake)
        assertEquals(WindowWriteResult.Gone, inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED)))
        assertEquals(listOf(dose), fx.history(intake))
    }

    @Test
    fun rr78_in_the_week_daily_became_weekly_each_version_writes_only_its_own_cells() = runTest {
        val intake = fx.newIntake()
        fx.changeSchedule(intake, ScheduleRule.Weekly(DayOfWeek.MONDAY), at = seoul("2026-11-11T10:00"))
        val (daily, weekly) = versionIds(intake)
        fx.at("2026-11-11T12:00")

        // The old daily version still owns the 11-10 cell, not the 11-11 one that holds the change.
        val tuesday = assertIs<WindowWriteResult.Recorded>(
            inWindow.record(cell(intake, "2026-11-10", DoseKind.TAKEN, takenAt = seoul("2026-11-10T08:00"), version = daily)),
        ).record
        assertEquals(daily, tuesday.scheduleVersionId)
        assertEquals(WindowWriteResult.Stale, inWindow.record(cell(intake, "2026-11-11", DoseKind.SKIPPED, version = daily)))

        // The weekly cell of that week sees the Tuesday dose by its time (supplement P-8, record-rules.md 5).
        assertEquals(WindowWriteResult.Already(tuesday), inWindow.record(cell(intake, "2026-11-09", DoseKind.SKIPPED, version = weekly)))

        // Moving the Tuesday dose to Wednesday 11:00 lands in the weekly cell: the daily version no longer owns it.
        val moved = assertIs<WindowWriteResult.Recorded>(inWindow.editTime(edit(tuesday.id, seoul("2026-11-11T11:00")))).record
        assertEquals(weekly, moved.scheduleVersionId)
        assertEquals("2026-11-09", moved.periodKey)
    }

    @Test
    fun in_the_week_a_schedule_changed_the_order_of_writes_does_not_matter() = runTest {
        val intake = fx.newIntake()
        fx.changeSchedule(intake, ScheduleRule.Weekly(DayOfWeek.MONDAY), at = seoul("2026-11-11T10:00"))
        val (daily, weekly) = versionIds(intake)
        fx.at("2026-11-11T11:00")

        // The new weekly cell first: the old daily cells of that week can no longer be filled.
        val week = assertIs<WindowWriteResult.Recorded>(inWindow.record(cell(intake, "2026-11-09", DoseKind.TAKEN, version = weekly))).record
        assertEquals(
            WindowWriteResult.Already(week),
            inWindow.record(cell(intake, "2026-11-10", DoseKind.TAKEN, takenAt = seoul("2026-11-10T08:00"), version = daily)),
        )
        assertEquals(WindowWriteResult.Already(week), inWindow.record(cell(intake, "2026-11-09", DoseKind.SKIPPED, version = daily)))
        assertEquals(listOf(week.id), fx.activeRows(intake).map { it.id })
        // The weekly judgment can still be changed: nothing else sits in its window.
        assertIs<WindowWriteResult.Recorded>(inWindow.editTime(edit(week.id, seoul("2026-11-11T10:30"))))
    }

    @Test
    fun in_the_week_a_schedule_changed_old_cells_fill_first_and_then_count_for_the_new_cell() = runTest {
        val intake = fx.newIntake()
        fx.changeSchedule(intake, ScheduleRule.Weekly(DayOfWeek.MONDAY), at = seoul("2026-11-11T10:00"))
        val (daily, weekly) = versionIds(intake)
        fx.at("2026-11-11T11:00")

        val monday = assertIs<WindowWriteResult.Recorded>(
            inWindow.record(cell(intake, "2026-11-09", DoseKind.TAKEN, takenAt = seoul("2026-11-09T08:00"), version = daily)),
        ).record
        assertIs<WindowWriteResult.Recorded>(
            inWindow.record(cell(intake, "2026-11-10", DoseKind.TAKEN, takenAt = seoul("2026-11-10T08:00"), version = daily)),
        )
        // record-rules.md 8: a record of the old schedule inside the new window completes it.
        assertEquals(WindowWriteResult.Already(monday), inWindow.record(cell(intake, "2026-11-09", DoseKind.TAKEN, version = weekly)))
    }

    @Test
    fun replacing_a_judgment_whose_own_cell_is_locked_is_refused() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-01T12:00")
        settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(6, 0)))
        fx.at("2026-11-10T05:00") // before 06:00: the 11-09 window
        val early = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-09")).record
        fx.at("2026-11-10T12:00")
        settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(4, 0)))

        // Under 04:00 the open 11-10 cell holds 05:00, but the row's own cell (11-09) ended before the change.
        fx.at("2026-11-11T10:00")
        assertEquals(
            WindowWriteResult.Locked,
            inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED, replacing = early.id)),
        )
        assertEquals(listOf(early.id), fx.activeRows(intake).map { it.id })
    }

    @Test
    fun a_dose_in_a_locked_cell_cannot_move_to_an_open_one_or_be_cancelled() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-09T08:00")
        val dayBefore = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-09")).record
        fx.at("2026-11-10T12:00")
        settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(6, 0)))
        fx.at("2026-11-11T10:00")

        assertEquals(WindowWriteResult.Locked, inWindow.editTime(edit(dayBefore.id, seoul("2026-11-10T20:00"))))
        assertEquals(VoidResult.Locked, fx.voidDose(dayBefore.id))
        assertEquals(listOf(dayBefore), fx.history(intake))
    }

    @Test
    fun a_cell_ending_exactly_at_the_settings_change_is_locked() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T05:00")
        settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(5, 0)))
        fx.at("2026-11-11T10:00")
        // Under 05:00 the 11-10 window ends at 11-11 05:00, the change time itself.
        assertEquals(WindowWriteResult.Locked, inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED)))
        assertIs<WindowWriteResult.Recorded>(inWindow.record(cell(intake, "2026-11-11", DoseKind.SKIPPED)))
    }

    @Test
    fun changing_the_current_windows_judgment_follows_the_settings_change_lock() = runTest {
        // Day start 06:00; a dose at 11-10 05:00 belongs to the 11-09 cell.
        fx.at("2026-11-09T12:00")
        settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(6, 0)))
        val intake = fx.newIntake()
        fx.at("2026-11-10T05:00")
        val dose = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-09")).record
        // Day start moves to 04:00: the dose now falls in the open 11-10 window, but its own 11-09 cell ended
        // at 11-10 04:00, before the change, so it is locked like every other way of changing it (P-5).
        fx.at("2026-11-10T05:30")
        settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(4, 0)))

        val skip = RecordCommand(intake, DoseKind.SKIPPED, DoseSource.APP, "2026-11-10", SEOUL.id)
        assertEquals(RecordResult.Locked, fx.changeJudgment(skip))
        assertEquals(listOf(dose.id), fx.activeRows(intake).map { it.id })
        assertEquals(VoidResult.Locked, fx.voidDose(dose.id))
    }

    @Test
    fun a_failed_insert_rolls_back_the_void() = runTest {
        // Every id is the same while the clock stands still, so the new row clashes with the old one.
        val sameIds = RecordInWindow(fx.db, fx.clock, UuidV7(fx.clock, ZeroRandom), TEST_DEVICE_ID)
        val intake = fx.newIntake()
        fx.at("2026-11-11T09:00")
        val skip = assertIs<WindowWriteResult.Recorded>(sameIds.record(cell(intake, "2026-11-10", DoseKind.SKIPPED))).record
        fx.at("2026-11-11T09:01")
        val dose = assertIs<WindowWriteResult.Recorded>(
            sameIds.record(cell(intake, "2026-11-11", DoseKind.TAKEN, takenAt = seoul("2026-11-11T08:00"))),
        ).record

        fx.at("2026-11-11T09:30")
        val first = assertIs<WindowWriteResult.Recorded>(
            sameIds.record(cell(intake, "2026-11-10", DoseKind.TAKEN, takenAt = seoul("2026-11-10T21:00"), replacing = skip.id)),
        ).record
        // Same clock: the next row would get `first.id` again.
        assertIs<WindowWriteResult.Error>(sameIds.editTime(edit(first.id, seoul("2026-11-10T20:00"))))
        assertIs<WindowWriteResult.Error>(
            sameIds.record(cell(intake, "2026-11-10", DoseKind.SKIPPED, replacing = first.id)),
        )
        assertEquals(setOf(first.id, dose.id), fx.activeRows(intake).map { it.id }.toSet(), "the voids were rolled back")
    }

    @Test
    fun a_settings_change_saved_with_the_clock_ahead_does_not_block_writes_forever() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-13T09:00") // clock two days ahead
        settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(5, 0)))
        fx.at("2026-11-11T10:00") // corrected

        assertIs<WindowWriteResult.Recorded>(inWindow.record(cell(intake, "2026-11-11", DoseKind.TAKEN)))
        assertEquals(seoul("2026-11-11T10:00"), settings.windowSettingsChangedAt(), "rewritten to now")
        assertEquals(WindowWriteResult.Locked, inWindow.record(cell(intake, "2026-11-10", DoseKind.SKIPPED)))
    }

    @Test
    fun a_time_edit_stays_in_its_own_cell_while_that_cell_holds_the_new_time() = runTest {
        val intake = fx.newIntake()
        fx.changeSchedule(intake, ScheduleRule.Weekly(DayOfWeek.MONDAY), at = seoul("2026-11-11T10:00"))
        val (daily, weekly) = versionIds(intake)
        fx.at("2026-11-11T10:30")
        val dose = assertIs<WindowWriteResult.Recorded>(inWindow.record(cell(intake, "2026-11-09", DoseKind.TAKEN, version = weekly))).record

        // Tuesday 08:00 is in the weekly cell and in the old daily 11-10 cell; the dose keeps its own cell.
        fx.at("2026-11-11T11:00")
        val moved = assertIs<WindowWriteResult.Recorded>(inWindow.editTime(edit(dose.id, seoul("2026-11-10T08:00")))).record
        assertEquals(weekly, moved.scheduleVersionId)
        assertEquals("2026-11-09", moved.periodKey)
        // The week before is the daily version's: the dose moves there.
        val earlier = assertIs<WindowWriteResult.Recorded>(inWindow.editTime(edit(moved.id, seoul("2026-11-08T08:00")))).record
        assertEquals(daily, earlier.scheduleVersionId)
        assertEquals("2026-11-08", earlier.periodKey)
    }

    @Test
    fun a_schedule_edit_with_the_clock_set_back_never_leaves_two_open_versions() = runTest {
        fx.at("2026-11-11T15:00")
        val person = people.ensureDefault().single().id
        val intake = assertIs<SaveIntakeResult.Saved>(saveIntake.create(IntakeDraft(person, "아침약"))).intakeId

        // Clock set back before the first version's start (04:00), then the schedule is changed.
        fx.at("2026-11-11T02:00")
        assertEquals(
            SaveIntakeResult.Saved(intake, newScheduleVersion = true),
            saveIntake.update(intake, IntakeDraft(person, "아침약", rule = ScheduleRule.Weekly(DayOfWeek.MONDAY))),
        )
        // Back to normal time, then changed again; the clock goes behind the second change.
        fx.at("2026-11-11T16:00")
        saveIntake.update(intake, IntakeDraft(person, "아침약"))
        fx.at("2026-11-11T15:30")
        saveIntake.update(intake, IntakeDraft(person, "아침약", rule = ScheduleRule.Weekly(DayOfWeek.FRIDAY)))

        // Ids follow the clock, which went back, so compare (from, to) pairs rather than list order.
        val versions = fx.db.scheduleVersionDao().listByIntake(intake)
        assertEquals(1, versions.count { it.effectiveTo == null }, "$versions")
        assertEquals(
            setOf(
                ms("2026-11-11T04:00") to ms("2026-11-11T04:00"), // first version, emptied by the 02:00 change
                ms("2026-11-11T04:00") to ms("2026-11-11T16:00"),
                ms("2026-11-11T16:00") to ms("2026-11-11T16:00"), // emptied by the 15:30 change
                ms("2026-11-11T16:00") to null,
            ),
            versions.map { it.effectiveFrom to it.effectiveTo }.toSet(),
        )
        val open = versions.single { it.effectiveTo == null }
        assertEquals(ScheduleRule.Weekly(DayOfWeek.FRIDAY), ScheduleParams.toRule(open))
        val effective = fx.db.scheduleVersionDao().findEffective(intake, ms("2026-11-11T17:00"))
        assertEquals(open.id, effective?.id)
    }
}
