package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.status.CellState
import com.judangbang.pillbadeum.status.HistoryCell
import com.judangbang.pillbadeum.status.HistoryDay
import com.judangbang.pillbadeum.status.HistoryMonth
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.WidgetLayout
import com.judangbang.pillbadeum.status.showsPeople
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** What SCR-008 shows: `#loading`, `#error`, or the month (`#normal`, `#empty`). */
internal sealed interface HistoryState {
    data object Loading : HistoryState

    /** The month could not be read (SCR-008#error). */
    data object Failed : HistoryState

    data class Ready(val month: HistoryMonth) : HistoryState
}

/** What SCR-008's buttons do (screens.md SCR-008 table of actions). */
internal class HistoryActions(
    val back: () -> Unit,
    val retry: () -> Unit,
    /** Opens the month of this first day. */
    val month: (LocalDate) -> Unit,
    /** Keeps one person's items, or everyone's with null (REQ-040). */
    val person: (String?) -> Unit,
    /** SCR-007 for the cell's window (supplement P-1). */
    val open: (HistoryCell) -> Unit,
)

/**
 * SCR-008 지난 기록, drawn as 09 History (TASK-019): the month, the person picker with two or more people, and the date
 * lines newest first with each window's judgment (REQ-037~040). [month] is the shown month's first day and [personId] the
 * chosen person, null for everyone. The app keeps [list] while SCR-007 is open on top, so back returns to the same line.
 * Times are on the phone's clock in [zone] (REQ-082).
 */
@Composable
internal fun HistoryScreen(
    state: HistoryState,
    month: LocalDate,
    personId: String?,
    people: List<PersonEntity>,
    message: String?,
    zone: TimeZone,
    list: LazyListState,
    actions: HistoryActions,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar("지난 기록", navigation = { BarIconButton("뒤로", close = false, actions.back) })
        // Above the list, so a message about a cell far down is seen (an archived item, SCR-007 closing).
        message?.let { Band(it, Modifier.padding(start = Ui.Side, end = Ui.Side, bottom = 8.dp), live = true) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val bodySm = with(density) { 14.sp.toDp().value }
            val titleMd = with(density) { 20.sp.toDp().value }
            val layout = DayLayout(HistoryLayout.columns(maxWidth.value, bodySm, titleMd), HistoryLayout.dateWidth(bodySm, titleMd).dp)
            // REQ-018: one active person, no picker; the cells name the person only while everyone's are shown.
            val choosing = showsPeople(people.size)
            val withPerson = choosing && personId == null
            val padding = PaddingValues(start = Ui.Side, end = Ui.Side, top = 4.dp, bottom = 24.dp)
            when (state) {
                // Not the list: an empty list would move a kept place back to the top before the month is read.
                HistoryState.Loading, HistoryState.Failed -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding),
                ) {
                    MonthRow(month, limits = null, onMonth = actions.month)
                    if (choosing) PeoplePicker(people, personId, actions.person)
                    if (state == HistoryState.Failed) FailedLines(actions.retry) else LoadingLines(layout)
                }
                is HistoryState.Ready -> {
                    val data = state.month
                    val today = data.today
                    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = padding) {
                        item(key = "month") { MonthRow(month, limitsOf(data), actions.month) }
                        if (choosing) item(key = "people") { PeoplePicker(people, personId, actions.person) }
                        if (data.isEmpty) {
                            item(key = "empty") {
                                Words(Labels.HISTORY_EMPTY, Ui.type(16, color = Ui.Muted), Modifier.padding(horizontal = 4.dp, vertical = 16.dp))
                            }
                        }
                        items(data.days, key = { it.date.toString() }) { day ->
                            DayLine(day, today, layout, withPerson, zone, actions.open)
                        }
                    }
                }
            }
        }
    }
}

/** Cells a row and the date column's width at this screen width and font size ([HistoryLayout]). */
private class DayLayout(val columns: Int, val dateWidth: Dp)

/** Whether the month buttons can go on (screens.md SCR-008 규칙): not before the first item's month, not after this month. */
private class MonthLimits(val previous: Boolean, val next: Boolean)

private fun limitsOf(data: HistoryMonth): MonthLimits {
    val today = data.today
    val first = data.firstMonth
    return MonthLimits(
        previous = first != null && data.month > first,
        next = data.month < LocalDate(today.year, today.month, 1),
    )
}

/**
 * `‹ 2026년 11월 ›` (09 History `.mon`). A button at a limit stays drawn, greyed and read as `이전 달 없음`·`다음 달 없음`;
 * while the month is read ([limits] null) both wait. TalkBack reads the new month when it changes.
 */
