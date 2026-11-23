package com.judangbang.pillbadeum

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import com.judangbang.pillbadeum.boundary.DayBoundaryAlarm
import com.judangbang.pillbadeum.reminder.ReminderAlarm
import com.judangbang.pillbadeum.reminder.ReminderChannels
import com.judangbang.pillbadeum.reminder.ReminderNotifications
import com.judangbang.pillbadeum.ui.AccessSetting
import com.judangbang.pillbadeum.ui.FEEDBACK_ADDRESS
import com.judangbang.pillbadeum.ui.Phone
import com.judangbang.pillbadeum.ui.ReminderAccess
import com.judangbang.pillbadeum.widget.DidITakeWidgetReceiver
import com.judangbang.pillbadeum.widget.WidgetPinnedReceiver
import kotlinx.datetime.LocalTime

/**
 * [Phone] on Android, for the settings screen (SCR-009, TASK-020), the intro (SCR-005, TASK-022) and what keeps reminders
 * from coming (SCR-004#permission, SCR-009, TASK-026) of [activity]. The one permission it asks for is the notification
 * permission, through [requestNotifications] (SCR-006, TASK-023): `MainActivity` launches the system window with it and
 * calls the given function when the answer comes back.
 */
class AndroidPhone(
    private val activity: Activity,
    private val requestNotifications: ((onAnswered: () -> Unit) -> Unit)? = null,
) : Phone {
    private val packageInfo by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.packageManager.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(activity.packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "own package not found", e)
            null
        }
    }
    override val appVersion: String get() = packageInfo?.versionName.orEmpty()

    private var lastMailLaunch: Long? = null

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

    override fun openFeedbackMail(): Boolean {
        val now = SystemClock.elapsedRealtime()
        // A second tap can reach this activity before the mail window takes focus (SPEC-053).
        if (lastMailLaunch?.let { now - it < MAIL_REPEAT_WINDOW_MS } == true) return true
        val buildNumber = packageInfo?.let(PackageInfoCompat::getLongVersionCode)
        val body = "앱 버전: $appVersion ($buildNumber)\n" +
            "Android 버전: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "기기 모델: ${Build.MANUFACTURER} ${Build.MODEL}"
        val draft = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$FEEDBACK_ADDRESS"))
            .putExtra(Intent.EXTRA_SUBJECT, "[필받음] 의견")
            .putExtra(Intent.EXTRA_TEXT, body)
        // Start directly, since Android's package visibility can hide an installed mail handler from lookups.
        return try {
            activity.startActivity(draft)
            lastMailLaunch = now
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    override fun copyFeedbackAddress() {
        val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("이메일 주소", FEEDBACK_ADDRESS))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activity, "주소를 복사했어요", Toast.LENGTH_SHORT).show()
        }
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
     * Notifications and exact alarms are judged as the reminders judge them (R3): [ReminderNotifications.canPostDoses]
     * (the app's notifications on and the dose-reminder channel not switched off; a channel not created yet counts as
     * on) and [ReminderAlarm.canScheduleExact] (`canScheduleExactAlarms()` from Android 12, always allowed before).
     * Battery use is unrestricted while the app is left out of battery optimization and, from Android 9, not restricted
     * in the background. A state that cannot be read is not shown as a problem.
     */
    override fun reminderAccess(): ReminderAccess =
        ReminderAccess(
            notifications = readOr(true) { ReminderNotifications.canPostDoses(activity) },
            exactAlarms = readOr(true) { ReminderAlarm.canScheduleExact(activity) },
            batteryUnrestricted = readOr(true) { batteryUnrestricted() },
        )

    /**
     * `POST_NOTIFICATIONS` (Android 13, REQ-049) while it is not granted. Without [requestNotifications], or when the
     * window cannot be launched, there is nothing to ask and SCR-006#permission says what to do instead.
     */
    override fun askNotifications(onAnswered: () -> Unit): Boolean {
        val request = requestNotifications ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        return try {
            request(onAnswered)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no notification permission window", e)
            false
        }
    }

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

    private fun batteryUnrestricted(): Boolean {
        val power = activity.getSystemService(PowerManager::class.java) ?: return true
        if (!power.isIgnoringBatteryOptimizations(activity.packageName)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        return activity.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted != true
    }

    private fun notificationSettings(): Intent {
        val onlyChannel = readOr(false) {
            ReminderNotifications.canPost(activity) && !ReminderNotifications.canPostDoses(activity)
        }
        return if (onlyChannel) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, ReminderChannels.DOSES)
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
        const val MAIL_REPEAT_WINDOW_MS = 1_000L
    }
}
