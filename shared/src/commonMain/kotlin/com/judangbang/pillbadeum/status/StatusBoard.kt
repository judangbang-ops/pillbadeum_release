package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.domain.Window
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.dailyWindowOn
import com.judangbang.pillbadeum.domain.logicalDateOf
import com.judangbang.pillbadeum.domain.mondayToSunday
import com.judangbang.pillbadeum.domain.windowOf
import com.judangbang.pillbadeum.usecase.findActiveIn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

/** Read-only snapshot for the widget and the app's "오늘 · 이번 주" screen. */
data class StatusBoard(
    val now: Instant,
    val settings: WindowSettings,
    /** Logical date of [now] (header `11/11 (수)`). */
    val baseDate: LocalDate,
    /** Monday..Sunday of the week containing [baseDate]. */
    val week: List<LocalDate>,
    val items: List<IntakeStatus>,
) {
    val doneCount: Int get() = items.count { it.isDone }
    val totalCount: Int get() = items.size
}

data class IntakeStatus(
    val intakeId: String,
    val name: String,
    val widgetAlias: String?,
    val slotLabel: SlotLabel?,
    val person: PersonEntity,
    val rule: ScheduleRule,
    /** Current window; its period key is the one a widget button is rendered with. */
    val window: Window,
    /** Active judgment of [window], null while pending. */
    val judgment: DoseRecordEntity?,
    /** Latest active judgment taken before [window] started ("마지막 ..."). */
    val lastBefore: DoseRecordEntity?,
    /** Mon..Sun cells for daily items; empty for weekly items. */
    val weekCells: List<DayCell>,
) {
    val isDone: Boolean get() = judgment != null

    /** Short name for the widget: the alias when set. */
    val displayName: String get() = widgetAlias ?: name

    /** When the next window opens (weekly: the next anchor day at the day start). */
    val nextWindowStart: Instant get() = window.end
}

enum class DayState {
    /** An active judgment exists for that day. */
    DONE,

    /** Past day without a judgment. */
    EMPTY,

    /** Today, not judged yet. */
    TODAY_PENDING,

    /** Later this week. */
    FUTURE,

    /** No schedule version was in effect (e.g. before the item was registered). */
    NOT_SCHEDULED,
}

data class DayCell(val date: LocalDate, val state: DayState, val isToday: Boolean)

/**
 * Builds the [StatusBoard] from the database at the injected clock's time. Archived intakes are
 * excluded; their records stay in the database.
 */
class LoadStatusBoard(
    private val db: DidITakeDatabase,
    private val clock: Clock,
) {
    suspend operator fun invoke(): StatusBoard {
        val now = clock.now()
        val settings = db.settingDao().loadWindowSettings()
        val baseDate = logicalDateOf(now, settings)
        val week = mondayToSunday(baseDate)
        val people = db.personDao().listActive().associateBy { it.id }

        val items = db.intakeDao().listActive().mapNotNull { intake ->
            val person = people[intake.personId] ?: return@mapNotNull null
            val schedule = db.scheduleVersionDao().findEffective(intake.id, now.toEpochMilliseconds())
                ?: return@mapNotNull null
            val rule = ScheduleParams.toRule(schedule)
            val window = windowOf(rule, now, settings)
            val records = db.doseRecordDao()
            IntakeStatus(
                intakeId = intake.id,
                name = intake.name,
                widgetAlias = intake.widgetAlias,
                slotLabel = intake.slotLabel,
                person = person,
                rule = rule,
                window = window,
                judgment = records.findActiveIn(intake.id, window),
                lastBefore = records.findLastActiveBefore(intake.id, window.start.toEpochMilliseconds()),
                weekCells = if (rule is ScheduleRule.Daily) weekCells(intake.id, week, baseDate, settings) else emptyList(),
            )
        }
        return StatusBoard(now, settings, baseDate, week, items)
    }

    private suspend fun weekCells(
        intakeId: String,
        week: List<LocalDate>,
        today: LocalDate,
        settings: WindowSettings,
    ): List<DayCell> = week.map { date ->
        val state = when {
            date > today -> DayState.FUTURE
            else -> {
                val window = dailyWindowOn(date, settings)
                val lastInstant = window.end.toEpochMilliseconds() - 1
                when {
                    db.doseRecordDao().findActiveIn(intakeId, window) != null -> DayState.DONE
                    db.scheduleVersionDao().findEffective(intakeId, lastInstant) == null -> DayState.NOT_SCHEDULED
                    date == today -> DayState.TODAY_PENDING
                    else -> DayState.EMPTY
                }
            }
        }
        DayCell(date, state, isToday = date == today)
    }
}