@Composable
private fun MonthRow(month: LocalDate, limits: MonthLimits?, onMonth: (LocalDate) -> Unit) {
    val previous = month.minus(1, DateTimeUnit.MONTH)
    val next = month.plus(1, DateTimeUnit.MONTH)
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        MonthButton(
            label = if (limits == null || limits.previous) Labels.monthButton(previous, month) else "이전 달 없음",
            forward = false,
            enabled = limits?.previous == true,
        ) { onMonth(previous) }
        Words(
            Labels.monthTitle(month),
            Ui.type(20, Ui.ExtraBold, lineHeight = 1.25f, center = true),
            Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            heading = true,
        )
        MonthButton(
            label = if (limits == null || limits.next) Labels.monthButton(next, month) else "다음 달 없음",
            forward = true,
            enabled = limits?.next == true,
        ) { onMonth(next) }
    }
}

/** A 48 dp chevron (09 History `.ib`, `.ib.off` in the strong line color when it cannot go on). */
@Composable
private fun MonthButton(label: String, forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) Ui.Ink else Ui.LineStrong
    Box(
        Modifier.size(48.dp).clip(Ui.ButtonShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            // The board's path in a 24 unit box: `M15 5l-7 7 7 7`, forward `M9 5l7 7-7 7`.
            val u = size.width / 24f
            val chevron = Path().apply {
                if (forward) {
                    moveTo(9 * u, 5 * u)
                    lineTo(16 * u, 12 * u)
                    lineTo(9 * u, 19 * u)
                } else {
                    moveTo(15 * u, 5 * u)
                    lineTo(8 * u, 12 * u)
                    lineTo(15 * u, 19 * u)
                }
            }
            drawPath(chevron, color, style = Stroke(2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** `전체` and the active people, three a row (09 History `.pick`, REQ-040). */
@Composable
private fun PeoplePicker(people: List<PersonEntity>, chosen: String?, onPerson: (String?) -> Unit) {
    Box(Modifier.padding(bottom = 12.dp)) {
        PickGrid(listOf<PersonEntity?>(null) + people, columns = 3) { person, modifier ->
            PickCell(person?.name ?: Labels.PICK_EVERYONE, person?.id == chosen, modifier) { onPerson(person?.id) }
        }
    }
}

/** One date line (09 History `.day`): the date, then the cells; TalkBack can jump between dates (headings). */
@Composable
private fun DayLine(
    day: HistoryDay,
    today: LocalDate,
    layout: DayLayout,
    withPerson: Boolean,
    zone: TimeZone,
    onCell: (HistoryCell) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().topLine().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(HistoryLayout.DAY_GAP.dp),
    ) {
        val spoken = Labels.historyDay(day.date, today)
        Column(
            Modifier.width(layout.dateWidth).clearAndSetSemantics {
                contentDescription = spoken
                heading()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Words(day.date.day.toString(), Ui.type(20, Ui.ExtraBold, lineHeight = 1.25f, center = true))
            Words(Labels.weekday(day.date.dayOfWeek), Ui.type(14, color = Ui.Muted, lineHeight = 1.25f, center = true))
            if (day.date == today) {
                Words(Labels.TODAY, Ui.type(14, Ui.ExtraBold, lineHeight = 1.25f, center = true), Modifier.padding(top = 2.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HistoryLayout.CELL_GAP.dp)) {
            HistoryLayout.rows(day.cells, layout.columns).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(HistoryLayout.CELL_GAP.dp),
                ) {
                    row.forEach { cell -> Cell(cell, withPerson, zone, { onCell(cell) }, Modifier.weight(1f).fillMaxHeight()) }
                    if (row.none { it.rule is ScheduleRule.Weekly }) repeat(layout.columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The thin line above a date line (`.day` border-top). */
private fun Modifier.topLine(): Modifier = drawBehind {
    drawLine(Ui.LineSubtle, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
}

/** The mark before a judgment's words: taken and skipped differ by more than color (REQ-037, S-5). */
private enum class Mark { CHECK, MINUS }

/**
 * One window's cell (09 History `.cp`): taken in the person's color with a check and the time, the current window dashed
 * in the person's color with `아직`, a skip grey with a minus and `건너뜀`, a missed window outlined with `빠짐`. When everyone's
 * cells are shown the person's name comes before the item's (Claude's choice: the board tells people apart by color
 * only). TalkBack reads one sentence ([Labels.historySpoken]); a tap opens SCR-007.
 */
@Composable
private fun Cell(cell: HistoryCell, withPerson: Boolean, zone: TimeZone, onClick: () -> Unit, modifier: Modifier) {
    val person = personLook(cell.person.color)
    var m = modifier.heightIn(min = 48.dp).clip(Ui.ButtonShape)
    m = when (cell.state) {
        CellState.TAKEN -> m.background(person.solid)
        CellState.OPEN -> m.background(Ui.Surface).dashedBorder(2.dp, person.solid, 12.dp, dash = 5.dp, gap = 4.dp)
        CellState.SKIPPED -> m.background(Ui.Skip)
        CellState.MISSED -> m.background(Ui.Surface).border(1.5.dp, Ui.LineStrong, Ui.ButtonShape)
    }
    val ink = when (cell.state) {
        CellState.TAKEN -> Ui.OnSolid
        CellState.OPEN -> person.ink
        CellState.SKIPPED, CellState.MISSED -> Ui.Muted
    }
    val mark = when (cell.state) {
        CellState.TAKEN -> Mark.CHECK
        CellState.SKIPPED -> Mark.MINUS
        CellState.MISSED, CellState.OPEN -> null
    }
    val spoken = Labels.historySpoken(cell, withPerson, zone)
    Column(
        m.clickable(role = Role.Button, onClickLabel = "판정 고치기", onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = HistoryLayout.CELL_PAD.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        BasicText(
            buildAnnotatedString {
                if (withPerson) {
                    withStyle(SpanStyle(fontWeight = Ui.SemiBold)) { append(WidgetLayout.keepWords(cell.person.name)) }
                    append(" ")
                }
                append(WidgetLayout.keepWords(cell.intakeName))
            },
            style = Ui.type(14, Ui.ExtraBold, ink, lineHeight = 1.25f),
        )
        StatusLine(
            prefix = if (cell.rule is ScheduleRule.Weekly) Labels.weekRange(cell) else null,
            mark = mark,
            text = Labels.historyStatus(cell, zone),
            color = ink,
        )
        if (cell.clockWarning) Words(Labels.CLOCK_MARK, Ui.type(14, Ui.ExtraBold, ink, lineHeight = 1.25f))
    }
}

/** `✓ 08:02`, `– 건너뜀`, weekly `11/09~11/15 아직`: the mark stays with its words when the line breaks. */
@Composable
private fun StatusLine(prefix: String?, mark: Mark?, text: String, color: Color) {
    val line = buildAnnotatedString {
        prefix?.let { append(WidgetLayout.keepWords(it) + " ") }
        if (mark != null) {
            appendInlineContent(MARK, if (mark == Mark.CHECK) "✓" else "-")
            append(Labels.NBSP)
        }
        append(WidgetLayout.keepWords(text))
    }
    val inline = if (mark == null) {
        emptyMap()
    } else {
        mapOf(MARK to InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) { MarkIcon(mark, color) })
    }
    BasicText(line, style = Ui.type(14, color = color, lineHeight = 1.25f), inlineContent = inline)
}

private const val MARK = "mark"

/** A check for a dose, a minus for a skip, drawn in the cell's text color (the widget's marks, without their disc). */
@Composable
private fun MarkIcon(mark: Mark, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val s = size.minDimension
        val stroke = s * 0.14f
        when (mark) {
            Mark.CHECK -> drawPath(
                Path().apply {
                    moveTo(s * 0.16f, s * 0.54f)
                    lineTo(s * 0.4f, s * 0.76f)
                    lineTo(s * 0.84f, s * 0.28f)
                },
                color,
                style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            Mark.MINUS -> drawLine(color, Offset(s * 0.2f, s * 0.5f), Offset(s * 0.8f, s * 0.5f), stroke, StrokeCap.Round)
        }
    }
}

/** SCR-008#loading (screens.md): grey lines where the date lines go; TalkBack reads `불러오는 중…`. */
@Composable
private fun LoadingLines(layout: DayLayout) {
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = Labels.LOADING }) {
        repeat(4) {
            Row(
                Modifier.fillMaxWidth().topLine().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(HistoryLayout.DAY_GAP.dp),
            ) {
                Box(Modifier.width(layout.dateWidth).height(44.dp).clip(RoundedCornerShape(8.dp)).background(Ui.Sunken))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(HistoryLayout.CELL_GAP.dp)) {
                    repeat(layout.columns) { Box(Modifier.weight(1f).height(48.dp).clip(Ui.ButtonShape).background(Ui.Sunken)) }
                }
            }
        }
    }
}

/** SCR-008#error: the same words and button as SCR-004#error. */
@Composable
private fun FailedLines(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Words(
            Labels.LOAD_FAILED,
            Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f),
            Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        ActionButton("다시 시도", ButtonLook.Ink, onRetry)
    }
}
