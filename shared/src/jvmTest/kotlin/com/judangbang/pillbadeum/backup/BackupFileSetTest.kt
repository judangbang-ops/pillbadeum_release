package com.judangbang.pillbadeum.backup

import com.judangbang.pillbadeum.data.AllergyEntity
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.HealthCardEntity
import com.judangbang.pillbadeum.data.IntakeEntity
import com.judangbang.pillbadeum.data.IntakeMedicationEntity
import com.judangbang.pillbadeum.data.MedicationEntity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.ScheduleVersionEntity
import com.judangbang.pillbadeum.data.buildDidITakeDatabase
import com.judangbang.pillbadeum.data.didITakeDatabaseBuilder
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.LatePolicy
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.UuidV7
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.TestClock
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.RecordCommand
import com.judangbang.pillbadeum.usecase.RecordDose
import com.judangbang.pillbadeum.usecase.RecordResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/**
 * TASK-028, record-rules-supplement.md B-1: a backup made of the files the rule file names gives
 * back every record. Regression test for the WAL measurement of the 2026-11-11 review (A1).
 *
 * "Backup" here = copying the files of a live, never-closed file database (the disk state Auto
 * Backup sees: it stops the app before backing up, and a stopped process never closes the
 * database). "Restore" = opening the copy with the app's database builder. The real backup on a
 * phone is checked on an emulator (RR-26, RR-27, docs/07-qa).
 *
 * - RR-26: 1 record, the main file still has no tables (everything is in the WAL)
 * - RR-27: 400 records, the main file was checkpointed twice and still misses the newest rows
 * - RR-28: negative control. Without the WAL the same check must fail, otherwise the positive
 *   tests prove nothing
 * - TC-102: a record, a health-card entry and a medication line restore together
 */
