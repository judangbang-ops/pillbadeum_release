package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.status.DayState
import com.judangbang.pillbadeum.status.IntakeStatus
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.StatusBoard
import com.judangbang.pillbadeum.status.showsPeople
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import com.judangbang.pillbadeum.usecase.VoidResult
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone

private object Palette {
    val Background = Color(0xFFEEF1F4)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1C2129)
    val Muted = Color(0xFF5A6372)
    val Primary = Color(0xFF1D4E89)
    val Line = Color(0xFFC3CAD3)
    val Dashed = Color(0xFF8E98A6)
    val Cell = Color(0xFFE1E5EA)
    val CellOff = Color(0xFFF4F6F8)
    val Warn = Color(0xFFFCEBD9)
    val WarnInk = Color(0xFF7A3E06)
}

private fun personColor(hex: String): Color = Color(PersonColors.argb(hex))

private sealed interface Screen {
    data object Home : Screen

    /** [editing] null = new item. */
    data class Edit(val editing: IntakeStatus?) : Screen
}

/**
 * Root screen shared by every platform.
 *
 * @param onDataChanged called after every write (record, void, item save/archive) so the platform
 *   can refresh its widgets from the database.
 * @param refreshKey bump to reload (e.g. on resume, after the widget recorded something).
 */
@Composable
fun App(core: DidITakeCore, onDataChanged: suspend () -> Unit, refreshKey: Int = 0) {
    val scope = rememberCoroutineScope()
    var board by remember { mutableStateOf<StatusBoard?>(null) }
    var people by remember { mutableStateOf<List<PersonEntity>>(emptyList()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        people = core.people.list()
        board = core.loadStatusBoard()
    }

    LaunchedEffect(refreshKey) { reload() }

    Box(Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding()) {
        when (val current = screen) {
            Screen.Home -> HomeScreen(
                board = board,
                message = message,
                onAdd = { message = null; screen = Screen.Edit(null) },
                onEdit = { message = null; screen = Screen.Edit(it) },
                onRecord = { item ->
                    scope.launch {
                        val result = core.record(item.intakeId, DoseSource.APP, item.window.periodKey)
                        val b = board
                        message = if (b == null) null else Labels.recordMessage(result, item.name, core.clock.now(), TimeZone.currentSystemDefault())
                        onDataChanged()
                        reload()
                    }
                },
                onAcknowledgeRestore = {
                    scope.launch {
                        // SCR-004 [확인했어요]: a failed save keeps the notice (screens.md 4장).
                        message = if (runCatching { core.acknowledgeRestore() }.isSuccess) null else "저장하지 못했어요. 다시 눌러 주세요"
                        onDataChanged()
                        reload()
                    }
                },
                onVoid = { item ->
                    val judgment = item.judgment ?: return@HomeScreen
                    scope.launch {
                        message = when (core.voidDose(judgment.id)) {
                            VoidResult.Voided -> "${item.name} 기록을 취소했어요. 기록은 무효로 남아요"
                            VoidResult.NotActive -> "이미 취소된 기록이에요"
                            VoidResult.Locked -> Labels.LOCKED
                        }
                        onDataChanged()
                        reload()
                    }
                },
            )
            is Screen.Edit -> EditScreen(
                editing = current.editing,
                people = people,
                onAddPerson = { name, color, onAdded ->
                    scope.launch {
                        val added = runCatching { core.people.add(name, color) }.getOrNull()
                        people = core.people.list()
                        added?.let { onAdded(it.id) }
                    }
                },
                onCancel = { screen = Screen.Home },
                onSave = { draft ->
                    scope.launch {
                        val editing = current.editing
                        val result = if (editing == null) core.saveIntake.create(draft) else core.saveIntake.update(editing.intakeId, draft)
                        message = when (result) {
                            is SaveIntakeResult.Saved -> null
                            is SaveIntakeResult.Invalid -> "이름과 대상자를 확인해 주세요"
                            SaveIntakeResult.Gone -> "이미 삭제된 항목이에요"
                        }
                        if (result !is SaveIntakeResult.Invalid) {
                            onDataChanged()
                            reload()
                            screen = Screen.Home
                        }
                    }
                },
                onArchive = { item ->
                    scope.launch {
                        core.archiveIntake(item.intakeId)
                        message = "${item.name} 항목을 삭제했어요. 지난 기록은 남아요"
                        onDataChanged()
                        reload()
                        screen = Screen.Home
                    }
                },
                error = message,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Home: 오늘 · 이번 주

@Composable
private fun HomeScreen(
    board: StatusBoard?,
    message: String?,
    onAdd: () -> Unit,
    onEdit: (IntakeStatus) -> Unit,
    onRecord: (IntakeStatus) -> Unit,
    onAcknowledgeRestore: () -> Unit,
    onVoid: (IntakeStatus) -> Unit,
) {
    var confirmVoid by remember { mutableStateOf<IntakeStatus?>(null) }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("기록", size = 26, bold = true, modifier = Modifier.weight(1f))
            DarkButton("항목 추가", onAdd)
        }
        if (board == null) {
            Text("불러오는 중…", color = Palette.Muted)
            return@Column
        }
        board.restoreNotice?.let { notice ->
            // SCR-004#restored (supplement R-3, REQ-073, REQ-074): at the very top until confirmed.
            RestoreNoticeCard(Labels.restoreAppNotice(notice, TimeZone.currentSystemDefault()), onAcknowledgeRestore)
        }
        Text("${Labels.date(board.baseDate)} 기준 · ${Labels.count(board)}", color = Palette.Primary, bold = true)
        message?.let { Notice(it) }

        Text("오늘 · 이번 주", color = Palette.Muted)
        if (board.items.isEmpty()) {
            Card { Text("아직 복용 항목이 없어요. [항목 추가]로 등록해 주세요.", color = Palette.Muted) }
        }
        val zone = TimeZone.currentSystemDefault()
        board.items.forEach { item ->
            IntakeRow(
                item = item,
                // The row layout of the 09 mockup comes with TASK-016; until then the parts are joined.
                detail = Labels.appRow(item, board, zone)
                    .let { row -> listOfNotNull(row.detail, row.boldLine, row.skipLine).joinToString(" · ") },
                showPerson = board.showPeople,
                spoken = Labels.spoken(item, board.now, zone, withPerson = board.showPeople, name = item.name),
                onClick = { onEdit(item) },
                onRecord = { onRecord(item) },
                onVoid = { confirmVoid = item },
            )
        }

        val daily = board.items.filter { it.rule is ScheduleRule.Daily }
        if (daily.isNotEmpty()) {
            Text("이번 주", color = Palette.Muted)
            WeekGrid(board, daily)
        }

        Card {
            Row(Modifier.fillMaxWidth()) {
                Text("하루 시작 ${board.settings.dayStart}", color = Palette.Muted, size = 13, modifier = Modifier.weight(1f))
                Text("기준 시간대 ${board.settings.baseZone.id}", color = Palette.Muted, size = 13)
            }
        }
        Text(
            "기록 취소는 앱에서만 할 수 있어요. 취소해도 기록은 지워지지 않고 무효로 남아요. " +
                "이 앱은 기록 도구이며 복용 방법은 의사·약사와 상의해 주세요.",
            color = Palette.Muted,
            size = 12,
        )
    }

    confirmVoid?.let { item ->
        ConfirmDialog(
            title = "기록을 취소할까요?",
            body = "${item.name}의 이번 기록(${stampOf(item, board)})을 무효로 바꿔요. " +
                "기록은 지워지지 않고 이력에 남아요.",
            confirm = "기록 취소",
            onConfirm = { confirmVoid = null; onVoid(item) },
            onDismiss = { confirmVoid = null },
        )
    }
}

private fun stampOf(item: IntakeStatus, board: StatusBoard?): String {
    val judgment = item.judgment ?: return ""
    if (board == null) return ""
    return Labels.stamp(Instant.fromEpochMilliseconds(judgment.takenAt), board.now, TimeZone.currentSystemDefault())
}

/**
 * One item row. [showPerson]: two or more people are active, so the person badge is drawn (REQ-018).
 * [spoken] is what TalkBack reads for the row, the widget's sentence ([Labels.spoken], REQ-068): the row's
 * own texts are not read, and the button stays a stop of its own.
 */
@Composable
private fun IntakeRow(
    item: IntakeStatus,
    detail: String,
    showPerson: Boolean,
    spoken: String,
    onClick: () -> Unit,
    onRecord: () -> Unit,
    onVoid: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(done = item.isDone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showPerson) {
                        PersonTag(item.person)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(item.name, bold = true, size = 15)
                }
                Text(detail, color = Palette.Muted, size = 12)
            }
        }
        if (item.isDone) LineButton("기록 취소", onVoid) else LineButton("먹었어", onRecord, primary = true)
    }
}

