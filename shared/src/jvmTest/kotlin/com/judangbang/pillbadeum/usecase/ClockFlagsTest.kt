package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.LoadStatusBoard
import com.judangbang.pillbadeum.status.WidgetModel
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime

/**
 * Judgments recorded while the phone clock was ahead (TASK-006, REQ-009, record-rules-supplement.md
 * C-1~C-3, original RR-19). Tests named rrNN follow the counterexample rows; do not edit their
 * expectations to make them pass.
 */
class ClockFlagsTest {
    private val fx = DbFixture()
    private val flag = FlagClockMismatch(fx.db, fx.clock)
    private val resolve = ResolveClockFlag(fx.db, fx.clock)
    private val board = LoadStatusBoard(fx.db, fx.clock)
    private val inWindow = RecordInWindow(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)

    @AfterTest
    fun tearDown() = fx.close()

    /** RR-64 setup: the clock says Friday 11-13 08:00, the widget records, the clock goes back to Wednesday. */
    private suspend fun recordedOnAWrongFriday(): Pair<String, String> {
        val intake = fx.newIntake()
        fx.at("2026-11-13T08:00")
        val friday = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-13")).record
        fx.at("2026-11-11T09:00")
        assertEquals(1, flag(), "TIME_SET raises the flag")
        return intake to friday.id
    }

