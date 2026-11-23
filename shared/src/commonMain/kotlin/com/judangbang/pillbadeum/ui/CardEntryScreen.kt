package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.CardEntryFields
import com.judangbang.pillbadeum.usecase.CardEntryKind
import com.judangbang.pillbadeum.usecase.CardField
import com.judangbang.pillbadeum.usecase.CardFieldProblem
import com.judangbang.pillbadeum.usecase.SaveCardEntryResult
import com.judangbang.pillbadeum.usecase.codePointCount
import com.judangbang.pillbadeum.usecase.limitCodePointInput

private val lineBreaks = Regex("\\R")

internal fun cardEditorTitle(kind: CardEntryKind, editing: Boolean): String {
    val action = if (editing) "고치기" else "적기"
    return "${Labels.cardEntryTitle(kind)} $action"
}

@Composable
internal fun CardEntryScreen(
    kind: CardEntryKind,
    initial: CardEntryFields?,
    busy: Boolean,
    message: String?,
    invalid: SaveCardEntryResult.Invalid?,
    onChange: (CardField) -> Unit,
    onClose: () -> Unit,
    onSave: (CardEntryFields) -> Unit,
) {
    val fields = kind.formFields()
    var values by rememberSaveable {
        mutableStateOf(fields.map { initial?.valueOf(it).orEmpty() }.toTypedArray())
    }
    val initialParts = initial?.valueOf(CardField.DATE).orEmpty().split('-')
    var dateParts by rememberSaveable {
        mutableStateOf(Array(3) { initialParts.getOrNull(it).orEmpty() })
    }
    var datePrecision by rememberSaveable {
        mutableStateOf(DatePrecision.entries[initialParts.size.coerceIn(1, 3) - 1])
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stacked = LocalDensity.current.fontScale >= 1.5f || maxWidth < 320.dp
        Column(Modifier.fillMaxSize()) {
            TopBar(cardEditorTitle(kind, initial != null), navigation = { BarIconButton("닫기", true, onClose) })
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = Ui.Side, vertical = 8.dp),
            ) {
                message?.let { Band(it, Modifier.padding(bottom = 16.dp), live = true) }
                CardNote(Labels.cardEntryHint(kind), Modifier.padding(bottom = 16.dp))
                fields.forEachIndexed { index, field ->
                    val error = invalid?.takeIf { it.field == field }?.let { Labels.cardEntryError(kind, field, it.problem) }
                    if (field == CardField.DATE) {
                        CardDateInput(
                            label = Labels.cardEntryField(kind, field),
                            parts = dateParts,
                            precision = datePrecision,
                            stacked = stacked,
                            error = error,
                            onPrecision = { datePrecision = it; onChange(field) },
                            onPart = { part, value -> dateParts = dateParts.copyOf().also { it[part] = value }; onChange(field) },
                        )
                    } else {
                        val required = index == 0 || (kind == CardEntryKind.GUARDIAN && field == CardField.PHONE)
                        CardEntryInput(kind, field, values[index], required, error) {
                            values = values.copyOf().also { copy -> copy[index] = it }
                            onChange(field)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.LineSubtle))
            ActionButton(
                "저장", ButtonLook.Ink,
                onClick = {
                    val valuesByField = fields.zip(values.asList()).toMap()
                    onSave(kind.toEntry(valuesByField, datePrecision.iso(dateParts)))
                },
                modifier = Modifier.padding(horizontal = Ui.Side, vertical = 12.dp),
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun CardEntryInput(
    kind: CardEntryKind,
    field: CardField,
    value: String,
    required: Boolean,
    error: String?,
    onChange: (String) -> Unit,
) {
    val limit = checkNotNull(field.maxCharacters)
    val count = value.trim().codePointCount()
    val multiline = field == CardField.NOTE || field == CardField.REACTION
    Field(
        label = Labels.cardEntryField(kind, field),
        value = value,
        onChange = { input ->
            val text = if (multiline) input else input.replace(lineBreaks, " ")
            onChange(text.limitCodePointInput(limit))
        },
        placeholder = if (field == CardField.PLACE) "예: 가상대학교병원" else "",
        optional = !required,
        note = if (count >= limit && error == null) Labels.cardEntryError(kind, field, CardFieldProblem.TOO_LONG) else null,
        error = error,
        keyboardOptions = if (field == CardField.PHONE) KeyboardOptions(keyboardType = KeyboardType.Phone) else KeyboardOptions.Default,
        singleLine = !multiline,
        counter = if (count * 10 >= limit * 9) "$count/$limit" else null,
    )
}

private enum class DatePrecision(val label: String, val digits: Int) {
    YEAR(Labels.CARD_DATE_YEAR, 1),
    MONTH(Labels.CARD_DATE_MONTH, 2),
    DAY(Labels.CARD_DATE_DAY, 3);

    /** Serialize the entered prefix. SaveCardEntry checks any incomplete or invalid date. */
    fun iso(parts: Array<String>): String? {
        val entered = parts.take(digits).map(String::trim).dropLastWhile(String::isEmpty)
        return entered.takeIf { it.isNotEmpty() }?.mapIndexed { index, value ->
            if (index > 0 && value.length == 1) value.padStart(2, '0') else value
        }?.joinToString("-")
    }
}

@Composable
private fun CardDateInput(
    label: String,
    parts: Array<String>,
    precision: DatePrecision,
    stacked: Boolean,
    error: String?,
    onPrecision: (DatePrecision) -> Unit,
    onPart: (Int, String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label, optional = true)
        Box(Modifier.selectableGroup()) {
            PickGrid(DatePrecision.entries, columns = if (stacked) 1 else 3) { option, modifier ->
                PickCell(option.label, option == precision, modifier) { onPrecision(option) }
            }
        }
        val units = listOf("년", "월", "일")
        val spokenUnits = listOf("연도", "월", "일")
        val item: @Composable (Int, Modifier) -> Unit = { index, modifier ->
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FormInput(
                    // Digits only, up to the unit's width: SaveCardEntry still checks the date itself.
                    parts[index], { onPart(index, it.filter { c -> c in '0'..'9' }.take(if (index == 0) 4 else 2)) },
                    "$label, ${spokenUnits[index]}",
                    modifier = Modifier.widthIn(min = 48.dp).weight(1f),
                    invalid = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Words(units[index], Ui.type(16), Modifier.hiddenFromTalkBack())
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(precision.digits) { item(it, Modifier.widthIn(max = 190.dp)) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(precision.digits) { item(it, Modifier.weight(if (it == 0) 1.3f else 1f)) }
                if (precision == DatePrecision.YEAR) Box(Modifier.weight(2f))
            }
        }
        if (error == null) {
            CardNote("모르면 비워 두세요.")
        } else {
            Words(error, Ui.type(14, Ui.SemiBold, Ui.Danger), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

private fun CardEntryKind.formFields(): List<CardField> = when (this) {
    CardEntryKind.ALLERGY -> listOf(CardField.SUBSTANCE, CardField.REACTION)
    CardEntryKind.CONDITION, CardEntryKind.IMPLANTED_DEVICE -> listOf(CardField.NAME, CardField.DATE, CardField.NOTE)
    CardEntryKind.PROCEDURE -> listOf(CardField.NAME, CardField.DATE, CardField.PLACE)
    CardEntryKind.CARE_PROVIDER -> listOf(CardField.PROVIDER_NAME, CardField.DEPARTMENT, CardField.DOCTOR, CardField.PHONE)
    CardEntryKind.GUARDIAN -> listOf(CardField.NAME, CardField.RELATION, CardField.PHONE)
}

private fun CardEntryFields.valueOf(field: CardField): String? = when (this) {
    is CardEntryFields.Allergy -> if (field == CardField.SUBSTANCE) substance else reaction
    is CardEntryFields.Condition -> when (field) {
        CardField.NAME -> name
        CardField.DATE -> diagnosedOn
        else -> note
    }
    is CardEntryFields.Procedure -> when (field) {
        CardField.NAME -> name
        CardField.DATE -> performedOn
        else -> place
    }
    is CardEntryFields.ImplantedDevice -> when (field) {
        CardField.NAME -> name
        CardField.DATE -> implantedOn
        else -> note
    }
    is CardEntryFields.CareProvider -> when (field) {
        CardField.PROVIDER_NAME -> name
        CardField.DEPARTMENT -> department
        CardField.DOCTOR -> doctor
        else -> phone
    }
    is CardEntryFields.Guardian -> when (field) {
        CardField.NAME -> name
        CardField.RELATION -> relation
        else -> phone
    }
}

private fun CardEntryKind.toEntry(values: Map<CardField, String>, date: String?): CardEntryFields = when (this) {
    CardEntryKind.ALLERGY -> CardEntryFields.Allergy(values.getValue(CardField.SUBSTANCE), values[CardField.REACTION])
    CardEntryKind.CONDITION -> CardEntryFields.Condition(values.getValue(CardField.NAME), date, values[CardField.NOTE])
    CardEntryKind.PROCEDURE -> CardEntryFields.Procedure(values.getValue(CardField.NAME), date, values[CardField.PLACE])
    CardEntryKind.IMPLANTED_DEVICE -> CardEntryFields.ImplantedDevice(values.getValue(CardField.NAME), date, values[CardField.NOTE])
    CardEntryKind.CARE_PROVIDER -> CardEntryFields.CareProvider(
        values.getValue(CardField.PROVIDER_NAME), values[CardField.DEPARTMENT], values[CardField.DOCTOR], values[CardField.PHONE],
    )
    CardEntryKind.GUARDIAN -> CardEntryFields.Guardian(values.getValue(CardField.NAME), values.getValue(CardField.PHONE), values[CardField.RELATION])
}
