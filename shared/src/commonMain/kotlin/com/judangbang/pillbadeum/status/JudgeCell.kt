package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.data.loadWindowSettingsChangedAt
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.domain.Window
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.firstVersionStart
import com.judangbang.pillbadeum.usecase.FlagClockMismatch
import com.judangbang.pillbadeum.usecase.isLockedJudgment
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * One cell of SCR-007 판정 고치기 (TASK-018): a schedule version's window with its period key (record-rules-supplement.md
 * P-1), as it was when the screen read it at [now].
 */
data class JudgeCell(
    val intakeId: String,
    val intakeName: String,
    val slotLabel: SlotLabel?,
    val person: PersonEntity,
    val scheduleVersionId: String,
    val rule: ScheduleRule,
    val window: Window,
    /** The judgment the cell shows (P-8: its own version's by period key, another version's by time), null when none. */
    val judgment: DoseRecordEntity?,
    /** [judgment] has an unanswered clock warning (SCR-007#future, supplement C-2). */
    val clockWarning: Boolean,
    /**
     * SCR-007#locked (P-5, RR-44): the window, or the own cell of the judgment it shows, ended before the last day-start or
     * base-zone change. Only `[그대로 두기]` of a clock warning still works there (C-3).
     */
    val locked: Boolean,
    /**
     * The old version's window that a schedule change cut short (P-8), kept to show that version's judgment. Only the
     * judgment's time edit and its cancel are offered: the window itself belongs to the new version.
     */
    val cut: Boolean,
    /** The earliest time a dose of this item can move to: the start of its first version's first window (P-4). */
    val earliest: Instant,
    val now: Instant,
    val settings: WindowSettings,
    /** Two or more people are not archived: the person's name is shown ([showsPeople], REQ-018). */
    val showPeople: Boolean,
) {
    /** The window holds [now]: the title is `오늘 기록` and `[지금 먹었어]` is offered (09 Judge, P-2). */
    val current: Boolean get() = now in window

    val isTaken: Boolean get() = judgment?.kind == DoseKind.TAKEN
    val isSkipped: Boolean get() = judgment?.kind == DoseKind.SKIPPED

    /** The window's first day, its period key: the date of the title. */
    val day: LocalDate get() = LocalDate.parse(window.periodKey)

    /**
     * The times `[먹은 시각 고르기]` and a past window's `[먹었어로 바꾸기]` offer: inside the window and not after [at]
     * (P-2, REQ-032). Another day is recorded from its own cell (SCR-008).
     */
    fun recordRange(at: Instant, zone: TimeZone): PickRange =
        PickRange(window.start, minOf(window.end - 1.milliseconds, at), zone)

    /**
     * The times `[먹은 시각 고치기]` offers (P-6): from a day before the window to a day after it, not before [earliest]
     * and not after [at]. A time outside the window moves the dose to the cell holding that time (Claude's choice: a
     * dose put on the wrong day is usually a day off; further moves are a cancel and a new record from that day).
     */
    fun editRange(at: Instant, zone: TimeZone): PickRange =
        PickRange(maxOf(window.start - 1.days, earliest), minOf(window.end + 1.days - 1.milliseconds, at), zone)
}

/** What [LoadJudgeCell] found. */
sealed interface JudgeLoad {
    data class Ready(val cell: JudgeCell) : JudgeLoad

    /** SCR-007#gone: the item or its person is missing or archived. */
    data object Gone : JudgeLoad

    /** No such cell: its window has not started, or a schedule change gave the window to another version (P-8). */
    data object Missing : JudgeLoad
}

/**
 * Reads one cell of SCR-007 with the history screen's cells ([ItemCells], supplement P-8), so both show the same judgment.
 * Like [LoadStatusBoard], reading raises clock flags first (supplement C-1).
 */
