package com.zmanlab.diditake.usecase

import com.zmanlab.diditake.data.saveWindowSettings
import com.zmanlab.diditake.domain.DoseKind
import com.zmanlab.diditake.domain.DoseSource
import com.zmanlab.diditake.domain.ScheduleRule
import com.zmanlab.diditake.domain.UuidV7
import com.zmanlab.diditake.domain.WindowSettings
import com.zmanlab.diditake.testing.DbFixture
import com.zmanlab.diditake.testing.TestClock
import com.zmanlab.diditake.testing.ZeroRandom
import com.zmanlab.diditake.testing.seoul
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/**
 * record-rules.md section 9, one test per counterexample number (cNN = row NN), run against a real
 * in-memory Room database with the bundled SQLite driver.
 */
class RecordRulesCounterexampleTest {
    private val fx = DbFixture()

    @AfterTest
    fun tearDown() = fx.close()

    @Test
    fun c01_doubleTap_recordsOnce() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:02")
        val first = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))
        fx.at("2026-11-11T08:02:00.300")
        val second = assertIs<RecordResult.Already>(fx.record(intake, renderedKey = "2026-11-11"))

        assertEquals(first.record.id, second.existing.id)
        assertEquals(1, fx.history(intake).size)
    }

    @Test
    fun c02_concurrentRecords_recordOnce() = runTest {
        repeat(25) { round ->
            val intake = fx.newIntake()
            fx.at("2026-11-11T08:02")
            val gate = CompletableDeferred<Unit>()
            val results = withContext(Dispatchers.Default) {
                val widget = async {
                    gate.await()
                    fx.record(intake, renderedKey = "2026-11-11", source = DoseSource.WIDGET)
                }
                val app = async {
                    gate.await()
                    fx.record(intake, renderedKey = null, source = DoseSource.APP)
                }
                gate.complete(Unit)
                awaitAll(widget, app)
            }
            assertEquals(1, results.count { it is RecordResult.Recorded }, "round $round: $results")
            assertEquals(1, results.count { it is RecordResult.Already }, "round $round: $results")
            assertEquals(1, fx.history(intake).size, "round $round")
        }
    }

    @Test
    fun c03_midnightRetap_sameWindow() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T23:59")
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))
        fx.at("2026-11-12T00:01")
        assertIs<RecordResult.Already>(fx.record(intake, renderedKey = "2026-11-11"))
        assertEquals(1, fx.history(intake).size)
    }

    @Test
    fun c04_weeklyBoundary_anchorDayOpensNewWindow() = runTest {
        val intake = fx.newIntake(ScheduleRule.Weekly(DayOfWeek.MONDAY))
        fx.at("2026-11-15T22:00") // Sunday
        val sunday = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-09"))
        fx.at("2026-11-16T08:00") // Monday
        val monday = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-16"))

        assertEquals("2026-11-09", sunday.record.periodKey)
        assertEquals("2026-11-16", monday.record.periodKey)
        assertEquals(2, fx.activeRows(intake).size)
    }

    @Test
    fun c06_voidThenRerecord_keepsHistory() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:02")
        val first = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))
        fx.at("2026-11-11T08:10")
        assertEquals(VoidResult.Voided, fx.voidDose(first.record.id))
        assertEquals(VoidResult.NotActive, fx.voidDose(first.record.id), "second cancel changes nothing")
        fx.at("2026-11-11T08:15")
        val second = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))

        val history = fx.history(intake)
        assertEquals(2, history.size)
        assertEquals(listOf(second.record.id), fx.activeRows(intake).map { it.id })
        val voided = history.single { it.id == first.record.id }
        assertNull(voided.active)
        assertEquals(seoul("2026-11-11T08:10").toEpochMilliseconds(), voided.voidedAt)
        assertEquals(VoidReasons.USER_CANCEL, voided.voidReason)
    }

    @Test
    fun c07_skipThenWidgetTap_noChange() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:00")
        val skipped = assertIs<RecordResult.Recorded>(
            fx.record(intake, renderedKey = null, source = DoseSource.APP, kind = DoseKind.SKIPPED),
        )
        fx.at("2026-11-11T09:00")
        val tap = assertIs<RecordResult.Already>(fx.record(intake, renderedKey = "2026-11-11"))

        assertEquals(skipped.record.id, tap.existing.id)
        assertEquals(DoseKind.SKIPPED, tap.existing.kind)
        assertEquals(listOf(DoseKind.SKIPPED), fx.history(intake).map { it.kind })
    }

    @Test
    fun c07_skipToTaken_viaChangeJudgment() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:00")
        val skipped = assertIs<RecordResult.Recorded>(
            fx.record(intake, renderedKey = null, source = DoseSource.APP, kind = DoseKind.SKIPPED),
        )
        fx.at("2026-11-11T10:00")
        val change = RecordCommand(intake, DoseKind.TAKEN, DoseSource.APP, renderedPeriodKey = "2026-11-11", deviceZoneId = "Asia/Seoul")
        val taken = assertIs<RecordResult.Recorded>(fx.changeJudgment(change))

        assertEquals(DoseKind.TAKEN, taken.record.kind)
        assertEquals("2026-11-11", taken.record.periodKey)
        val history = fx.history(intake)
        assertEquals(2, history.size)
        val oldRow = history.single { it.id == skipped.record.id }
        assertNull(oldRow.active)
        assertEquals(VoidReasons.CHANGED, oldRow.voidReason)
        assertEquals(listOf(taken.record.id), fx.activeRows(intake).map { it.id })

        // Repeating the change or tapping the widget afterwards changes nothing.
        assertIs<RecordResult.Already>(fx.changeJudgment(change))
        assertIs<RecordResult.Already>(fx.record(intake, renderedKey = "2026-11-11"))
        assertEquals(2, fx.history(intake).size)
    }

    @Test
    fun c07_changeJudgment_ignoredInsert_rollsBackVoid() = runTest {
        // Identical ids make the replacement clash with the old row's primary key.
        val clock = TestClock(seoul("2026-11-11T08:00"))
        DbFixture(start = clock.now, ids = UuidV7(clock, ZeroRandom)).use { local ->
            val intake = local.newIntake()
            val skipped = assertIs<RecordResult.Recorded>(
                local.record(intake, renderedKey = null, source = DoseSource.APP, kind = DoseKind.SKIPPED),
            )
            val change = RecordCommand(intake, DoseKind.TAKEN, DoseSource.APP, renderedPeriodKey = null, deviceZoneId = "Asia/Seoul")
            assertIs<RecordResult.Error>(local.changeJudgment(change))

            // The void was rolled back: the window still has its original judgment.
            val row = local.history(intake).single()
            assertEquals(skipped.record.id, row.id)
            assertTrue(row.isActive)
            assertNull(row.voidedAt)
        }
    }

    @Test
    fun c08_deviceZoneChange_windowUsesBaseZone() = runTest {
        val intake = fx.newIntake()
        // 08:00 in Seoul is still 2026-11-10 in Los Angeles; the window follows the base zone.
        fx.at("2026-11-11T08:00")
        val recorded = assertIs<RecordResult.Recorded>(
            fx.record(intake, renderedKey = "2026-11-11", deviceZoneId = "America/Los_Angeles"),
        )
        assertEquals("2026-11-11", recorded.record.periodKey)
        assertEquals("America/Los_Angeles", recorded.record.zoneId)
    }

    @Test
    fun c09_archivedIntake_returnsGone() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:00")
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))
        assertEquals(1, fx.db.intakeDao().archive(intake, fx.clock.now().toEpochMilliseconds()))

        fx.at("2026-11-12T08:00")
        assertEquals(RecordResult.Gone, fx.record(intake, renderedKey = "2026-11-12"))
        assertEquals(RecordResult.Gone, fx.record("no-such-intake", renderedKey = "2026-11-12"))
        assertEquals(1, fx.activeRows(intake).size, "records of an archived intake are kept")
    }

    @Test
    fun c10_scheduleChangeDailyToWeekly_sameDay_returnsAlready() = runTest {
        val intake = fx.newIntake(ScheduleRule.Daily)
        fx.at("2026-11-11T08:00") // Wednesday
        val daily = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))

        fx.at("2026-11-11T12:00")
        fx.changeSchedule(intake, ScheduleRule.Weekly(DayOfWeek.MONDAY), at = fx.clock.now())

        fx.at("2026-11-11T18:00")
        // A widget still showing the daily key is stale; the refreshed one shows the weekly key.
        assertEquals(RecordResult.Stale("2026-11-09"), fx.record(intake, renderedKey = "2026-11-11"))
        val again = assertIs<RecordResult.Already>(fx.record(intake, renderedKey = "2026-11-09"))
        assertEquals(daily.record.id, again.existing.id)
        assertEquals(1, fx.history(intake).size)
    }

    @Test
    fun c13_staleRenderedKey_returnsStale() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-12T08:00") // widget was rendered yesterday and never refreshed
        assertEquals(RecordResult.Stale("2026-11-12"), fx.record(intake, renderedKey = "2026-11-11"))
        assertTrue(fx.history(intake).isEmpty())
    }

    @Test
    fun c14_archivedRightAfterRender_returnsGone() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:00") // widget rendered with key 2026-11-11
        fx.db.intakeDao().archive(intake, fx.clock.now().toEpochMilliseconds())
        fx.at("2026-11-11T08:01")
        assertEquals(RecordResult.Gone, fx.record(intake, renderedKey = "2026-11-11"))
        assertTrue(fx.history(intake).isEmpty())
    }

    @Test
    fun c15_dayStartChangedAtDawn_noDuplicate() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-12T03:00") // day start 04:00: still the 2026-11-11 window
        val early = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))
        assertEquals("2026-11-11", early.record.periodKey)

        fx.at("2026-11-12T03:30")
        fx.db.settingDao().saveWindowSettings(WindowSettings(dayStart = LocalTime(2, 0)))

        fx.at("2026-11-12T03:40") // day start 02:00: now the 2026-11-12 window, which holds 03:00
        val after = assertIs<RecordResult.Already>(fx.record(intake, renderedKey = "2026-11-12"))
        assertEquals(early.record.id, after.existing.id)
        assertEquals(1, fx.history(intake).size)
    }

    @Test
    fun c16_anchorChangeSameDay_returnsAlready() = runTest {
        val intake = fx.newIntake(ScheduleRule.Weekly(DayOfWeek.MONDAY))
        fx.at("2026-11-11T08:00") // Wednesday, window 2026-11-09 (Mon)
        val taken = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-09"))

        fx.at("2026-11-11T12:00")
        fx.changeSchedule(intake, ScheduleRule.Weekly(DayOfWeek.WEDNESDAY), at = fx.clock.now())

        fx.at("2026-11-11T18:00") // new window 2026-11-11 (Wed) already holds the 08:00 dose
        val again = assertIs<RecordResult.Already>(fx.record(intake, renderedKey = "2026-11-11"))
        assertEquals(taken.record.id, again.existing.id)
        assertEquals(1, fx.history(intake).size)
    }

    @Test
    fun c17_weeklyTwoDaysEarly_widgetSaysAlready() = runTest {
        val intake = fx.newIntake(ScheduleRule.Weekly(DayOfWeek.MONDAY))
        fx.at("2026-11-09T08:00") // Monday
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-09"))
        fx.at("2026-11-14T09:00") // Saturday, two days before the next anchor
        assertIs<RecordResult.Already>(fx.record(intake, renderedKey = "2026-11-09"))
        assertEquals(1, fx.history(intake).size)
    }

    @Test
    fun c20_yearEnd_keysAreDates() = runTest {
        val daily = fx.newIntake(ScheduleRule.Daily)
        fx.at("2026-12-31T08:00")
        assertEquals("2026-12-31", assertIs<RecordResult.Recorded>(fx.record(daily, "2026-12-31")).record.periodKey)
        fx.at("2027-01-01T08:00")
        assertEquals("2027-01-01", assertIs<RecordResult.Recorded>(fx.record(daily, "2027-01-01")).record.periodKey)

        val weekly = fx.newIntake(ScheduleRule.Weekly(DayOfWeek.MONDAY))
        fx.at("2026-12-28T08:00") // Monday of ISO week 2026-W53
        val monday = assertIs<RecordResult.Recorded>(fx.record(weekly, "2026-12-28"))
        assertEquals("2026-12-28", monday.record.periodKey)
        fx.at("2027-01-02T08:00") // same window across the year boundary
        val saturday = assertIs<RecordResult.Already>(fx.record(weekly, "2026-12-28"))
        assertEquals(monday.record.id, saturday.existing.id)
    }

    @Test
    fun c23_notificationStaleKey_returnsStale() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-12T07:30") // notification posted 2026-11-11 21:00, tapped next morning
        assertEquals(
            RecordResult.Stale("2026-11-12"),
            fx.record(intake, renderedKey = "2026-11-11", source = DoseSource.NOTIFICATION),
        )
        assertTrue(fx.history(intake).isEmpty())
    }

    @Test
    fun c24_ignoredInsertWithoutActiveRow_returnsError() = runTest {
        // Fixed clock + zero random: every generated id is identical, so the second insert clashes
        // on the primary key and OR IGNORE drops it although no active judgment exists.
        val clock = TestClock(seoul("2026-11-11T08:00"))
        DbFixture(start = clock.now, ids = UuidV7(clock, ZeroRandom)).use { local ->
            val intake = local.newIntake()
            val first = assertIs<RecordResult.Recorded>(local.record(intake, renderedKey = "2026-11-11"))
            assertEquals(VoidResult.Voided, local.voidDose(first.record.id))

            val result = local.record(intake, renderedKey = "2026-11-11")
            assertIs<RecordResult.Error>(result, "must not be reported as Already: $result")
            assertTrue(local.activeRows(intake).isEmpty())
            assertNotNull(local.history(intake).single().voidedAt)
        }
    }
}
