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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.status.ClockWarning
import com.judangbang.pillbadeum.status.DayCell
import com.judangbang.pillbadeum.status.DayState
import com.judangbang.pillbadeum.status.IntakeStatus
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.StatusBoard
import com.judangbang.pillbadeum.status.WidgetLayout
import kotlin.math.sqrt
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/** What the first screen shows: `#loading`, `#error` or the board (SCR-004). */
internal sealed interface HomeState {
    data object Loading : HomeState

    /** The database could not be opened or read (SCR-004#error). */
    data object Failed : HomeState

    data class Ready(val board: StatusBoard) : HomeState
}

/** What the first screen's buttons do (screens.md SCR-004 table of actions). */
internal class HomeActions(
    val retry: () -> Unit,
    val history: () -> Unit,
    val settings: () -> Unit,
    val add: () -> Unit,
    val edit: (IntakeStatus) -> Unit,
    val more: (IntakeStatus) -> Unit,
    val record: (IntakeStatus) -> Unit,
    val void: (IntakeStatus) -> Unit,
    val acknowledgeRestore: () -> Unit,
    val keepClock: (ClockWarning) -> Unit,
    val keepAllClocks: (List<ClockWarning>) -> Unit,
    val cancelClock: (ClockWarning) -> Unit,
)

/**
 * SCR-004 기록 (오늘·이번 주), drawn as 09 BoxHome, HomeEmpty and HomeRestored. [zone] is the phone's zone, where
 * times are shown (REQ-082).
 */
@Composable
internal fun HomeScreen(state: HomeState, message: String?, zone: TimeZone, actions: HomeActions) {
    // The intake or judgment a question is open for; kept when the screen is recreated.
    var askVoid by rememberSaveable { mutableStateOf<String?>(null) }
    var askClock by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar("기록") {
            if (state is HomeState.Ready) {
                BarButton("지난 기록", actions.history)
                BarButton("설정", actions.settings)
            }
        }
        when (state) {
            HomeState.Loading -> Words(Labels.LOADING, Ui.type(16, color = Ui.Muted), Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            HomeState.Failed -> FailedBody(actions.retry)
            is HomeState.Ready -> ReadyBody(
                board = state.board,
                message = message,
                zone = zone,
                actions = actions,
                onAskVoid = { askVoid = it.intakeId },
                onAskClock = { askClock = it.judgment.id },
            )
        }
    }

    val board = (state as? HomeState.Ready)?.board
    askVoid?.let { id ->
        val item = board?.items?.firstOrNull { it.intakeId == id && it.isTaken }
        LaunchedEffect(board, id) { if (board != null && item == null) askVoid = null }
        if (item != null) {
            ConfirmDialog(
                title = "기록을 취소할까요?",
                body = "${item.name}의 이번 기록(${stampOf(item.judgment?.takenAt, board, zone)})을 무효로 바꿔요. " +
                    "기록은 지워지지 않고 이력에 남아요.",
                confirm = "기록 취소",
                onConfirm = { askVoid = null; actions.void(item) },
                onDismiss = { askVoid = null },
            )
        }
    }
    askClock?.let { id ->
        val warning = board?.clockWarnings?.firstOrNull { it.judgment.id == id }
        LaunchedEffect(board, id) { if (board != null && warning == null) askClock = null }
        if (warning != null) {
            ConfirmDialog(
                title = "기록을 취소할까요?",
                body = "${warning.intakeName}의 기록(${stampOf(warning.judgment.takenAt, board, zone)})을 무효로 바꿔요. " +
                    "기록은 지워지지 않고 이력에 남아요.",
                confirm = "기록 취소",
                onConfirm = { askClock = null; actions.cancelClock(warning) },
                onDismiss = { askClock = null },
            )
        }
    }
}

/** Clock warnings listed at once; answering one shows the next. */
private const val SHOWN_WARNINGS = 2

private fun stampOf(takenAt: Long?, board: StatusBoard, zone: TimeZone): String =
    takenAt?.let { Labels.stamp(Instant.fromEpochMilliseconds(it), board.now, zone) } ?: ""

@Composable
private fun FailedBody(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Ui.Side, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Words(
            Labels.LOAD_FAILED,
            Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f),
            Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        ActionButton("다시 시도", ButtonLook.Ink, onRetry)
    }
}