@Composable
private fun StatusDot(done: Boolean) {
    if (done) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.Primary), contentAlignment = Alignment.Center) {
            Text("✓", color = Color.White, bold = true)
        }
    } else {
        Canvas(Modifier.size(36.dp)) {
            val stroke = 2.dp.toPx()
            drawCircle(
                color = Palette.Dashed,
                radius = size.minDimension / 2 - stroke,
                style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
            )
        }
    }
}

@Composable
private fun PersonTag(person: PersonEntity) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(personColor(person.color)).padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(person.name, color = Color.White, size = 11, bold = true)
    }
}

@Composable
private fun WeekGrid(board: StatusBoard, daily: List<IntakeStatus>) {
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(64.dp))
                board.week.forEach { day ->
                    Text(Labels.weekday(day.dayOfWeek), color = Palette.Muted, size = 12, align = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            daily.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.displayName, size = 13, modifier = Modifier.width(64.dp))
                    item.weekCells.forEach { cell ->
                        val color = when (cell.state) {
                            DayState.DONE -> Palette.Primary
                            DayState.EMPTY, DayState.TODAY_PENDING -> Palette.Cell
                            DayState.FUTURE, DayState.NOT_SCHEDULED -> Palette.CellOff
                        }
                        var cellModifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(color)
                        if (cell.isToday) cellModifier = cellModifier.border(2.dp, Palette.Ink, RoundedCornerShape(6.dp))
                        Box(cellModifier)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Registration / edit

@Composable
private fun EditScreen(
    editing: IntakeStatus?,
    people: List<PersonEntity>,
    /** Adds a person; `onAdded` gets the new person's id, which the form then selects. */
    onAddPerson: (name: String, color: String, onAdded: (String) -> Unit) -> Unit,
    onCancel: () -> Unit,
    onSave: (IntakeDraft) -> Unit,
    onArchive: (IntakeStatus) -> Unit,
    error: String?,
) {
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var alias by remember { mutableStateOf(editing?.widgetAlias ?: "") }
    var personId by remember { mutableStateOf(editing?.person?.id ?: people.firstOrNull()?.id) }
    var weekly by remember { mutableStateOf(editing?.rule is ScheduleRule.Weekly) }
    var anchor by remember { mutableStateOf((editing?.rule as? ScheduleRule.Weekly)?.anchor ?: DayOfWeek.MONDAY) }
    var slot by remember { mutableStateOf(editing?.slotLabel) }
    var addingPerson by remember { mutableStateOf(false) }
    var newPersonName by remember { mutableStateOf("") }
    var newPersonColor by remember { mutableStateOf(PersonColors.PALETTE[1]) }
    var confirmArchive by remember { mutableStateOf(false) }
    if (personId == null && people.isNotEmpty()) personId = people.first().id

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(if (editing == null) "항목 추가" else "항목 수정", size = 26, bold = true)
        error?.let { Notice(it) }

        Field("이름", name, { name = it }, "예: 아침약")
        Field("위젯 별칭 (선택)", alias, { alias = it }, "위젯에 짧게 보일 이름")

        // With one active person the person field is folded to [+ 가족 추가] alone (REQ-018, SCR-006 rule).
        val showPeople = showsPeople(people.size)
        if (showPeople) Text("대상자", color = Palette.Muted)
        Chips {
            if (showPeople) {
                people.forEach { p ->
                    Chip(p.name, selected = p.id == personId, accent = personColor(p.color)) { personId = p.id }
                }
            }
            Chip("+ 가족 추가", selected = addingPerson) { addingPerson = !addingPerson }
        }
        if (addingPerson) {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("이름", newPersonName, { newPersonName = it }, "예: 엄마")
                    Chips {
                        PersonColors.PALETTE.forEach { hex ->
                            val selected = hex == newPersonColor
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(personColor(hex))
                                    .border(if (selected) 3.dp else 0.dp, Palette.Ink, CircleShape)
                                    .clickable { newPersonColor = hex },
                            )
                        }
                    }
                    DarkButton("추가") {
                        if (newPersonName.isNotBlank()) {
                            // The new person is who the item is for, so it is selected (Claude's choice, TASK-015).
                            onAddPerson(newPersonName, newPersonColor) { id -> personId = id }
                            newPersonName = ""
                            addingPerson = false
                        }
                    }
                }
            }
        }

        Text("주기", color = Palette.Muted)
        Chips {
            Chip("매일", selected = !weekly) { weekly = false }
            Chip("주 1회", selected = weekly) { weekly = true }
        }
        if (weekly) {
            Text("기준 요일 (그 요일 하루 시작부터 7일)", color = Palette.Muted, size = 13)
            Chips {
                DayOfWeek.entries.forEach { day ->
                    Chip(Labels.weekday(day), selected = day == anchor) { anchor = day }
                }
            }
        } else {
            Text("시간대 (표시용)", color = Palette.Muted, size = 13)
            Chips {
                Chip("없음", selected = slot == null) { slot = null }
                SlotLabel.entries.forEach { s -> Chip(Labels.slot(s), selected = s == slot) { slot = s } }
            }
        }
        if (editing != null) {
            Text("주기를 바꾸면 지금부터 새 일정이 적용돼요. 이번 창의 기록은 그대로 인정돼요.", color = Palette.Muted, size = 12)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineButton("닫기", onCancel)
            DarkButton("저장") {
                val pid = personId ?: return@DarkButton
                onSave(
                    IntakeDraft(
                        personId = pid,
                        name = name,
                        widgetAlias = alias,
                        rule = if (weekly) ScheduleRule.Weekly(anchor) else ScheduleRule.Daily,
                        slotLabel = slot,
                    ),
                )
            }
        }
        if (editing != null) {
            LineButton("항목 삭제", onClick = { confirmArchive = true })
            Text("삭제해도 지난 기록은 지워지지 않아요.", color = Palette.Muted, size = 12)
        }
    }

    if (confirmArchive && editing != null) {
        ConfirmDialog(
            title = "항목을 삭제할까요?",
            body = "${editing.name}이(가) 위젯과 목록에서 사라져요. 지난 기록은 남아요.",
            confirm = "삭제",
            onConfirm = { confirmArchive = false; onArchive(editing) },
            onDismiss = { confirmArchive = false },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Small building blocks (foundation only, no Material dependency)

@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Ink,
    size: Int = 15,
    bold: Boolean = false,
    align: TextAlign = TextAlign.Start,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = align,
            lineHeight = (size * 1.4).sp,
        ),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card).padding(14.dp)) { content() }
}

