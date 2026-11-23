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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.WidgetLayout
import com.judangbang.pillbadeum.usecase.CARD_ENTRIES_PER_KIND
import com.judangbang.pillbadeum.usecase.CardEntry
import com.judangbang.pillbadeum.usecase.CardEntryFields
import com.judangbang.pillbadeum.usecase.CardEntryKind
import com.judangbang.pillbadeum.usecase.HealthCardDisplay
import com.judangbang.pillbadeum.usecase.HealthCardSummary
import com.judangbang.pillbadeum.usecase.StaleCardNotice

internal data class HealthCardActions(
    val onClose: () -> Unit,
    val onRetry: () -> Unit,
    val onShow: () -> Unit,
    val onEmergency: () -> Unit,
    val onExport: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onAdd: (CardEntryKind) -> Unit,
    val onEdit: (CardEntry) -> Unit,
    val onItem: (String) -> Unit,
    val onDelete: (CardEntry?) -> Unit,
)

@Composable
internal fun HealthCardScreen(
    card: HealthCardSummary?,
    personName: String?,
    failed: Boolean,
    busy: Boolean,
    message: String?,
    actions: HealthCardActions,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stacked = LocalDensity.current.fontScale >= 1.5f || maxWidth < 320.dp
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = (card?.person?.name ?: personName)?.let(Labels::healthCardTitle) ?: Labels.HEALTH_CARD,
                navigation = { BarIconButton("닫기", close = true, actions.onClose) },
                actions = {
                    if (card?.archived == true) {
                        Words(
                            Labels.CARD_ARCHIVED, Ui.type(14, Ui.SemiBold, Ui.Muted),
                            Modifier.clip(Ui.ButtonShape).background(Ui.Skip).padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                },
            )
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(
                    start = Ui.Side, end = Ui.Side, top = 4.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    failed -> CardReadFailed(actions.onRetry)
                    card == null -> CardLoading(sections = CardEntryKind.entries.size + 1)
                    else -> HealthCardBody(card, busy, stacked, message, actions)
                }
            }
        }
    }
}

private const val NO_EMERGENCY_CONTENT = "응급 대비 문장에 넣을 내용이 없어요"

@Composable
private fun HealthCardBody(
    card: HealthCardSummary,
    busy: Boolean,
    stacked: Boolean,
    message: String?,
    actions: HealthCardActions,
) {
    var deletion by remember { mutableStateOf<DeleteCardConfirmation?>(null) }
    val enabled = !busy
    val canOpenEmergency = card.canOpenEmergencyText()

    message?.let { Band(it, live = true) }
    if (card.display == HealthCardDisplay.ARCHIVED) CardNote(Labels.CARD_ARCHIVED_HINT)
    card.staleNotice?.let {
        CardNotice(it, enabled, canOpenEmergency, stacked, actions)
    }
    if (card.canShowOrExport()) {
        ActionButton(Labels.CARD_SHOW, ButtonLook.Ink, actions.onShow, enabled = enabled)
        CardButtonPair(
            stacked,
            first = { ActionButton(Labels.CARD_EMERGENCY, ButtonLook.Line, actions.onEmergency, it, enabled && canOpenEmergency) },
            second = { ActionButton(Labels.CARD_EXPORT, ButtonLook.Line, actions.onExport, it, enabled) },
        )
        CardNote(if (canOpenEmergency) Labels.CARD_EMERGENCY_HINT else NO_EMERGENCY_CONTENT)
    } else {
        val (title, detail) = Labels.CARD_EMPTY.split(". ", limit = 2)
        Column(
            Modifier.fillMaxWidth().dashedBorder(1.dp, Ui.LineStrong, 18.dp, 4.dp, 4.dp).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Words("$title.", Ui.type(18, Ui.ExtraBold, center = true))
            Words(detail, Ui.type(14, color = Ui.Muted, center = true))
        }
    }

    if (card.medications.isNotEmpty()) {
        CardDivider()
        CardSectionTitle(Labels.CARD_MEDICATIONS)
        Column(Modifier.fillMaxWidth()) {
            card.medications.forEach { row ->
                CardMedicationRow(row.text, row.dose, stacked, enabled, card.archived) { actions.onItem(row.intakeId) }
            }
        }
        CardNote(if (card.archived) Labels.CARD_ARCHIVED_MEDICATION_HINT else Labels.CARD_MEDICATION_HINT)
    }

    val entries = card.entries.groupBy { it.fields.kind }
    for (kind in CardEntryKind.entries) {
        CardDivider()
        CardSection(
            kind, entries[kind].orEmpty(), stacked, enabled,
            onAdd = { actions.onAdd(kind) },
            onEdit = actions.onEdit,
            onDelete = { deletion = DeleteCardConfirmation(it) },
        )
    }
    card.lastEditedDate?.let { CardNote(Labels.cardLastEdited(it), Modifier.padding(top = 8.dp)) }
    if (card.entries.isNotEmpty()) {
        ActionButton(Labels.CARD_DELETE_ALL, ButtonLook.Danger, { deletion = DeleteCardConfirmation(null) }, enabled = enabled)
    }
    deletion?.let { confirmation ->
        val prompt = if (confirmation.entry == null) Labels.cardDeleteAllPrompt(card.person.name) else Labels.CARD_ENTRY_DELETE_PROMPT
        ConfirmDialog(
            title = prompt.substringBefore("? ") + "?",
            body = prompt.substringAfter("? "),
            confirm = "지우기",
            onConfirm = {
                if (deletion != null) {
                    deletion = null
                    actions.onDelete(confirmation.entry)
                }
            },
            onDismiss = { deletion = null },
            cancel = "취소",
            stackButtons = stacked,
        )
    }
}

