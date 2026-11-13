package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.PeopleView
import com.judangbang.pillbadeum.status.PersonRow
import com.judangbang.pillbadeum.status.WidgetPick
import com.judangbang.pillbadeum.usecase.PersonColors

/** What SCR-010 shows: `#loading` (grey rows), a read error, or the people (`#normal`). */
internal sealed interface PeopleState {
    data object Loading : PeopleState

    /** The people could not be read: `[다시 시도]` (Claude's design, as SCR-009's). */
    data object Failed : PeopleState

    data class Ready(val view: PeopleView) : PeopleState
}

/** What SCR-010's list does (screens.md SCR-010 table of actions). */
internal class PeopleActions(
    val back: () -> Unit,
    val retry: () -> Unit,
    /** `[대상자 추가]`: the form for a new person. */
    val add: () -> Unit,
    /** A person's line: the form with their name and color. */
    val edit: (PersonRow) -> Unit,
    /** `[되살리기]` under `보관됨`, without a question (REQ-095, SPEC-044). */
    val restore: (PersonRow) -> Unit,
)

/**
 * SCR-010 대상자 관리, drawn as 09 People (TASK-021): every person who is not archived on a lid of their color with how
 * many items they have, `[대상자 추가]`, and the archived people under `보관됨` with `[되살리기]` (REQ-016, REQ-017, REQ-085,
 * REQ-086, REQ-093, REQ-095). A line opens [PersonFormScreen]; `[보관]` is there. Results and failures show in the band
 * under the bar ([message]); system back returns to the settings (REQ-094, SPEC-021).
 */
@Composable
internal fun PeopleScreen(state: PeopleState, message: String?, actions: PeopleActions) {
    Column(Modifier.fillMaxSize()) {
        TopBar("대상자 관리", navigation = { BarIconButton("뒤로", close = false, actions.back) })
        message?.let { Band(it, Modifier.padding(start = Ui.Side, end = Ui.Side, bottom = 8.dp), live = true) }
        if (state == PeopleState.Failed) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = Ui.Side, end = Ui.Side, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Words(
                    Labels.PEOPLE_LOAD_FAILED,
                    Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f),
                    Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                ActionButton("다시 시도", ButtonLook.Ink, actions.retry)
            }
            return@Column
        }
        val view = (state as? PeopleState.Ready)?.view
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = Ui.Side, end = Ui.Side, top = 4.dp, bottom = 24.dp),
        ) {
            Words(Labels.PEOPLE_NOTE, Ui.type(14, color = Ui.Muted), Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp))
            if (view == null) {
                LoadingLines()
                return@Column
            }
            view.active.forEach { row -> PersonLine(row) { actions.edit(row) } }
            ActionButton("대상자 추가", ButtonLook.Ink, actions.add, Modifier.padding(top = 16.dp)) { PlusIcon(Ui.OnSolid, 18.dp) }
            if (view.archived.isNotEmpty()) {
                SectionTitle("보관됨")
                view.archived.forEachIndexed { index, row ->
                    ArchivedLine(row, line = index < view.archived.lastIndex) { actions.restore(row) }
                }
            }
        }
    }
}

/**
 * SCR-010's person form, drawn as 09 PersonAdd: a name and one of the six colors. [editing] null adds a person, with the
 * one-phone advice under the colors (REQ-074, REQ-085, SPEC-004); otherwise it changes their name and color (REQ-086,
 * SPEC-026) and offers `[보관]` while someone else is not archived (REQ-093). An empty name is not saved and is asked for
 * under the field; what was typed and an open question survive the screen being recreated (BUG-003). `[닫기]` and system
 * back save nothing.
 */
