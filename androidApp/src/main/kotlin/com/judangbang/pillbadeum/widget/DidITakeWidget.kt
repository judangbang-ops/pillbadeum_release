package com.judangbang.pillbadeum.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.R
import com.judangbang.pillbadeum.boundary.DayBoundaryAlarm
import com.judangbang.pillbadeum.container
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.WidgetButton
import com.judangbang.pillbadeum.status.WidgetModel
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.RecordResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Per-widget Glance state (Preferences). */
private object WidgetState {
    /** Bumped by [WidgetUpdater]: a running session reloads its model when this changes. */
    val REFRESH = longPreferencesKey("refresh_at")
    val MESSAGE = stringPreferencesKey("message")
    val MESSAGE_AT = longPreferencesKey("message_at")
    val MESSAGE_WARN = longPreferencesKey("message_warn")

    /** A message older than this is not drawn (it also clears itself while the session is alive). */
    const val MESSAGE_TTL_MS: Long = 10_000
}

/** Action parameters a button is rendered with (sequences.md section 1). */
private object WidgetParams {
    val INTAKE_ID = ActionParameters.Key<String>("intake_id")
    val PERIOD_KEY = ActionParameters.Key<String>("rendered_period_key")
    val NAME = ActionParameters.Key<String>("name")
}

private object WidgetColors {
    val Ink = Color(0xFF1C2129)
    val Muted = Color(0xFF5A6372)
    val Primary = Color(0xFF1D4E89)
    val WarnBg = Color(0xFFFCEBD9)
    val WarnInk = Color(0xFF7A3E06)
    val InfoBg = Color(0xFFE3ECF8)
    val InfoInk = Color(0xFF173E6C)
}

/**
 * Home screen widget. It renders [WidgetModel] (computed by the shared module) and sends taps to
 * [RecordFromWidget]; it never decides a judgment on its own.
 */
class DidITakeWidget : GlanceAppWidget() {
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.container
        val initial = loadOrNull { container.widgetModel() }
        provideContent {
            val prefs = currentState<Preferences>()
            val refreshAt = prefs[WidgetState.REFRESH] ?: 0L
            var model by remember { mutableStateOf(initial) }
            // update()/updateAll() on a live session only recomposes; reload from DB explicitly.
            LaunchedEffect(refreshAt) { loadOrNull { container.widgetModel() }?.let { model = it } }

            val message = prefs[WidgetState.MESSAGE]
            val messageAt = prefs[WidgetState.MESSAGE_AT] ?: 0L
            var wallClock by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(messageAt) {
                wallClock = System.currentTimeMillis()
                val left = messageAt + WidgetState.MESSAGE_TTL_MS - wallClock
                if (left > 0) {
                    delay(left)
                    wallClock = System.currentTimeMillis()
                }
            }
            val visible = message?.takeIf { wallClock - messageAt in 0 until WidgetState.MESSAGE_TTL_MS }
            WidgetContent(model, visible, warn = (prefs[WidgetState.MESSAGE_WARN] ?: 0L) == 1L)
        }
    }

    private suspend fun <T> loadOrNull(block: suspend () -> T): T? =
        runCatching { block() }.onFailure { Log.e(TAG, "widget model load failed", it) }.getOrNull()

    companion object {
        private const val TAG = "DidITakeWidget"
    }
}

class DidITakeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DidITakeWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // First widget placed: make sure the day-boundary refresh is armed.
        context.container.appScope.launch { DayBoundaryAlarm.scheduleNext(context) }
    }
}

/** Widget tap: RecordDose with the rendered period key, then message + refresh. */
class RecordFromWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intakeId = parameters[WidgetParams.INTAKE_ID] ?: return
        val container = context.container
        val core = container.core()
        val result = core.record(intakeId, DoseSource.WIDGET, parameters[WidgetParams.PERIOD_KEY])
        val text = Labels.recordMessage(
            result = result,
            name = parameters[WidgetParams.NAME].orEmpty(),
            now = core.clock.now(),
            zone = core.windowSettings().baseZone,
        )
        if (result is RecordResult.Error) Log.e("DidITakeWidget", "record failed: ${result.message}")
        updateAppWidgetState(context, glanceId) { prefs ->
            if (text == null) {
                prefs.remove(WidgetState.MESSAGE)
                prefs.remove(WidgetState.MESSAGE_AT)
            } else {
                prefs[WidgetState.MESSAGE] = text
                prefs[WidgetState.MESSAGE_AT] = System.currentTimeMillis()
                prefs[WidgetState.MESSAGE_WARN] = if (result is RecordResult.Stale) 0L else 1L
            }
        }
        // Recorded, Stale and Gone all need a fresh render; Already refreshes harmlessly.
        container.afterWrite()
    }
}

