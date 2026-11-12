package com.judangbang.pillbadeum.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.judangbang.pillbadeum.container
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.PickOption
import com.judangbang.pillbadeum.status.WidgetChoice
import com.judangbang.pillbadeum.status.WidgetPick
import com.judangbang.pillbadeum.status.showsPeople
import com.judangbang.pillbadeum.ui.PickState
import com.judangbang.pillbadeum.ui.WidgetPickScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SCR-002, the widget's person picker (TASK-012, SPEC-005). The launcher opens it when the widget is placed and, on
 * Android 12 and later, from the widget's Reconfigure button (`widgetFeatures="reconfigurable"`); the widget opens it
 * from its `위젯을 다시 설정해 주세요` line (SCR-001#reconfigure).
 *
 * - With one person who is not archived it never shows: the widget is set to 전체 and placed (REQ-018). The theme is
 *   translucent and nothing is drawn while the people are read, so the launcher stays in view meanwhile; a read that
 *   takes longer than [LOADING_AFTER_MS] shows `불러오는 중…`.
 * - Back and [취소] leave the result canceled, so a widget being placed is not placed (official configuration-activity
 *   rules). A widget already on the home screen stays as it was.
 * - A choice is saved in the widget's Glance state and the widget is drawn at once: the platform sends no update to a
 *   widget that has a configuration activity, so this activity asks for it.
 */
class WidgetPickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_CANCELED, result)
        val glanceId = glanceIdOf(this, appWidgetId)
        if (glanceId == null) {
            Log.w(TAG, "not one of this app's widgets: $appWidgetId")
            finish()
            return
        }
        val container = applicationContext.container

        setContent {
            val scope = rememberCoroutineScope()
            var state by remember { mutableStateOf<PickState>(PickState.Waiting) }
            var saving by remember { mutableStateOf(false) }

            fun pick(choice: WidgetChoice, options: List<PickOption>) {
                if (saving) return
                saving = true
                scope.launch {
                    val saved = runCatching { WidgetChoices.write(applicationContext, glanceId, choice) }
                        .onFailure { Log.e(TAG, "saving the widget's choice failed", it) }
                        .isSuccess
                    if (!saved) {
                        state = PickState.Ready(options, error = Labels.PICK_SAVE_FAILED)
                        saving = false
                        return@launch
                    }
                    runCatching { DidITakeWidget().update(applicationContext, glanceId) }
                        .onFailure { Log.e(TAG, "drawing the widget after its choice failed", it) }
                    setResult(Activity.RESULT_OK, result)
                    finish()
                }
            }

            LaunchedEffect(Unit) {
                delay(LOADING_AFTER_MS)
                if (state == PickState.Waiting) state = PickState.Loading
            }
            LaunchedEffect(Unit) {
                val board = runCatching { container.statusBoard() }
                    .onFailure { Log.e(TAG, "reading the people failed", it) }
                    .getOrNull()
                // Without the people only 전체 can be offered; it needs nothing from the database.
                val options = board?.let(WidgetPick::options) ?: listOf(WidgetPick.everyoneOnly())
                if (board != null && !showsPeople(board.people.size)) pick(WidgetChoice.Everyone, options)
                else state = PickState.Ready(options)
            }

            WidgetPickScreen(
                state = state,
                onPick = { choice -> (state as? PickState.Ready)?.let { pick(choice, it.options) } },
                onCancel = { finish() },
            )
        }
    }

    companion object {
        private const val TAG = "WidgetPickActivity"

        /** How long nothing is drawn before `불러오는 중…` appears. */
        const val LOADING_AFTER_MS: Long = 400

        /** [appWidgetId]'s Glance id when it is one of this app's widgets; another app can start this activity too. */
        fun glanceIdOf(context: Context, appWidgetId: Int): GlanceId? {
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null
            val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId) ?: return null
            if (info.provider != ComponentName(context, DidITakeWidgetReceiver::class.java)) return null
            return runCatching { GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId) }.getOrNull()
        }

        /**
         * Opens this picker for a widget already on the home screen (its `#reconfigure` line). The data URI keeps one
         * widget's pending intent apart from another's, which extras alone do not.
         */
        fun intentFor(context: Context, appWidgetId: Int): Intent =
            Intent(context, WidgetPickActivity::class.java)
                .setData(Uri.parse("pillbadeum-widget://pick/$appWidgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
    }
}
