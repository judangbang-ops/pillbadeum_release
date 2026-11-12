package com.judangbang.pillbadeum.data

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

    /**
     * This installation's id (data-model.md section 0, device_id). Written on first launch and
     * replaced after a restore, both by `restore.CheckInstall` (supplement R-1, R-2).
     */
    const val DEVICE_ID: String = "device_id"

    /** `device_id` the restored database had before this phone replaced it (supplement R-2, REQ-056). */
    const val RESTORED_FROM_DEVICE_ID: String = "restored_from_device_id"

    /** `1` from restore detection until [확인했어요], else `0` or absent (supplement R-3, REQ-073). */
    const val RESTORE_NOTICE_PENDING: String = "restore_notice_pending"

    /**
     * Epoch millis of the latest active judgment before the restore; empty when the restored
     * database has no judgment, `later` when every one is after this phone's clock (supplement R-2).
     */
    const val RESTORE_NOTICE_LAST_RECORD_AT: String = "restore_notice_last_record_at"

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
