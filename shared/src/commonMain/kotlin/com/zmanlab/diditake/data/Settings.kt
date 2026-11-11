package com.zmanlab.diditake.data

import com.zmanlab.diditake.domain.UuidV7
import com.zmanlab.diditake.domain.WindowSettings
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** Keys of the `setting` table (data-model.md section 2, setting). */
object SettingKeys {
    const val DAY_START: String = "day_start"
    const val BASE_ZONE: String = "base_zone"
    const val BACKUP_POLICY: String = "backup_policy"
    const val SHOW_NAMES_ON_LOCK: String = "show_names_on_lock"

    /** This installation's id, written once on first launch (data-model.md section 0, device_id). */
    const val DEVICE_ID: String = "device_id"
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

/** Returns the stored device id, creating it on first call. */
suspend fun SettingDao.loadOrCreateDeviceId(ids: UuidV7): String =
    get(SettingKeys.DEVICE_ID) ?: ids.next().also { upsert(SettingEntity(SettingKeys.DEVICE_ID, it)) }
