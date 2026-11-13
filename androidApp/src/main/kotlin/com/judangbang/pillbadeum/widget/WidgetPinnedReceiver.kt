package com.judangbang.pillbadeum.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.judangbang.pillbadeum.container
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * The success callback of SCR-005's `[위젯 추가]` (TASK-022): the launcher sends it with the new widget's id
 * ([AppWidgetManager.EXTRA_APPWIDGET_ID]) once the widget is placed, and nothing when the user declines. The widget gets
 * its choice ([WidgetChoices.choosePinned]) and is drawn at once. Not exported: only the pending intent of
 * [callback], which runs as this app, reaches it.
 */
class WidgetPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val app = context.applicationContext
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                try {
                    val choice = WidgetChoices.choosePinned(app, appWidgetId)
                    Log.i(TAG, "widget $appWidgetId placed from the intro: ${choice?.let { it::class.simpleName } ?: "no choice"}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Without a choice the widget shows everyone with one person, and asks to be set up from two on.
                    Log.e(TAG, "giving the widget placed from the intro its choice failed", e)
                }
                try {
                    WidgetPickActivity.glanceIdOf(app, appWidgetId)?.let { DidITakeWidget().update(app, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "drawing the widget placed from the intro failed", e)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WidgetPinnedReceiver"

        /**
         * The callback given to `requestPinAppWidget`. Mutable, so that the launcher can add the widget's id; the intent
         * names this receiver, which Android 14 asks of a mutable pending intent.
         */
        fun callback(context: Context): PendingIntent {
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, WidgetPinnedReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            )
        }
    }
}
