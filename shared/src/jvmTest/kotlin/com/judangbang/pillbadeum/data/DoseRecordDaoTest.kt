package com.judangbang.pillbadeum.data

import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** Storage guarantees of data-model.md sections 2-3, checked directly against the DAO. */
class DoseRecordDaoTest {
    private val fx = DbFixture()
    private val dao = fx.db.doseRecordDao()

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun row(intakeId: String, periodKey: String, active: Int?): DoseRecordEntity {
        val now = fx.clock.now().toEpochMilliseconds()
        val scheduleId = checkNotNull(fx.db.scheduleVersionDao().findEffective(intakeId, now)).id
        return DoseRecordEntity(
            id = fx.ids.next(),
            intakeId = intakeId,
            scheduleVersionId = scheduleId,
            periodKey = periodKey,
            kind = DoseKind.TAKEN,
            takenAt = now,
            zoneId = SEOUL.id,
            recordedAt = now,
            source = DoseSource.WIDGET,
            active = active,
            voidedAt = if (active == null) now else null,
            voidReason = if (active == null) "TEST" else null,
            createdAt = now,
            updatedAt = now,
            deviceId = TEST_DEVICE_ID,
        )
    }

    @Test
    fun uniqueIndex_secondActiveRowIgnored_voidedRowsRepeat() = runTest {
        val intake = fx.newIntake()

        assertNotEquals(-1L, dao.insertOrIgnore(row(intake, "2026-11-11", active = 1)))
        assertEquals(-1L, dao.insertOrIgnore(row(intake, "2026-11-11", active = 1)), "second active row must be ignored")
        assertNotEquals(-1L, dao.insertOrIgnore(row(intake, "2026-11-11", active = null)))
        assertNotEquals(-1L, dao.insertOrIgnore(row(intake, "2026-11-11", active = null)))
        // Another window of the same intake is independent.
        assertNotEquals(-1L, dao.insertOrIgnore(row(intake, "2026-11-12", active = 1)))

        val rows = dao.listByIntake(intake)
        assertEquals(4, rows.size)
        assertEquals(1, rows.count { it.periodKey == "2026-11-11" && it.isActive })
        assertEquals(2, rows.count { it.periodKey == "2026-11-11" && it.active == null })
    }

    @Test
    fun voidActive_onlyTouchesActiveRows_andBumpsVersion() = runTest {
        val intake = fx.newIntake()
        val active = row(intake, "2026-11-11", active = 1)
        dao.insertOrIgnore(active)
        val voidAt = seoul("2026-11-11T09:00").toEpochMilliseconds()

        assertEquals(1, dao.voidActive(active.id, voidAt, "USER_CANCEL"))
        assertEquals(0, dao.voidActive(active.id, voidAt, "USER_CANCEL"), "already voided")
        assertEquals(0, dao.voidActive("missing-id", voidAt, "USER_CANCEL"))

        val voided = checkNotNull(dao.findById(active.id))
        assertNull(voided.active)
        assertEquals(voidAt, voided.voidedAt)
        assertEquals(voidAt, voided.updatedAt)
        assertEquals("USER_CANCEL", voided.voidReason)
        assertEquals(2, voided.version)
    }

    @Test
    fun enums_areStoredAsTextCodes() = runTest {
        val intake = fx.newIntake()
        dao.insertOrIgnore(row(intake, "2026-11-11", active = 1))
        val stored = fx.db.useReaderConnection { connection ->
            connection.usePrepared("SELECT kind, source, typeof(kind) FROM dose_record") { statement ->
                check(statement.step())
                listOf(statement.getText(0), statement.getText(1), statement.getText(2))
            }
        }
        assertEquals(listOf("TAKEN", "WIDGET", "text"), stored)
    }

    @Test
    fun foreignKeys_hardDeleteOfReferencedIntakeFails_noCascade() = runTest {
        val intake = fx.newIntake()
        dao.insertOrIgnore(row(intake, "2026-11-11", active = 1))

        assertFailsWith<SQLiteException> {
            fx.db.useWriterConnection { connection ->
                connection.usePrepared("DELETE FROM intake WHERE id = ?") { statement ->
                    statement.bindText(1, intake)
                    statement.step()
                }
            }
        }
        assertEquals(1, dao.listByIntake(intake).size, "records survive")
    }

    @Test
    fun foreignKeys_insertOrIgnoreForMissingIntakeStillThrows() = runTest {
        val intake = fx.newIntake()
        val orphan = row(intake, "2026-11-11", active = 1).copy(intakeId = "deleted-intake")
        // OR IGNORE does not swallow foreign key violations (review T4): this surfaces as an error.
        assertFailsWith<SQLiteException> { dao.insertOrIgnore(orphan) }
    }
}
