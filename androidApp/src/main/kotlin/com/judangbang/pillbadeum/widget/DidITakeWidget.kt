package com.judangbang.pillbadeum.widget

import android.content.Context
import android.util.Log
import android.util.TypedValue
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
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.Visibility
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.visibility
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.R
import com.judangbang.pillbadeum.boundary.DayBoundaryAlarm
import com.judangbang.pillbadeum.container
import com.judangbang.pillbadeum.design.Tokens
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.TileState
import com.judangbang.pillbadeum.status.WidgetButton
import com.judangbang.pillbadeum.status.WidgetLayout
import com.judangbang.pillbadeum.status.WidgetModel
import com.judangbang.pillbadeum.usecase.RecordResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** Per-widget Glance state (Preferences). */
private object WidgetState {
    /** Bumped by [WidgetUpdater]: a running session reloads its model when this changes. */
    val REFRESH = longPreferencesKey("refresh_at")
    val MESSAGE = stringPreferencesKey("message")
    val MESSAGE_AT = longPreferencesKey("message_at")

    /** A message older than this is not drawn (it also clears itself while the session is alive). */
    const val MESSAGE_TTL_MS: Long = 10_000
}

/** Action parameters a button is rendered with (sequences.md section 1). */
private object WidgetParams {
    val INTAKE_ID = ActionParameters.Key<String>("intake_id")
    val PERIOD_KEY = ActionParameters.Key<String>("rendered_period_key")
    val NAME = ActionParameters.Key<String>("name")
}

/**
 * Home screen widget (SCR-001), drawn as the 09 mockup's 요일 약통 (TASK-014): a fixed header with the weekday chip,
 * tiles two or one a row in a list that scrolls on its own, and a down arrow while the list overflows. It renders
 * [WidgetModel] (computed by the shared module), arranges it with [WidgetLayout] for each size the launcher gives,
 * and sends taps to [RecordFromWidget]; it never decides a judgment on its own.
 */