@Composable
private fun ReadyBody(
    board: StatusBoard,
    message: String?,
    zone: TimeZone,
    actions: HomeActions,
    onAskVoid: (IntakeStatus) -> Unit,
    onAskClock: (ClockWarning) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bodySm = with(LocalDensity.current) { 14.sp.toDp().value }
        val columns = HomeLayout.columns(maxWidth.value, bodySm)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = Ui.Side, end = Ui.Side, top = 4.dp, bottom = 24.dp),
        ) {
            val bands = board.restoreNotice != null || board.clockWarnings.isNotEmpty()
            if (bands) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    board.restoreNotice?.let { notice ->
                        // SCR-004#restored (REQ-073, REQ-074): at the very top until confirmed.
                        Band(Labels.restoreAppNotice(notice, zone)) {
                            ActionButton("확인했어요", ButtonLook.InkSmall, actions.acknowledgeRestore)
                        }
                    }
                    // Claude's choice (supplement C-3 한계, TASK-016): a clock set far back flags many good doses, so two
                    // or more warnings get one band that keeps them all, and the warnings are listed a few at a time.
                    val warnings = board.clockWarnings
                    if (warnings.size > 1) {
                        Band(Labels.clockWarnings(warnings.size, SHOWN_WARNINGS)) {
                            ActionButton("모두 그대로 두기", ButtonLook.InkSmall, { actions.keepAllClocks(warnings) })
                        }
                    }
                    warnings.take(SHOWN_WARNINGS).forEach { warning ->
                        // SCR-004#future (REQ-009, supplement C-2): stays until answered, even after its day comes.
                        Band(
                            Labels.clockWarning(warning.judgment, zone),
                            title = if (board.showPeople) "${warning.person.name} ${warning.intakeName}" else warning.intakeName,
                        ) {
                            ActionButton("그대로 두기", ButtonLook.InkSmall, { actions.keepClock(warning) })
                            ActionButton("기록 취소", ButtonLook.LineSmall, { onAskClock(warning) })
                        }
                    }
                }
            }
            Hero(board, Modifier.padding(top = if (bands) 20.dp else 0.dp))
            message?.let { Band(it, Modifier.padding(top = 12.dp), live = true) }

            if (board.items.isEmpty()) {
                EmptyBox(actions.add)
                Notes(board, zone, Modifier.padding(top = 24.dp))
                return@Column
            }
            SectionTitle("오늘")
            Column(verticalArrangement = Arrangement.spacedBy(HomeLayout.GAP.dp)) {
                board.items.chunked(columns).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(HomeLayout.GAP.dp),
                    ) {
                        row.forEach { item ->
                            Tile(
                                item = item,
                                board = board,
                                zone = zone,
                                minHeight = if (columns == 2) 148.dp else 0.dp,
                                actions = actions,
                                onAskVoid = onAskVoid,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            val daily = board.items.filter { it.rule is ScheduleRule.Daily }
            if (daily.isNotEmpty()) {
                SectionTitle("이번 주 약통")
                WeekBox(board, daily, namesAbove = columns == 1)
            }
            ActionButton("항목 추가", ButtonLook.Ink, actions.add, Modifier.padding(top = 16.dp)) { PlusIcon(Ui.OnSolid) }
            Notes(board, zone, Modifier.padding(top = 24.dp))
        }
    }
}

/** The weekday badge, the base date and the count (09 BoxHome `.hero`). */
@Composable
private fun Hero(board: StatusBoard, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Ui.Ink).hiddenFromTalkBack(),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(Labels.weekday(board.baseDate.dayOfWeek), style = Ui.type(24, Ui.ExtraBold, Ui.OnSolid, lineHeight = 1f))
        }
        Column(Modifier.weight(1f)) {
            Words(Labels.longDate(board.baseDate), Ui.type(20, Ui.ExtraBold, lineHeight = 1.3f))
            if (board.items.isEmpty()) {
                Words(Labels.dayStart(board), Ui.type(14, color = Ui.Muted))
            } else {
                val (done, rest) = Labels.countParts(board)
                val tail = "$rest · ${Labels.dayStart(board)}"
                Words(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Ui.Ink, fontWeight = Ui.ExtraBold)) { append(done) }
                        append(" ")
                        append(WidgetLayout.keepWords(tail))
                    },
                    plain = "$done $tail",
                    style = Ui.type(14, color = Ui.Muted),
                )
            }
        }
    }
}

