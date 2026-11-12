package com.judangbang.pillbadeum.restore

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.SettingEntity
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.domain.UuidV7
import kotlin.time.Clock

/**
 * The install marker of record-rules-supplement.md R-1: one small file that is never backed up
 * (`noBackupFilesDir/install_marker` on Android) and holds this installation's `device_id`.
 *
 * Writes must be durable before they return: R-2 writes the marker before the database.
 */
interface InstallMarker {
    /** The `device_id` written in the marker, or null when there is no marker. */
    suspend fun read(): String?

    suspend fun write(deviceId: String)
}

/** What [CheckInstall] found on this start (supplement R-1 table). */
sealed interface InstallState {
    /** No `device_id` in the database (first start, app data cleared, or the database alone was lost). */
    data object New : InstallState

    /** Marker and database agree: a normal start, including after an app update. */
    data object Normal : InstallState

    /** The database came from a backup (marker missing or holding another id). R-2 was done. */
    data class Restored(val previousDeviceId: String) : InstallState
}

/** Result of [CheckInstall]: the `device_id` this installation writes from now on. */
data class InstallCheckResult(val deviceId: String, val state: InstallState)

/**
 * Restore detection on every first entry of a process (app, widget, alarm, boot), before any
 * write: supplement R-1 and R-2, REQ-056, REQ-073. The callback `BackupAgent.onRestoreFinished` is
 * not used on purpose (R-1, review A2).
 *
 * | database `device_id` | marker        | state    | action |
 * | -------------------- | ------------- | -------- | ------ |
 * | none                 | any           | New      | new id to the marker first, then to the database |
 * | X                    | X             | Normal   | none |
 * | X                    | none or not X | Restored | R-2 |
 *
 * Callers run it once per process and serialize it (AppContainer does it under its mutex).
 */
class CheckInstall(
    private val db: DidITakeDatabase,
    private val clock: Clock,
    private val ids: UuidV7,
    private val marker: InstallMarker,
) {
    suspend operator fun invoke(): InstallCheckResult {
        val settings = db.settingDao()
        val stored = settings.get(SettingKeys.DEVICE_ID)
        val marked = marker.read()
        return when {
            stored == null -> {
                val id = ids.next()
                marker.write(id)
                settings.upsert(SettingEntity(SettingKeys.DEVICE_ID, id))
                InstallCheckResult(id, InstallState.New)
            }
            marked == stored -> InstallCheckResult(stored, InstallState.Normal)
            else -> InstallCheckResult(restore(stored), InstallState.Restored(stored))
        }
    }

    /**
     * R-2. The new id goes to the marker FIRST: if the process dies before the transaction, the
     * next start sees marker != database again and repeats R-2, and `restored_from_device_id`
     * still gets the old phone's id from the database (RR-72).
     */
    private suspend fun restore(previous: String): String {
        val id = ids.next()
        marker.write(id)
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val now = clock.now().toEpochMilliseconds()
                val settings = db.settingDao()
                val lastRecordAt = db.doseRecordDao().latestActiveTakenAtBefore(now)
                settings.upsert(SettingEntity(SettingKeys.DEVICE_ID, id))
                settings.upsert(SettingEntity(SettingKeys.RESTORED_FROM_DEVICE_ID, previous))
                settings.upsert(SettingEntity(SettingKeys.RESTORE_NOTICE_LAST_RECORD_AT, lastRecordAt?.toString().orEmpty()))
                settings.upsert(SettingEntity(SettingKeys.RESTORE_NOTICE_PENDING, PENDING))
            }
        }
        return id
    }

    companion object {
        const val PENDING: String = "1"
        const val DONE: String = "0"
    }
}

/**
 * [확인했어요] on the restore notice (supplement R-3): the notice disappears from the app and the
 * widgets. Re-scheduling dose reminders with `reminder_last_run_at` = now is TASK-040.
 */
class AcknowledgeRestore(private val db: DidITakeDatabase) {
    suspend operator fun invoke() {
        db.settingDao().upsert(SettingEntity(SettingKeys.RESTORE_NOTICE_PENDING, CheckInstall.DONE))
    }
}
