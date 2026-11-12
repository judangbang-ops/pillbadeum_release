package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.SettingEntity
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.domain.WindowSettings
import kotlin.time.Clock
import kotlin.time.Instant

sealed interface SaveSettingsResult {
    /** New values stored; for window settings the change time was stored too. */
    data object Changed : SaveSettingsResult

    /** Same as the stored values; nothing written. */
    data object Unchanged : SaveSettingsResult

    /** Rejected input (a day start with seconds); nothing written. */
    data object Invalid : SaveSettingsResult
}

/**
 * The settings screen's writes (SCR-009, REQ-011, REQ-053, TASK-009).
 *
 * A new day start or base zone applies from the moment it is saved: stored judgments keep their
 * period keys (record-rules.md section 8, RR-15), and the save time goes to
 * `window_settings_changed_at` so that windows which ended before it cannot be edited (supplement
 * P-5). Callers re-render widgets and reschedule reminders after [SaveSettingsResult.Changed].
 */
class ManageSettings(
    private val db: DidITakeDatabase,
    private val clock: Clock,
) {
    suspend fun windowSettings(): WindowSettings = db.settingDao().loadWindowSettings()

    /** When the day start or base zone last changed, or null if never (supplement P-5). */
    suspend fun windowSettingsChangedAt(): Instant? =
        db.settingDao().get(SettingKeys.WINDOW_SETTINGS_CHANGED_AT)?.toLongOrNull()?.let(Instant::fromEpochMilliseconds)

    /** Day start must be a whole minute (stored as `HH:MM`). */
    suspend fun saveWindowSettings(new: WindowSettings): SaveSettingsResult {
        if (new.dayStart.second != 0 || new.dayStart.nanosecond != 0) return SaveSettingsResult.Invalid
        return db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val dao = db.settingDao()
                // A stored value that cannot be read counts as different, so a valid save repairs it.
                val old = runCatching { dao.loadWindowSettings() }.getOrNull()
                if (old != null && old.dayStart == new.dayStart && old.baseZone.id == new.baseZone.id) {
                    return@immediateTransaction SaveSettingsResult.Unchanged
                }
                dao.upsert(SettingEntity(SettingKeys.DAY_START, new.dayStart.toString()))
                dao.upsert(SettingEntity(SettingKeys.BASE_ZONE, new.baseZone.id))
                dao.upsert(
                    SettingEntity(SettingKeys.WINDOW_SETTINGS_CHANGED_AT, clock.now().toEpochMilliseconds().toString()),
                )
                SaveSettingsResult.Changed
            }
        }
    }

    /** Medicine names on the lock screen and in notifications; off by default (REQ-046, REQ-053). */
    suspend fun showNamesOnLock(): Boolean = db.settingDao().get(SettingKeys.SHOW_NAMES_ON_LOCK) == "true"

    suspend fun setShowNamesOnLock(show: Boolean): SaveSettingsResult {
        if (showNamesOnLock() == show) return SaveSettingsResult.Unchanged
        db.settingDao().upsert(SettingEntity(SettingKeys.SHOW_NAMES_ON_LOCK, show.toString()))
        return SaveSettingsResult.Changed
    }
}