class BackupFileSetTest {
    private val root: File = createTempDirectory("backup-file-set").toFile()
    private val opened = mutableListOf<DidITakeDatabase>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        root.deleteRecursively()
    }

    /** File names of the cloud-backup section in data_extraction_rules.xml (same set as B-1). */
    private val ruleFiles: Set<String> by lazy {
        val rules = BackupRules.parse(BackupRules.androidAppFile("res/xml/data_extraction_rules.xml"))
        val cloud = BackupRules.childElements(rules).single { it.tagName == "cloud-backup" }
        BackupRules.rulesOf(cloud).filter { it.tag == "include" && it.domain == BackupRules.DOMAIN_DATABASE }
            .map { it.path }.toSet()
    }

    @Test
    fun rr26_oneRecord_ruleFilesRestoreIt() = runTest(timeout = 3.minutes) {
        val live = writeRecords(count = 1)
        assertEquals(1, restoredCount(live, ruleFiles))
    }

    @Test
    fun rr27_400Records_ruleFilesRestoreAll() = runTest(timeout = 3.minutes) {
        val live = writeRecords(count = 400)
        assertEquals(400, restoredCount(live, ruleFiles))
    }

    @Test
    fun rr28_negativeControl_withoutWal_recordsAreLost() = runTest(timeout = 3.minutes) {
        val wal = DidITakeDatabase.FILE_NAME + "-wal"
        assertTrue(wal in ruleFiles, "rule file must name $wal: $ruleFiles")

        val one = writeRecords(count = 1, name = "one")
        assertNotEquals(1, restoredCount(one, ruleFiles - wal), "main file alone must not hold the first record")

        val many = writeRecords(count = 400, name = "many")
        val withoutWal = restoredCount(many, ruleFiles - wal)
        assertTrue(withoutWal < 400, "main file alone must miss the newest records, got $withoutWal")
    }

    @Test
    fun tc102_ruleFilesRestoreRecordCardEntryAndMedicationLine() = runTest(timeout = 3.minutes) {
        val live = writeRecords(count = 1)
        val intake = assertNotNull(live.db.intakeDao().findById(live.intakeId))
        val personId = intake.personId
        val now = intake.createdAt
        val card = HealthCardEntity(
            personId = personId, editedAt = now, createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID,
        )
        val entry = AllergyEntity(
            id = "test-entry", personId = personId, substance = "Synthetic substance",
            createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID,
        )
        val medication = MedicationEntity(
            id = "test-med", name = "Synthetic medication", createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID,
        )
        val line = IntakeMedicationEntity(
            id = "test-line", intakeId = live.intakeId, medicationId = medication.id, qtyPerTake = "Test quantity",
            effectiveFrom = now, createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID,
        )
        live.db.healthCardDao().upsert(card)
        live.db.allergyDao().upsert(entry)
        live.db.medicationDao().upsert(medication)
        live.db.intakeMedicationDao().upsert(line)
        val records = live.db.doseRecordDao().listByIntake(live.intakeId)
        assertEquals(1, records.size)

        val restored = restore(live, ruleFiles)
        assertEquals(records, restored.doseRecordDao().listByIntake(live.intakeId))
        assertEquals(card, restored.healthCardDao().findByPerson(personId))
        assertEquals(listOf(entry), restored.allergyDao().listByPerson(personId))
        assertEquals(listOf(line), restored.intakeMedicationDao().listByIntake(live.intakeId))
        assertEquals(medication, restored.medicationDao().findById(line.medicationId))
    }

    /** Live database state after [count] daily records; the database is left open on purpose. */
    private class Live(val dir: File, val intakeId: String, val db: DidITakeDatabase)

    private suspend fun writeRecords(count: Int, name: String = "live"): Live {
        val dir = File(root, name).apply { mkdirs() }
        val clock = TestClock(seoul("2026-01-01T08:00"))
        val ids = UuidV7(clock, Random(seed = 7))
        // Same finishing call the app uses (AppContainer -> buildDidITakeDatabase).
        val db = buildDidITakeDatabase(didITakeDatabaseBuilder(File(dir, DidITakeDatabase.FILE_NAME)), Dispatchers.IO)
        opened += db
        val now = clock.now().toEpochMilliseconds()
        val personId = ids.next()
        db.personDao().insert(
            PersonEntity(id = personId, name = "Test person", color = "#4E79A7", createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID),
        )
        val intakeId = ids.next()
        db.intakeDao().insert(
            IntakeEntity(id = intakeId, personId = personId, name = "Test dose", sortOrder = 0, createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID),
        )
        db.scheduleVersionDao().insert(
            ScheduleVersionEntity(
                id = ids.next(), intakeId = intakeId, type = ScheduleParams.typeOf(ScheduleRule.Daily),
                paramsJson = ScheduleParams.encode(ScheduleRule.Daily), latePolicy = LatePolicy.KEEP,
                effectiveFrom = seoul("2025-12-01T00:00").toEpochMilliseconds(), createdAt = now, updatedAt = now,
                deviceId = TEST_DEVICE_ID,
            ),
        )
        val recordDose = RecordDose(db, clock, ids, TEST_DEVICE_ID)
        repeat(count) { day ->
            val result = recordDose(RecordCommand(intakeId, DoseKind.TAKEN, DoseSource.WIDGET, null, SEOUL.id))
            check(result is RecordResult.Recorded) { "day $day: $result" }
            clock.now = clock.now() + 1.days
        }
        return Live(dir, intakeId, db)
    }

    private suspend fun restoredCount(live: Live, names: Set<String>): Int =
        restore(live, names).doseRecordDao().listByIntake(live.intakeId).size

    /** Copies [names] that exist in the live folder and opens the copy with the app's builder. */
    private fun restore(live: Live, names: Set<String>): DidITakeDatabase {
        val target = File(root, "restore-${opened.size}").apply { mkdirs() }
        names.forEach { name ->
            File(live.dir, name).takeIf { it.isFile }?.copyTo(File(target, name))
        }
        val restored = buildDidITakeDatabase(didITakeDatabaseBuilder(File(target, DidITakeDatabase.FILE_NAME)), Dispatchers.IO)
        opened += restored
        return restored
    }
}
