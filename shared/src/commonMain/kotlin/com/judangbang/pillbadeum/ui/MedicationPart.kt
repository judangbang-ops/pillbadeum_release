package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.judangbang.pillbadeum.usecase.IntakeMedicationDraft
import com.judangbang.pillbadeum.usecase.limitCodePointInput

internal data class MedicationFormRow(val key: Int, val draft: IntakeMedicationDraft)

internal val MedicationRowsSaver = Saver<List<MedicationFormRow>, ArrayList<String>>(
    save = { rows ->
        rows.flatMapTo(ArrayList()) { (key, draft) ->
            listOf(key.toString(), draft.name, draft.quantity, draft.id.orEmpty())
        }
    },
    restore = { saved ->
        saved.chunked(4).map { (key, name, quantity, id) ->
            MedicationFormRow(key.toInt(), IntakeMedicationDraft(name, quantity, id.ifEmpty { null }))
        }
    },
)

/** SCR-006, ItemFormMeds: the fields share a row at normal text size and stack at large text sizes. */
@Composable
internal fun MedicationPart(
    rows: List<MedicationFormRow>,
    largeText: Boolean,
    errors: IntArray,
    focusKey: Int?,
    onFocused: () -> Unit,
    onChange: (Int, IntakeMedicationDraft) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldLabel("약", optional = true)
        ItemFormNote("이 항목으로 먹는 약을 적으면 건강 카드에 보여요. 적지 않아도 돼요.")
        rows.forEachIndexed { index, row ->
            key(row.key) {
                MedicationRow(
                    number = index + 1,
                    draft = row.draft,
                    largeText = largeText,
                    nameError = row.key in errors,
                    focusName = row.key == focusKey,
                    onFocused = onFocused,
                    onChange = { onChange(row.key, it) },
                    onRemove = { onRemove(row.key) },
                )
            }
        }
        ItemFormNote("1회 투약량은 한 번에 먹는 양이에요. 예: 1정, 반 알")
        if (rows.size < IntakeMedicationDraft.MAX_ROWS) {
            ActionButton("약 더하기", ButtonLook.Line, onAdd, icon = { PlusIcon(Ui.Ink, 18.dp) })
        } else {
            ItemFormNote("약은 10개까지 적을 수 있어요")
        }
    }
}

@Composable
private fun MedicationRow(
    number: Int,
    draft: IntakeMedicationDraft,
    largeText: Boolean,
    nameError: Boolean,
    focusName: Boolean,
    onFocused: () -> Unit,
    onChange: (IntakeMedicationDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(focusName) {
        if (focusName) {
            focus.requestFocus()
            bringIntoView.bringIntoView()
            onFocused()
        }
    }
    val labelStyle = Ui.type(14, color = Ui.Muted, lineHeight = 1.25f)
    var nameLimited by rememberSaveable { mutableStateOf(false) }
    var quantityLimited by rememberSaveable { mutableStateOf(false) }
    val nameLimit = IntakeMedicationDraft.MAX_NAME
    val quantityLimit = IntakeMedicationDraft.MAX_QUANTITY
    val name: @Composable (Modifier) -> Unit = { modifier ->
        Field(
            label = "약 이름",
            value = draft.name,
            onChange = { input ->
                val limited = input.limitCodePointInput(nameLimit)
                nameLimited = limited.trim() != input.trim()
                onChange(draft.copy(name = limited))
            },
            placeholder = "",
            error = "약 이름을 입력해 주세요".takeIf { nameError },
            note = "${nameLimit}자까지 적을 수 있어요".takeIf { nameLimited && !nameError },
            modifier = modifier,
            inputModifier = Modifier.bringIntoViewRequester(bringIntoView).focusRequester(focus),
            spokenLabel = "약 $number, 약 이름",
            labelStyle = labelStyle,
            gap = 2.dp,
        )
    }
    val quantity: @Composable (Modifier) -> Unit = { modifier ->
        Field(
            label = "1회 투약량",
            value = draft.quantity,
            onChange = { input ->
                val limited = input.limitCodePointInput(quantityLimit)
                quantityLimited = limited.trim() != input.trim()
                onChange(draft.copy(quantity = limited))
            },
            placeholder = "예: 1정, 반 알",
            note = "${quantityLimit}자까지 적을 수 있어요".takeIf { quantityLimited },
            modifier = modifier,
            labelStyle = labelStyle,
            gap = 2.dp,
        )
    }
    val remove: @Composable () -> Unit = {
        Box(
            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "${draft.name.ifBlank { "약 $number" }} 빼기" },
            contentAlignment = if (largeText) Alignment.CenterStart else Alignment.Center,
        ) {
            Words("빼기", Ui.type(14, Ui.SemiBold, Ui.Danger), Modifier.hiddenFromTalkBack())
        }
    }
    val rowModifier = Modifier.fillMaxWidth()
        .drawBehind { drawLine(Ui.LineSubtle, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
        .padding(bottom = 8.dp)
        .semantics { isTraversalGroup = true }
    if (largeText) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            name(Modifier.fillMaxWidth())
            quantity(Modifier.fillMaxWidth())
            remove()
        }
    } else {
        Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            name(Modifier.weight(1f))
            quantity(Modifier.width(96.dp))
            Box(Modifier.padding(bottom = 4.dp)) { remove() }
        }
    }
}
