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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Rule

/**
 * Schema migrations against the exported schema JSON (REQ-072). The tests build real files
 * from the previous Room schema and check both the migrated schema and the stored rows.
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

    // Expected values below pin what users upgrading from schema 1 get. If a later change to
    // windowOf, ScheduleParams or the WindowSettings defaults breaks them, fix that change: do not
    // edit these expectations, or the 1 -> 2 result silently differs between users.

    private fun ms(local: String): Long = seoul(local).toEpochMilliseconds()

    private fun ny(local: String): Long =
        LocalDateTime.parse(local).toInstant(TimeZone.of("America/New_York")).toEpochMilliseconds()

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
            assertTrue(v2.rows("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test
    fun v1_to_v2_uses_the_stored_day_start_and_base_zone_across_dst() = runTest {
        // New York differs from Seoul by 13-14 hours, so ignoring the stored zone fails here.
        // 2026-03-08 has no 02:00-03:00 in New York: the 02:30 day start falls into the gap and
        // that day's window starts at 03:30 EDT (windowOf resolves the gap forward).
        helper.createDatabase(1).use { v1 ->
            v1.setting(SettingKeys.DAY_START, "02:30")
            v1.setting(SettingKeys.BASE_ZONE, "America/New_York")
            v1.person("p1")
            v1.intake("nov", "p1", createdAt = ny("2026-11-11T15:00"))
            v1.version("nov-v1", "nov", "DAILY", "{}", from = ny("2026-11-11T15:00"))
            v1.intake("early", "p1", createdAt = ny("2026-11-11T02:00"))
            v1.version("early-v1", "early", "DAILY", "{}", from = ny("2026-11-11T02:00"))
            v1.intake("gap", "p1", createdAt = ny("2026-03-08T03:10"))
            v1.version("gap-v1", "gap", "DAILY", "{}", from = ny("2026-03-08T03:10"))
            v1.intake("after-gap", "p1", createdAt = ny("2026-03-08T09:00"))
            v1.version("after-gap-v1", "after-gap", "DAILY", "{}", from = ny("2026-03-08T09:00"))
            v1.intake("sun", "p1", createdAt = ny("2026-03-10T12:00"))
            v1.version("sun-v1", "sun", "WEEKLY", """{"anchorWeekday":"SUN"}""", from = ny("2026-03-10T12:00"))
        }
        helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2)).use { v2 ->
            assertEquals(
                mapOf(
                    "nov-v1" to ny("2026-11-11T02:30"),
                    "early-v1" to ny("2026-11-10T02:30"), // before the 02:30 day start
                    "gap-v1" to ny("2026-03-07T02:30"), // 03:10 is still before the 03:30 boundary
                    "after-gap-v1" to ny("2026-03-08T03:30"),
                    "sun-v1" to ny("2026-03-08T03:30"), // weekly (SUN) window began at the shifted boundary
                ),
                v2.longs("SELECT id, effective_from FROM schedule_version"),
            )
        }
    }

    @Test
    fun v1_to_v2_keeps_starts_when_settings_cannot_be_read() = runTest {
        for ((key, bad) in listOf(SettingKeys.DAY_START to "not a time", SettingKeys.BASE_ZONE to "Mars/Phobos")) {
            tearDown()
            helper.createDatabase(1).use { v1 ->
                v1.setting(key, bad)
                v1.person("p1")
                v1.intake("a", "p1", createdAt = ms("2026-11-11T15:00"))
                v1.version("a-v1", "a", "DAILY", "{}", from = ms("2026-11-11T15:00"))
            }
            helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2)).use { v2 ->
                assertEquals(
                    ms("2026-11-11T15:00"),
                    v2.longs("SELECT id, effective_from FROM schedule_version")["a-v1"],
                    "$key = $bad",
                )
            }
        }
    }

    @Test
    fun v1_to_v2_skips_unreadable_versions_and_moves_the_rest() = runTest {
        helper.createDatabase(1).use { v1 ->
            v1.person("p1")
            v1.intake("bad-params", "p1", createdAt = ms("2026-11-11T15:00"))
            v1.version("bad-params-v1", "bad-params", "WEEKLY", "{}", from = ms("2026-11-11T15:00"))
            v1.intake("unknown-type", "p1", createdAt = ms("2026-11-11T15:00"))
            v1.version("unknown-type-v1", "unknown-type", "EVERY_N_DAYS", """{"n":2}""", from = ms("2026-11-11T15:00"))
            v1.intake("fine", "p1", createdAt = ms("2026-11-11T15:00"))
            v1.version("fine-v1", "fine", "DAILY", "{}", from = ms("2026-11-11T15:00"))
            v1.intake("no-version", "p1", createdAt = ms("2026-11-11T15:00"))
        }
        helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2)).use { v2 ->
            assertEquals(
                mapOf(
                    "bad-params-v1" to ms("2026-11-11T15:00"),
                    "unknown-type-v1" to ms("2026-11-11T15:00"),
                    "fine-v1" to ms("2026-11-11T04:00"),
                ),
                v2.longs("SELECT id, effective_from FROM schedule_version"),
            )
            assertTrue(v2.rows("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test
    fun v2_to_v3_keeps_first_release_rows_and_seeds_each_card() = runTest {
        val tables = mapOf(
            "person" to "id", "intake" to "id", "schedule_version" to "id", "dose_record" to "id",
            "clock_flag" to "dose_id", "reminder_rule" to "intake_id", "reminder_snooze" to "intake_id",
            "setting" to "key", "medication" to "id", "intake_medication" to "id", "dose_version" to "id",
        )
        val originalRows = helper.createDatabase(2).use { v2 ->
            v2.person("p1")
            v2.person("p2")
            v2.intake("a", "p1", createdAt = 100L)
            v2.intake("b", "p1", createdAt = 100L)
            v2.intake("c", "p2", createdAt = 100L)
            v2.execSQL("UPDATE person SET updated_at = 300, archived_at = 300 WHERE id = 'p1'")
            v2.execSQL("UPDATE person SET updated_at = 800 WHERE id = 'p2'")
            v2.execSQL("UPDATE intake SET updated_at = 600, archived_at = 600 WHERE id = 'a'")
            v2.execSQL("UPDATE intake SET updated_at = 200 WHERE id = 'b'")
            v2.execSQL("UPDATE intake SET updated_at = 700 WHERE id = 'c'")

            listOf("a", "b", "c").forEach { id ->
                v2.version("$id-v1", id, "DAILY", "{}", from = 100L)
            }
            repeat(10) { index ->
                val intakeId = listOf("a", "b", "c")[index % 3]
                val day = "2026-01-${10 + index}"
                v2.dose("d$index", intakeId, "$intakeId-v1", day, ms("${day}T08:00"), active = index != 9)
            }
            v2.execSQL("INSERT INTO clock_flag (dose_id, flagged_at) VALUES ('d0', 1000)")
            v2.execSQL(
                "INSERT INTO reminder_rule (intake_id, enabled, start_time, created_at, updated_at, version, device_id) " +
                    "VALUES ('b', 1, '08:00', 100, 200, 1, 'old-device')",
            )
            v2.execSQL("INSERT INTO reminder_snooze (intake_id, period_key, fire_at) VALUES ('c', '2026-01-12', 2000)")
            v2.setting(SettingKeys.DEVICE_ID, "test-install")
            assertEquals(10L, v2.count("SELECT COUNT(*) FROM dose_record"))
            tables.mapValues { (table, key) -> v2.rows("SELECT * FROM `$table` ORDER BY `$key`") }
        }

        val beforeMigration = Clock.System.now().toEpochMilliseconds()
        helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_2_3)).use { v3 ->
            val afterMigration = Clock.System.now().toEpochMilliseconds()
            tables.forEach { (table, key) ->
                assertEquals(originalRows.getValue(table), v3.rows("SELECT * FROM `$table` ORDER BY `$key`"), table)
            }
            assertEquals(
                listOf(
                    listOf("p1", "600", null, null, "1", "test-install"),
                    listOf("p2", "800", null, null, "1", "test-install"),
                ),
                v3.rows(
                    "SELECT person_id, edited_at, emergency_copied_at, stale_notice_closed_at, version, device_id " +
                        "FROM health_card ORDER BY person_id",
                ),
            )
            val created = v3.longs("SELECT person_id, created_at FROM health_card")
            assertEquals(created, v3.longs("SELECT person_id, updated_at FROM health_card"))
            assertTrue(created.values.toSet().single() in beforeMigration..afterMigration)
            for (table in listOf("condition", "procedure_history", "allergy", "implanted_device", "care_contact")) {
                assertEquals(0L, v3.count("SELECT COUNT(*) FROM `$table`"), table)
            }
            assertTrue(v3.rows("PRAGMA foreign_key_check").isEmpty())
        }
    }

    @Test
    fun v2_to_v3_without_people_creates_empty_card_tables() = runTest {
        helper.createDatabase(2).use { v2 -> v2.setting(SettingKeys.DEVICE_ID, "test-install") }

        helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_2_3)).use { v3 ->
            val cardTables = listOf(
                "health_card", "condition", "procedure_history", "allergy", "implanted_device", "care_contact",
            )
            for (table in cardTables) {
                assertEquals(0L, v3.count("SELECT COUNT(*) FROM `$table`"), table)
            }
            assertEquals(
                listOf(listOf(SettingKeys.DEVICE_ID, "test-install")),
                v3.rows("SELECT `key`, `value` FROM setting"),
            )
        }
    }

    @Test
    fun v2_without_intakes_or_install_id_uses_person_metadata_when_opened() = runTest {
        helper.createDatabase(2).use { v2 ->
            v2.person("p1")
            v2.execSQL("UPDATE person SET updated_at = 500 WHERE id = 'p1'")
        }

        val db = buildDidITakeDatabase(didITakeDatabaseBuilder(dbFile.toFile()), Dispatchers.IO)
        try {
            val card = assertNotNull(db.healthCardDao().findByPerson("p1"))
            assertEquals(500L, card.editedAt)
            assertEquals("old-device", card.deviceId)
        } finally {
            db.close()
        }
    }

    @Test
    fun v1_to_v3_opens_with_room_and_keeps_schedule_and_card_timestamps() = runTest {
        val registeredAt = ms("2026-11-11T15:00")
        helper.createDatabase(1).use { v1 ->
            v1.person("p1")
            v1.intake("a", "p1", createdAt = registeredAt)
            v1.version("a-v1", "a", "DAILY", "{}", from = registeredAt)
        }
        val db = buildDidITakeDatabase(didITakeDatabaseBuilder(dbFile.toFile()), Dispatchers.IO)
        try {
            val version = db.scheduleVersionDao().findEffective("a", ms("2026-11-11T08:00"))
            assertEquals("a-v1", version?.id, "registration-day morning belongs to the first version (P-4, RR-41)")
            assertTrue(db.scheduleVersionDao().findEffective("a", ms("2026-11-11T03:59")) == null)
            val card = assertNotNull(db.healthCardDao().findByPerson("p1"))
            assertEquals(registeredAt, card.editedAt)
            assertEquals("old-device", card.deviceId)
        } finally {
            db.close()
        }
    }

    // --- raw rows shared by schemas 1 and 2 ---

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
