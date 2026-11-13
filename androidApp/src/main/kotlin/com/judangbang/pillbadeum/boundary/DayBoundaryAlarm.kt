package com.judangbang.pillbadeum.boundary

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.judangbang.pillbadeum.container
import com.judangbang.pillbadeum.reminder.Reminders
import com.judangbang.pillbadeum.widget.WidgetUpdater
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

/**
 * Day-boundary refresh (sequences.md section 4): an inexact alarm at the next day start in the
 * base zone. No exact-alarm permission: a late refresh is safe because the widget header shows its
 * base date and taps are checked against the rendered period key.
 */
object DayBoundaryAlarm {
    const val ACTION: String = "com.judangbang.pillbadeum.action.DAY_BOUNDARY"
    private const val TAG = "DayBoundaryAlarm"

    /** Small margin so the alarm never lands on the old side of the boundary. */
    private val MARGIN = 5.seconds

    suspend fun scheduleNext(context: Context) {
        val at = context.container.core().nextDayBoundary() + MARGIN
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, DayBoundaryReceiver::class.java).setAction(ACTION)
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // RTC (not _WAKEUP): the widget only needs to be right when someone looks at the screen.
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC, at.toEpochMilliseconds(), pending)
        Log.i(TAG, "next day boundary refresh at $at")
    }
}

/**
 * Boundary alarm, boot, clock and time zone changes: raise clock warnings (supplement C-1), refresh
 * widgets and re-arm the alarm.
 *
 * Reminders (TASK-027): after a boot, clock or time zone change the overdue slots are collected, one
 * reminder per item (supplement N-10, N-12, SPEC-041). At the boundary and after an update only the next
 * reminder alarm is planned, and reminders of windows that ended are taken off (N-9, N-13, SPEC-040).
 */
class DayBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in HANDLED) return
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                try {
                    // A judgment taken "in the future" means the clock was ahead when it was recorded.
                    context.container.core().flagClockMismatch()
                    WidgetUpdater.updateAll(context)
                    DayBoundaryAlarm.scheduleNext(context)
                } catch (e: Exception) {
                    Log.e("DayBoundaryReceiver", "boundary refresh failed ($action)", e)
                }
                if (action in COLLECT) Reminders.collect(context, alarmRang = false) else Reminders.afterWrite(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED = setOf(
            DayBoundaryAlarm.ACTION,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )

        /** When overdue reminder slots are collected (supplement N-12); the rest only re-plan. */
        val COLLECT = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
