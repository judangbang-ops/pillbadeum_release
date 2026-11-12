package com.judangbang.pillbadeum.data

import com.judangbang.pillbadeum.domain.ReminderSettings
import com.judangbang.pillbadeum.domain.UuidV7
import com.judangbang.pillbadeum.domain.WindowSettings
import kotlin.time.Duration.Companion.minutes
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

    // Common reminder settings and run state (record-rules-supplement.md N-1, N-2; TASK-011).
    const val REMINDER_INTERVAL_MINUTES: String = "reminder_interval_minutes"
    const val REMINDER_MAX_PER_DAY: String = "reminder_max_per_day"
    const val QUIET_START: String = "quiet_start"
    const val QUIET_END: String = "quiet_end"

    /** When overdue reminders were last collected and shown (UTC epoch ms, supplement N-8, N-12). */
    const val REMINDER_LAST_RUN_AT: String = "reminder_last_run_at"
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

/** Common reminder settings with the documented defaults for missing keys (supplement N-1). */
suspend fun SettingDao.loadReminderSettings(): ReminderSettings = ReminderSettings(
    interval = get(SettingKeys.REMINDER_INTERVAL_MINUTES)?.toInt()?.minutes ?: ReminderSettings.DEFAULT_INTERVAL,
    maxPerDay = get(SettingKeys.REMINDER_MAX_PER_DAY)?.toInt() ?: ReminderSettings.DEFAULT_MAX_PER_DAY,
    quietStart = get(SettingKeys.QUIET_START)?.let(LocalTime::parse) ?: ReminderSettings.DEFAULT_QUIET_START,
    quietEnd = get(SettingKeys.QUIET_END)?.let(LocalTime::parse) ?: ReminderSettings.DEFAULT_QUIET_END,
)

suspend fun SettingDao.saveReminderSettings(settings: ReminderSettings) {
    upsert(SettingEntity(SettingKeys.REMINDER_INTERVAL_MINUTES, settings.interval.inWholeMinutes.toString()))
    upsert(SettingEntity(SettingKeys.REMINDER_MAX_PER_DAY, settings.maxPerDay.toString()))
    upsert(SettingEntity(SettingKeys.QUIET_START, settings.quietStart.toString()))
    upsert(SettingEntity(SettingKeys.QUIET_END, settings.quietEnd.toString()))
}

/**
 * `reminder_last_run_at`, rewritten to [now] when it is missing or after [now] (the clock went back),
 * as supplement N-12 says for every read. Inside the caller's write transaction.
 */
suspend fun SettingDao.loadReminderLastRunAt(now: Instant): Instant {
    val stored = get(SettingKeys.REMINDER_LAST_RUN_AT)?.toLongOrNull()?.let(Instant::fromEpochMilliseconds)
    if (stored != null && stored <= now) return stored
    saveReminderLastRunAt(now)
    return now
}

suspend fun SettingDao.saveReminderLastRunAt(at: Instant) {
    upsert(SettingEntity(SettingKeys.REMINDER_LAST_RUN_AT, at.toEpochMilliseconds().toString()))
}
