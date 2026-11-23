package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.AllergyEntity
import com.judangbang.pillbadeum.data.CareContactEntity
import com.judangbang.pillbadeum.data.CareContactKind
import com.judangbang.pillbadeum.data.ConditionEntity
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.ImplantedDeviceEntity
import com.judangbang.pillbadeum.data.ProcedureHistoryEntity
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.domain.UuidV7
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

enum class CardField(val maxCharacters: Int? = 50) {
    NAME, SUBSTANCE, PROVIDER_NAME, DATE(null), NOTE(200), PLACE, REACTION(200), DEPARTMENT, DOCTOR, PHONE(20), RELATION,
}

enum class CardFieldProblem { REQUIRED, TOO_LONG, INVALID_DATE, BEFORE_1900, FUTURE_DATE }

sealed interface SaveCardEntryResult {
    data class Saved(val id: String) : SaveCardEntryResult
    data class Invalid(val field: CardField, val problem: CardFieldProblem) : SaveCardEntryResult
    data object LimitReached : SaveCardEntryResult
    data object Gone : SaveCardEntryResult
}

class SaveCardEntry(
    private val db: DidITakeDatabase,
    private val clock: Clock,
    private val ids: UuidV7,
    private val deviceId: String,
) {
    /** Allocate once when opening a new entry so repeated submits keep the same identity. */
    fun newEntryId(): String = ids.next()

    suspend fun add(personId: String, entryId: String, fields: CardEntryFields): SaveCardEntryResult =
        save(personId, entryId, fields, create = true)

    suspend fun edit(personId: String, entryId: String, fields: CardEntryFields): SaveCardEntryResult =
        save(personId, entryId, fields, create = false)

    private suspend fun save(personId: String, id: String, fields: CardEntryFields, create: Boolean): SaveCardEntryResult =
        db.useWriterConnection { writer ->
            writer.immediateTransaction {
                if (db.personDao().findById(personId) == null) return@immediateTransaction SaveCardEntryResult.Gone
                val instant = clock.now()
                val now = instant.toEpochMilliseconds()
                val today = instant.toLocalDateTime(db.settingDao().loadWindowSettings().baseZone).date
                val normalized = fields.normalized()
                normalized.invalid(today)?.let { return@immediateTransaction it }
                val result = write(personId, id, normalized, create, now)
                if (result == null) db.markHealthCardEdited(personId, now, deviceId)
                result ?: SaveCardEntryResult.Saved(id)
            }
        }

    private suspend fun write(
        personId: String, id: String, fields: CardEntryFields, create: Boolean, now: Long,
    ): SaveCardEntryResult? = when (fields) {
        is CardEntryFields.Condition -> db.conditionDao().let { dao ->
            writeEntry(
                dao.listByPerson(personId), ConditionEntity::id, id, create,
                insert = {
                    dao.insert(
                        ConditionEntity(
                            id = id, personId = personId,
                            name = fields.name, diagnosedOn = fields.diagnosedOn, note = fields.note,
                            createdAt = now, updatedAt = now, deviceId = deviceId,
                        ),
                    )
                },
                update = {
                    dao.upsert(
                        it.copy(
                            name = fields.name, diagnosedOn = fields.diagnosedOn, note = fields.note,
                            updatedAt = now, version = it.version + 1, deviceId = deviceId,
                        ),
                    )
                },
            )
        }
        is CardEntryFields.Procedure -> db.procedureHistoryDao().let { dao ->
            writeEntry(
                dao.listByPerson(personId), ProcedureHistoryEntity::id, id, create,
                insert = {
                    dao.insert(
                        ProcedureHistoryEntity(
                            id = id, personId = personId,
                            name = fields.name, performedOn = fields.performedOn, place = fields.place,
                            createdAt = now, updatedAt = now, deviceId = deviceId,
                        ),
                    )
                },
                update = {
                    dao.upsert(
                        it.copy(
                            name = fields.name, performedOn = fields.performedOn, place = fields.place,
                            updatedAt = now, version = it.version + 1, deviceId = deviceId,
                        ),
                    )
                },
            )
        }
        is CardEntryFields.Allergy -> db.allergyDao().let { dao ->
            writeEntry(
                dao.listByPerson(personId), AllergyEntity::id, id, create,
                insert = {
                    dao.insert(
                        AllergyEntity(
                            id = id, personId = personId,
                            substance = fields.substance, reaction = fields.reaction,
                            createdAt = now, updatedAt = now, deviceId = deviceId,
                        ),
                    )
                },
                update = {
                    dao.upsert(
                        it.copy(
                            substance = fields.substance, reaction = fields.reaction,
                            updatedAt = now, version = it.version + 1, deviceId = deviceId,
                        ),
                    )
                },
            )
        }
        is CardEntryFields.ImplantedDevice -> db.implantedDeviceDao().let { dao ->
            writeEntry(
                dao.listByPerson(personId), ImplantedDeviceEntity::id, id, create,
                insert = {
                    dao.insert(
                        ImplantedDeviceEntity(
                            id = id, personId = personId,
                            name = fields.name, implantedOn = fields.implantedOn, note = fields.note,
                            createdAt = now, updatedAt = now, deviceId = deviceId,
                        ),
                    )
                },
                update = {
                    dao.upsert(
                        it.copy(
                            name = fields.name, implantedOn = fields.implantedOn, note = fields.note,
                            updatedAt = now, version = it.version + 1, deviceId = deviceId,
                        ),
                    )
                },
            )
        }
        is CardEntryFields.CareProvider -> db.careContactDao().let { dao ->
            writeEntry(
                dao.listByPerson(personId).filter { it.kind == CareContactKind.CARE_PROVIDER }, CareContactEntity::id, id, create,
                insert = {
                    dao.insert(
                        CareContactEntity(
                            id = id, personId = personId, kind = CareContactKind.CARE_PROVIDER,
                            name = fields.name, department = fields.department, doctor = fields.doctor, phone = fields.phone,
                            createdAt = now, updatedAt = now, deviceId = deviceId,
                        ),
                    )
                },
                update = {
                    dao.upsert(
                        it.copy(
                            name = fields.name, department = fields.department, doctor = fields.doctor, phone = fields.phone,
                            updatedAt = now, version = it.version + 1, deviceId = deviceId,
                        ),
                    )
                },
            )
        }
        is CardEntryFields.Guardian -> db.careContactDao().let { dao ->
            writeEntry(
                dao.listByPerson(personId).filter { it.kind == CareContactKind.GUARDIAN }, CareContactEntity::id, id, create,
                insert = {
                    dao.insert(
                        CareContactEntity(
                            id = id, personId = personId, kind = CareContactKind.GUARDIAN,
                            name = fields.name, phone = fields.phone, relation = fields.relation,
                            createdAt = now, updatedAt = now, deviceId = deviceId,
                        ),
                    )
                },
                update = {
                    dao.upsert(
                        it.copy(
                            name = fields.name, phone = fields.phone, relation = fields.relation,
                            updatedAt = now, version = it.version + 1, deviceId = deviceId,
                        ),
                    )
                },
            )
        }
    }

    // A null result is a new write, so the caller records the card edit in the same transaction.
    private suspend fun <Entry> writeEntry(
        rows: List<Entry>,
        idOf: (Entry) -> String,
        id: String,
        create: Boolean,
        insert: suspend () -> Unit,
        update: suspend (Entry) -> Unit,
    ): SaveCardEntryResult? {
        val existing = rows.firstOrNull { idOf(it) == id }
        if (create) {
            if (existing != null) return SaveCardEntryResult.Saved(id)
            if (rows.size >= 20) return SaveCardEntryResult.LimitReached
            insert()
        } else {
            if (existing == null) return SaveCardEntryResult.Gone
            update(existing)
        }
        return null
    }
}

