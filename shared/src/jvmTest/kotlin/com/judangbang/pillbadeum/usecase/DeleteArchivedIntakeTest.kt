package com.judangbang.pillbadeum.usecase

import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.testing.DbFixture
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Permanent delete of an archived intake (TASK-010, REQ-015). */
class DeleteArchivedIntakeTest {
    private val fx = DbFixture()
    private val delete = DeleteArchivedIntake(fx.db)
    private val archive = ArchiveIntake(fx.db, fx.clock)

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun count(sql: String): Long = fx.db.useWriterConnection { connection ->
        connection.usePrepared(sql) { statement ->
            check(statement.step())
            statement.getLong(0)
        }
    }

    @Test
    fun deletes_the_intake_and_everything_pointing_at_it_and_later_taps_are_gone() = runTest {
        val intake = fx.newIntake()
        val other = fx.newIntake()
        fx.at("2026-11-11T08:00")
        val dose = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11")).record
        fx.at("2026-11-11T08:10")
        assertEquals(VoidResult.Voided, fx.voidDose(dose.id))
        fx.at("2026-11-11T08:20")
        val again = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11")).record
        assertIs<RecordResult.Recorded>(fx.record(other, renderedKey = "2026-11-11"))
        fx.db.useWriterConnection { connection ->
            connection.executeSQL(
                "INSERT INTO reminder_rule VALUES ('$intake', 1, '08:00', 0, 0, 1, 'test-device')",
            )
            connection.executeSQL("INSERT INTO reminder_snooze VALUES ('$intake', '2026-11-11', 0)")
            connection.executeSQL("INSERT INTO clock_flag VALUES ('${again.id}', 0, NULL)")
        }
        fx.changeSchedule(intake, com.judangbang.pillbadeum.domain.ScheduleRule.Weekly(kotlinx.datetime.DayOfWeek.MONDAY), fx.clock.now())

        assertEquals(DeleteIntakeResult.NotArchived, delete(intake), "active intakes are not deleted")
        assertTrue(archive(intake))
        assertEquals(DeleteIntakeResult.Deleted, delete(intake))

        assertNull(fx.db.intakeDao().findById(intake))
        assertTrue(fx.history(intake).isEmpty())
        assertTrue(fx.db.scheduleVersionDao().listByIntake(intake).isEmpty())
        for (table in listOf("reminder_rule", "reminder_snooze", "clock_flag")) {
            assertEquals(0L, count("SELECT COUNT(*) FROM $table"), table)
        }
        // The other intake is untouched.
        assertEquals(1, fx.activeRows(other).size)
        assertEquals(1, fx.db.scheduleVersionDao().listByIntake(other).size)

        fx.at("2026-11-11T09:00")
        assertEquals(RecordResult.Gone, fx.record(intake, renderedKey = "2026-11-11"))
        assertEquals(DeleteIntakeResult.Gone, delete(intake))
    }
}
