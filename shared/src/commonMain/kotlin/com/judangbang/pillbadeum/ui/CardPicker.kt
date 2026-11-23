package com.judangbang.pillbadeum.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.HealthCardSelection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

private const val PICK_CARD_TITLE = "누구의 건강 카드를 볼까요?"
private const val SAME_NAME_NOTICE = "이름이 같은 대상자가 있어요. 대상자 관리에서 이름을 바꾸면 헷갈리지 않아요"

private data class CardOption(val person: PersonEntity, val detail: String?)

@Composable
internal fun HomeCardPicker(
    core: DidITakeCore,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf<List<CardOption>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refresh(picked: String? = null) {
        try {
            val selection = core.loadHealthCard.selection()
            val active = when (selection) {
                HealthCardSelection.None -> emptyList()
                is HealthCardSelection.Open -> {
                    if (!shown || picked == selection.personId) {
                        onOpen(selection.personId)
                        return
                    }
                    // The sheet was already open. Keep it open and redraw its remaining row.
                    core.people.list()
                }
                is HealthCardSelection.Choose -> selection.people
            }
            if (picked != null && active.any { it.id == picked }) {
                onOpen(picked)
                return
            }
            if (active.isEmpty()) {
                onDismiss()
                return
            }
            val duplicates = active.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
            val itemNames = if (duplicates.isEmpty()) emptyMap() else {
                core.db.intakeDao().listActive().groupBy({ it.personId }, { it.name })
            }
            options = active.map { person ->
                val detail = if (person.name in duplicates) {
                    val names = itemNames[person.id].orEmpty()
                    when (names.size) {
                        0 -> "항목 없음"
                        1 -> "항목: ${names.first()}"
                        else -> "항목: ${names.first()} 외 ${names.size - 1}"
                    }
                } else null
                CardOption(person, detail)
            }
            shown = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onFailure()
        }
    }

    LaunchedEffect(core) {
        core.db.invalidationTracker.createFlow("person", "intake").collect { refresh() }
    }
    options?.let { rows ->
        CardPickerSheet(rows, onDismiss) { personId ->
            if (!checking) {
                checking = true
                scope.launch {
                    try {
                        refresh(personId)
                    } finally {
                        checking = false
                    }
                }
            }
        }
    }
}

@Composable
private fun CardPickerSheet(options: List<CardOption>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val slide = remember { Animatable(1f) }
        LaunchedEffect(Unit) { slide.animateTo(0f) }
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(1f).fillMaxWidth().pointerInput(onDismiss) { detectTapGestures { onDismiss() } })
            Column(
                Modifier.fillMaxWidth()
                    .graphicsLayer { translationY = slide.value * size.height }
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Ui.Surface)
                    .verticalScroll(rememberScrollState())
                    .padding(start = Ui.Side, end = Ui.Side, top = 24.dp, bottom = 16.dp),
            ) {
                Words(
                    PICK_CARD_TITLE, Ui.type(18, Ui.ExtraBold, lineHeight = 1.25f),
                    Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp), heading = true,
                )
                options.forEach { option ->
                    val title = Labels.healthCardTitle(option.person.name)
                    Row(
                        Modifier.listLine(line = true)
                            .clickable(role = Role.Button) { onPick(option.person.id) }
                            .clearAndSetSemantics {
                                contentDescription = listOfNotNull(title, option.detail).joinToString(", ")
                            }.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Lid(option.person, off = false)
                        Column(Modifier.weight(1f)) {
                            Words(title, Ui.type(16, lineHeight = 1.25f))
                            option.detail?.let { Words(it, Ui.type(14, color = Ui.Muted)) }
                        }
                        Chevron(0f)
                    }
                }
                if (options.any { it.detail != null }) {
                    Words(SAME_NAME_NOTICE, Ui.type(14, color = Ui.Muted), Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))
                }
                ActionButton(
                    "닫기", ButtonLook(null, Ui.Muted, weight = Ui.SemiBold, minHeight = 48.dp),
                    onDismiss, Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun HealthCardIcon() {
    Canvas(Modifier.size(20.dp).hiddenFromTalkBack()) {
        val unit = size.width / 24
        val width = 2 * unit
        drawRect(Ui.Ink, Offset(4 * unit, 6 * unit), Size(16 * unit, 12 * unit), style = Stroke(width))
        drawLine(Ui.Ink, Offset(8 * unit, 10 * unit), Offset(12 * unit, 10 * unit), width)
        drawLine(Ui.Ink, Offset(8 * unit, 14 * unit), Offset(16 * unit, 14 * unit), width)
    }
}
