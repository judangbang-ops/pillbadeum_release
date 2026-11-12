package com.judangbang.pillbadeum.lockexp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log

/**
 * Lock-screen notification experiment (TASK-025 groundwork, 2026-11-12 review B6). Not product code.
 *
 * Posts the same two dose reminders ("아침약", "저녁약", each with the three buttons of SCR-003) with one
 * visibility method, so a script can lock the emulator and read whether the item names or buttons show on
 * the lock screen under the device setting "show all content" / "hide sensitive content".
 *
 * `adb shell am broadcast -n com.judangbang.pillbadeum.lockexp/.PostReceiver --es method <method>`
 *
 * Every call first cancels everything this app posted. Methods ([METHODS]):
 * - `private_public`: VISIBILITY_PRIVATE with a public version "복용 시간이에요" (the plan in screens.md SCR-003)
 * - `private_nopublic`: VISIBILITY_PRIVATE without a public version (system redaction, for reference)
 * - `secret`: VISIBILITY_SECRET (nothing on the lock screen)
 * - `secret_summary`: SECRET reminders in one group whose PUBLIC summary is "복용 시간이에요"
 * - `secret_plus_public`: SECRET reminders plus a separate PUBLIC "복용 시간이에요" notification
 * - `public_generic`: only the PUBLIC "복용 시간이에요" (what a "post a generic one while locked" method shows)
 * - `public_full`: VISIBILITY_PUBLIC reminders (the plan when "이름 표시" is on: names and buttons on the lock screen)
 * - `cancel`: nothing
 */
class PostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "복용 알림 (실험)", NotificationManager.IMPORTANCE_HIGH))
        nm.cancelAll()
        val method = intent.getStringExtra("method")
        if (method !in METHODS) {
            resultData = "unknown method $method"
            return
        }
        when (method) {
            "private_public" -> ITEMS.forEachIndexed { i, name ->
                nm.notify(i + 1, full(context, name, Notification.VISIBILITY_PRIVATE, publicVersion = generic(context)))
            }
            "private_nopublic" -> ITEMS.forEachIndexed { i, name ->
                nm.notify(i + 1, full(context, name, Notification.VISIBILITY_PRIVATE))
            }
            "secret" -> ITEMS.forEachIndexed { i, name ->
                nm.notify(i + 1, full(context, name, Notification.VISIBILITY_SECRET))
            }
            "secret_summary" -> {
                ITEMS.forEachIndexed { i, name ->
                    nm.notify(i + 1, full(context, name, Notification.VISIBILITY_SECRET, group = GROUP))
                }
                nm.notify(SUMMARY_ID, generic(context, group = GROUP, summary = true))
            }
            "secret_plus_public" -> {
                ITEMS.forEachIndexed { i, name ->
                    nm.notify(i + 1, full(context, name, Notification.VISIBILITY_SECRET))
                }
                nm.notify(GENERIC_ID, generic(context))
            }
            "public_generic" -> nm.notify(GENERIC_ID, generic(context))
            "public_full" -> ITEMS.forEachIndexed { i, name ->
                nm.notify(i + 1, full(context, name, Notification.VISIBILITY_PUBLIC))
            }
        }
        val text = "posted $method enabled=${nm.areNotificationsEnabled()} active=${nm.activeNotifications.size}"
        Log.i(TAG, text)
        resultData = text
    }

    private fun full(
        context: Context,
        name: String,
        visibility: Int,
        publicVersion: Notification? = null,
        group: String? = null,
    ): Notification {
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("$name 먹어~!")
            .setContentText("마지막 복용 어제 08:02")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(visibility)
            .addAction(action(context, "먹었어", 1))
            .addAction(action(context, "30분 뒤", 2))
            .addAction(action(context, "오늘 건너뜀", 3))
        if (publicVersion != null) builder.setPublicVersion(publicVersion)
        if (group != null) builder.setGroup(group)
        if (Build.VERSION.SDK_INT >= 29) builder.setAllowSystemGeneratedContextualActions(false)
        return builder.build()
    }

    private fun generic(context: Context, group: String? = null, summary: Boolean = false): Notification {
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(GENERIC_TITLE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (group != null) builder.setGroup(group).setGroupSummary(summary)
        return builder.build()
    }

    private fun action(context: Context, title: String, code: Int): Notification.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, NoopReceiver::class.java).setAction("noop.$code"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_popup_reminder), title, pending).build()
    }

    private companion object {
        const val TAG = "LockExp"
        const val CHANNEL = "reminders"
        const val GROUP = "doses"
        const val GENERIC_TITLE = "복용 시간이에요"
        const val GENERIC_ID = 10
        const val SUMMARY_ID = 100
        val ITEMS = listOf("아침약", "저녁약")
        val METHODS = setOf(
            "private_public", "private_nopublic", "secret", "secret_summary", "secret_plus_public", "public_generic",
            "public_full", "cancel",
        )
    }
}

class NoopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("LockExp", "button ${intent.action}")
    }
}
