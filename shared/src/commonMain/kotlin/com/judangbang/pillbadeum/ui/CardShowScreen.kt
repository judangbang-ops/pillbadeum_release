package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.CardEntryFields
import com.judangbang.pillbadeum.usecase.CardEntryKind
import com.judangbang.pillbadeum.usecase.HealthCardSummary

/** SCR-013, CardShow and CardShow200: a read-only summary with the close action above the scroll. */
@Composable
internal fun CardShowScreen(card: HealthCardSummary?, personName: String?, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(56.dp).padding(end = 8.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            BarIconButton("닫기", close = true, onClose)
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = Ui.Side, end = Ui.Side, top = 4.dp),
        ) {
            (card?.person?.name ?: personName)?.let { name ->
                Words(
                    name, Ui.type(26, Ui.ExtraBold, lineHeight = 1.2f),
                    Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp), heading = true,
                )
            }
            if (card == null) {
                CardLoading(sections = 3)
            } else {
                CardShowBody(card)
            }
        }
    }
}

@Composable
private fun CardShowBody(card: HealthCardSummary) {
    Words(
        Labels.CARD_SHOW_DISCLAIMER, Ui.type(16, Ui.SemiBold, Ui.Muted),
        Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
    )
    if (card.medications.isNotEmpty()) {
        ShowSection(Labels.CARD_MEDICATIONS) {
            card.medications.forEach { medication -> ShowLine(medication.text) }
        }
    }
    val entries = card.entries.groupBy { it.fields.kind }
    for (kind in CardEntryKind.entries) {
        val section = entries[kind] ?: continue
        ShowSection(Labels.cardEntryTitle(kind)) {
            section.forEach { ShowEntry(it.fields) }
        }
    }
    card.lastEditedDate?.let { date ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.LineStrong))
        Words(
            Labels.cardLastEdited(date), Ui.type(16, color = Ui.Muted),
            Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 20.dp),
        )
    }
}

@Composable
private fun ShowSection(title: String, lines: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.5.dp).background(Ui.Ink))
    Column(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Words(title, Ui.type(18, Ui.ExtraBold, Ui.Muted, lineHeight = 1.2f), heading = true)
        lines()
    }
}

@Composable
private fun ShowLine(text: String) {
    Words(text, Ui.type(20, Ui.SemiBold))
}

@Composable
private fun ShowEntry(fields: CardEntryFields) {
    when (fields) {
        is CardEntryFields.Allergy -> ShowLine(withDetail(fields.substance, fields.reaction?.let { "반응: $it" }))
        is CardEntryFields.Condition -> ShowLine(withDateAndDetail(fields.name, fields.diagnosedOn, fields.note))
        is CardEntryFields.Procedure -> ShowLine(withDateAndDetail(fields.name, fields.performedOn, fields.place))
        is CardEntryFields.ImplantedDevice -> ShowLine(withDateAndDetail(fields.name, fields.implantedOn, fields.note))
        is CardEntryFields.CareProvider -> Column {
            ShowLine(withDetail(fields.name, fields.department))
            val contact = listOfNotNull(fields.doctor?.let { "담당 의사 $it" }, fields.phone)
            if (contact.isNotEmpty()) Words(contact.joinToString(" · "), Ui.type(18))
        }
        is CardEntryFields.Guardian -> ShowLine("${withDetail(fields.name, fields.relation)} ${fields.phone}")
    }
}

private fun withDetail(title: String, detail: String?): String =
    if (detail == null) title else "$title ($detail)"

private fun withDateAndDetail(title: String, date: String?, detail: String?): String =
    listOfNotNull(withDetail(title, date?.let(Labels::cardDate)), detail).joinToString(" · ")