private class DeleteCardConfirmation(val entry: CardEntry?)

@Composable
private fun CardNotice(
    notice: StaleCardNotice,
    enabled: Boolean,
    canOpenEmergency: Boolean,
    stacked: Boolean,
    actions: HealthCardActions,
) {
    Column(
        Modifier.fillMaxWidth().clip(Ui.Tile).background(Ui.Notice).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Words(Labels.cardNotice(notice), Ui.type(14, Ui.SemiBold, Ui.NoticeInk))
        if (notice == StaleCardNotice.EMPTY) {
            ActionButton("닫기", ButtonLook.LineSmall, actions.onDismissNotice, enabled = enabled)
        } else {
            CardButtonPair(
                stacked,
                first = { ActionButton(Labels.CARD_EMERGENCY, ButtonLook.Ink, actions.onEmergency, it, enabled && canOpenEmergency) },
                second = { ActionButton(Labels.CARD_NOTICE_ACKNOWLEDGED, ButtonLook.Line, actions.onDismissNotice, it, enabled) },
            )
        }
    }
}

@Composable
internal fun CardButtonPair(
    stacked: Boolean,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CardMedicationRow(text: String, dose: String, stacked: Boolean, enabled: Boolean, archived: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(Ui.ButtonShape)
        .then(if (archived) Modifier else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick))
        .padding(horizontal = 4.dp, vertical = 6.dp)
    val label = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = Ui.SemiBold)) { append(WidgetLayout.keepWords(dose)) }
        withStyle(SpanStyle(color = Ui.Muted)) { append(WidgetLayout.keepWords(text.removePrefix(dose))) }
    }
    if (stacked && !archived) {
        Column(modifier) {
            Words(label, text, Ui.type(16, lineHeight = 1.25f))
            CardChevron()
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Words(label, text, Ui.type(16, lineHeight = 1.25f), Modifier.weight(1f))
            if (!archived) CardChevron()
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.LineSubtle))
}

@Composable
private fun CardChevron() {
    Words("›", Ui.type(20, color = Ui.Muted), Modifier.padding(horizontal = 4.dp).hiddenFromTalkBack())
}

@Composable
private fun CardSection(
    kind: CardEntryKind,
    entries: List<CardEntry>,
    stacked: Boolean,
    enabled: Boolean,
    onAdd: () -> Unit,
    onEdit: (CardEntry) -> Unit,
    onDelete: (CardEntry) -> Unit,
) {
    val title = Labels.cardEntryTitle(kind)
    val canAdd = entries.size < CARD_ENTRIES_PER_KIND
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (stacked) {
            CardSectionTitle(title)
            if (canAdd) CardAdd(title, enabled, onAdd) else CardNote(Labels.CARD_ENTRY_LIMIT)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardSectionTitle(title, Modifier.weight(1f))
                if (canAdd) CardAdd(title, enabled, onAdd) else CardNote(Labels.CARD_ENTRY_LIMIT)
            }
        }
        CardNote(Labels.cardEntryHint(kind))
        entries.forEachIndexed { index, entry ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.LineSubtle))
            CardEntryRow(entry.fields, title, stacked, enabled, { onEdit(entry) }, { onDelete(entry) })
        }
    }
}

