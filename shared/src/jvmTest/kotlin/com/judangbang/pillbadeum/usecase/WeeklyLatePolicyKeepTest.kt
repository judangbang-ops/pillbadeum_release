package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.domain.LatePolicy
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.status.LoadStatusBoard
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek

/**
 * TASK-004: every schedule version the app writes keeps the weekly anchor after a late dose
 * (record-rules-supplement.md W-1, decision Q15 1st-release method). RR-60 and RR-62 check the
 * anchor part; the "마지막 복용" wording of those rows is TASK-041.
 *
 * Calendar used below: 2026-11-02 Mon, 11-08 Sun, 11-09 Mon, 11-10 Tue, 11-16 Mon (Asia/Seoul,
 * day start 04:00).
 */
class WeeklyLatePolicyKeepTest {
    private val fx = DbFixture(start = seoul("2026-11-02T07:00"))
    private val saveIntake = SaveIntake(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val people = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val board = LoadStatusBoard(fx.db, fx.clock)

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun weeklyMonday(): String {
        val me = people.ensureDefault().single().id
        val saved = assertIs<SaveIntakeResult.Saved>(
            saveIntake.create(IntakeDraft(me, "주사제 A", rule = ScheduleRule.Weekly(DayOfWeek.MONDAY))),
        )
        return saved.intakeId
    }

    private suspend fun assertSingleMondayKeepVersion(intakeId: String) {
        val version = fx.db.scheduleVersionDao().listByIntake(intakeId).single()
        assertEquals(LatePolicy.KEEP, version.latePolicy)
        assertEquals(ScheduleRule.Weekly(DayOfWeek.MONDAY), ScheduleParams.toRule(version))
        assertNull(version.effectiveTo, "a late dose never closes the version")
    }

    @Test
    fun newIntake_firstVersion_isKeep() = runTest {
        val daily = assertIs<SaveIntakeResult.Saved>(
            saveIntake.create(IntakeDraft(people.ensureDefault().single().id, "아침약")),
        ).intakeId
        val weekly = weeklyMonday()

        assertEquals(LatePolicy.KEEP, fx.db.scheduleVersionDao().listByIntake(daily).single().latePolicy)
        assertEquals(LatePolicy.KEEP, fx.db.scheduleVersionDao().listByIntake(weekly).single().latePolicy)
    }

    @Test
    fun scheduleChange_newVersion_isKeep() = runTest {
        val intake = weeklyMonday()
        fx.at("2026-11-04T09:00")
        val me = people.ensureDefault().single().id
        assertTrue(
            assertIs<SaveIntakeResult.Saved>(
                saveIntake.update(intake, IntakeDraft(me, "주사제 A", rule = ScheduleRule.Weekly(DayOfWeek.THURSDAY))),
            ).newScheduleVersion,
        )
        fx.at("2026-11-05T09:00")
        assertTrue(
            assertIs<SaveIntakeResult.Saved>(
                saveIntake.update(intake, IntakeDraft(me, "주사제 A", rule = ScheduleRule.Daily)),
            ).newScheduleVersion,
        )

        val versions = fx.db.scheduleVersionDao().listByIntake(intake)
        assertEquals(3, versions.size)
        assertEquals(listOf(LatePolicy.KEEP, LatePolicy.KEEP, LatePolicy.KEEP), versions.map { it.latePolicy })
    }

    @Test
    fun displayOnlyEdit_keepsTheVersionAndItsPolicy() = runTest {
        val intake = weeklyMonday()
        fx.at("2026-11-04T09:00")
        val me = people.ensureDefault().single().id
        assertFalse(
            assertIs<SaveIntakeResult.Saved>(
                saveIntake.update(
                    intake,
                    IntakeDraft(me, "주사제 B", widgetAlias = "주사", rule = ScheduleRule.Weekly(DayOfWeek.MONDAY)),
                ),
            ).newScheduleVersion,
        )
        assertSingleMondayKeepVersion(intake)
    }

    /** RR-60: Sunday 22:00 dose (late, previous window), Monday 08:10 widget tap is recorded. */
    @Test
    fun rr60_sundayLateDose_mondayTapRecorded_anchorStaysMonday() = runTest {
        val intake = weeklyMonday()
        fx.at("2026-11-08T22:00")
        val sunday = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-02"))
        assertEquals("2026-11-02", sunday.record.periodKey, "Sunday belongs to the window that began on 11/02")

        fx.at("2026-11-09T08:00")
        val monday = board().items.single()
        assertEquals("2026-11-09", monday.window.periodKey, "the new window still opens on Monday")
        assertEquals(seoul("2026-11-09T04:00"), monday.window.start)
        assertFalse(monday.isDone, "widget shows the Monday window as pending")
        assertEquals(sunday.record.id, monday.lastBefore?.id, "last dose is Sunday 22:00")

        fx.at("2026-11-09T08:10")
        val tap = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-09"))
        assertEquals("2026-11-09", tap.record.periodKey, "recorded without a question (limit of W-1)")
        assertEquals(2, fx.activeRows(intake).size)
        assertSingleMondayKeepVersion(intake)
    }

    /** RR-62: a Tuesday dose counts for the window that began Monday; the next Monday is pending. */
    @Test
    fun rr62_tuesdayDose_nextMondayPending_anchorStaysMonday() = runTest {
        val intake = weeklyMonday()
        fx.at("2026-11-10T08:00")
        val tuesday = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-09"))
        assertEquals("2026-11-09", tuesday.record.periodKey)

        fx.at("2026-11-16T08:00")
        val next = board().items.single()
        assertEquals("2026-11-16", next.window.periodKey, "next window opens on Monday 11/16, not Tuesday")
        assertEquals(seoul("2026-11-16T04:00"), next.window.start)
        assertEquals(seoul("2026-11-23T04:00"), next.window.end)
        assertFalse(next.isDone)
        assertEquals(tuesday.record.id, next.lastBefore?.id, "last dose is Tuesday 08:00")
        assertSingleMondayKeepVersion(intake)
    }
}
