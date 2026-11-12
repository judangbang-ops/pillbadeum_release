package com.judangbang.pillbadeum.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Debug-only (TASK-042, case P of the widget scroll check). Turns [ToggleTarget] on or off the way
 * WorkManager turns its RescheduleReceiver on: `setComponentEnabledSetting(..., DONT_KILL_APP)`.
 * The system then sends PACKAGE_CHANGED for this package, and the AppWidgetService answers that by
 * dropping the views of this package's widgets (hosts show the initial loading layout) and asking for
 * an update. The case watches whether the widget comes back and how long it takes.
 *
 * `adb shell am broadcast -n com.judangbang.pillbadeum/.debug.ComponentToggleReceiver`
 */
class ComponentToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = ComponentName(context, ToggleTarget::class.java)
        val pm = context.packageManager
        val next = if (pm.getComponentEnabledSetting(target) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        pm.setComponentEnabledSetting(target, next, PackageManager.DONT_KILL_APP)
        Log.i(TAG, "toggled ToggleTarget to ${if (next == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) "enabled" else "disabled"}")
    }

    companion object {
        const val TAG = "ComponentToggleReceiver"
    }
}

/** Does nothing; only its enabled state changes (manifest default: disabled, like RescheduleReceiver). */
class ToggleTarget : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
