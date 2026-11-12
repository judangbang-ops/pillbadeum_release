package com.judangbang.pillbadeum.reminder

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.judangbang.pillbadeum.container
import kotlinx.coroutines.launch

/**
 * The reminder alarm, the buttons and swipes of reminder notifications, and the exact-alarm permission
 * change (TASK-024, TASK-025, TASK-027). Not exported, and every PendingIntent to it is immutable, so other
 * apps cannot record, skip or snooze through it (REQ-081). The system's own broadcast still reaches it.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                when (action) {
                    ReminderAlarm.ACTION -> Reminders.collect(context)
                    ACTION_TAKE, ACTION_SKIP, ACTION_SNOOZE -> Reminders.onButton(context, intent)
                    ACTION_DISMISSED -> Reminders.onDismissed(context)
                    // Exact alarms were allowed (Android 12+): arm the same alarm again, now exact (SPEC-029, SPEC-032).
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> Reminders.replan(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "reminder broadcast failed ($action)", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val ACTION_TAKE: String = "com.judangbang.pillbadeum.action.REMINDER_TAKE"
        const val ACTION_SKIP: String = "com.judangbang.pillbadeum.action.REMINDER_SKIP"
        const val ACTION_SNOOZE: String = "com.judangbang.pillbadeum.action.REMINDER_SNOOZE"
        const val ACTION_DISMISSED: String = "com.judangbang.pillbadeum.action.REMINDER_DISMISSED"

        private val HANDLED = setOf(
            ReminderAlarm.ACTION,
            ACTION_TAKE,
            ACTION_SKIP,
            ACTION_SNOOZE,
            ACTION_DISMISSED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
    }
}