/** Refreshes every placed widget from the database (architecture.md section 6: updateAll). */
object WidgetUpdater {
    suspend fun updateAll(context: Context) {
        val stamp = System.currentTimeMillis()
        val ids = GlanceAppWidgetManager(context).getGlanceIds(DidITakeWidget::class.java)
        ids.forEach { id -> updateAppWidgetState(context, id) { it[WidgetState.REFRESH] = stamp } }
        DidITakeWidget().updateAll(context)
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun WidgetContent(model: WidgetModel?, message: String?, warn: Boolean) {
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(ImageProvider(R.drawable.widget_background))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Labels.APP_NAME, style = TextStyle(color = ColorProvider(WidgetColors.Ink), fontSize = 15.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.width(8.dp))
            Text(
                model?.dateLabel.orEmpty(),
                style = TextStyle(color = ColorProvider(WidgetColors.Primary), fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(model?.countLabel.orEmpty(), style = TextStyle(color = ColorProvider(WidgetColors.Muted), fontSize = 12.sp))
        }
        Spacer(GlanceModifier.height(10.dp))

        when {
            model == null -> Text("불러오는 중…", style = TextStyle(color = ColorProvider(WidgetColors.Muted), fontSize = 12.sp))
            model.buttons.isEmpty() -> Text(
                Labels.EMPTY_WIDGET,
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
                style = TextStyle(color = ColorProvider(WidgetColors.Muted), fontSize = 12.sp),
            )
            else -> model.buttons.chunked(BUTTONS_PER_ROW).forEach { row ->
                Row(modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    row.forEach { button -> Pill(button, GlanceModifier.defaultWeight()) }
                    repeat(BUTTONS_PER_ROW - row.size) { Spacer(GlanceModifier.defaultWeight()) }
                }
            }
        }

        if (message != null) {
            Spacer(GlanceModifier.height(4.dp))
            Box(
                modifier = GlanceModifier.fillMaxWidth()
                    .background(
                        ImageProvider(R.drawable.widget_message),
                        colorFilter = ColorFilter.tint(ColorProvider(if (warn) WidgetColors.WarnBg else WidgetColors.InfoBg)),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    message,
                    style = TextStyle(color = ColorProvider(if (warn) WidgetColors.WarnInk else WidgetColors.InfoInk), fontSize = 12.sp),
                )
            }
        }
    }
}

@Composable
private fun Pill(button: WidgetButton, modifier: GlanceModifier) {
    val action = actionRunCallback<RecordFromWidget>(
        actionParametersOf(
            WidgetParams.INTAKE_ID to button.intakeId,
            WidgetParams.PERIOD_KEY to button.periodKey,
            WidgetParams.NAME to button.title,
        ),
    )
    // The whole pill (circle + labels) is the touch target: well above 48dp.
    Column(
        modifier = modifier.clickable(action).semantics { contentDescription = button.description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = GlanceModifier.size(60.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = GlanceModifier.size(54.dp).background(
                    ImageProvider(if (button.done) R.drawable.widget_circle_done else R.drawable.widget_circle_pending),
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (button.done) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_widget_check),
                        contentDescription = null,
                        modifier = GlanceModifier.size(26.dp),
                    )
                }
            }
            Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                Box(
                    modifier = GlanceModifier.size(20.dp).background(
                        ImageProvider(R.drawable.widget_badge),
                        colorFilter = ColorFilter.tint(ColorProvider(Color(PersonColors.argb(button.personColor)))),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        button.personInitial,
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
        Text(
            button.title,
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(WidgetColors.Ink),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
        Text(
            button.subtitle,
            maxLines = 2,
            style = TextStyle(color = ColorProvider(WidgetColors.Muted), fontSize = 10.sp, textAlign = TextAlign.Center),
        )
    }
}

private const val BUTTONS_PER_ROW = 4
