package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.domain.UuidV7
import kotlin.time.Clock

/** Person (대상자) registration: the default "나" on first launch, plus family members. */
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