private fun CardEntryFields.invalid(today: LocalDate): SaveCardEntryResult.Invalid? = when (this) {
    is CardEntryFields.Condition ->
        textProblem(CardField.NAME, name, required = true) ?: dateProblem(diagnosedOn, today)
            ?: textProblem(CardField.NOTE, note)
    is CardEntryFields.Procedure ->
        textProblem(CardField.NAME, name, required = true) ?: dateProblem(performedOn, today)
            ?: textProblem(CardField.PLACE, place)
    is CardEntryFields.Allergy ->
        textProblem(CardField.SUBSTANCE, substance, required = true)
            ?: textProblem(CardField.REACTION, reaction)
    is CardEntryFields.ImplantedDevice ->
        textProblem(CardField.NAME, name, required = true) ?: dateProblem(implantedOn, today)
            ?: textProblem(CardField.NOTE, note)
    is CardEntryFields.CareProvider ->
        textProblem(CardField.PROVIDER_NAME, name, required = true)
            ?: textProblem(CardField.DEPARTMENT, department)
            ?: textProblem(CardField.DOCTOR, doctor)
            ?: textProblem(CardField.PHONE, phone)
    is CardEntryFields.Guardian ->
        textProblem(CardField.NAME, name, required = true)
            ?: textProblem(CardField.RELATION, relation)
            ?: textProblem(CardField.PHONE, phone, required = true)
}

private fun textProblem(field: CardField, value: String?, required: Boolean = false) = when {
    required && value.isNullOrBlank() -> SaveCardEntryResult.Invalid(field, CardFieldProblem.REQUIRED)
    value != null && value.codePointCount() > checkNotNull(field.maxCharacters) ->
        SaveCardEntryResult.Invalid(field, CardFieldProblem.TOO_LONG)
    else -> null
}

private fun dateProblem(value: String?, today: LocalDate): SaveCardEntryResult.Invalid? {
    if (value.isNullOrEmpty()) return null
    val date = earliestCardDate(value)
    val problem = when {
        date == null -> CardFieldProblem.INVALID_DATE
        date.year < MIN_CARD_YEAR -> CardFieldProblem.BEFORE_1900
        date > today -> CardFieldProblem.FUTURE_DATE
        else -> return null
    }
    return SaveCardEntryResult.Invalid(CardField.DATE, problem)
}
