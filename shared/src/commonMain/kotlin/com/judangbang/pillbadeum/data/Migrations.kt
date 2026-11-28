package com.judangbang.pillbadeum.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.judangbang.pillbadeum.domain.ScheduleType
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.firstVersionStart
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Hand-written schema migrations (REQ-072). Each one has a test in `MigrationTest` that opens the
 * exported schema of the old version, migrates and validates against the new schema JSON.
 */
object Migrations {
    /**
     * 1 -> 2 (TASK-003): reminder and clock-flag tables, and the first schedule version of every
     * intake starts at its registration window (record-rules-supplement.md N-1, N-2, C-1, P-4).
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // CREATE statements copied from schemas/.../2.json so Room's schema check passes.
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `reminder_rule` (`intake_id` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
                    "`start_time` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "`version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, PRIMARY KEY(`intake_id`), " +
                    "FOREIGN KEY(`intake_id`) REFERENCES `intake`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `reminder_snooze` (`intake_id` TEXT NOT NULL, `period_key` TEXT NOT NULL, " +
                    "`fire_at` INTEGER NOT NULL, PRIMARY KEY(`intake_id`), " +
                    "FOREIGN KEY(`intake_id`) REFERENCES `intake`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `clock_flag` (`dose_id` TEXT NOT NULL, `flagged_at` INTEGER NOT NULL, " +
                    "`resolved_at` INTEGER, PRIMARY KEY(`dose_id`), " +
                    "FOREIGN KEY(`dose_id`) REFERENCES `dose_record`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            moveFirstVersionsToRegistrationWindow(connection)
        }
    }

    /** Adds the health-card tables and seeds timestamps for existing people (TASK-057, ERD 8.5). */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // CREATE statements from the Room 3 export (ERD 8.3).
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `health_card` (" +
                    "`person_id` TEXT NOT NULL, `edited_at` INTEGER NOT NULL, `emergency_copied_at` INTEGER, " +
                    "`stale_notice_closed_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "`version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, PRIMARY KEY(`person_id`), " +
                    "FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `condition` (" +
                    "`id` TEXT NOT NULL, `person_id` TEXT NOT NULL, `name` TEXT NOT NULL, `diagnosed_on` TEXT, " +
                    "`note` TEXT, `code_system` TEXT, `code` TEXT, `source` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
                    "`device_id` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `ix_condition_person` ON `condition` (`person_id`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `procedure_history` (" +
                    "`id` TEXT NOT NULL, `person_id` TEXT NOT NULL, `name` TEXT NOT NULL, `performed_on` TEXT, " +
                    "`place` TEXT, `code_system` TEXT, `code` TEXT, `source` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
                    "`device_id` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `ix_procedure_history_person` ON `procedure_history` (`person_id`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `allergy` (" +
                    "`id` TEXT NOT NULL, `person_id` TEXT NOT NULL, `substance` TEXT NOT NULL, `reaction` TEXT, " +
                    "`code_system` TEXT, `code` TEXT, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, `device_id` TEXT NOT NULL, " +
                    "PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `ix_allergy_person` ON `allergy` (`person_id`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `implanted_device` (" +
                    "`id` TEXT NOT NULL, `person_id` TEXT NOT NULL, `name` TEXT NOT NULL, `implanted_on` TEXT, " +
                    "`note` TEXT, `code_system` TEXT, `code` TEXT, `source` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
                    "`device_id` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `ix_implanted_device_person` ON `implanted_device` (`person_id`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `care_contact` (" +
                    "`id` TEXT NOT NULL, `person_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                    "`department` TEXT, `doctor` TEXT, `relation` TEXT, `phone` TEXT, `source` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
                    "`device_id` TEXT NOT NULL, PRIMARY KEY(`id`), " +
                    "FOREIGN KEY(`person_id`) REFERENCES `person`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `ix_care_contact_person_kind` ON `care_contact` (`person_id`, `kind`)",
            )

            val now = Clock.System.now().toEpochMilliseconds()
            connection.prepare(
                """
                INSERT INTO health_card (
                    person_id, edited_at, emergency_copied_at, stale_notice_closed_at,
                    created_at, updated_at, version, device_id
                )
                SELECT p.id,
                       MAX(p.updated_at, IFNULL((SELECT MAX(i.updated_at) FROM intake i WHERE i.person_id = p.id), 0)),
                       NULL, NULL, ?1, ?1, 1,
                       IFNULL((SELECT `value` FROM `setting` WHERE `key` = 'device_id'), p.device_id)
                FROM person p
                """.trimIndent(),
            ).use { statement ->
                statement.bindLong(1, now)
                statement.step()
            }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

    /**
     * Supplement P-4 for databases made before schema 2: the earliest schedule version of each
     * intake starts at the start of the window that held its old start (the registration moment).
     *
     * Judgments are not touched. `updated_at` and `version` stay as they are: this corrects a rule,
     * it is not a user edit. When the stored window settings or a version's params cannot be read,
     * that start is left unchanged so that opening the database never fails here.
     */
    private fun moveFirstVersionsToRegistrationWindow(connection: SQLiteConnection) {
        val settings = runCatching {
            parseWindowSettings(
                dayStart = connection.settingValue(SettingKeys.DAY_START),
                baseZone = connection.settingValue(SettingKeys.BASE_ZONE),
            )
        }.getOrNull() ?: return

        // "First" = smallest effective_from, then smallest id. archived_at is not looked at: the app
        // never archives schedule versions, and it cannot write two versions in the same ms.
        val moves = mutableListOf<Pair<String, Long>>()
        connection.prepare(
            """
            SELECT v.id, v.type, v.params_json, v.effective_from FROM schedule_version v
            WHERE NOT EXISTS (
                SELECT 1 FROM schedule_version o
                WHERE o.intake_id = v.intake_id
                  AND (o.effective_from < v.effective_from OR (o.effective_from = v.effective_from AND o.id < v.id))
            )
            """.trimIndent(),
        ).use { statement ->
            while (statement.step()) {
                val id = statement.getText(0)
                val from = statement.getLong(3)
                val start = firstStartOrNull(statement.getText(1), statement.getText(2), from, settings) ?: continue
                if (start < from) moves += id to start
            }
        }
        connection.prepare("UPDATE schedule_version SET effective_from = ? WHERE id = ?").use { update ->
            for ((id, start) in moves) {
                update.bindLong(1, start)
                update.bindText(2, id)
                update.step()
                update.reset()
            }
        }
    }

    private fun firstStartOrNull(type: String, paramsJson: String, from: Long, settings: WindowSettings): Long? =
        runCatching {
            val rule = ScheduleParams.decode(ScheduleType.valueOf(type), paramsJson)
            firstVersionStart(rule, Instant.fromEpochMilliseconds(from), settings).toEpochMilliseconds()
        }.getOrNull()

    private fun SQLiteConnection.settingValue(key: String): String? =
        prepare("SELECT `value` FROM setting WHERE `key` = ?").use { statement ->
            statement.bindText(1, key)
            if (statement.step()) statement.getText(0) else null
        }
}
