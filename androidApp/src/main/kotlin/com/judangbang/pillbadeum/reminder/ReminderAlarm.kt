package com.judangbang.pillbadeum.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlin.time.Instant

/**
 * The one reminder alarm of every item (supplement N-8, TASK-024). Exact while the person allows exact
 * alarms (`canScheduleExactAlarms()`, Android 12 and later; always before 12), inexact otherwise. Both wake
 * the phone and may ring in Doze. `USE_EXACT_ALARM` is never declared (REQ-052), and no copy promises a
 * delay in minutes either way (N-11).
 *
 * [arm] takes what `PlanReminders.nextAlarm` or `fire` returned: a time already past rings at once, null
 * cancels the alarm (supplement N-13 '구현', the order the app keeps).
 */
object ReminderAlarm {
    const val ACTION: String = "com.judangbang.pillbadeum.action.REMINDER_ALARM"
    private const val TAG = "ReminderAlarm"

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    fun arm(context: Context, at: Instant?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        if (at == null) {
            alarmManager.cancel(pending)
            Log.i(TAG, "no reminder alarm needed")
            return
        }
        val millis = at.toEpochMilliseconds()
        if (canScheduleExact(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
                Log.i(TAG, "exact reminder alarm at $at")
                return
            } catch (e: SecurityException) {
                // Exact alarms were disallowed between the check and this call: an inexact alarm instead.
                Log.w(TAG, "exact reminder alarm refused, setting an inexact one", e)
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        Log.i(TAG, "inexact reminder alarm at $at")
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
