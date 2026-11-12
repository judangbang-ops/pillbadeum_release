package com.judangbang.pillbadeum

import android.app.Activity
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.judangbang.pillbadeum.boundary.DayBoundaryAlarm
import com.judangbang.pillbadeum.ui.Phone
import kotlinx.datetime.LocalTime

/** [Phone] on Android, for the settings screen of [activity] (SCR-009, TASK-020). */
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

    private companion object {
        const val TAG = "AndroidPhone"
    }
}