class LoadJudgeCell(
    private val db: DidITakeDatabase,
    private val clock: Clock,
) {
    private val flagClockMismatch = FlagClockMismatch(db, clock)

    suspend operator fun invoke(intakeId: String, scheduleVersionId: String, periodKey: String): JudgeLoad {
        flagClockMismatch()
        val now = clock.now()
        val intake = db.intakeDao().findById(intakeId)
        val person = intake?.let { db.personDao().findById(it.personId) }
        if (intake == null || intake.archivedAt != null || person == null || person.archivedAt != null) return JudgeLoad.Gone
        val date = runCatching { LocalDate.parse(periodKey) }.getOrNull() ?: return JudgeLoad.Missing

        val settings = db.settingDao().loadWindowSettings()
        val flagged = db.clockFlagDao().listFlaggedJudgments().mapTo(HashSet()) { it.id }
        val cell = ItemCells(db, settings, now).build(intake, person, date, date, flagged)[date]
            ?.firstOrNull { it.scheduleVersionId == scheduleVersionId && it.window.periodKey == periodKey }
            ?: return JudgeLoad.Missing

        val first = db.scheduleVersionDao().listByIntake(intake.id).filter { it.archivedAt == null }.minBy { it.effectiveFrom }
        val earliest = firstVersionStart(ScheduleParams.toRule(first), Instant.fromEpochMilliseconds(first.effectiveFrom), settings)
        // A change time after now means the clock was ahead when the settings were saved; it counts as now (P-5, N-12).
        val changedAt = db.settingDao().loadWindowSettingsChangedAt()?.let { minOf(it, now) }
        val judgment = cell.judgment
        val locked = changedAt != null && (cell.window.end <= changedAt || (judgment != null && db.isLockedJudgment(judgment, now)))
        return JudgeLoad.Ready(
            JudgeCell(
                intakeId = intake.id,
                intakeName = intake.name,
                slotLabel = intake.slotLabel,
                person = person,
                scheduleVersionId = cell.scheduleVersionId,
                rule = cell.rule,
                window = cell.window,
                judgment = judgment,
                clockWarning = cell.clockWarning,
                locked = locked,
                cut = cell.cutByScheduleChange,
                earliest = earliest,
                now = now,
                settings = settings,
                showPeople = showsPeople(db.personDao().listActive().size),
            ),
        )
    }
}

/**
 * The times SCR-007's time picker offers (TASK-018; Claude's design, the 09 boards do not draw it): every [STEP] minutes of
 * the phone's clock in [zone], from [from] to [to], both included. The picker shows a date, then the hours of that date
 * that have a time, then the minutes; a time the clock skips (a daylight-saving gap) is never offered.
 */
class PickRange(val from: Instant, val to: Instant, val zone: TimeZone) {
    /** Dates with at least one time, oldest first. */
    val dates: List<LocalDate> by lazy {
        if (from > to) return@lazy emptyList()
        val last = to.toLocalDateTime(zone).date
        generateSequence(from.toLocalDateTime(zone).date) { it.plus(1, DateTimeUnit.DAY) }
            .takeWhile { it <= last }
            .filter { hours(it).isNotEmpty() }
            .toList()
    }

    val isEmpty: Boolean get() = dates.isEmpty()

    /** Hours of [date] that have at least one time. */
    fun hours(date: LocalDate): List<Int> = (0..23).filter { hour -> MINUTES.any { at(date, hour, it) != null } }

    /** Minutes of [date] [hour] that are offered. */
    fun minutes(date: LocalDate, hour: Int): List<Int> = MINUTES.filter { at(date, hour, it) != null }

    /** The instant of [date] [hour]:[minute] on the phone's clock, or null when it is not offered. */
    fun at(date: LocalDate, hour: Int, minute: Int): Instant? {
        if (hour !in 0..23 || minute !in MINUTES) return null
        val local = LocalDateTime(date, LocalTime(hour, minute))
        val instant = local.toInstant(zone)
        if (instant.toLocalDateTime(zone) != local) return null
        return instant.takeIf { it in from..to }
    }

    /** The offered time at or just before [at] on the phone's clock, to start a time edit from the stored time. */
    fun floor(at: Instant): Instant? {
        val local = at.toLocalDateTime(zone)
        return at(local.date, local.hour, local.minute - local.minute % STEP)
    }

    /** The earliest and the latest offered time, for the note under the picker; null when nothing is offered. */
    val first: Instant? get() = dates.firstOrNull()?.let { date -> hours(date).first().let { h -> at(date, h, minutes(date, h).first()) } }
    val last: Instant? get() = dates.lastOrNull()?.let { date -> hours(date).last().let { h -> at(date, h, minutes(date, h).last()) } }

    companion object {
        /** Minutes between two offered times. */
        const val STEP: Int = 5

        /** The minutes of an hour the picker offers: `00`, `05` … `55`. */
        val MINUTES: List<Int> = (0 until 60 step STEP).toList()
    }
}