/** One item's tile (09 BoxHome `.t`): pending, taken or skipped, with `[⋯]` at the top right. */
@Composable
private fun Tile(
    item: IntakeStatus,
    board: StatusBoard,
    zone: TimeZone,
    minHeight: Dp,
    actions: HomeActions,
    onAskVoid: (IntakeStatus) -> Unit,
    modifier: Modifier,
) {
    val person = personLook(item.person.color)
    val (fill, ink) = when {
        item.isTaken -> person.solid to Ui.OnSolid
        item.isSkipped -> Ui.Skip to Ui.Muted
        else -> person.tint to person.ink
    }
    val row = Labels.appRow(item, board, zone)
    // TalkBack reads the tile as one sentence, the widget's (REQ-068); the buttons stay stops of their own.
    val spoken = Labels.spoken(item, board.now, zone, withPerson = board.showPeople, name = item.name, inApp = true)
    Box(modifier.heightIn(min = minHeight).clip(Ui.Tile).background(fill).clickable(onClickLabel = "항목 수정") { actions.edit(item) }) {
        Column(Modifier.fillMaxHeight().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)) {
            Column(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // The name lines leave room for [⋯].
                if (board.showPeople) Words(item.person.name, Ui.type(14, Ui.SemiBold, ink), Modifier.padding(end = 34.dp))
                Words(item.name, Ui.type(18, Ui.ExtraBold, ink, lineHeight = 1.3f), Modifier.padding(end = 34.dp))
                row.lines.forEach { (line, bold) ->
                    Words(line, Ui.type(14, if (bold) Ui.ExtraBold else FontWeight.Normal, ink, lineHeight = 1.35f))
                }
                if (item.clockWarning) Words(Labels.CLOCK_MARK, Ui.type(14, Ui.ExtraBold, ink, lineHeight = 1.35f))
            }
            Spacer(Modifier.weight(1f))
            when {
                item.isTaken -> ActionButton("기록 취소", ButtonLook.OnSolid, { onAskVoid(item) }, Modifier.padding(top = 8.dp))
                item.isSkipped -> Unit // SCR-004#skipped: [⋯] only (REQ-033)
                else -> ActionButton("먹었어", ButtonLook.person(person.solid), { actions.record(item) }, Modifier.padding(top = 8.dp))
            }
        }
        // 48 dp, not the 44 px of 09 BoxHome `.mo`: every target is at least 48 dp (REQ-067). The dots stay where 09 has them.
        MoreButton(item.name, ink, { actions.more(item) }, Modifier.align(Alignment.TopEnd).padding(top = 2.dp))
    }
}

/** `[⋯]`: opens SCR-007 for the tile's window (screens.md 5장 4번). */
@Composable
private fun MoreButton(name: String, color: Color, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$name 판정 고치기" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            for (x in listOf(4f, 10f, 16f)) drawCircle(color, radius = 1.8.dp.toPx(), center = Offset(x.dp.toPx(), 10.dp.toPx()))
        }
    }
}

/**
 * `이번 주 약통` (09 BoxHome `.wk`): daily items, Monday to Sunday (REQ-035). Each row is read as one sentence
 * ([Labels.weekSpoken]). With large text the names go above their cells.
 */
@Composable
private fun WeekBox(board: StatusBoard, daily: List<IntakeStatus>, namesAbove: Boolean) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Ui.LineSubtle, Ui.Tile)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().hiddenFromTalkBack(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!namesAbove) Spacer(Modifier.width(64.dp))
            board.week.forEach { day ->
                val today = day == board.baseDate
                Words(
                    Labels.weekday(day.dayOfWeek),
                    Ui.type(14, if (today) Ui.ExtraBold else Ui.SemiBold, if (today) Ui.Ink else Ui.Muted, center = true),
                    Modifier.weight(1f),
                )
            }
        }
        daily.forEach { item ->
            val solid = personLook(item.person.color).solid
            val sentence = Labels.weekSpoken(item, withPerson = board.showPeople)
            Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = sentence }) {
                if (namesAbove) Words(item.name, Ui.type(14, Ui.SemiBold), Modifier.padding(top = 6.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!namesAbove) Words(item.name, Ui.type(14, Ui.SemiBold, lineHeight = 1.25f), Modifier.width(64.dp), maxLines = 1)
                    item.weekCells.forEach { cell -> Box(Modifier.weight(1f).height(28.dp).drawBehind { drawCell(cell, solid) }) }
                }
            }
        }
    }
}

