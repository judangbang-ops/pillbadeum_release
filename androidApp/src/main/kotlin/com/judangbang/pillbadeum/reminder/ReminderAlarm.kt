package com.judangbang.pillbadeum.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The one reminder alarm of every item (supplement N-8, TASK-024). Exact while the person allows exact
 * alarms (`canScheduleExactAlarms()`, Android 12 and later; always before 12), inexact otherwise. Both wake
 * the phone and may ring in Doze. `USE_EXACT_ALARM` is never declared (REQ-052), and no copy promises a
 * delay in minutes either way (N-11).
 *
 * [arm] takes what `PlanReminders.nextAlarm` or `fire` returned: a time already past rings at once, null
 * cancels the alarm (supplement N-13 '구현', the order the app keeps).
 *
 * The inexact alarm (`setAndAllowWhileIdle`) may come up to an hour late even while the phone is in use: Android
 * allows 75% of the time until it, at most an hour (AOSP `AlarmManagerService.maxTriggerTime`), and from Android 14
 * exact alarms are off for a new install until the person allows them. So the inexact path also sets a windowed
 * alarm ([WINDOW], the shortest window Android 12 gives without exact alarms) that rings within ten minutes while
 * the phone is not idle; the allow-while-idle one still rings in Doze. Whichever comes first collects, and the other
 * finds nothing new and arms the next time again (PR #20 independent review).
 */
object ReminderAlarm {
    const val ACTION: String = "com.judangbang.pillbadeum.action.REMINDER_ALARM"

    /** Retries in a row after a failed collect, carried by the retry alarm ([retry]). */
    const val EXTRA_ATTEMPT: String = "com.judangbang.pillbadeum.reminder.attempt"

    /** False when the failed collect was the first run after a boot, clock or time zone change ([retry]). */
    const val EXTRA_ALARM_RANG: String = "com.judangbang.pillbadeum.reminder.alarm_rang"

    /** The window of the second alarm of the inexact path. */
    val WINDOW: Duration = 10.minutes

    /** How long after a failed collect the alarm rings again, by the retries before it ([retry]). */
    val RETRY_DELAYS: List<Duration> = listOf(1.minutes, 5.minutes, 15.minutes, 60.minutes)

    private const val TAG = "ReminderAlarm"
    private const val REQUEST_ALARM = 0
    private const val REQUEST_WINDOW = 1

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /**
     * The planned alarm. Its extras are written out (no retries, an alarm's collect) rather than left empty, so the
     * PendingIntent updated in place (FLAG_UPDATE_CURRENT) never keeps a retry count from before.
     */
    fun arm(context: Context, at: Instant?) =
        arm(context, at, Intent().putExtra(EXTRA_ATTEMPT, 0).putExtra(EXTRA_ALARM_RANG, true))

    /**
     * After a failed collect (a busy or full database, say): the alarm rings again after the next of [RETRY_DELAYS],
     * so one failure does not stop the reminders until the next write, start or day boundary (PR #20 independent
     * review). A collect that works arms the planned time again, which ends the retries.
     */
    fun retry(context: Context, now: Instant, attempt: Int, alarmRang: Boolean) {
        val delay = RETRY_DELAYS[attempt.coerceIn(0, RETRY_DELAYS.lastIndex)]
        Log.w(TAG, "collect failed, ringing again in $delay (retry ${attempt + 1})")
        arm(context, now + delay, Intent().putExtra(EXTRA_ATTEMPT, attempt + 1).putExtra(EXTRA_ALARM_RANG, alarmRang))
    }

    private fun arm(context: Context, at: Instant?, extras: Intent) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val main = pendingIntent(context, REQUEST_ALARM, extras)
        val window = pendingIntent(context, REQUEST_WINDOW, extras)
        if (at == null) {
            alarmManager.cancel(main)
            alarmManager.cancel(window)
            Log.i(TAG, "no reminder alarm needed")
            return
        }
        val millis = at.toEpochMilliseconds()
        if (canScheduleExact(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, main)
                alarmManager.cancel(window)
                Log.i(TAG, "exact reminder alarm at $at")
                return
            } catch (e: SecurityException) {
                // Exact alarms were disallowed between the check and this call: an inexact alarm instead.
                Log.w(TAG, "exact reminder alarm refused, setting an inexact one", e)
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, main)
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, millis, WINDOW.inWholeMilliseconds, window)
        Log.i(TAG, "inexact reminder alarm at $at, and one within $WINDOW of it")
    }

    /** Both alarms go to [ReminderReceiver] with [ACTION]; the request code tells them apart, extras do not. */
    private fun pendingIntent(context: Context, requestCode: Int, extras: Intent): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtras(extras),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
