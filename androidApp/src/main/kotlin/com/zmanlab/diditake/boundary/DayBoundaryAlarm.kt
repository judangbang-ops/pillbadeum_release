package com.zmanlab.diditake.boundary

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zmanlab.diditake.container
import com.zmanlab.diditake.widget.WidgetUpdater
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

/**
 * Day-boundary refresh (sequences.md section 4): an inexact alarm at the next day start in the
 * base zone. No exact-alarm permission: a late refresh is safe because the widget header shows its
 * base date and taps are checked against the rendered period key.
 */
object DayBoundaryAlarm {
    const val ACTION: String = "com.zmanlab.diditake.action.DAY_BOUNDARY"
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

/** Boundary alarm, boot, clock and time zone changes: refresh widgets and re-arm the alarm. */
class DayBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                WidgetUpdater.updateAll(context)
                DayBoundaryAlarm.scheduleNext(context)
            } catch (e: Exception) {
                Log.e("DayBoundaryReceiver", "boundary refresh failed (${intent.action})", e)
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
    }
}
