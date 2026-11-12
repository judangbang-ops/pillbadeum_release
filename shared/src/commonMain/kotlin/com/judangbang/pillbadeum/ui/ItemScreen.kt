package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.status.IntakeStatus
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.WidgetLayout
import com.judangbang.pillbadeum.status.showsPeople
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.PersonColors
import kotlinx.datetime.DayOfWeek

/**
 * SCR-006 항목 추가·수정, drawn as 09 ItemForm without the reminder part (TASK-023). [editing] null adds a new item.
 * What has been typed survives the screen being recreated (BUG-003); system back is the same as `[닫기]` (REQ-094).
 */
@Composable
internal fun ItemScreen(
    editing: IntakeStatus?,
    people: List<PersonEntity>,
    message: String?,
    /** Adds a person; `onAdded` gets the new person's id, which the form then selects (TASK-015). */
    onAddPerson: (name: String, color: String, onAdded: (String) -> Unit) -> Unit,
    onClose: () -> Unit,
    onSave: (IntakeDraft) -> Unit,
    onArchive: (IntakeStatus) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(editing?.name ?: "") }
    var alias by rememberSaveable { mutableStateOf(editing?.widgetAlias ?: "") }
    var personId by rememberSaveable { mutableStateOf(editing?.person?.id) }
    var weekly by rememberSaveable { mutableStateOf(editing?.rule is ScheduleRule.Weekly) }
    var anchorName by rememberSaveable { mutableStateOf(((editing?.rule as? ScheduleRule.Weekly)?.anchor ?: DayOfWeek.MONDAY).name) }
    var slotName by rememberSaveable { mutableStateOf(editing?.slotLabel?.name ?: "") }
    var addingPerson by rememberSaveable { mutableStateOf(false) }
    var newPersonName by rememberSaveable { mutableStateOf("") }
    var newPersonColor by rememberSaveable { mutableStateOf(PersonColors.PALETTE[1]) }
    var nameError by rememberSaveable { mutableStateOf(false) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }

    val anchor = DayOfWeek.entries.first { it.name == anchorName }
    val slot = SlotLabel.entries.firstOrNull { it.name == slotName }
    // The chosen person, or the first one while none is chosen (or the chosen one was archived meanwhile).
    val chosen = people.firstOrNull { it.id == personId } ?: people.firstOrNull()

    fun save() {
        val pid = chosen?.id ?: return
        if (name.isBlank()) {
            nameError = true
            return
        }
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val largeText = HomeLayout.columns(maxWidth.value, with(LocalDensity.current) { 14.sp.toDp().value }) == 1
        Column(Modifier.fillMaxSize()) {
            TopBar(
                if (editing == null) "항목 추가" else "항목 수정",
                navigation = { BarIconButton("닫기", close = true, onClose) },
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = Ui.Side, end = Ui.Side, top = 4.dp, bottom = 8.dp),
            ) {
                message?.let { Band(it, Modifier.padding(bottom = 16.dp), live = true) }
                Field(
                    label = "약 이름",
                    value = name,
                    onChange = { name = it; nameError = false },
                    placeholder = "예: 혈압약",
                    error = if (nameError) Labels.NAME_REQUIRED else null,
                )
                Field(
                    label = "위젯·알림에 보일 이름",
                    optional = true,
                    value = alias,
                    onChange = { alias = it },
                    placeholder = "예: 아침 약",
                    note = "홈 화면에서 약 이름을 가리고 싶을 때 써요.",
                )

                // With one active person the field folds to [+ 가족 추가] alone (REQ-018, TASK-015).
                val family = showsPeople(people.size)
                Group(if (family) "누구 약인가요?" else null) {
                    if (family) {
                        PickGrid(people + null, columns = 3) { person, modifier ->
                            if (person == null) {
                                AddCell("+ 가족", modifier) { addingPerson = !addingPerson }
                            } else {
                                PickCell(person.name, person.id == chosen?.id, modifier, person = personLook(person.color)) { personId = person.id }
                            }
                        }
                    } else {
                        AddCell("+ 가족 추가", Modifier.fillMaxWidth()) { addingPerson = !addingPerson }
                    }
                    if (addingPerson) {
                        PersonAdd(
                            name = newPersonName,
                            onName = { newPersonName = it },
                            color = newPersonColor,
                            onColor = { newPersonColor = it },
                            onCancel = { addingPerson = false; newPersonName = "" },
                            onAdd = {
                                if (newPersonName.isNotBlank()) {
                                    onAddPerson(newPersonName, newPersonColor) { id -> personId = id }
                                    newPersonName = ""
                                    addingPerson = false
                                }
                            },
                        )
                    }
                }

                Group("얼마나 자주?") {
                    PickGrid(listOf(false, true), columns = 2) { isWeekly, modifier ->
                        PickCell(if (isWeekly) "주 1회" else "매일", weekly == isWeekly, modifier) { weekly = isWeekly }
                    }
                    if (editing != null) Note("주기를 바꾸면 지금부터 새 일정이 적용돼요. 이번 창의 기록은 그대로 인정돼요.")
                }
                if (weekly) {
                    Group("무슨 요일에?") {
                        PickGrid(DayOfWeek.entries, columns = 4) { day, modifier ->
                            PickCell(Labels.weekday(day), day == anchor, modifier) { anchorName = day.name }
                        }
                        val last = DayOfWeek.entries[(anchor.ordinal + 6) % 7]
                        Note("${Labels.weekdayName(anchor)}부터 ${Labels.weekdayName(last)} 사이에 한 번 먹으면 완료예요.")
                    }
                } else {
                    Group("언제 먹나요?") {
                        PickGrid(SlotLabel.entries + null, columns = if (largeText) 3 else 5) { s, modifier ->
                            PickCell(s?.let(Labels::slot) ?: "없음", s == slot, modifier, size = 15) { slotName = s?.name ?: "" }
                        }
                    }
                }
                if (editing != null) {
                    // [보관], not [항목 삭제]: archiving keeps the records (REQ-014, TERM-018, SCR-006).
                    ActionButton("보관", ButtonLook.Danger, { confirmArchive = true })
                    Note("보관해도 지난 기록은 지워지지 않아요.", Modifier.padding(horizontal = 4.dp))
                }
            }
            // `.foot.line`: [저장] stays above the keyboard and the list.
            Column(
                Modifier.fillMaxWidth().background(Ui.Surface)
                    .drawBehind { drawLine(Ui.LineSubtle, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                    .padding(start = Ui.Side, end = Ui.Side, top = 12.dp, bottom = 16.dp),
            ) {
                ActionButton("저장", ButtonLook.Ink, { save() })
            }
        }
    }

    if (confirmArchive && editing != null) {
        ConfirmDialog(
            title = "${Labels.withObject(editing.name)} 보관할까요?",
            body = "위젯·알림·오늘 목록에서 빠지고 지난 기록은 남아요.",
            confirm = "보관",
            onConfirm = { confirmArchive = false; onArchive(editing) },
            onDismiss = { confirmArchive = false },
        )
    }
}

