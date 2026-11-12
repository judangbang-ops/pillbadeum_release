package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.domain.UuidV7
import kotlin.time.Clock

/** Outcome of editing, archiving or restoring a person. Only [Saved] changed a row. */
sealed interface PersonChange {
    data object Saved : PersonChange

    /** No such person in the state the call needs (active for edit/archive, archived for restore). */
    data object Gone : PersonChange

    /** Archiving would leave no active person; nothing changed (TASK-008). */
    data object LastPerson : PersonChange
}

/**
 * Person (대상자) management: the default "나" on first launch, family members, edit, archive and
 * restore (REQ-016, REQ-017). Archiving keeps the person's intakes and judgments; their buttons and
 * reminders disappear because every reader and writer skips intakes of archived people (REQ-005).
 */
class ManagePeople(
    private val db: DidITakeDatabase,
    private val clock: Clock,
    private val ids: UuidV7,
    private val deviceId: String,
) {
    suspend fun list(): List<PersonEntity> = db.personDao().listActive()

    /** Creates the default person when no person exists yet. Returns the people afterwards. */
    suspend fun ensureDefault(): List<PersonEntity> {
        val existing = list()
        if (existing.isNotEmpty()) return existing
        add(DEFAULT_PERSON_NAME, PersonColors.DEFAULT)
        return list()
    }

    /** @throws IllegalArgumentException on a blank name or a color outside `#RRGGBB`. */
    suspend fun add(name: String, color: String): PersonEntity {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "person name is blank" }
        require(PersonColors.isValid(color)) { "color must be #RRGGBB: $color" }
        val now = clock.now().toEpochMilliseconds()
        val person = PersonEntity(
            id = ids.next(),
            name = trimmed,
            color = color,
            createdAt = now,
            updatedAt = now,
            deviceId = deviceId,
        )
        db.personDao().insert(person)
        return person
    }

    /** Archived people for SCR-010 "되살리기", most recently archived first. */
    suspend fun listArchived(): List<PersonEntity> = db.personDao().listArchived()

    /** @throws IllegalArgumentException on a blank name or a color outside `#RRGGBB`. */
    suspend fun update(personId: String, name: String, color: String): PersonChange {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "person name is blank" }
        require(PersonColors.isValid(color)) { "color must be #RRGGBB: $color" }
        val changed = db.personDao().updateDetails(personId, trimmed, color, clock.now().toEpochMilliseconds())
        return if (changed == 1) PersonChange.Saved else PersonChange.Gone
    }

    /** Archives an active person unless it is the last one (checked in the same write transaction). */
    suspend fun archive(personId: String): PersonChange = db.useWriterConnection { transactor ->
        transactor.immediateTransaction {
            val dao = db.personDao()
            val person = dao.findById(personId)
            when {
                person == null || person.archivedAt != null -> PersonChange.Gone
                dao.countActive() <= 1 -> PersonChange.LastPerson
                dao.archive(personId, clock.now().toEpochMilliseconds()) == 1 -> PersonChange.Saved
                else -> PersonChange.Gone
            }
        }
    }

    /** Brings an archived person back; their intakes show again with their past judgments. */
    suspend fun restore(personId: String): PersonChange {
        val changed = db.personDao().restore(personId, clock.now().toEpochMilliseconds())
        return if (changed == 1) PersonChange.Saved else PersonChange.Gone
    }

    companion object {
        const val DEFAULT_PERSON_NAME: String = "나"
    }
}

/** Badge colors offered in the app (ui-mockup-v3: 나 = green, 가족 = orange). */
object PersonColors {
    const val DEFAULT: String = "#3F7D58"

    val PALETTE: List<String> = listOf(DEFAULT, "#B5562B", "#1D4E89", "#7A4E9C", "#A0405E", "#5B6B2E")

    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    fun isValid(color: String): Boolean = HEX.matches(color)

    /** `#RRGGBB` as an opaque ARGB int. Invalid input falls back to [DEFAULT]. */
    fun argb(color: String): Int {
        val hex = if (isValid(color)) color else DEFAULT
        return (0xFF000000L or hex.substring(1).toLong(16)).toInt()
    }
}