@Composable
private fun Notice(text: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Warn).padding(12.dp)) {
        Text(text, color = Palette.WarnInk, size = 13)
    }
}

@Composable
private fun RestoreNoticeCard(text: String, onAcknowledge: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Warn).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text, color = Palette.WarnInk, size = 15, bold = true)
        DarkButton("확인했어요", onAcknowledge)
    }
}

@Composable
private fun DarkButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Ink)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, bold = true)
    }
}

@Composable
private fun LineButton(label: String, onClick: () -> Unit, primary: Boolean = false) {
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (primary) Palette.Primary else Palette.Card)
            .border(1.dp, if (primary) Palette.Primary else Palette.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Color.White else Palette.Muted, size = 13, bold = primary)
    }
}

@Composable
private fun Chips(content: @Composable () -> Unit) {
    // Horizontal scroll instead of a wrapping row keeps narrow screens usable.
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Chip(label: String, selected: Boolean, accent: Color = Palette.Primary, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (selected) accent else Palette.Card)
            .border(1.dp, if (selected) accent else Palette.Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.White else Palette.Ink, size = 14, bold = selected)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Palette.Muted, size = 13)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Palette.Ink, fontSize = 16.sp),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Card)
                        .border(1.dp, Palette.Line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text(placeholder, color = Palette.Dashed, size = 16)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Palette.Card).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, size = 18, bold = true)
            Text(body, color = Palette.Muted, size = 14)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                LineButton("아니요", onDismiss)
                DarkButton(confirm, onConfirm)
            }
        }
    }
}
