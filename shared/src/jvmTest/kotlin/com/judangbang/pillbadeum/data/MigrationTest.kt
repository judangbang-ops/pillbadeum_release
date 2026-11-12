package com.judangbang.pillbadeum.data

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.judangbang.pillbadeum.testing.seoul
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule

/**
 * Schema migrations against the exported schema JSON (REQ-072, TASK-003). Each test builds a real
 * version-1 database file with raw SQL, migrates it and lets Room validate the result against
 * `schemas/.../2.json`.
 */
class MigrationTest {
    private val dbFile: Path = Files.createTempFile("pillbadeum-migration", ".db")

    @get:Rule
    val helper = MigrationTestHelper(
        schemaDirectoryPath = Path("schemas"),
        databasePath = dbFile,
        driver = BundledSQLiteDriver(),
        databaseClass = DidITakeDatabase::class,
        databaseFactory = { DidITakeDatabaseConstructor.initialize() },
    )

    @AfterTest
    fun tearDown() {
        listOf("", "-wal", "-shm", "-journal").forEach { Path("$dbFile$it").deleteIfExists() }
    }

    private fun ms(local: String): Long = seoul(local).toEpochMilliseconds()

    @Test
    fun v1_to_v2_moves_first_versions_and_keeps_every_judgment() = runTest {
        helper.createDatabase(1).use { v1 ->
            v1.person("p1")
            // Daily item registered Wednesday 15:00, changed to weekly (MON) the next day 10:00.
            v1.intake("daily", "p1", createdAt = ms("2026-11-11T15:00"))
            v1.version("daily-v1", "daily", "DAILY", "{}", from = ms("2026-11-11T15:00"), to = ms("2026-11-12T10:00"))
            v1.version("daily-v2", "daily", "WEEKLY", """{"anchorWeekday":"MON"}""", from = ms("2026-11-12T10:00"))
            // Weekly (MON) item registered Wednesday 15:00: its first window started Monday 04:00.
            v1.intake("weekly", "p1", createdAt = ms("2026-11-11T15:00"))
            v1.version("weekly-v1", "weekly", "WEEKLY", """{"anchorWeekday":"MON"}""", from = ms("2026-11-11T15:00"))
            // Registered 02:00 on Thursday: before the 04:00 day start, so Wednesday's window.
            v1.intake("night", "p1", createdAt = ms("2026-11-12T02:00"))
            v1.version("night-v1", "night", "DAILY", "{}", from = ms("2026-11-12T02:00"))

            v1.dose("d1", "daily", "daily-v1", "2026-11-11", takenAt = ms("2026-11-11T21:00"), active = true)
            v1.dose("d2", "daily", "daily-v1", "2026-11-11", takenAt = ms("2026-11-11T20:00"), active = false)
            v1.dose("d3", "weekly", "weekly-v1", "2026-11-09", takenAt = ms("2026-11-11T16:00"), active = true)
        }
        val judgmentsBefore = helper.createdRows("SELECT * FROM dose_record ORDER BY id")

        helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2)).use { v2 ->
            assertEquals(
                mapOf(
                    "daily-v1" to ms("2026-11-11T04:00"),
                    "daily-v2" to ms("2026-11-12T10:00"),
                    "weekly-v1" to ms("2026-11-09T04:00"),
                    "night-v1" to ms("2026-11-11T04:00"),
                ),
                v2.longs("SELECT id, effective_from FROM schedule_version"),
            )
            assertEquals(ms("2026-11-12T10:00"), v2.longs("SELECT id, effective_to FROM schedule_version")["daily-v1"])
            assertEquals(judgmentsBefore, v2.rows("SELECT * FROM dose_record ORDER BY id"), "judgments are untouched")
            for (table in listOf("reminder_rule", "reminder_snooze", "clock_flag")) {
                assertEquals(0L, v2.count("SELECT COUNT(*) FROM $table"), table)
            }
        }
    }

    @Test
    fun v1_to_v2_uses_the_stored_day_start_and_base_zone() = runTest {
        helper.createDatabase(1).use { v1 ->
            v1.setting(SettingKeys.DAY_START, "06:00")
            v1.setting(SettingKeys.BASE_ZONE, "Asia/Tokyo") // same offset as Seoul, different id
            v1.person("p1")
            v1.intake("a", "p1", createdAt = ms("2026-11-11T05:00"))
            v1.version("a-v1", "a", "DAILY", "{}", from = ms("2026-11-11T05:00"))
        }
        helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2)).use { v2 ->
            // 05:00 is before the 06:00 day start: the window began 11/10 06:00.
            assertEquals(ms("2026-11-10T06:00"), v2.longs("SELECT id, effective_from FROM schedule_version")["a-v1"])
        }
    }

    @Test
    fun v1_to_v2_keeps_starts_when_settings_cannot_be_read() = runTest {
        helper.createDatabase(1).use { v1 ->
            v1.setting(SettingKeys.DAY_START, "not a time")
            v1.person("p1")
            v1.intake("a", "p1", createdAt = ms("2026-11-11T15:00"))
            v1.version("a-v1", "a", "DAILY", "{}", from = ms("2026-11-11T15:00"))
        }
        helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2)).use { v2 ->
            assertEquals(ms("2026-11-11T15:00"), v2.longs("SELECT id, effective_from FROM schedule_version")["a-v1"])
        }
    }

    @Test
    fun migrated_file_opens_with_room_and_the_registration_morning_is_scheduled() = runTest {
        helper.createDatabase(1).use { v1 ->
            v1.person("p1")
            v1.intake("a", "p1", createdAt = ms("2026-11-11T15:00"))
            v1.version("a-v1", "a", "DAILY", "{}", from = ms("2026-11-11T15:00"))
        }
        val db = buildDidITakeDatabase(didITakeDatabaseBuilder(dbFile.toFile()), Dispatchers.IO)
        try {
            val version = db.scheduleVersionDao().findEffective("a", ms("2026-11-11T08:00"))
            assertEquals("a-v1", version?.id, "registration-day morning belongs to the first version (P-4, RR-41)")
            assertTrue(db.scheduleVersionDao().findEffective("a", ms("2026-11-11T03:59")) == null)
        } finally {
            db.close()
        }
    }

    // --- raw version-1 rows (column lists from schemas/.../1.json) ---

    private fun SQLiteConnection.person(id: String) = execSQL(
        "INSERT INTO person (id, name, color, created_at, updated_at, version, device_id) " +
            "VALUES ('$id', '나', '#3F7D58', 0, 0, 1, 'old-device')",
    )

    private fun SQLiteConnection.intake(id: String, personId: String, createdAt: Long) = execSQL(
        "INSERT INTO intake (id, person_id, name, sort_order, created_at, updated_at, version, device_id) " +
            "VALUES ('$id', '$personId', '$id', 0, $createdAt, $createdAt, 1, 'old-device')",
    )

    private fun SQLiteConnection.version(
        id: String,
        intakeId: String,
        type: String,
        params: String,
        from: Long,
        to: Long? = null,
    ) = execSQL(
        "INSERT INTO schedule_version " +
            "(id, intake_id, type, params_json, late_policy, effective_from, effective_to, created_at, updated_at, version, device_id) " +
            "VALUES ('$id', '$intakeId', '$type', '$params', 'ASK', $from, ${to ?: "NULL"}, $from, $from, 1, 'old-device')",
    )

    private fun SQLiteConnection.dose(
        id: String,
        intakeId: String,
        versionId: String,
        periodKey: String,
        takenAt: Long,
        active: Boolean,
    ) = execSQL(
        "INSERT INTO dose_record (id, intake_id, schedule_version_id, period_key, kind, taken_at, zone_id, " +
            "recorded_at, source, active, voided_at, void_reason, created_at, updated_at, version, device_id) " +
            "VALUES ('$id', '$intakeId', '$versionId', '$periodKey', 'TAKEN', $takenAt, 'Asia/Seoul', $takenAt, 'WIDGET', " +
            (if (active) "1, NULL, NULL" else "NULL, $takenAt, 'USER_CANCEL'") + ", $takenAt, $takenAt, 1, 'old-device')",
    )

    private fun SQLiteConnection.setting(key: String, value: String) =
        execSQL("INSERT INTO setting (`key`, `value`) VALUES ('$key', '$value')")

    // --- reading back ---

    /** Rows as lists of nullable text, read on a separate connection to the version-1 file. */
    private fun MigrationTestHelper.createdRows(sql: String): List<List<String?>> =
        BundledSQLiteDriver().open(dbFile.toString()).use { it.rows(sql) }

    private fun SQLiteConnection.rows(sql: String): List<List<String?>> = prepare(sql).use { statement ->
        buildList {
            while (statement.step()) {
                add((0 until statement.getColumnCount()).map { if (statement.isNull(it)) null else statement.getText(it) })
            }
        }
    }

    private fun SQLiteConnection.longs(sql: String): Map<String, Long?> = prepare(sql).use { statement ->
        buildMap {
            while (statement.step()) put(statement.getText(0), if (statement.isNull(1)) null else statement.getLong(1))
        }
    }

    private fun SQLiteConnection.count(sql: String): Long = prepare(sql).use { statement ->
        check(statement.step())
        statement.getLong(0)
    }
}
