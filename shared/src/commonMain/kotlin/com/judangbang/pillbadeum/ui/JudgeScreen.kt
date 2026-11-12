package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.judangbang.pillbadeum.status.JudgeCell
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.PickRange
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** What SCR-007 shows: the cell, or a read that failed. */
internal sealed interface JudgeState {
    data object Loading : JudgeState

    /** The cell could not be read: `[다시 시도]`, as SCR-004#error. */
    data object Failed : JudgeState

    data class Ready(val cell: JudgeCell) : JudgeState
}

/** What SCR-007's buttons do (screens.md SCR-007 table). A picked time is an instant on the phone's clock. */
internal class JudgeActions(
    val back: () -> Unit,
    val retry: () -> Unit,
    /** `[지금 먹었어]`, current window only (P-2). */
    val recordNow: () -> Unit,
    /** `[먹은 시각 고르기]` and then `[이 시각으로 기록]`. */
    val recordAt: (Instant) -> Unit,
    val skip: () -> Unit,
    /** `[먹은 시각 고치기]` and then `[이 시각으로 고치기]`. */
    val editTime: (Instant) -> Unit,
    /** `[먹었어로 바꾸기]`: null is now (current window), else the picked time (REQ-034). */
    val takeInstead: (Instant?) -> Unit,
    /** `[기록 취소]` or `[건너뜀 취소]`, after the question. */
    val void: () -> Unit,
    /** `[그대로 두기]` of a clock warning (SCR-007#future). */
    val keepClock: () -> Unit,
    /** `[기록 취소]` of a clock warning, after the question. */
    val cancelClock: () -> Unit,
)

/** What the time picker is choosing for. */
private enum class Picking { RECORD, EDIT, CHANGE }

/**
 * SCR-007 판정 고치기, drawn as 09 Judge and JudgeTaken and the TASK-044 drawings of past windows. The picker that
 * `[먹은 시각 고르기]`, `[먹은 시각 고치기]` and a past window's `[먹었어로 바꾸기]` open is Claude's design (the 09 boards
 * do not draw it): a date, an hour and a minute in five-minute steps, each a 48 dp cell, then a confirm button. [clock] is
 * the app's clock: the picker offers times up to when it opened.
 */
@Composable
internal fun JudgeScreen(state: JudgeState, message: String?, zone: TimeZone, clock: () -> Instant, actions: JudgeActions) {
    val cell = (state as? JudgeState.Ready)?.cell
    Column(Modifier.fillMaxSize()) {
        TopBar(cell?.let(Labels::judgeTitle) ?: "판정 고치기", navigation = { BarIconButton("뒤로", close = false, actions.back) })
        when (state) {
            JudgeState.Loading -> Words(Labels.LOADING, Ui.type(16, color = Ui.Muted), Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            JudgeState.Failed -> Column(
                Modifier.fillMaxWidth().padding(horizontal = Ui.Side, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Words(
                    Labels.LOAD_FAILED,
                    Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f),
                    Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                ActionButton("다시 시도", ButtonLook.Ink, actions.retry)
            }
            is JudgeState.Ready -> JudgeBody(state.cell, message, zone, clock, actions)
        }
    }
}

/**
 * The cell, then its buttons or the picker, and the questions. Its state is kept when the screen is recreated (BUG-003)
 * and starts again when the cell's judgment changes: a write that landed closes the picker and the question.
 */
@Composable
private fun JudgeBody(cell: JudgeCell, message: String?, zone: TimeZone, clock: () -> Instant, actions: JudgeActions) {
    val judgment = cell.judgment
    val judgmentKey = judgment?.id ?: ""
    var picking by rememberSaveable(judgmentKey) { mutableStateOf<String?>(null) }
    var pickOpenedAt by rememberSaveable(judgmentKey) { mutableStateOf(0L) }
    var ask by rememberSaveable(judgmentKey) { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val large = HomeLayout.columns(maxWidth.value, with(LocalDensity.current) { 14.sp.toDp().value }) == 1
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = Ui.Side, end = Ui.Side, top = 4.dp, bottom = 24.dp),
        ) {
            // The #locked band already says what a refused write would say.
            if (message != null && !(cell.locked && message == Labels.LOCKED)) {
                Band(message, Modifier.padding(bottom = 8.dp), live = true)
            }
            JudgeTile(cell, zone)
            val kind = picking?.let { name -> Picking.entries.firstOrNull { it.name == name } }
            if (kind != null && !cell.locked) {
                val at = Instant.fromEpochMilliseconds(pickOpenedAt)
                TimePicker(
                    kind = kind,
                    range = if (kind == Picking.EDIT) cell.editRange(at, zone) else cell.recordRange(at, zone),
                    initial = if (kind == Picking.EDIT) judgment?.takenAt?.let(Instant::fromEpochMilliseconds) else null,
                    preferLast = cell.current,
                    solid = personLook(cell.person.color).solid,
                    large = large,
                    onConfirm = { time ->
                        when (kind) {
                            Picking.RECORD -> actions.recordAt(time)
                            Picking.EDIT -> actions.editTime(time)
                            Picking.CHANGE -> actions.takeInstead(time)
                        }
                    },
                    onCancel = { picking = null },
                )
            } else {
                Choices(
                    cell = cell,
                    zone = zone,
                    actions = actions,
                    onPick = {
                        pickOpenedAt = clock().toEpochMilliseconds()
                        picking = it.name
                    },
                    onAsk = { ask = it },
                )
            }
            val base = cell.settings.baseZone.id
            if (zone.id != base) Note("날짜는 기준 시간대($base)로 셈해요.", Modifier.padding(top = 16.dp))
        }
    }

    if (ask != null && judgment != null) {
        val skip = cell.isSkipped
        val clockQuestion = ask == ASK_CLOCK
        ConfirmDialog(
            title = if (skip && !clockQuestion) "건너뜀을 취소할까요?" else "기록을 취소할까요?",
            body = if (skip) {
                "${cell.intakeName}의 건너뜀을 무효로 바꿔요. 기록은 지워지지 않고 이력에 남아요."
            } else {
                "${cell.intakeName}의 기록(${Labels.cellTime(cell, Instant.fromEpochMilliseconds(judgment.takenAt), zone)})을 무효로 바꿔요. " +
                    "기록은 지워지지 않고 이력에 남아요."
            },
            confirm = if (skip && !clockQuestion) "건너뜀 취소" else "기록 취소",
            onConfirm = {
                ask = null
                if (clockQuestion) actions.cancelClock() else actions.void()
            },
            onDismiss = { ask = null },
        )
    }
}

