package com.judangbang.pillbadeum.debug

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
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
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
import com.judangbang.pillbadeum.R
import com.judangbang.pillbadeum.container
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.WidgetButton
import com.judangbang.pillbadeum.status.WidgetModel
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.widget.RecordFromWidget
import com.judangbang.pillbadeum.widget.WidgetColors
import com.judangbang.pillbadeum.widget.WidgetParams
import com.judangbang.pillbadeum.widget.WidgetState

/** Which body the probe draws. Set by [WidgetHostActivity] before it binds the widget. */
enum class ProbeLayout {
    /** `LazyVerticalGrid(GridCells.Fixed(4))`: four buttons a row, the list scrolls (screens.md 5장 1번). */
    GRID,

    /** `LazyColumn`: one item a row (circle, name, last dose), the layout for large fonts (TASK-044). */
    LIST,
    ;

    companion object {
        private const val PREFS = "widget_probe"
        private const val KEY = "layout"

        fun of(context: Context): ProbeLayout =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { name -> entries.firstOrNull { it.name == name } } ?: GRID

        fun set(context: Context, layout: ProbeLayout) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, layout.name).commit()
        }
    }
}

private val PROBE_REFRESH = longPreferencesKey("probe_refresh_at")

/**
 * TASK-042 technical test (debug builds only): can buttons inside a Glance lazy list record a dose,
 * and does a four-column grid fit at 4x2? It renders the same [WidgetModel] as the product widget and
 * records through the product [RecordFromWidget], so a tap here takes the same path as a real tap.
 * The header stays fixed above the scrolling list (screens.md SCR-001#overflow).
 */
class ScrollProbeWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = context.container
        val layout = ProbeLayout.of(context)
        val initial = load(context)
        provideContent {
            val prefs = currentState<Preferences>()
            val refreshAt = prefs[PROBE_REFRESH] ?: 0L
            var model by remember { mutableStateOf(initial) }
            LaunchedEffect(refreshAt) { load(context)?.let { model = it } }
            ProbeContent(model, layout, prefs[WidgetState.MESSAGE])
        }
        Log.i(TAG, "provideGlance layout=$layout items=${initial?.buttons?.size} core=${container.hashCode()}")
    }

    private suspend fun load(context: Context): WidgetModel? =
        runCatching { context.container.widgetModel() }.onFailure { Log.e(TAG, "load failed", it) }.getOrNull()

    companion object {
        const val TAG = "ScrollProbeWidget"
    }
}

class ScrollProbeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScrollProbeWidget()
}

/**
 * Tap on a probe button: the product [RecordFromWidget] does the recording and the message, then
 * the probe reloads (the product [com.judangbang.pillbadeum.widget.WidgetUpdater] only knows the
 * product widget).
 */
class ProbeRecord : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Log.i(ScrollProbeWidget.TAG, "tap ${parameters[WidgetParams.NAME]}")
        RecordFromWidget().onAction(context, glanceId, parameters)
        updateAppWidgetState(context, glanceId) { it[PROBE_REFRESH] = System.currentTimeMillis() }
        ScrollProbeWidget().update(context, glanceId)
    }
}

@Composable
private fun ProbeContent(model: WidgetModel?, layout: ProbeLayout, message: String?) {
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .background(ImageProvider(R.drawable.widget_background))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // Fixed header: it does not scroll with the buttons.
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                Labels.APP_NAME,
                maxLines = 1,
                style = TextStyle(color = ColorProvider(WidgetColors.Ink), fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.width(6.dp))
            Text(
                model?.dateLabel.orEmpty(),
                maxLines = 1,
                style = TextStyle(color = ColorProvider(WidgetColors.Primary), fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(model?.countLabel.orEmpty(), maxLines = 1, style = TextStyle(color = ColorProvider(WidgetColors.Muted), fontSize = 12.sp))
        }
        if (message != null) {
            Text(
                message,
                maxLines = 2,
                style = TextStyle(color = ColorProvider(WidgetColors.WarnInk), fontSize = 12.sp),
            )
        }
        Spacer(GlanceModifier.height(4.dp))
        val buttons = model?.buttons.orEmpty()
        val body = GlanceModifier.fillMaxWidth().defaultWeight()
        when {
            model == null -> Text("불러오는 중…", style = TextStyle(color = ColorProvider(WidgetColors.Muted), fontSize = 12.sp))
            layout == ProbeLayout.GRID -> LazyVerticalGrid(gridCells = GridCells.Fixed(4), modifier = body) {
                items(buttons) { button -> GridPill(button) }
            }
            else -> LazyColumn(modifier = body) {
                items(buttons) { button -> ListRow(button) }
            }
        }
    }
}

private fun recordAction(button: WidgetButton) = actionRunCallback<ProbeRecord>(
    actionParametersOf(
        WidgetParams.INTAKE_ID to button.intakeId,
        WidgetParams.PERIOD_KEY to button.periodKey,
        WidgetParams.NAME to button.title,
    ),
)

/** One grid cell: circle, name, subtitle. The whole cell is the touch target. */
@Composable
private fun GridPill(button: WidgetButton) {
    Column(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)
            .clickable(recordAction(button)).semantics { contentDescription = button.description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Circle(button, 48)
        Text(
            button.title,
            maxLines = 1,
            style = TextStyle(color = ColorProvider(WidgetColors.Ink), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        )
        Text(
            button.subtitle,
            maxLines = 1,
            style = TextStyle(color = ColorProvider(WidgetColors.Muted), fontSize = 10.sp, textAlign = TextAlign.Center),
        )
    }
}

/** One list row for large fonts: circle, then name and last dose on the same line width. */
@Composable
private fun ListRow(button: WidgetButton) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)
            .clickable(recordAction(button)).semantics { contentDescription = button.description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Circle(button, 40)
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                button.title,
                maxLines = 1,
                style = TextStyle(color = ColorProvider(WidgetColors.Ink), fontSize = 13.sp, fontWeight = FontWeight.Bold),
            )
            Text(button.subtitle, maxLines = 1, style = TextStyle(color = ColorProvider(WidgetColors.Muted), fontSize = 12.sp))
        }
    }
}

@Composable
private fun Circle(button: WidgetButton, sizeDp: Int) {
    Box(modifier = GlanceModifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = GlanceModifier.size((sizeDp - 4).dp).background(
                ImageProvider(if (button.done) R.drawable.widget_circle_done else R.drawable.widget_circle_pending),
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (button.done) {
                Image(
                    provider = ImageProvider(R.drawable.ic_widget_check),
                    contentDescription = null,
                    modifier = GlanceModifier.size((sizeDp / 2).dp),
                )
            }
        }
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            Box(
                modifier = GlanceModifier.size(18.dp).background(
                    ImageProvider(R.drawable.widget_badge),
                    colorFilter = ColorFilter.tint(ColorProvider(Color(PersonColors.argb(button.personColor)))),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(button.personInitial, style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}
