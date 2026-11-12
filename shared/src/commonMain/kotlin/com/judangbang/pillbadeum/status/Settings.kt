package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.IntakeEntity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.data.loadReminderSettings
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.domain.ReminderSettings
import com.judangbang.pillbadeum.domain.WindowSettings
import kotlinx.datetime.TimeZone

/** An archived item in SCR-009's list, which can be deleted for good there (REQ-015, SPEC-025). */
data class ArchivedItem(val intakeId: String, val name: String, val person: PersonEntity)

/**
 * What SCR-009 shows from the database (TASK-020): the day and reminder settings with their defaults, the lock-screen
 * switch, how many people are not archived ([people], for `대상자 관리 2명`) and the archived items, most recently
 * archived first. [showPeople] says whether the items are named with their person (two or more active people, REQ-018).
 */
data class SettingsView(
    val window: WindowSettings,
    val reminders: ReminderSettings,
    val showNamesOnLock: Boolean,
    val people: Int,
    val archived: List<ArchivedItem>,
) {
    val showPeople: Boolean get() = showsPeople(people)
}

/** Reads [SettingsView] (SCR-009, TASK-020). */
class LoadSettings(private val db: DidITakeDatabase) {
    suspend operator fun invoke(): SettingsView {
        val settings = db.settingDao()
        val people = db.personDao().listAll().associateBy { it.id }
        val archived = db.intakeDao().listAll()
            .filter { it.archivedAt != null }
            .sortedWith(compareByDescending<IntakeEntity> { it.archivedAt }.thenBy { it.id })
            .mapNotNull { intake -> people[intake.personId]?.let { ArchivedItem(intake.id, intake.name, it) } }
        return SettingsView(
            window = settings.loadWindowSettings(),
            reminders = settings.loadReminderSettings(),
            showNamesOnLock = settings.get(SettingKeys.SHOW_NAMES_ON_LOCK) == "true",
            people = people.values.count { it.archivedAt == null },
            archived = archived,
        )
    }
}

/** The regions of IANA zone names offered for the base zone; `Etc/…`, `SystemV/…` and old aliases are left out. */
private val ZONE_REGIONS = setOf("Africa", "America", "Antarctica", "Asia", "Atlantic", "Australia", "Europe", "Indian", "Pacific")

/**
 * The base-zone list of SCR-009 (SPEC-033: a zone is picked from the list only): [current], then the phone's [device]
 * zone and the default Asia/Seoul, then every other region zone by name. Each appears once.
 */
fun baseZoneChoices(current: String, device: String): List<String> {
    val top = listOf(current, device, WindowSettings.DEFAULT_BASE_ZONE_ID).distinct()
    val rest = TimeZone.availableZoneIds.filter { it.substringBefore('/', "") in ZONE_REGIONS }.sorted()
    return top + rest.filterNot { it in top }
}
