package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.design.PersonPalette
import com.judangbang.pillbadeum.design.Tokens
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

    /**
     * Brings an archived person back; their intakes show again with their past judgments (REQ-095,
     * SPEC-044, supplement P-9). There is no archive history table, so the archived stretch is kept in the
     * schedule versions instead, in the same write transaction as the restore: every intake of the person
     * that is not archived itself has its open version closed at the person's archive time and a new version
     * with the same schedule opened at the restore time. History then has no cells while the person was
     * archived (no version, not missed), the window holding the archive keeps a cell only for that version's
     * own judgment, and the window holding the restore is the new version's (P-8). Restoring again changes
     * nothing ([PersonChange.Gone]). Recomputing reminders and widgets is the caller's job (N-9).
     */
    suspend fun restore(personId: String): PersonChange = db.useWriterConnection { transactor ->
        transactor.immediateTransaction {
            val people = db.personDao()
            val archivedAt = people.findById(personId)?.archivedAt ?: return@immediateTransaction PersonChange.Gone
            val now = clock.now().toEpochMilliseconds()
            val versions = db.scheduleVersionDao()
            for (intake in db.intakeDao().listActiveOfPerson(personId)) {
                val open = versions.findOpen(intake.id) ?: continue
                // A version that starts after the archive (changed then, or the clock was wrong) already
                // leaves the archived stretch out; it is kept as it is.
                if (open.effectiveFrom > archivedAt) continue
                // With the clock set back before the archive, close and open at now so versions never overlap,
                // and never before the open version's own start (that leaves it empty, as SaveIntake.update does).
                val closeAt = maxOf(minOf(archivedAt, now), open.effectiveFrom)
                check(versions.close(open.id, effectiveTo = closeAt, now = now) == 1) {
                    "open schedule version ${open.id} could not be closed"
                }
                versions.insert(
                    open.copy(
                        id = ids.next(),
                        effectiveFrom = maxOf(now, closeAt),
                        effectiveTo = null,
                        createdAt = now,
                        updatedAt = now,
                        version = 1,
                        deviceId = deviceId,
                    ),
                )
            }
            // Read as archived in this same transaction, so the row must change; anything else rolls back.
            check(people.restore(personId, now) == 1) { "archived person $personId could not be restored" }
            PersonChange.Saved
        }
    }

    companion object {
        const val DEFAULT_PERSON_NAME: String = "나"
    }
}

/**
 * Person colors: the six lids of the 09 tokens (`color.person.1`~`6`, [Tokens.PEOPLE]). A person stores only the
 * lid color; the tile tint and text color come from the same token (09 mockup README section 7). The first, blue, is
 * the default "나"; the list before the tokens started with the old Pillbox green.
 */
object PersonColors {
    val PALETTE: List<String> = Tokens.PEOPLE.map { it.solidHex }

    val DEFAULT: String = PALETTE.first()

    /**
     * The six colors this list offered before the 09 tokens (`#3F7D58` first). Nothing was released with them, but
     * test phones may still hold them; each maps to the token at the same place.
     */
    private val BEFORE_TOKENS: List<String> = listOf("#3F7D58", "#B5562B", "#1D4E89", "#7A4E9C", "#A0405E", "#5B6B2E")

    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    fun isValid(color: String): Boolean = HEX.matches(color)

    /** `#RRGGBB` as an opaque ARGB int. Invalid input falls back to [DEFAULT]. */
    fun argb(color: String): Int {
        val hex = if (isValid(color)) color else DEFAULT
        return (0xFF000000L or hex.substring(1).toLong(16)).toInt()
    }

    /** The token palette of a stored color. A color from neither list gets the first palette. */
    fun palette(color: String): PersonPalette {
        val index = PALETTE.indexOfFirst { it.equals(color, ignoreCase = true) }
            .takeIf { it >= 0 } ?: BEFORE_TOKENS.indexOfFirst { it.equals(color, ignoreCase = true) }
        return Tokens.PEOPLE[index.coerceAtLeast(0)]
    }
}
