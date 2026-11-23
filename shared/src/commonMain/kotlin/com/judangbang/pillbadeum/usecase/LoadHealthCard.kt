package com.judangbang.pillbadeum.usecase

import androidx.room3.deferredTransaction
import androidx.room3.useReaderConnection
import com.judangbang.pillbadeum.data.CareContactKind
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.IntakeEntity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.status.Labels
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

data class CardMedication(
    val intakeId: String,
    val name: String,
    val quantity: String?,
    val rule: ScheduleRule?,
    val slot: SlotLabel?,
    val hasMedicationName: Boolean,
) {
    private val dose get() = listOfNotNull(name, quantity?.takeIf { it.isNotBlank() }).joinToString(" ")
    private val schedule get() = rule?.let { Labels.healthCardSchedule(it, slot) }

    val text: String get() {
        val row = listOfNotNull(dose, schedule).joinToString(" · ")
        return if (hasMedicationName) row else "$row (${Labels.CARD_MEDICATION_NAME_MISSING})"
    }
    internal val emergencyText get() = if (hasMedicationName) listOfNotNull(dose, schedule).joinToString(" ") else text
}

enum class HealthCardDisplay { EMPTY, ARCHIVED, NORMAL }

data class HealthCardSummary(
    val person: PersonEntity,
    val entries: List<CardEntry>,
    val medications: List<CardMedication>,
    val lastEditedDate: LocalDate?,
    val staleNotice: StaleCardNotice?,
) {
    val archived get() = person.archivedAt != null
    val isEmpty get() = entries.isEmpty() && medications.isEmpty()
    val display: HealthCardDisplay get() = when {
        isEmpty -> HealthCardDisplay.EMPTY
        archived -> HealthCardDisplay.ARCHIVED
        else -> HealthCardDisplay.NORMAL
    }

    fun canShowOrExport(): Boolean = !isEmpty

    fun canOpenEmergencyText(): Boolean = medications.isNotEmpty() || entries.any { it.fields.kind != CardEntryKind.PROCEDURE }
}

sealed interface HealthCardSelection {
    data object None : HealthCardSelection
    data class Open(val personId: String) : HealthCardSelection
    data class Choose(val people: List<PersonEntity>) : HealthCardSelection
}

class LoadHealthCard(private val db: DidITakeDatabase) {
    /** Person management opens the card directly, including for an archived person. */
    suspend operator fun invoke(personId: String): HealthCardSummary? = load(personId, activeOnly = false)

    /** In management order. Also call again if a person was archived while the picker was open. */
    suspend fun selection(): HealthCardSelection {
        val active = db.personDao().listActive()
        return when (active.size) {
            0 -> HealthCardSelection.None
            1 -> HealthCardSelection.Open(active.single().id)
            else -> HealthCardSelection.Choose(active)
        }
    }

    /** Recheck that the person is selectable in the same snapshot used to load the card. */
    suspend fun fromHistory(personId: String): HealthCardSummary? = load(personId, activeOnly = true)

    private suspend fun load(personId: String, activeOnly: Boolean): HealthCardSummary? =
        db.useReaderConnection { reader ->
            reader.deferredTransaction {
                val person = db.personDao().findById(personId) ?: return@deferredTransaction null
                if (activeOnly && person.archivedAt != null) return@deferredTransaction null
                val card = db.healthCardDao().findByPerson(personId)
                val entries = db.listCardEntries(personId)
                val intakes = db.intakeDao().listActiveOfPerson(personId)
                val medications = db.currentMedications(intakes)
                val editedAt = card?.editedAt ?: intakes.takeIf { it.isNotEmpty() }?.let { summaryEditedAt(person, it) }
                val date = editedAt?.let {
                    Instant.fromEpochMilliseconds(it).toLocalDateTime(db.settingDao().loadWindowSettings().baseZone).date
                }
                HealthCardSummary(
                    person, entries, medications, date,
                    card?.staleNotice(db.healthCardRestoredAt(), entries.isEmpty() && medications.isEmpty()),
                )
            }
        }
}

/** An intake-only summary can exist before the first card write. */
internal fun summaryEditedAt(person: PersonEntity, intakes: List<IntakeEntity>): Long =
    maxOf(person.updatedAt, intakes.maxOfOrNull { it.updatedAt } ?: person.updatedAt)

private suspend fun DidITakeDatabase.currentMedications(intakes: List<IntakeEntity>): List<CardMedication> {
    val ordered = intakes.sortedWith(
        compareBy<IntakeEntity> { it.slotLabel?.ordinal ?: SlotLabel.entries.size }
            .thenBy { it.createdAt }
            .thenBy { it.sortOrder }
            .thenBy { it.id },
    )
    return ordered.flatMap { intake ->
        val rule = scheduleVersionDao().findOpen(intake.id)?.let(ScheduleParams::toRule)
        val rows = intakeMedicationDao().listByIntake(intake.id)
        if (rows.isEmpty()) {
            listOf(CardMedication(intake.id, intake.name, null, rule, intake.slotLabel, hasMedicationName = false))
        } else {
            rows.map { row ->
                val medication = checkNotNull(medicationDao().findById(row.medicationId))
                CardMedication(intake.id, medication.name, row.qtyPerTake, rule, intake.slotLabel, hasMedicationName = true)
            }
        }
    }
}

private suspend fun DidITakeDatabase.listCardEntries(personId: String): List<CardEntry> = buildList {
    for (row in conditionDao().listByPerson(personId)) {
        add(CardEntry(row.id, CardEntryFields.Condition(row.name, row.diagnosedOn, row.note)))
    }
    for (row in procedureHistoryDao().listByPerson(personId)) {
        add(CardEntry(row.id, CardEntryFields.Procedure(row.name, row.performedOn, row.place)))
    }
    for (row in allergyDao().listByPerson(personId)) {
        add(CardEntry(row.id, CardEntryFields.Allergy(row.substance, row.reaction)))
    }
    for (row in implantedDeviceDao().listByPerson(personId)) {
        add(CardEntry(row.id, CardEntryFields.ImplantedDevice(row.name, row.implantedOn, row.note)))
    }
    for (row in careContactDao().listByPerson(personId)) {
        val fields = when (row.kind) {
            CareContactKind.CARE_PROVIDER -> CardEntryFields.CareProvider(row.name, row.department, row.doctor, row.phone)
            CareContactKind.GUARDIAN -> CardEntryFields.Guardian(row.name, row.phone.orEmpty(), row.relation)
        }
        add(CardEntry(row.id, fields))
    }
}.sortedBy { it.fields.kind }
