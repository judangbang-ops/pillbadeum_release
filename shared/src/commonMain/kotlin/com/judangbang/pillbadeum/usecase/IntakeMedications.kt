package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.IntakeMedicationEntity
import com.judangbang.pillbadeum.data.MedicationEntity
import com.judangbang.pillbadeum.domain.UuidV7

/** One SCR-006 row. An ID is present only for a row already saved under this item. */
data class IntakeMedicationDraft(val name: String = "", val quantity: String = "", val id: String? = null) {
    companion object {
        const val MAX_ROWS = 10
        const val MAX_NAME = 50
        const val MAX_QUANTITY = 30
    }
}

/** Limit by Unicode code points so a surrogate pair counts as one character. */
internal fun String.takeMedicationCodePoints(limit: Int): String {
    var end = 0
    repeat(limit) {
        if (end == length) return this
        val high = this[end++].isHighSurrogate()
        if (high && end < length && this[end].isLowSurrogate()) end++
    }
    return substring(0, end)
}

/**
 * SCR-006 typing limit. Saving drops the outer spaces before counting (approved review 12), so they do not use up
 * [limit] here either: leading spaces stay, then at most [limit] code points follow. Spaces cut at the end were going
 * to be dropped anyway.
 */
internal fun String.limitMedicationInput(limit: Int): String {
    val lead = length - trimStart().length
    return substring(0, lead) + substring(lead).takeMedicationCodePoints(limit)
}

/** Called inside SaveIntake's writer transaction after validating that draft IDs belong to this item. */
internal suspend fun DidITakeDatabase.writeIntakeMedications(
    intakeId: String,
    drafts: List<IntakeMedicationDraft>,
    existing: Map<String, IntakeMedicationEntity>,
    now: Long,
    ids: UuidV7,
    deviceId: String,
): Boolean {
    val medications = medicationDao()
    val links = intakeMedicationDao()
    val kept = drafts.mapNotNull { it.id }.toSet()
    var changed = false
    for (row in existing.values) {
        if (row.id !in kept) {
            links.delete(intakeId, row.id)
            medications.deleteUnlinked(row.medicationId)
            changed = true
        }
    }
    for (draft in drafts) {
        val quantity = draft.quantity.ifEmpty { null }
        val row = existing[draft.id]
        if (row == null) {
            val medicationId = ids.next()
            medications.upsert(MedicationEntity(medicationId, draft.name, createdAt = now, updatedAt = now, deviceId = deviceId))
            links.upsert(
                IntakeMedicationEntity(
                    ids.next(), intakeId, medicationId, quantity,
                    effectiveFrom = now, createdAt = now, updatedAt = now, deviceId = deviceId,
                ),
            )
            changed = true
        } else {
            val medication = checkNotNull(medications.findById(row.medicationId))
            if (medication.name != draft.name) {
                medications.upsert(medication.copy(name = draft.name, updatedAt = now, version = medication.version + 1, deviceId = deviceId))
                changed = true
            }
            if (row.qtyPerTake != quantity) {
                links.upsert(row.copy(qtyPerTake = quantity, updatedAt = now, version = row.version + 1, deviceId = deviceId))
                changed = true
            }
        }
    }
    return changed
}