@Composable
internal fun PersonFormScreen(
    editing: PersonRow?,
    view: PeopleView,
    message: String?,
    onClose: () -> Unit,
    onSave: (name: String, color: String) -> Unit,
    onArchive: (PersonRow) -> Unit,
) {
    val others = view.active.map { it.person }.filter { it.id != editing?.person?.id }
    var name by rememberSaveable { mutableStateOf(editing?.person?.name.orEmpty()) }
    var color by rememberSaveable {
        mutableStateOf(editing?.person?.color?.let(PersonColors::canonical) ?: PersonColors.leastUsed(others.map { it.color }))
    }
    var nameError by rememberSaveable { mutableStateOf(false) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }

    fun save() {
        if (name.isBlank()) {
            nameError = true
            return
        }
        // Nothing changed: nothing to write or redraw.
        if (editing != null && name.trim() == editing.person.name && color == editing.person.color) return onClose()
        onSave(name, color)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (editing == null) "대상자 추가" else "대상자 수정", navigation = { BarIconButton("닫기", close = true, onClose) })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = Ui.Side, end = Ui.Side, top = 4.dp, bottom = 8.dp),
        ) {
            message?.let { Band(it, Modifier.padding(bottom = 16.dp), live = true) }
            Field(
                label = "이름",
                value = name,
                onChange = { name = it; nameError = false },
                placeholder = "예: 엄마",
                error = if (nameError) Labels.NAME_REQUIRED else null,
            )
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Words("색", Ui.type(15, Ui.SemiBold, lineHeight = 1.25f))
                Colors(color) { color = it }
                Labels.colorsInUse(others)?.let { Hint(it) }
            }
            when {
                editing == null -> Band(Labels.onePhoneFor(name))
                view.canArchive -> {
                    // [보관], as the item form: archiving keeps the records (REQ-017, TERM-018).
                    ActionButton("보관", ButtonLook.Danger, { confirmArchive = true })
                    Hint("보관해도 지난 기록은 지워지지 않아요.", Modifier.padding(horizontal = 4.dp))
                }
                // REQ-093: no [보관] for the last active person, and why.
                else -> Hint(Labels.LAST_PERSON, Modifier.padding(horizontal = 4.dp))
            }
        }
        // `.foot.line`: [저장] stays above the keyboard.
        Column(
            Modifier.fillMaxWidth().background(Ui.Surface)
                .drawBehind { drawLine(Ui.LineSubtle, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(start = Ui.Side, end = Ui.Side, top = 12.dp, bottom = 16.dp),
        ) {
            ActionButton("저장", ButtonLook.Ink, { save() })
        }
    }

    if (confirmArchive && editing != null) {
        ConfirmDialog(
            title = Labels.archivePersonQuestion(editing.person.name),
            body = Labels.archivePersonNote(editing.person.name, editing.items),
            confirm = "보관",
            onConfirm = { confirmArchive = false; onArchive(editing) },
            onDismiss = { confirmArchive = false },
        )
    }
}

