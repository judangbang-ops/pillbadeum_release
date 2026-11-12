package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.PersonEntity

/**
 * A person of SCR-010 with how many of their items are not archived themselves ([items], `약 2개`). For an archived
 * person these are the items a restore brings back (SPEC-044: items archived on their own stay archived).
 */
data class PersonRow(val person: PersonEntity, val items: Int)

/**
 * What SCR-010 shows from the database (TASK-021): the people who are not archived, oldest first as everywhere else,
 * and the archived ones under `보관됨`, most recently archived first.
 */
data class PeopleView(val active: List<PersonRow>, val archived: List<PersonRow>) {
    /** REQ-093, SPEC-027: the last active person cannot be archived, so their form has no `[보관]`. */
    val canArchive: Boolean get() = active.size > 1
}

/** Reads [PeopleView] (SCR-010, TASK-021). */
class LoadPeople(private val db: DidITakeDatabase) {
    suspend operator fun invoke(): PeopleView {
        val items = db.intakeDao().listActive().groupingBy { it.personId }.eachCount()
        val people = db.personDao()
        fun row(person: PersonEntity) = PersonRow(person, items[person.id] ?: 0)
        return PeopleView(active = people.listActive().map(::row), archived = people.listArchived().map(::row))
    }
}
