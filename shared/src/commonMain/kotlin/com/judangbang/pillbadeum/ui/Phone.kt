package com.judangbang.pillbadeum.ui

import kotlinx.datetime.LocalTime

/**
 * What can keep a reminder from coming, as the phone says now (SCR-004#permission, SCR-009 `알림이 오지 않을 때`,
 * TASK-026). All three are changed in the system settings, so the screens read them each time they show and on coming
 * back to the app (SCR-009#stale).
 */
data class ReminderAccess(
    /** The app's notifications and its dose-reminder channel are on (REQ-089); off as well while the permission is refused. */
    val notifications: Boolean,
    /** Exact alarms are allowed (REQ-050, SPEC-029); always before Android 12. */
    val exactAlarms: Boolean,
    /** The app's battery use is not restricted: it is left out of battery optimization (REQ-051, SPEC-030). */
    val batteryUnrestricted: Boolean,
)

/** The system settings that SCR-004's and SCR-009's `[설정 열기]` and `[허용하기]` open (SPEC-028, SPEC-029, SPEC-030). */
enum class AccessSetting { NOTIFICATIONS, EXACT_ALARMS, BATTERY }

/**
 * SCR-004#permission (TASK-026): the band the first screen shows while an item has reminders on, or null. Notifications
 * that are off come first and alone, since exact alarms change nothing until they are on (Claude's choice). Battery use
 * is only in the settings (SCR-009). Nothing shows before the phone was read.
 */
internal fun accessNotice(remindersOn: Boolean, access: ReminderAccess?): AccessSetting? = when {
    !remindersOn || access == null -> null
    !access.notifications -> AccessSetting.NOTIFICATIONS
    !access.exactAlarms -> AccessSetting.EXACT_ALARMS
    else -> null
}

/**
 * What the app asks of the phone itself, outside the database (SCR-009, TASK-020; SCR-005's widget request, TASK-022;
 * what keeps reminders from coming, TASK-026). Android gives its own; a test can give one that records the calls.
 */
interface Phone {
    /** The installed version (`versionName`, `0.1.0`), shown under `앱 정보`. */
    val appVersion: String

    /** Android 8.x (API 26, 27): the app's backup rules skip the backup there on purpose (REQ-054, supplement B-3). */
    val backupUnsupported: Boolean

    /** Whether a screen lock (PIN, pattern or password) is set, or null when it cannot be read (SPEC-035). */
    fun screenLock(): Boolean?

    /** Opens [url] in a browser; false when nothing can open it, and the app shows the address (SPEC-036). */
    fun openUrl(url: String): Boolean

    /**
     * Shows the system time picker at [initial], 24-hour and to the minute (SCR-009 rule: no rounding to hours or half
     * hours). [onPicked] gets the time chosen; nothing is called when the picker is cancelled.
     */
    fun pickTime(initial: LocalTime, onPicked: (LocalTime) -> Unit)

    /** After the day start or base zone changed: the day-boundary refresh is set for the new boundary (SPEC-033). */
    suspend fun windowSettingsChanged()

    /**
     * Whether the launcher takes a request to place the widget, which SCR-005's `[위젯 추가]` makes (Android 8 and later,
     * SPEC-002). Without it only the steps to place it by hand show (SCR-005#error).
     */
    fun canPinWidget(): Boolean

    /**
     * Asks the launcher to place the widget (SCR-005 `[위젯 추가]`); the launcher shows its own confirmation, and the
     * placed widget is set up as SCR-002 would set it (TASK-022). False when the request could not be made.
     */
    fun pinWidget(): Boolean

    /** What can keep a reminder from coming, read now (TASK-026). */
    fun reminderAccess(): ReminderAccess

    /**
     * Opens the system screen where [setting] is changed (SPEC-028, SPEC-029, SPEC-030). A phone without that screen
     * gets the app's information screen instead (SPEC-030). False when nothing could be opened; the screen says so.
     */
    fun openAccessSettings(setting: AccessSetting): Boolean
}