/** A labelled group of the form (box.css `.fld`). */
@Composable
private fun Group(label: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Words(it, Ui.type(15, Ui.SemiBold, lineHeight = 1.25f)) }
        content()
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Words(text, Ui.type(14, color = Ui.Muted), modifier.padding(top = 2.dp))
}

/** A text field (box.css `.in`): 1.5 dp border, 2 dp ink while focused, 2 dp danger with [error] under it. */
@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    optional: Boolean = false,
    note: String? = null,
    error: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (optional) {
            Words(
                buildAnnotatedString {
                    append(WidgetLayout.keepWords(label))
                    withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = Ui.Muted)) { append(" (선택)") }
                },
                plain = "$label (선택)",
                style = Ui.type(15, Ui.SemiBold, lineHeight = 1.25f),
            )
        } else {
            Words(label, Ui.type(15, Ui.SemiBold, lineHeight = 1.25f))
        }
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Ui.type(16, lineHeight = 1.25f),
            cursorBrush = SolidColor(Ui.Ink),
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            decorationBox = { inner ->
                val (width, color) = when {
                    error != null -> 2.dp to Ui.Danger
                    focused -> 2.dp to Ui.Ink
                    else -> 1.5.dp to Ui.LineStrong
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Ui.ButtonShape).background(Ui.Surface)
                        .border(width, color, Ui.ButtonShape).padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Words(placeholder, Ui.type(16, color = Ui.Muted, lineHeight = 1.25f), Modifier.hiddenFromTalkBack())
                    inner()
                }
            },
        )
        error?.let {
            Words(it, Ui.type(14, Ui.SemiBold, Ui.Danger), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        note?.let { Note(it) }
    }
}

