package com.judangbang.pillbadeum.data

import com.judangbang.pillbadeum.domain.UuidV7
import com.judangbang.pillbadeum.domain.WindowSettings
import kotlin.time.Instant
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

    /**
     * When `day_start` or `base_zone` last changed (UTC epoch ms). Windows that ended before it cannot
     * be edited, since past windows are computed with the current settings (supplement P-5).
     */
    const val WINDOW_SETTINGS_CHANGED_AT: String = "window_settings_changed_at"
}

/**
 * Reads the window settings, falling back to the documented defaults for missing keys.
 *
 * Stored values are written by the app in a fixed format (`HH:MM`, IANA zone id); a malformed
 * value throws instead of being silently replaced.
 */
suspend fun SettingDao.loadWindowSettings(): WindowSettings =
    parseWindowSettings(dayStart = get(SettingKeys.DAY_START), baseZone = get(SettingKeys.BASE_ZONE))

/** When the day start or base zone last changed, or null if never (supplement P-5). */
suspend fun SettingDao.loadWindowSettingsChangedAt(): Instant? =
    get(SettingKeys.WINDOW_SETTINGS_CHANGED_AT)?.toLongOrNull()?.let(Instant::fromEpochMilliseconds)

/**
 * Window settings from the stored `day_start` and `base_zone` values; null means "not stored yet"
 * and gives the default. A malformed value throws (callers that must not fail catch it).
 */
fun parseWindowSettings(dayStart: String?, baseZone: String?): WindowSettings = WindowSettings(
    dayStart = dayStart?.let(LocalTime::parse) ?: WindowSettings.DEFAULT_DAY_START,
    baseZone = TimeZone.of(baseZone ?: WindowSettings.DEFAULT_BASE_ZONE_ID),
)

suspend fun SettingDao.saveWindowSettings(settings: WindowSettings) {
    upsert(SettingEntity(SettingKeys.DAY_START, settings.dayStart.toString()))
    upsert(SettingEntity(SettingKeys.BASE_ZONE, settings.baseZone.id))
}

/** Returns the stored device id, creating it on first call. */
suspend fun SettingDao.loadOrCreateDeviceId(ids: UuidV7): String =
    get(SettingKeys.DEVICE_ID) ?: ids.next().also { upsert(SettingEntity(SettingKeys.DEVICE_ID, it)) }
