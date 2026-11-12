package com.judangbang.pillbadeum.data

import com.judangbang.pillbadeum.domain.WindowSettings
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** Keys of the `setting` table (data-model.md section 2, setting). */
object SettingKeys {
    const val DAY_START: String = "day_start"
    const val BASE_ZONE: String = "base_zone"
    const val BACKUP_POLICY: String = "backup_policy"
    const val SHOW_NAMES_ON_LOCK: String = "show_names_on_lock"

    /**
     * This installation's id (data-model.md section 0, device_id). Written on first launch and
     * replaced after a restore, both by `restore.CheckInstall` (supplement R-1, R-2).
     */
    const val DEVICE_ID: String = "device_id"

    /** `device_id` the restored database had before this phone replaced it (supplement R-2, REQ-056). */
    const val RESTORED_FROM_DEVICE_ID: String = "restored_from_device_id"

    /** `1` from restore detection until [확인했어요], else `0` or absent (supplement R-3, REQ-073). */
    const val RESTORE_NOTICE_PENDING: String = "restore_notice_pending"

    /** Epoch millis of the latest active judgment before the restore, or empty (supplement R-2). */
    const val RESTORE_NOTICE_LAST_RECORD_AT: String = "restore_notice_last_record_at"
}

/**
 * Reads the window settings, falling back to the documented defaults for missing keys.
 *
 * Stored values are written by the app in a fixed format (`HH:MM`, IANA zone id); a malformed
 * value throws instead of being silently replaced.
 */
suspend fun SettingDao.loadWindowSettings(): WindowSettings = WindowSettings(
    dayStart = get(SettingKeys.DAY_START)?.let(LocalTime::parse) ?: WindowSettings.DEFAULT_DAY_START,
    baseZone = TimeZone.of(get(SettingKeys.BASE_ZONE) ?: WindowSettings.DEFAULT_BASE_ZONE_ID),
)

suspend fun SettingDao.saveWindowSettings(settings: WindowSettings) {
    upsert(SettingEntity(SettingKeys.DAY_START, settings.dayStart.toString()))
    upsert(SettingEntity(SettingKeys.BASE_ZONE, settings.baseZone.id))
}