/** Cells of equal width in rows of [columns] (box.css `.pick`). */
@Composable
private fun <T> PickGrid(items: List<T>, columns: Int, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { cell(it, Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One choice (box.css `.pick .c`): ink when chosen; a person's cell in their tint, their color when chosen. */
@Composable
private fun PickCell(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    person: PersonLook? = null,
    size: Int = 16,
    onClick: () -> Unit,
) {
    val (fill, border, ink) = when {
        person != null && selected -> Triple(person.solid, person.solid, Ui.OnSolid)
        person != null -> Triple(person.tint, person.tint, person.ink)
        selected -> Triple(Ui.Ink, Ui.Ink, Ui.OnSolid)
        else -> Triple(Ui.Surface, Ui.LineStrong, Ui.Ink)
    }
    Box(
        modifier.heightIn(min = 52.dp).clip(Ui.ButtonShape).background(fill).border(1.5.dp, border, Ui.ButtonShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Words(label, Ui.type(size, if (selected) Ui.ExtraBold else Ui.SemiBold, ink, lineHeight = 1.25f, center = true))
    }
}

/** `[+ 가족]` (09 ItemForm `.pick .c.add`): a dashed cell that opens the person fields. */
@Composable
private fun AddCell(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 52.dp).clip(Ui.ButtonShape).background(Ui.Surface)
            .dashedBorder(1.5.dp, Ui.LineStrong, 12.dp, dash = 5.dp, gap = 4.dp)
            .clickable(role = Role.Button, onClick = onClick).padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Words(label, Ui.type(16, Ui.SemiBold, lineHeight = 1.25f, center = true))
    }
}

/** The person fields under `[+ 가족]`: a name and a lid color, with the one-phone advice (REQ-074). */
@Composable
private fun PersonAdd(
    name: String,
    onName: (String) -> Unit,
    color: String,
    onColor: (String) -> Unit,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp).border(1.dp, Ui.LineSubtle, Ui.Tile).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Field(label = "가족 이름", value = name, onChange = onName, placeholder = "예: 엄마")
        // 09 PersonAdd `.sw`: 48 dp swatches read by their color name, a check and a ring on the chosen one. Three to a
        // row, because six in a row inside this card would be narrower than 48 dp at 360 dp (REQ-067).
        PickGrid(PersonColors.PALETTE.withIndex().toList(), columns = 3) { (index, hex), modifier ->
            Swatch(COLOR_NAMES.getOrElse(index) { "색 ${index + 1}" }, personLook(hex).solid, hex == color, modifier) { onColor(hex) }
        }
        Note(Labels.ONE_PHONE)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            ActionButton("취소", ButtonLook.LineSmall, onCancel)
            ActionButton("추가", ButtonLook.InkSmall, onAdd)
        }
    }
}

/** The names 09 PersonAdd reads the six person colors by, in [PersonColors.PALETTE] order. */
private val COLOR_NAMES = listOf("파랑", "주황", "초록", "보라", "장미", "청록")

/** One person color (09 PersonAdd `.sw`): 2 dp surface and 2 dp ink around it and a check when chosen. */
@Composable
private fun Swatch(name: String, solid: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp)
            .drawBehind {
                if (selected) {
                    val r = 12.dp.toPx()
                    val outer = 4.dp.toPx()
                    val inner = 2.dp.toPx()
                    drawRoundRect(Ui.Ink, Offset(-outer, -outer), Size(size.width + outer * 2, size.height + outer * 2), CornerRadius(r + outer))
                    drawRoundRect(Ui.Surface, Offset(-inner, -inner), Size(size.width + inner * 2, size.height + inner * 2), CornerRadius(r + inner))
                }
            }
            .clip(Ui.ButtonShape).background(solid)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Canvas(Modifier.size(18.dp)) {
                val u = size.width / 24f
                val check = Path().apply {
                    moveTo(5 * u, 12.5f * u)
                    lineTo(9.5f * u, 17 * u)
                    lineTo(19 * u, 7.5f * u)
                }
                drawPath(check, Ui.OnSolid, style = Stroke(2.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
