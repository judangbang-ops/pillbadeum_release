package com.judangbang.pillbadeum

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.judangbang.pillbadeum.boundary.DayBoundaryAlarm
import com.judangbang.pillbadeum.ui.AccessSetting
import com.judangbang.pillbadeum.ui.Phone
import com.judangbang.pillbadeum.ui.ReminderAccess
import com.judangbang.pillbadeum.widget.DidITakeWidgetReceiver
import com.judangbang.pillbadeum.widget.WidgetPinnedReceiver
import kotlinx.datetime.LocalTime

/**
 * [Phone] on Android, for the settings screen (SCR-009, TASK-020), the intro (SCR-005, TASK-022) and what keeps reminders
 * from coming (SCR-004#permission, SCR-009, TASK-026) of [activity]. It asks for no permission of its own.
 */
class AndroidPhone(private val activity: Activity) : Phone {
    override val appVersion: String by lazy {
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.packageManager.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(activity.packageName, 0)
            }
            info.versionName.orEmpty()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "own package not found", e)
            ""
        }
    }

    /**
     * Android 8.x: `backup_rules.xml` gives `<include>` a `requireFlags` that 8.x cannot parse, so the whole backup is
     * skipped there on purpose (supplement B-3).
     */
    override val backupUnsupported: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.P

    /**
     * `KeyguardManager.isDeviceSecure()`: true with a PIN, pattern or password (API 23, no permission). That is the lock
     * the backup's client-side encryption needs (`requireFlags="clientSideEncryption"`, REQ-054). Null when it cannot be read.
     */
    override fun screenLock(): Boolean? =
        try {
            activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure
        } catch (e: RuntimeException) {
            Log.w(TAG, "screen lock state not readable", e)
            null
        }

    /** An `ACTION_VIEW` of [url]; no app to open it is a false, not a crash (SPEC-036). */
    override fun openUrl(url: String): Boolean =
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "no browser for the privacy policy", e)
            false
        }

    /** The platform's `TimePickerDialog`, 24-hour (SCR-009 rule). */
    override fun pickTime(initial: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(activity, { _, hour, minute -> onPicked(LocalTime(hour, minute)) }, initial.hour, initial.minute, true).show()
    }

    override suspend fun windowSettingsChanged() {
        DayBoundaryAlarm.scheduleNext(activity.applicationContext)
    }

    /** `AppWidgetManager.isRequestPinAppWidgetSupported()`: the default launcher takes the request (API 26). */
    override fun canPinWidget(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            AppWidgetManager.getInstance(activity).isRequestPinAppWidgetSupported
        } catch (e: RuntimeException) {
            Log.w(TAG, "widget pin support not readable", e)
            false
        }
    }

    /**
     * `AppWidgetManager.requestPinAppWidget` (API 26). True only says that the launcher took the request; the user may
     * still decline, and then nothing comes back. The launcher shows no configuration activity for a widget placed this
     * way, so [WidgetPinnedReceiver] gets the new widget's id and gives it its choice. It throws when the app is not in
     * the foreground or the user is locked; that is a false here.
     */
    override fun pinWidget(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val provider = ComponentName(activity, DidITakeWidgetReceiver::class.java)
        return try {
            AppWidgetManager.getInstance(activity).requestPinAppWidget(provider, null, WidgetPinnedReceiver.callback(activity))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "widget pin request refused", e)
            false
        }
    }

    /**
     * Notifications are on while the app's notifications are on and the dose-reminder channel is not switched off; a
     * channel not created yet counts as on. Exact alarms follow `canScheduleExactAlarms()` from Android 12 and are always
     * allowed before. Battery use is unrestricted while the app is left out of battery optimization and, from Android 9,
     * not restricted in the background. A state that cannot be read is not shown as a problem.
     */
    override fun reminderAccess(): ReminderAccess =
        ReminderAccess(
            notifications = readOr(true) { notificationsOn() },
            exactAlarms = readOr(true) { exactAlarmsAllowed() },
            batteryUnrestricted = readOr(true) { batteryUnrestricted() },
        )

    /**
     * The app's notification settings, or the dose channel's own when only the channel is off (SPEC-028); the request for
     * exact alarms of this app (Android 12, SPEC-029); the list of battery optimization (SPEC-030). Each falls back to the
     * app's information screen, where all three can be found, when the phone has no such screen (SPEC-030's exception).
     */
    override fun openAccessSettings(setting: AccessSetting): Boolean {
        val first = when (setting) {
            AccessSetting.NOTIFICATIONS -> notificationSettings()
            AccessSetting.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", activity.packageName, null))
                } else {
                    null
                }
            AccessSetting.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
        return listOfNotNull(first, appInfo).any(::start)
    }

    private fun notificationsOn(): Boolean {
        val manager = activity.getSystemService(NotificationManager::class.java) ?: return true
        return manager.areNotificationsEnabled() && !doseChannelOff(manager)
    }

    private fun doseChannelOff(manager: NotificationManager): Boolean =
        manager.getNotificationChannel(DOSES_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE

    private fun exactAlarmsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return activity.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: true
    }

    private fun batteryUnrestricted(): Boolean {
        val power = activity.getSystemService(PowerManager::class.java) ?: return true
        if (!power.isIgnoringBatteryOptimizations(activity.packageName)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        return activity.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted != true
    }

    private fun notificationSettings(): Intent {
        val manager = activity.getSystemService(NotificationManager::class.java)
        val onlyChannel = manager != null && readOr(false) { manager.areNotificationsEnabled() && doseChannelOff(manager) }
        return if (onlyChannel) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, DOSES_CHANNEL)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        }
    }

    private fun start(intent: Intent): Boolean =
        try {
            activity.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "no screen for ${intent.action}", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "screen refused: ${intent.action}", e)
            false
        }

    private inline fun readOr(fallback: Boolean, read: () -> Boolean): Boolean =
        try {
            read()
        } catch (e: RuntimeException) {
            Log.w(TAG, "reminder access not readable", e)
            fallback
        }

    private companion object {
        const val TAG = "AndroidPhone"

        /** R3's `ReminderChannels.DOSES` (PR #20): this becomes that constant once both pull requests are merged. */
        const val DOSES_CHANNEL = "dose_reminders"
    }
}