    @Test
    fun rr64_rr19_clock_set_back_after_a_wrong_record_warns_in_the_app_until_answered() = runTest {
        val (intake, friday) = recordedOnAWrongFriday()

        // Wednesday: the Wednesday cell is pending, the app warns, the widget does not.
        val wednesday = board()
        val item = wednesday.items.single()
        assertNull(item.judgment)
        assertFalse(item.clockWarning)
        assertEquals(listOf(friday), wednesday.clockWarnings.map { it.judgment.id })
        assertEquals(
            "기록 시각이 휴대폰 시계와 맞지 않았어요 (금 08:00). 확인해 주세요",
            Labels.clockWarning(wednesday.clockWarnings.single().judgment, SEOUL),
        )
        assertFalse(WidgetModel.from(wednesday).buttons.single().clockWarning)
        // The Wednesday button records as usual.
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))

        // Thursday: still only in the app.
        fx.at("2026-11-12T09:00")
        assertFalse(board().items.single().clockWarning)
        assertEquals(listOf(friday), board().clockWarnings.map { it.judgment.id })

        // Friday comes: the condition no longer holds, the warning stays, and the widget button shows `!`.
        fx.at("2026-11-13T09:00")
        val fridayBoard = board()
        assertTrue(fridayBoard.items.single().clockWarning)
        assertEquals(listOf(friday), fridayBoard.clockWarnings.map { it.judgment.id })
        val button = WidgetModel.from(fridayBoard).buttons.single()
        assertTrue(button.clockWarning)
        assertEquals(Labels.CLOCK_CHECK_IN_APP, button.subtitle)
        // A tap changes nothing.
        assertIs<RecordResult.Already>(fx.record(intake, renderedKey = "2026-11-13"))
    }

    @Test
    fun rr65_cancel_voids_the_judgment_and_resolves_the_flag() = runTest {
        val (intake, friday) = recordedOnAWrongFriday()
        fx.at("2026-11-13T09:00")

        assertEquals(ClockFlagResult.Resolved, resolve.cancel(friday))

        val row = fx.history(intake).single { it.id == friday }
        assertNull(row.active)
        assertEquals(VoidReasons.USER_CANCEL, row.voidReason)
        assertNotNull(fx.db.clockFlagDao().find(friday)?.resolvedAt)
        val now = board()
        assertEquals(emptyList(), now.clockWarnings)
        assertNull(now.items.single().judgment, "the open Friday window is pending again")
        assertEquals(ClockFlagResult.NotFlagged, resolve.cancel(friday), "answering twice changes nothing")
    }

    @Test
    fun rr66_keep_leaves_the_judgment_and_drops_the_warning() = runTest {
        val (intake, friday) = recordedOnAWrongFriday()

        assertEquals(ClockFlagResult.Resolved, resolve.keep(friday))

        assertTrue(fx.history(intake).single { it.id == friday }.isActive)
        assertEquals(emptyList(), board().clockWarnings)
        // The judgment is still "in the future", but an answered flag is not raised again.
        assertEquals(0, flag())
        fx.at("2026-11-13T09:00")
        assertFalse(board().items.single().clockWarning)
        assertEquals(ClockFlagResult.NotFlagged, resolve.keep(friday))
    }

    @Test
    fun cancel_follows_the_settings_change_lock_and_keep_still_works() = runTest {
        // Day start 04:00. The clock says 11-13 03:00 (still the 11-12 cell) and the widget records.
        val intake = fx.newIntake()
        fx.at("2026-11-13T03:00")
        val wrong = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-12")).record
        // The clock goes back two hours, then day start moves to 00:00: the 11-12 cell now ends at 11-13 00:00,
        // before the change, so it is locked (P-5) like any other way of voiding it.
        fx.at("2026-11-13T01:00")
        assertEquals(1, flag())
        assertEquals(
            SaveSettingsResult.Changed,
            ManageSettings(fx.db, fx.clock).saveWindowSettings(WindowSettings(dayStart = LocalTime(0, 0))),
        )

        assertEquals(ClockFlagResult.Locked, resolve.cancel(wrong.id))
        assertTrue(fx.history(intake).single { it.id == wrong.id }.isActive)
        assertNull(fx.db.clockFlagDao().find(wrong.id)?.resolvedAt)
        assertEquals(VoidResult.Locked, VoidDose(fx.db, fx.clock)(wrong.id))

        assertEquals(ClockFlagResult.Resolved, resolve.keep(wrong.id))
        assertEquals(emptyList(), board().clockWarnings)
    }

    @Test
    fun rr67_a_normal_record_leaves_no_flag() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:00")
        val record = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11")).record
        fx.at("2026-11-11T08:00:30")
        assertEquals(0, flag())
        assertNull(fx.db.clockFlagDao().find(record.id))
        assertEquals(emptyList(), board().clockWarnings)
    }

    @Test
    fun rr88_a_clock_one_minute_ahead_is_within_the_margin() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:01")
        val record = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11")).record
        fx.at("2026-11-11T08:00") // automatic time sync right after the tap
        assertEquals(0, flag())

        // The margin is "more than 5 minutes": exactly 5 minutes is fine, one millisecond more is not.
        fx.clock.now = seoul("2026-11-11T08:01") - FlagClockMismatch.MARGIN
        assertEquals(0, flag())
        fx.clock.now = seoul("2026-11-11T08:01") - FlagClockMismatch.MARGIN - kotlin.time.Duration.parse("1ms")
        assertEquals(1, flag())
        assertNotNull(fx.db.clockFlagDao().find(record.id))
    }

    @Test
    fun rr89_cancel_on_the_home_screen_resolves_the_flag() = runTest {
        val (_, friday) = recordedOnAWrongFriday()
        assertEquals(VoidResult.Voided, fx.voidDose(friday))
        assertNotNull(fx.db.clockFlagDao().find(friday)?.resolvedAt)
        assertEquals(emptyList(), board().clockWarnings)
    }

    @Test
    fun rr89_a_time_edit_resolves_the_flag_of_the_voided_row() = runTest {
        val (intake, friday) = recordedOnAWrongFriday()
        // The real dose was this morning: move it from "Friday" to Wednesday 08:00.
        val moved = assertIs<WindowWriteResult.Recorded>(
            inWindow.editTime(TimeEditCommand(friday, seoul("2026-11-11T08:00"), SEOUL.id, fx.clock.now())),
        )
        assertEquals(friday, moved.replaced?.id)
        assertNotNull(fx.db.clockFlagDao().find(friday)?.resolvedAt)
        assertNull(fx.db.clockFlagDao().find(moved.record.id))
        assertEquals(emptyList(), board().clockWarnings)
        assertEquals(listOf("2026-11-11"), fx.activeRows(intake).map { it.periodKey })
    }

    @Test
    fun rr89_changing_the_judgment_resolves_the_flag() = runTest {
        val (intake, friday) = recordedOnAWrongFriday()
        fx.at("2026-11-13T09:00")
        val change = RecordCommand(intake, DoseKind.SKIPPED, DoseSource.APP, renderedPeriodKey = "2026-11-13", deviceZoneId = SEOUL.id)
        assertIs<RecordResult.Recorded>(fx.changeJudgment(change))
        assertNotNull(fx.db.clockFlagDao().find(friday)?.resolvedAt)
        assertEquals(emptyList(), board().clockWarnings)
    }

    @Test
    fun reading_the_board_raises_flags_without_a_broadcast() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-13T08:00")
        val friday = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-13")).record
        fx.at("2026-11-11T09:00") // no TIME_SET handled (e.g. the receiver was killed)

        assertEquals(listOf(friday.id), board().clockWarnings.map { it.judgment.id })
    }

    @Test
    fun warnings_of_archived_items_are_not_shown() = runTest {
        val (intake, _) = recordedOnAWrongFriday()
        ArchiveIntake(fx.db, fx.clock)(intake)
        assertEquals(emptyList(), board().clockWarnings)
    }
}
