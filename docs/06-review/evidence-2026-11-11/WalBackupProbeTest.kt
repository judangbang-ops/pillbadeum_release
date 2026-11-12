package com.zmanlab.diditake.probe

import androidx.room3.useReaderConnection
import com.zmanlab.diditake.data.DidITakeDatabase
import com.zmanlab.diditake.data.IntakeEntity
import com.zmanlab.diditake.data.PersonEntity
import com.zmanlab.diditake.data.ScheduleParams
import com.zmanlab.diditake.data.ScheduleVersionEntity
import com.zmanlab.diditake.data.buildDidITakeDatabase
import com.zmanlab.diditake.data.didITakeDatabaseBuilder
import com.zmanlab.diditake.domain.DoseKind
import com.zmanlab.diditake.domain.DoseSource
import com.zmanlab.diditake.domain.LatePolicy
import com.zmanlab.diditake.domain.ScheduleRule
import com.zmanlab.diditake.domain.UuidV7
import com.zmanlab.diditake.testing.SEOUL
import com.zmanlab.diditake.testing.TEST_DEVICE_ID
import com.zmanlab.diditake.testing.TestClock
import com.zmanlab.diditake.testing.seoul
import com.zmanlab.diditake.usecase.RecordCommand
import com.zmanlab.diditake.usecase.RecordDose
import com.zmanlab.diditake.usecase.RecordResult
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Scratch probe (review only, not part of the product): which journal mode the app's database
 * builder gives a FILE database, and what a copy of the main file alone contains while the
 * process is still alive (the on-disk state a process kill would leave behind).
 */
class WalBackupProbeTest {
    @Test
    fun probe() = runBlocking {
        val root = File(checkNotNull(System.getenv("PROBE_DIR")) { "PROBE_DIR not set" })
        root.deleteRecursively()
        val live = File(root, "live").apply { mkdirs() }
        val dbFile = File(live, DidITakeDatabase.FILE_NAME)
        val out = StringBuilder()

        val clock = TestClock(seoul("2026-01-01T08:00"))
        val ids = UuidV7(clock, Random(seed = 7))
        // Same finishing call the app uses (AppContainer -> buildDidITakeDatabase).
        val db = buildDidITakeDatabase(didITakeDatabaseBuilder(dbFile), Dispatchers.IO)
        val recordDose = RecordDose(db, clock, ids, TEST_DEVICE_ID)

        val now = clock.now().toEpochMilliseconds()
        val personId = ids.next()
        db.personDao().insert(
            PersonEntity(id = personId, name = "Probe person", color = "#4E79A7", createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID),
        )
        val intakeId = ids.next()
        db.intakeDao().insert(
            IntakeEntity(id = intakeId, personId = personId, name = "Probe dose", sortOrder = 0, createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID),
        )
        db.scheduleVersionDao().insert(
            ScheduleVersionEntity(
                id = ids.next(), intakeId = intakeId, type = ScheduleParams.typeOf(ScheduleRule.Daily),
                paramsJson = ScheduleParams.encode(ScheduleRule.Daily), latePolicy = LatePolicy.ASK,
                effectiveFrom = seoul("2025-12-01T00:00").toEpochMilliseconds(), createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID,
            ),
        )

        val journalMode = db.useReaderConnection { c ->
            c.usePrepared("PRAGMA journal_mode") { st -> check(st.step()); st.getText(0) }
        }
        val autoCheckpoint = db.useReaderConnection { c ->
            c.usePrepared("PRAGMA wal_autocheckpoint") { st -> check(st.step()); st.getLong(0) }
        }
        val pageSize = db.useReaderConnection { c ->
            c.usePrepared("PRAGMA page_size") { st -> check(st.step()); st.getLong(0) }
        }
        out.appendLine("journal_mode=$journalMode wal_autocheckpoint=$autoCheckpoint page_size=$pageSize")

        fun snapshot(label: String, recorded: Int) {
            val files = live.listFiles()!!.sortedBy { it.name }.joinToString(" ") { "${it.name}=${it.length()}" }
            out.appendLine("$label recorded=$recorded files: $files")
            val only = File(root, "$label/only-db").apply { mkdirs() }
            dbFile.copyTo(File(only, dbFile.name), overwrite = true)
            val both = File(root, "$label/db-and-wal").apply { mkdirs() }
            dbFile.copyTo(File(both, dbFile.name), overwrite = true)
            File(live, dbFile.name + "-wal").takeIf { it.exists() }?.copyTo(File(both, dbFile.name + "-wal"), overwrite = true)
        }

        var recorded = 0
        val milestones = setOf(1, 30, 90, 365, 400)
        // In WAL mode the main file is written only by a checkpoint, so a change of its size or
        // modification time marks one.
        var mainState = dbFile.length() to dbFile.lastModified()
        for (day in 1..400) {
            val result = recordDose(RecordCommand(intakeId, DoseKind.TAKEN, DoseSource.WIDGET, null, SEOUL.id))
            check(result is RecordResult.Recorded) { "day $day: $result" }
            recorded++
            val state = dbFile.length() to dbFile.lastModified()
            if (state != mainState) {
                out.appendLine("main file written (checkpoint) after record $recorded: ${dbFile.name}=${dbFile.length()}")
                mainState = state
            }
            if (recorded in milestones) snapshot("after-%03d".format(recorded), recorded)
            clock.now = clock.now() + 1.days
        }
        File(root, "probe-result.txt").writeText(out.toString())
        println(out)
        // Deliberately not closing the database: a killed Android process never closes it either.
    }
}
