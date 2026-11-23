package com.judangbang.pillbadeum.usecase

import kotlinx.datetime.LocalDate

/** Section order of the health card (SCR-011). */
enum class CardEntryKind { ALLERGY, CONDITION, PROCEDURE, IMPLANTED_DEVICE, CARE_PROVIDER, GUARDIAN }

/** The editor keeps these values as entered. A date, when present, uses ISO year, year-month or date. */
sealed interface CardEntryFields {
    val kind: CardEntryKind

    data class Condition(val name: String, val diagnosedOn: String? = null, val note: String? = null) : CardEntryFields {
        override val kind get() = CardEntryKind.CONDITION
    }

    data class Procedure(val name: String, val performedOn: String? = null, val place: String? = null) : CardEntryFields {
        override val kind get() = CardEntryKind.PROCEDURE
    }

    data class Allergy(val substance: String, val reaction: String? = null) : CardEntryFields {
        override val kind get() = CardEntryKind.ALLERGY
    }

    data class ImplantedDevice(val name: String, val implantedOn: String? = null, val note: String? = null) : CardEntryFields {
        override val kind get() = CardEntryKind.IMPLANTED_DEVICE
    }

    data class CareProvider(
        val name: String,
        val department: String? = null,
        val doctor: String? = null,
        val phone: String? = null,
    ) : CardEntryFields {
        override val kind get() = CardEntryKind.CARE_PROVIDER
    }

    data class Guardian(val name: String, val phone: String, val relation: String? = null) : CardEntryFields {
        override val kind get() = CardEntryKind.GUARDIAN
    }
}

data class CardEntry(val id: String, val fields: CardEntryFields)

internal const val MIN_CARD_YEAR = 1900
private val cardDatePattern = Regex("[0-9]{4}(-[0-9]{2}){0,2}")

/** The first possible date at the precision supplied, solely for validation. */
internal fun earliestCardDate(value: String): LocalDate? {
    if (!cardDatePattern.matches(value)) return null
    val isoDate = when (value.length) {
        4 -> "$value-01-01"
        7 -> "$value-01"
        else -> value
    }
    return try {
        LocalDate.parse(isoDate)
    } catch (_: IllegalArgumentException) {
        null
    }
}