@Composable
private fun CardAdd(title: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(Ui.ButtonShape)
            .dashedBorder(1.dp, Ui.LineStrong, 12.dp, 4.dp, 4.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$title ${Labels.CARD_ADD}" }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Words(Labels.CARD_ADD, Ui.type(15, Ui.SemiBold), Modifier.hiddenFromTalkBack())
    }
}

private data class CardRowText(val title: String, val detail: String)

private fun CardEntryFields.rowText(): CardRowText = when (this) {
    is CardEntryFields.Allergy -> CardRowText(substance, reaction?.let { "반응 $it" }.orEmpty())
    is CardEntryFields.Condition -> CardRowText(name, diagnosedOn?.let(Labels::cardDate).orEmpty())
    is CardEntryFields.Procedure -> CardRowText(name, performedOn?.let(Labels::cardDate).orEmpty())
    is CardEntryFields.ImplantedDevice -> CardRowText(name, implantedOn?.let(Labels::cardDate).orEmpty())
    is CardEntryFields.CareProvider -> CardRowText(name, listOfNotNull(department, doctor?.let { "담당 의사 $it" }, phone).joinToString(" · "))
    is CardEntryFields.Guardian -> CardRowText(name, listOfNotNull(relation, phone).joinToString(" · "))
}

@Composable
private fun CardEntryRow(fields: CardEntryFields, kind: String, stacked: Boolean, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val (title, detail) = fields.rowText()
    val spoken = listOf(kind, title, detail).filter(String::isNotEmpty).joinToString(", ")
    val row: @Composable (Modifier) -> Unit = { modifier ->
        val label = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = Ui.SemiBold)) { append(WidgetLayout.keepWords(title)) }
            if (detail.isNotEmpty()) {
                append('\n')
                withStyle(SpanStyle(color = Ui.Muted, fontSize = Ui.type(14).fontSize)) {
                    append(WidgetLayout.keepWords(detail))
                }
            }
        }
        Box(
            modifier.heightIn(min = 48.dp).clip(Ui.ButtonShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onEdit)
                .semantics { contentDescription = spoken }.padding(vertical = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicText(
                label, Modifier.hiddenFromTalkBack(), style = Ui.type(16, lineHeight = 1.25f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val delete: @Composable () -> Unit = {
        Box(
            Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(Ui.ButtonShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onDelete)
                .semantics { contentDescription = "$title 칸 지우기" }
                .padding(horizontal = if (stacked) 0.dp else 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Words("지우기", Ui.type(15, Ui.SemiBold, Ui.Danger), Modifier.hiddenFromTalkBack())
        }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth()) {
            row(Modifier.fillMaxWidth())
            delete()
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row(Modifier.weight(1f))
            delete()
        }
    }
}

@Composable
private fun CardSectionTitle(text: String, modifier: Modifier = Modifier) {
    Words(text, Ui.type(18, Ui.ExtraBold, lineHeight = 1.25f), modifier, heading = true)
}

@Composable
private fun CardDivider() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(Ui.LineSubtle))
}

@Composable
internal fun CardNote(text: String, modifier: Modifier = Modifier) {
    Words(text, Ui.type(14, color = Ui.Muted), modifier)
}

@Composable
internal fun CardLoading(sections: Int) {
    Column(
        Modifier.fillMaxWidth().semantics {
            contentDescription = Labels.LOADING
            liveRegion = LiveRegionMode.Polite
        },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(sections) {
            Box(Modifier.fillMaxWidth(.55f).height(22.dp).clip(Ui.ButtonShape).background(Ui.Sunken))
            Box(Modifier.fillMaxWidth().height(48.dp).clip(Ui.ButtonShape).background(Ui.Sunken))
        }
    }
}

@Composable
internal fun CardReadFailed(onRetry: () -> Unit) {
    Band(Labels.CARD_LOAD_FAILED, live = true)
    ActionButton("다시 시도", ButtonLook.Ink, onRetry)
}