class DidITakeWidget : GlanceAppWidget() {
    /** One composition per launcher size: the arrangement depends on the exact width and height. */
    override val sizeMode: SizeMode = SizeMode.Exact

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
            WidgetContent(model, visible)
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
            // Times in the phone's own zone (REQ-082, TASK-043).
            zone = TimeZone.currentSystemDefault(),
        )
        if (result is RecordResult.Error) Log.e("DidITakeWidget", "record failed: ${result.message}")
        updateAppWidgetState(context, glanceId) { prefs ->
            if (text == null) {
                prefs.remove(WidgetState.MESSAGE)
                prefs.remove(WidgetState.MESSAGE_AT)
            } else {
                prefs[WidgetState.MESSAGE] = text
                prefs[WidgetState.MESSAGE_AT] = System.currentTimeMillis()
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
// Drawing (09 tokens, TASK-044 drawing section 2). Every part the layout can hide stays in the tree with its
// visibility switched, so a message or the arrow never changes the tree around the list: PR #16 saw the list jump
// to the top when a message line was inserted above it (report 1.2).

private fun color(argb: Long): ColorProvider = ColorProvider(Color(argb))

/** A Glance [Text] whose Korean words stay whole: lines break only at spaces ([WidgetLayout.keepWords]). */
@Composable
private fun Words(text: String, modifier: GlanceModifier = GlanceModifier, style: TextStyle) {
    Text(WidgetLayout.keepWords(text), modifier = modifier, style = style)
}

/** dp of the widget's 16sp and 14sp text at the phone's font size (Android 14+ grows large fonts nonlinearly). */
private fun widgetFonts(context: Context): WidgetLayout.Fonts {
    val metrics = context.resources.displayMetrics
    fun dp(sp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics) / metrics.density
    return WidgetLayout.Fonts(body = dp(16f), bodySm = dp(14f))
}

private fun visibleIf(shown: Boolean): Visibility = if (shown) Visibility.Visible else Visibility.Gone

/** The whole widget for one size. Internal for WidgetRenderTest, which draws it with fixed content. */
@Composable
internal fun WidgetContent(model: WidgetModel?, message: String?) {
    val size = LocalSize.current
    val plan = model?.let { WidgetLayout.plan(it, size.width.value, size.height.value, widgetFonts(LocalContext.current), message) }
    val openApp = actionStartActivity<MainActivity>()
    Box(modifier = GlanceModifier.fillMaxSize()) {
        Column(
            modifier = GlanceModifier.fillMaxSize()
                .background(ImageProvider(R.drawable.widget_background))
                .padding(horizontal = WidgetLayout.PAD_H.dp, vertical = WidgetLayout.PAD_V.dp),
        ) {
            // The tap message takes the header's place for 10 seconds (TASK-044 decision 2); both open the app.
            Box(modifier = GlanceModifier.fillMaxWidth().visibility(visibleIf(message == null))) {
                Header(model, wraps = plan?.headerWraps == true, openApp)
            }
            Box(modifier = GlanceModifier.fillMaxWidth().visibility(visibleIf(message != null))) {
                Band(message.orEmpty(), openApp)
            }
            // SCR-001#restored (supplement R-3): a short line, the full notice is in the app. Tiles still record.
            Column(modifier = GlanceModifier.fillMaxWidth().visibility(visibleIf(model?.restoreLine != null))) {
                Spacer(GlanceModifier.height(WidgetLayout.GAP.dp))
                Band(model?.restoreLine.orEmpty(), openApp)
            }
            Spacer(GlanceModifier.height(WidgetLayout.GAP.dp))
            Body(model, plan, openApp, GlanceModifier.fillMaxWidth().defaultWeight())
        }
        // SCR-001#overflow (REQ-078): no text, read as '더 있음'. A list never reports its scroll position, so the
        // arrow stays while the rows are estimated taller than the list (PR #16 report 3.5).
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Image(
                provider = ImageProvider(R.drawable.widget_more),
                contentDescription = Labels.MORE,
                modifier = GlanceModifier.size(44.dp, 19.dp).visibility(visibleIf(plan?.overflow == true)),
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

@Composable
private fun Header(model: WidgetModel?, wraps: Boolean, openApp: Action) {
    val modifier = GlanceModifier.fillMaxWidth().clickable(openApp).padding(horizontal = WidgetLayout.HEADER_PAD_H.dp)
    val date = TextStyle(color = color(Tokens.INK), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    when {
        model == null -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            // Zero wide: a Spacer with no width took the whole row and squeezed the name to nothing (TASK-014 render check).
            Spacer(GlanceModifier.width(0.dp).height(WidgetLayout.CHIP_MIN.dp))
            Words(Labels.APP_NAME, style = date)
        }
        !wraps -> Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            DayChip(model.weekday)
            Spacer(GlanceModifier.width(WidgetLayout.HEADER_GAP.dp))
            Words(model.headerDate, style = date)
            Spacer(GlanceModifier.defaultWeight())
            Count(model)
        }
        // Large fonts: the count goes under the date, at the right (TASK-044 drawing, 200%).
        else -> Column(modifier = modifier) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DayChip(model.weekday)
                Spacer(GlanceModifier.width(WidgetLayout.HEADER_GAP.dp))
                Words(model.headerDate, modifier = GlanceModifier.defaultWeight(), style = date)
            }
            Spacer(GlanceModifier.height(WidgetLayout.HEADER_LINE_GAP.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.End) { Count(model) }
        }
    }
}

/** Weekday on `color.ink.default`, at least 28dp square (09 BoxWidget `.dow`). */
@Composable
private fun DayChip(weekday: String) {
    Box(
        modifier = GlanceModifier.background(ImageProvider(R.drawable.widget_chip)),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(GlanceModifier.size(WidgetLayout.CHIP_MIN.dp))
        Words(
            weekday,
            modifier = GlanceModifier.padding(horizontal = WidgetLayout.CHIP_PAD_H.dp),
            style = TextStyle(color = color(Tokens.INK_ON_SOLID), fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
    }
}

/** `1 / 4` bold, then ` 완료 · 건너뜀 1` muted (S-1). */
@Composable
private fun Count(model: WidgetModel) {
    Words(model.countDone, style = TextStyle(color = color(Tokens.INK), fontSize = 14.sp, fontWeight = FontWeight.Bold))
    Words(" " + model.countRest, style = TextStyle(color = color(Tokens.INK_MUTED), fontSize = 14.sp))
}

/** Notice band: the tap message and the restore line (`color.surface.notice`). A tap opens the app. */
@Composable
private fun Band(text: String, openApp: Action) {
    Words(
        text,
        modifier = GlanceModifier.fillMaxWidth()
            .background(ImageProvider(R.drawable.widget_notice))
            .clickable(openApp, rippleOverride = R.drawable.widget_notice_ripple)
            .padding(horizontal = WidgetLayout.BAND_PAD_H.dp, vertical = WidgetLayout.BAND_PAD_V.dp),
        style = TextStyle(color = color(Tokens.INK_NOTICE), fontSize = 14.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
private fun Body(model: WidgetModel?, plan: WidgetLayout.Plan?, openApp: Action, modifier: GlanceModifier) {
    val quiet = TextStyle(color = color(Tokens.INK_MUTED), fontSize = 14.sp)
    when {
        model == null -> Words(Labels.LOADING, modifier = modifier.padding(horizontal = WidgetLayout.HEADER_PAD_H.dp), style = quiet)
        model.buttons.isEmpty() || plan == null -> Words(
            Labels.EMPTY_WIDGET,
            modifier = modifier.clickable(openApp).padding(horizontal = WidgetLayout.HEADER_PAD_H.dp),
            style = quiet,
        )
        else -> {
            // A list of rows, not a grid: a Glance grid jumps back to the top after every tap (PR #16 report 1.2).
            val rows = model.buttons.chunked(plan.columns)
            LazyColumn(modifier = modifier) {
                itemsIndexed(rows) { index, row ->
                    val bottom = when {
                        index < rows.lastIndex -> WidgetLayout.GAP
                        plan.overflow -> WidgetLayout.END_PAD
                        else -> 0f
                    }
                    TileRow(row, plan, bottom)
                }
            }
        }
    }
}

/**
 * One row of tiles of equal height. Every child fills the row's height, so the row takes the tallest tile's height
 * and stretches the others to it (a LinearLayout sizes a wrap-content row by its children only when all of them do).
 */
@Composable
private fun TileRow(row: List<WidgetButton>, plan: WidgetLayout.Plan, bottom: Float) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(bottom = bottom.dp)) {
        row.forEachIndexed { index, button ->
            if (index > 0) Spacer(GlanceModifier.width(WidgetLayout.GAP.dp).fillMaxHeight())
            Tile(button, button.intakeId in plan.whoAbove, GlanceModifier.defaultWeight().fillMaxHeight())
        }
        repeat(plan.columns - row.size) {
            Spacer(GlanceModifier.width(WidgetLayout.GAP.dp).fillMaxHeight())
            Spacer(GlanceModifier.defaultWeight().fillMaxHeight())
        }
    }
}

/**
 * One item (09 BoxWidget `.t`): the person's tint with ink text while pending, the person's lid with white text once
 * taken, grey once skipped (S-3). The whole tile is the touch target, at least 64dp tall.
 */
@Composable
private fun Tile(button: WidgetButton, whoAbove: Boolean, modifier: GlanceModifier) {
    val (fill, ink) = when (button.state) {
        TileState.PENDING -> button.palette.tint to button.palette.ink
        TileState.TAKEN -> button.palette.solid to Tokens.INK_ON_SOLID
        TileState.SKIPPED -> Tokens.SURFACE_SKIP to Tokens.INK_MUTED
    }
    val record = actionRunCallback<RecordFromWidget>(
        actionParametersOf(
            WidgetParams.INTAKE_ID to button.intakeId,
            WidgetParams.PERIOD_KEY to button.periodKey,
            WidgetParams.NAME to button.title,
        ),
    )
    Row(
        modifier = modifier
            .background(ImageProvider(R.drawable.widget_tile), colorFilter = ColorFilter.tint(color(fill)))
            .clickable(record, rippleOverride = R.drawable.widget_tile_ripple)
            .semantics { contentDescription = button.description }
            .padding(
                start = WidgetLayout.TILE_PAD_START.dp,
                end = WidgetLayout.TILE_PAD_END.dp,
                top = WidgetLayout.TILE_PAD_V.dp,
                bottom = WidgetLayout.TILE_PAD_V.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Keeps the tile 64dp tall (48 + the padding) however short its text is.
        Spacer(GlanceModifier.width(0.dp).height((WidgetLayout.TILE_MIN_HEIGHT - 2 * WidgetLayout.TILE_PAD_V).dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            NameLine(button, whoAbove, ink)
            button.lines.forEachIndexed { index, line ->
                // W-2: a pending weekly item's last dose at the name's size.
                val large = index == 0 && button.largeFirstLine
                Spacer(GlanceModifier.height(WidgetLayout.TILE_LINE_GAP.dp))
                Words(
                    line,
                    style = TextStyle(
                        color = color(ink),
                        fontSize = if (large) 16.sp else 14.sp,
                        fontWeight = if (large) FontWeight.Medium else FontWeight.Normal,
                    ),
                )
            }
        }
    }
}

/**
 * `엄마 혈압약` and the state icon at the right of the first line (09 BoxWidget `.tr`). The person's name is drawn only
 * with two or more people (REQ-018) and goes on its own line when both names do not fit ([WidgetLayout]).
 */
@Composable
private fun NameLine(button: WidgetButton, whoAbove: Boolean, ink: Long) {
    val who = button.who
    val whoStyle = TextStyle(color = color(ink), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val nameStyle = TextStyle(color = color(ink), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    if (who != null && whoAbove) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Words(who, modifier = GlanceModifier.defaultWeight(), style = whoStyle)
            Spacer(GlanceModifier.width(WidgetLayout.ICON_GAP.dp))
            StateIcon(button)
        }
        Words(button.title, style = nameStyle)
    } else {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (who != null) {
                // 2dp down so the smaller name sits near the item name's baseline.
                Words(who, modifier = GlanceModifier.padding(top = 2.dp, end = WidgetLayout.WHO_GAP.dp), style = whoStyle)
            }
            Words(button.title, modifier = GlanceModifier.defaultWeight(), style = nameStyle)
            Spacer(GlanceModifier.width(WidgetLayout.ICON_GAP.dp))
            StateIcon(button)
        }
    }
}

/** Dashed ring in the person's ink (pending), white disc with a check in the lid color (taken), grey disc with a minus (skipped). */
@Composable
private fun StateIcon(button: WidgetButton) {
    val icon = GlanceModifier.size(WidgetLayout.ICON.dp)
    when (button.state) {
        TileState.PENDING -> Image(
            provider = ImageProvider(R.drawable.widget_icon_ring),
            contentDescription = null,
            modifier = icon,
            colorFilter = ColorFilter.tint(color(button.palette.ink)),
        )
        TileState.TAKEN -> Box(modifier = icon, contentAlignment = Alignment.Center) {
            Image(provider = ImageProvider(R.drawable.widget_icon_disc), contentDescription = null, modifier = GlanceModifier.fillMaxSize())
            Image(
                provider = ImageProvider(R.drawable.ic_widget_check),
                contentDescription = null,
                modifier = GlanceModifier.size(14.dp),
                colorFilter = ColorFilter.tint(color(button.palette.solid)),
            )
        }
        TileState.SKIPPED -> Image(provider = ImageProvider(R.drawable.widget_icon_skipped), contentDescription = null, modifier = icon)
    }
}
