package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.status.Labels

enum class EmergencySection { ALLERGY, MEDICATION, CONDITION, IMPLANTED_DEVICE, GUARDIAN, CARE_PROVIDER }

data class EmergencyLine(val section: EmergencySection, val text: String)

data class EmergencyText(val text: String, val lines: List<EmergencyLine>) {
    val characterCount: Int get() = text.length
}

class BuildEmergencyText {
    operator fun invoke(card: HealthCardSummary): EmergencyText? {
        if (card.isEmpty) return null
        val entries = card.entries.groupBy { it.fields.kind }
        val lines = buildList {
            fun addSection(section: EmergencySection, parts: List<String>) {
                if (parts.isNotEmpty()) add(EmergencyLine(section, parts.joinToString(", ")))
            }
            fun addEntries(section: EmergencySection, kind: CardEntryKind) {
                addSection(section, entries[kind].orEmpty().map { it.fields.emergencyText() })
            }
            addEntries(EmergencySection.ALLERGY, CardEntryKind.ALLERGY)
            addSection(EmergencySection.MEDICATION, card.medications.map { it.emergencyText })
            addEntries(EmergencySection.CONDITION, CardEntryKind.CONDITION)
            addEntries(EmergencySection.IMPLANTED_DEVICE, CardEntryKind.IMPLANTED_DEVICE)
            addEntries(EmergencySection.GUARDIAN, CardEntryKind.GUARDIAN)
            addEntries(EmergencySection.CARE_PROVIDER, CardEntryKind.CARE_PROVIDER)
        }
        val sentences = buildList {
            add(card.person.name)
            for (line in lines) add("${Labels.emergencySection(line.section, inSentence = true)}: ${line.text}")
            add(Labels.emergencyFooter(checkNotNull(card.lastEditedDate)))
        }
        return EmergencyText(sentences.joinToString(". ", postfix = "."), lines)
    }
}

private fun CardEntryFields.emergencyText(): String = when (this) {
    is CardEntryFields.Allergy -> withDetails(substance, reaction)
    is CardEntryFields.Condition -> withDetails(name, diagnosedOn?.let(Labels::cardDate), note)
    is CardEntryFields.ImplantedDevice -> withDetails(name, implantedOn?.let(Labels::cardDate), note)
    is CardEntryFields.Guardian -> listOfNotNull(withDetails(name, relation), phone.takeIf { it.isNotBlank() }).joinToString(" ")
    is CardEntryFields.CareProvider -> listOfNotNull(withDetails(name, department, doctor), phone?.takeIf { it.isNotBlank() })
        .joinToString(" ")
    is CardEntryFields.Procedure -> error("Procedures are not part of the emergency text")
}

private fun withDetails(name: String, vararg details: String?): String {
    val shown = details.filterNotNull().filter { it.isNotBlank() }
    return if (shown.isEmpty()) name else "$name(${shown.joinToString(", ")})"
}