/** A line of the list (box.css `.li`): at least 56 dp, with a subtle line under it unless it is the last of its group. */
private fun Modifier.listLine(line: Boolean): Modifier = fillMaxWidth().heightIn(min = 56.dp).drawBehind {
    if (line) {
        val w = 1.dp.toPx()
        drawLine(Ui.LineSubtle, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
    }
}

/** A person who is not archived: lid, name, `약 2개` and the chevron, read `엄마, 약 2개` and opened to change them. */
@Composable
private fun PersonLine(row: PersonRow, onClick: () -> Unit) {
    val detail = Labels.personItems(row.items, archived = false)
    Row(
        Modifier.listLine(true).clickable(onClickLabel = "이름·색 바꾸기", role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = "${row.person.name}, $detail" }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Lid(row.person, off = false)
        NameAndDetail(row.person.name, detail, Modifier.weight(1f))
        Chevron(0f)
    }
}

/**
 * An archived person (09 People `.li.arch`): the grey lid, `약 1개 · 지난 기록은 남아요` and `[되살리기]`, read `아빠 되살리기`.
 * The button sits at the end of the line while it takes at most 45% of the room beside the lid; with large text it goes
 * under the name, where the name starts, so it stays on the screen (09 README 4.1, 200%).
 */
@Composable
private fun ArchivedLine(row: PersonRow, line: Boolean, onRestore: () -> Unit) {
    val name = row.person.name
    val detail = Labels.personItems(row.items, archived = true)
    Box(Modifier.listLine(line).padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        Layout(
            contents = listOf(
                { Lid(row.person, off = true) },
                { NameAndDetail(name, detail, Modifier.clearAndSetSemantics { contentDescription = "$name, $detail" }) },
                {
                    ActionButton("되살리기", ButtonLook.LineSmall, onRestore, Modifier.semantics { contentDescription = "$name 되살리기" })
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { (lids, texts, buttons), constraints ->
            val width = constraints.maxWidth
            val gap = 12.dp.roundToPx()
            val lid = lids.single().measure(Constraints())
            val start = lid.width + gap
            val room = (width - start).coerceAtLeast(0)
            val button = buttons.single()
            val buttonWidth = button.maxIntrinsicWidth(Constraints.Infinity)
            if (buttonWidth <= room * 0.45f) {
                val buttonPlaced = button.measure(Constraints(maxWidth = buttonWidth.coerceAtMost(room)))
                val text = texts.single().measure(Constraints(maxWidth = (room - buttonPlaced.width - gap).coerceAtLeast(0)))
                val height = maxOf(lid.height, text.height, buttonPlaced.height)
                layout(width, height) {
                    lid.place(0, (height - lid.height) / 2)
                    text.place(start, (height - text.height) / 2)
                    buttonPlaced.place(width - buttonPlaced.width, (height - buttonPlaced.height) / 2)
                }
            } else {
                val text = texts.single().measure(Constraints(maxWidth = room))
                val buttonPlaced = button.measure(Constraints(maxWidth = room))
                val first = maxOf(lid.height, text.height)
                layout(width, first + gap + buttonPlaced.height) {
                    lid.place(0, (first - lid.height) / 2)
                    text.place(start, (first - text.height) / 2)
                    buttonPlaced.place(start, first + gap)
                }
            }
        }
    }
}

/** `.k` with its `small`: the name, and under it what the person has. */
@Composable
private fun NameAndDetail(name: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Words(name, Ui.type(16, lineHeight = 1.25f))
        Words(detail, Ui.type(14, color = Ui.Muted))
    }
}

/** 09 `.lid`: 40 dp in the person's color with their first letter; grey with a muted letter while archived (`.lid.off`). */
@Composable
private fun Lid(person: PersonEntity, off: Boolean) {
    Box(
        Modifier.size(40.dp).clip(Ui.ButtonShape).background(if (off) Ui.Skip else personLook(person.color).solid).hiddenFromTalkBack(),
        contentAlignment = Alignment.Center,
    ) {
        Words(WidgetPick.firstLetter(person.name), Ui.type(16, Ui.ExtraBold, if (off) Ui.Muted else Ui.OnSolid, lineHeight = 1.25f))
    }
}

/** SCR-010#loading: grey lines in place of the list, read as loading. */
@Composable
private fun LoadingLines() {
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = Labels.LOADING }) {
        repeat(2) {
            Row(
                Modifier.listLine(true).padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(40.dp).clip(Ui.ButtonShape).background(Ui.Sunken))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(width = 72.dp, height = 16.dp).clip(RoundedCornerShape(6.dp)).background(Ui.Sunken))
                    Box(Modifier.size(width = 48.dp, height = 12.dp).clip(RoundedCornerShape(6.dp)).background(Ui.Sunken))
                }
            }
        }
    }
}

/**
 * 09 PersonAdd `.sws`: the six colors in one row, 8 dp apart, read by their names. Where six would be narrower than 48 dp
 * (REQ-067) they go three to a row.
 */
@Composable
private fun Colors(chosen: String, onPick: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if ((maxWidth - 8.dp * 5) / 6 >= 48.dp) 6 else 3
        PickGrid(PersonColors.PALETTE.withIndex().toList(), columns = columns, gap = 8.dp) { (index, hex), modifier ->
            Swatch(Labels.COLOR_NAMES[index], personLook(hex).solid, hex == chosen, modifier) { onPick(hex) }
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Words(text, Ui.type(14, color = Ui.Muted), modifier.padding(top = 2.dp))
}