private const val ASK_VOID = "void"
private const val ASK_CLOCK = "clock"

/** The cell (09 Judge `.tile`): the person, the item, its cycle, the window and the judgment. */
@Composable
private fun JudgeTile(cell: JudgeCell, zone: TimeZone) {
    val person = personLook(cell.person.color)
    val (fill, ink) = when {
        cell.isTaken -> person.solid to Ui.OnSolid
        cell.isSkipped -> Ui.Skip to Ui.Muted
        else -> person.tint to person.ink
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(Ui.Tile).background(fill).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (cell.showPeople) Words(cell.person.name, Ui.type(14, Ui.SemiBold, ink))
        Words(cell.intakeName, Ui.type(24, Ui.ExtraBold, ink, lineHeight = 1.3f))
        Words(Labels.judgeCycle(cell.rule, cell.slotLabel), Ui.type(14, color = ink), spoken = Labels.schedule(cell.rule, cell.slotLabel))
        Words(
            Labels.judgeWindow(cell, zone),
            Ui.type(14, color = ink),
            Modifier.padding(top = 10.dp),
            spoken = Labels.judgeWindowSpoken(cell, zone),
        )
        // Read out when it changes, so TalkBack hears what a button did.
        Words(
            Labels.judgeStatus(cell, zone),
            Ui.type(18, Ui.ExtraBold, ink, lineHeight = 1.3f),
            Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** The buttons for the cell's judgment (screens.md SCR-007 table, 09 Judge and JudgeTaken, TASK-044 drawings). */
@Composable
private fun Choices(cell: JudgeCell, zone: TimeZone, actions: JudgeActions, onPick: (Picking) -> Unit, onAsk: (String) -> Unit) {
    val solid = personLook(cell.person.color).solid
    val judgment = cell.judgment
    // SCR-007#future (REQ-009, supplement C-3): the warning and its two answers; a locked cell keeps only [그대로 두기].
    if (cell.clockWarning && judgment != null) {
        Band(Labels.clockWarning(judgment, zone), Modifier.padding(top = 20.dp)) {
            ActionButton("그대로 두기", ButtonLook.InkSmall, actions.keepClock)
            if (!cell.locked) ActionButton("기록 취소", ButtonLook.LineSmall, { onAsk(ASK_CLOCK) })
        }
    }
    if (cell.locked) {
        // SCR-007#locked (REQ-032, P-5): no buttons.
        Band("${Labels.LOCKED}.", Modifier.padding(top = 20.dp))
        Note(Labels.judgeNote(cell, zone), Modifier.padding(top = 24.dp).padding(horizontal = 4.dp))
        return
    }
    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            judgment == null -> {
                if (cell.current) ActionButton("지금 먹었어", ButtonLook.person(solid), actions.recordNow)
                ActionButton("먹은 시각 고르기", if (cell.current) ButtonLook.Line else ButtonLook.person(solid), { onPick(Picking.RECORD) })
                ActionButton("건너뜀", ButtonLook.Line, actions.skip)
            }
            cell.isTaken -> {
                ActionButton("먹은 시각 고치기", ButtonLook.Line, { onPick(Picking.EDIT) })
                // With a clock warning the band's [기록 취소] is the one (C-3).
                if (!cell.clockWarning) ActionButton("기록 취소", ButtonLook.Danger, { onAsk(ASK_VOID) })
            }
            else -> {
                // A cut cell's window is the new version's: its skip can only be cancelled (P-8).
                if (!cell.cut) {
                    ActionButton(
                        "먹었어로 바꾸기",
                        ButtonLook.person(solid),
                        { if (cell.current) actions.takeInstead(null) else onPick(Picking.CHANGE) },
                    )
                }
                if (!cell.clockWarning) ActionButton("건너뜀 취소", ButtonLook.Danger, { onAsk(ASK_VOID) })
            }
        }
        Note(Labels.judgeNote(cell, zone), Modifier.padding(top = 4.dp).padding(horizontal = 4.dp))
    }
}

/**
 * The time picker (Claude's design): the dates the range spans when more than one, the hours of the chosen date that have a
 * time, and the minutes in five-minute steps, each a 48 dp cell read as `21시`, `40분`. Nothing is chosen at first, except
 * the stored time for a time edit. [preferLast] starts on the last date (the current window's today).
 */
@Composable
private fun TimePicker(
    kind: Picking,
    range: PickRange,
    initial: Instant?,
    preferLast: Boolean,
    solid: Color,
    large: Boolean,
    onConfirm: (Instant) -> Unit,
    onCancel: () -> Unit,
) {
    val start = initial?.let(range::floor)?.toLocalDateTime(range.zone)
    var dateText by rememberSaveable { mutableStateOf(start?.date?.toString()) }
    var hour by rememberSaveable { mutableStateOf(start?.hour ?: NONE) }
    var minute by rememberSaveable { mutableStateOf(start?.minute ?: NONE) }
    val dates = range.dates
    val date = dateText?.let(LocalDate::parse)?.takeIf { it in dates } ?: if (preferLast) dates.lastOrNull() else dates.firstOrNull()
    val hours = date?.let(range::hours).orEmpty()
    val minutes = if (date != null && hour in hours) range.minutes(date, hour) else emptyList()
    val chosen = if (date != null && hour in hours && minute in minutes) range.at(date, hour, minute) else null

    Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Words(if (kind == Picking.EDIT) "먹은 시각 고치기" else "먹은 시각 고르기", Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f), heading = true)
        Note(Labels.pickNote(range, edit = kind == Picking.EDIT), Modifier.padding(horizontal = 4.dp))
        if (date == null) {
            ActionButton("닫기", ButtonLook.Line, onCancel)
            return@Column
        }
        if (dates.size > 1) {
            PickLabel("날짜")
            PickGrid(dates, columns = if (large) 1 else 2) { d, modifier ->
                TimeCell(Labels.date(d), Labels.longDate(d), d == date, enabled = true, modifier) {
                    dateText = d.toString()
                    if (hour !in range.hours(d)) hour = NONE
                    if (hour == NONE || minute !in range.minutes(d, hour)) minute = NONE
                }
            }
        }
        PickLabel("시")
        PickGrid(hours, columns = if (large) 4 else 6) { h, modifier ->
            TimeCell(two(h), "${h}시", h == hour, enabled = true, modifier) {
                hour = h
                if (minute !in range.minutes(date, h)) minute = NONE
            }
        }
        PickLabel("분")
        PickGrid(PickRange.MINUTES, columns = if (large) 4 else 6) { m, modifier ->
            // Before an hour is chosen every minute can be; afterwards only that hour's.
            val allowed = hour == NONE || m in minutes
            TimeCell(two(m), "${m}분", m == minute, enabled = allowed, modifier) { minute = m }
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp)) {
            Words("고른 시각", Ui.type(14, color = Ui.Muted))
            Words(
                chosen?.let { Labels.picked(it, range.zone) } ?: "시와 분을 골라 주세요",
                Ui.type(18, Ui.ExtraBold, if (chosen != null) Ui.Ink else Ui.Muted, lineHeight = 1.3f),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val confirm = when (kind) {
            Picking.RECORD -> "이 시각으로 기록"
            Picking.EDIT -> "이 시각으로 고치기"
            Picking.CHANGE -> "이 시각으로 바꾸기"
        }
        ActionButton(confirm, ButtonLook.person(solid), { chosen?.let(onConfirm) }, enabled = chosen != null)
        ActionButton("취소", ButtonLook.Line, onCancel)
    }
}

private const val NONE = -1

private fun two(n: Int): String = n.toString().padStart(2, '0')

@Composable
private fun PickLabel(text: String) {
    Words(text, Ui.type(15, Ui.SemiBold, lineHeight = 1.25f), Modifier.padding(top = 8.dp, start = 4.dp))
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Words(text, Ui.type(14, color = Ui.Muted), modifier)
}

/** One date, hour or minute (box.css `.pick .c`): ink when chosen, sunken when it cannot be chosen. */
@Composable
private fun TimeCell(label: String, spoken: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (fill, border, ink) = when {
        selected -> Triple(Ui.Ink, Ui.Ink, Ui.OnSolid)
        enabled -> Triple(Ui.Surface, Ui.LineStrong, Ui.Ink)
        else -> Triple(Ui.Sunken, Ui.Sunken, Ui.Muted)
    }
    Box(
        modifier.heightIn(min = 48.dp).clip(Ui.ButtonShape).background(fill).border(1.5.dp, border, Ui.ButtonShape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = spoken }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Words(label, Ui.type(16, if (selected) Ui.ExtraBold else Ui.SemiBold, ink, lineHeight = 1.25f, center = true), Modifier.hiddenFromTalkBack())
    }
}