/** One day of the week box (box.css `.cell`): taken, skipped (stripes), missed, today pending, later. */
private fun DrawScope.drawCell(cell: DayCell, solid: Color) {
    val radius = 8.dp.toPx()
    if (cell.isToday) {
        // `.now`: a 2 dp white gap and a 2 dp ink ring outside the cell.
        val outer = 4.dp.toPx()
        val inner = 2.dp.toPx()
        drawRoundRect(Ui.Ink, Offset(-outer, -outer), Size(size.width + 2 * outer, size.height + 2 * outer), CornerRadius(radius + outer))
        drawRoundRect(Ui.Surface, Offset(-inner, -inner), Size(size.width + 2 * inner, size.height + 2 * inner), CornerRadius(radius + inner))
    }
    val corner = CornerRadius(radius)
    when (cell.state) {
        DayState.DONE -> drawRoundRect(solid, cornerRadius = corner)
        DayState.SKIPPED -> clipPath(Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), corner)) }) {
            // repeating-linear-gradient(135deg, line-strong 0 2px, skip 2px 7px)
            drawRect(Ui.Skip)
            val root2 = sqrt(2f)
            val step = 7.dp.toPx() * root2
            var c = 1.dp.toPx() * root2
            while (c < size.width + size.height + step) {
                drawLine(Ui.LineStrong, Offset(c, 0f), Offset(0f, c), strokeWidth = 2.dp.toPx())
                c += step
            }
        }
        DayState.EMPTY -> {
            drawRoundRect(Ui.Surface, cornerRadius = corner)
            val w = 1.5.dp.toPx()
            drawRoundRect(Ui.LineStrong, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(radius - w / 2), style = Stroke(w))
        }
        DayState.TODAY_PENDING -> {
            drawRoundRect(Ui.Surface, cornerRadius = corner)
            val w = 2.dp.toPx()
            drawRoundRect(
                solid,
                Offset(w / 2, w / 2),
                Size(size.width - w, size.height - w),
                CornerRadius(radius - w / 2),
                style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
        DayState.FUTURE, DayState.NOT_SCHEDULED -> drawRoundRect(Ui.Sunken, cornerRadius = corner)
    }
}

/** SCR-004#empty (09 HomeEmpty): seven empty lids and `[항목 추가]`. */
@Composable
private fun EmptyBox(onAdd: () -> Unit) {
    Column(
        Modifier.padding(top = 24.dp).fillMaxWidth()
            .dashedBorder(1.5.dp, Ui.LineStrong, 18.dp, dash = 5.dp, gap = 4.dp)
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 20.dp).hiddenFromTalkBack(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(7) { Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(8.dp)).background(Ui.Sunken)) }
        }
        Words("아직 복용 항목이 없어요.", Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f, center = true), Modifier.padding(bottom = 6.dp))
        Words("[항목 추가]로 등록해 주세요.", Ui.type(16, color = Ui.Muted, center = true))
        ActionButton("항목 추가", ButtonLook.Ink, onAdd, Modifier.padding(top = 20.dp)) { PlusIcon(Ui.OnSolid, 18.dp) }
    }
}

/**
 * The notes at the bottom: the medical note (REQ-036) and, only while the phone's zone is not the base zone, which zone
 * dates are counted in (REQ-082; Claude's choice, the 09 boards leave it out).
 */
@Composable
private fun Notes(board: StatusBoard, zone: TimeZone, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val base = board.settings.baseZone.id
        if (zone.id != base) Words("날짜는 기준 시간대($base)로 셈해요.", Ui.type(14, color = Ui.Muted))
        Words(Labels.MEDICAL_NOTE, Ui.type(14, color = Ui.Muted))
    }
}
