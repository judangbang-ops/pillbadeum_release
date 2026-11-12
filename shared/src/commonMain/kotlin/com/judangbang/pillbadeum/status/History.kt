package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.data.IntakeEntity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.ScheduleSpan
import com.judangbang.pillbadeum.domain.Window
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.dailyWindowOn
import com.judangbang.pillbadeum.domain.logicalDateOf
import com.judangbang.pillbadeum.domain.windowWithKey
import com.judangbang.pillbadeum.usecase.FlagClockMismatch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** One month of the history screen (SCR-008, REQ-037~040, record-rules-supplement.md P-8, S-5). */
data class HistoryMonth(
    /** First day of the month. */
    val month: LocalDate,
    val now: Instant,
    val settings: WindowSettings,
    /** Dates that have at least one cell, newest first. */
    val days: List<HistoryDay>,
    /** First day of the month of the earliest schedule start, for the "이전 달" limit; null with no items. */
    val firstMonth: LocalDate?,
    /** Two or more people are not archived: the person picker is shown (REQ-018, REQ-040, [showsPeople]). */
    val showPeople: Boolean = true,
) {
    /** SCR-008#empty: no item had a window in this month. */
    val isEmpty: Boolean get() = days.isEmpty()
}

data class HistoryDay(val date: LocalDate, val cells: List<HistoryCell>)

/**
 * One window of one item drawn on the line of the date the window starts (supplement P-8). A weekly
 * window is one cell on its anchor day; the other days of that week have no cell, not a missed one.
 */
data class HistoryCell(
    val intakeId: String,
    val intakeName: String,
    val person: PersonEntity,
    /** The schedule version the cell belongs to; tapping opens SCR-007 with it (P-1). */
    val scheduleVersionId: String,
    val rule: ScheduleRule,
    val window: Window,
    val state: CellState,
    /** The judgment shown in this cell, null for [CellState.MISSED] and [CellState.OPEN]. */
    val judgment: DoseRecordEntity?,
    /** [judgment] has an unanswered clock warning (SCR-008#future, supplement C-2). */
    val clockWarning: Boolean = false,
    /**
     * The old version's window that a schedule change cut short, kept only to show that version's own
     * judgment (supplement P-8). SCR-007 offers [기록 취소] and the time edit here; `RecordInWindow.record`
     * treats the window as the new version's and refuses a judgment change or a new record as stale.
     */
    val cutByScheduleChange: Boolean = false,
)

enum class CellState {
    TAKEN,

    /** Drawn with its own mark and the word `건너뜀`, never by colour alone (S-5). */
    SKIPPED,

    /** The window ended without an active judgment (REQ-038). */
    MISSED,

    /** The current window, not judged yet. */
    OPEN,
}

/**
 * Cells of one item between two logical dates (supplement P-8). Shared by the history screen and the home
 * screen's week grid so both draw the same cells:
 *
 * - a schedule version has the windows that end after it starts and, once closed, the windows that ended
 *   by its end ([ScheduleSpan.owns]); the window holding a schedule change is the new version's. The old
 *   version keeps a cell for that cut window only when it holds the old version's own judgment, so a judgment
 *   written before the change never drops out of history (Claude's choice, same idea as archiving below);
 * - windows that have not started yet have no cell;
 * - after the item or its person was archived there are no cells. The window holding that moment keeps
 *   its cell only when it has a judgment (Claude's choice: what was recorded before archiving stays visible,
 *   and the half-finished window is not counted as missed);
 * - a cell shows a judgment of its own version only by period key, and a judgment of another version by
 *   taken_at inside the window. So a day-start change never puts one judgment in two cells of the same
 *   version (RR-77), and in the week a schedule changed one judgment can show in both versions' cells (RR-78).
 */
internal class ItemCells(
    private val db: DidITakeDatabase,
    private val settings: WindowSettings,
    private val now: Instant,
) {
    suspend fun build(
        intake: IntakeEntity,
        person: PersonEntity,
        from: LocalDate,
        to: LocalDate,
        flagged: Set<String>,
    ): Map<LocalDate, List<HistoryCell>> {
        val spans = db.scheduleVersionDao().listByIntake(intake.id)
            .filter { it.archivedAt == null }
            .map { ScheduleSpan(it.id, ScheduleParams.toRule(it), Instant.fromEpochMilliseconds(it.effectiveFrom), it.effectiveTo?.let(Instant::fromEpochMilliseconds)) }
        if (spans.isEmpty()) return emptyMap()
        val cutoff = listOfNotNull(intake.archivedAt, person.archivedAt).minOrNull()?.let(Instant::fromEpochMilliseconds)

        // Weekly windows starting on `to` reach 7 days further; one extra day covers DST shifts.
        val records = db.doseRecordDao().listActiveForCells(
            intakeId = intake.id,
            fromKey = from.toString(),
            toKey = to.toString(),
            fromMillis = dailyWindowOn(from, settings).start.toEpochMilliseconds(),
            toMillis = dailyWindowOn(to.plus(8, DateTimeUnit.DAY), settings).start.toEpochMilliseconds(),
        )

        val result = LinkedHashMap<LocalDate, List<HistoryCell>>()
        var date = from
        while (date <= to) {
            val all = spans.mapNotNull { span -> cellOf(intake, person, span, date, cutoff, records, flagged) }
            // A cut cell is only there so its judgment stays visible; drop it when another cell of the line
            // already shows that judgment (a schedule changed and changed back in one window).
            val shown = all.filterNot { it.cutByScheduleChange }.mapNotNullTo(HashSet()) { it.judgment?.id }
            val cells = all.filterNot { it.cutByScheduleChange && it.judgment?.id in shown }
            if (cells.isNotEmpty()) result[date] = cells
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return result
    }

    private fun cellOf(
        intake: IntakeEntity,
        person: PersonEntity,
        span: ScheduleSpan,
        date: LocalDate,
        cutoff: Instant?,
        records: List<DoseRecordEntity>,
        flagged: Set<String>,
    ): HistoryCell? {
        val window = windowWithKey(span.rule, date.toString(), settings) ?: return null
        if (window.start > now) return null
        val cut = !span.owns(window) && span.to != null && window.start < span.to && window.end > span.to &&
            window.end > span.from
        if (!span.owns(window) && !cut) return null
        val judgment = if (cut) ownJudgment(span, window, records) ?: return null else pick(span, window, records)
        if (cutoff != null && window.end > cutoff && (window.start >= cutoff || judgment == null)) return null
        val state = when {
            judgment?.kind == DoseKind.TAKEN -> CellState.TAKEN
            judgment?.kind == DoseKind.SKIPPED -> CellState.SKIPPED
            now < window.end -> CellState.OPEN
            else -> CellState.MISSED
        }
        return HistoryCell(
            intakeId = intake.id,
            intakeName = intake.name,
            person = person,
            scheduleVersionId = span.versionId,
            rule = span.rule,
            window = window,
            state = state,
            judgment = judgment,
            clockWarning = judgment != null && judgment.id in flagged,
            cutByScheduleChange = cut,
        )
    }

    private fun ownJudgment(span: ScheduleSpan, window: Window, records: List<DoseRecordEntity>): DoseRecordEntity? =
        records.firstOrNull { it.scheduleVersionId == span.versionId && it.periodKey == window.periodKey }

    private fun pick(span: ScheduleSpan, window: Window, records: List<DoseRecordEntity>): DoseRecordEntity? =
        ownJudgment(span, window, records)
            ?: records.firstOrNull { it.scheduleVersionId != span.versionId && Instant.fromEpochMilliseconds(it.takenAt) in window }
}

/**
 * Reads one month of history (SCR-008, TASK-007). Archived items and people are included up to when
 * they were archived; [personId] keeps one person's items only (REQ-040). Voided judgments are not shown.
 * Like [LoadStatusBoard], reading raises clock flags first (supplement C-1).
 */
class LoadHistory(
    private val db: DidITakeDatabase,
    private val clock: Clock,
) {
    private val flagClockMismatch = FlagClockMismatch(db, clock)

    /** [month] is any date in the month to read. */
    suspend operator fun invoke(month: LocalDate, personId: String? = null): HistoryMonth {
        flagClockMismatch()
        val now = clock.now()
        val settings = db.settingDao().loadWindowSettings()
        val first = LocalDate(month.year, month.month, 1)
        val last = minOf(first.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY), logicalDateOf(now, settings))
        val flagged = db.clockFlagDao().listFlaggedJudgments().mapTo(HashSet()) { it.id }
        val people = db.personDao().listAll().associateBy { it.id }
        val cells = ItemCells(db, settings, now)

        val byDate = HashMap<LocalDate, MutableList<HistoryCell>>()
        val starts = mutableListOf<Long>()
        for (intake in db.intakeDao().listAll()) {
            val person = people[intake.personId] ?: continue
            if (personId != null && person.id != personId) continue
            db.scheduleVersionDao().listByIntake(intake.id).minOfOrNull { it.effectiveFrom }?.let(starts::add)
            if (last < first) continue
            for ((date, list) in cells.build(intake, person, first, last, flagged)) {
                byDate.getOrPut(date) { mutableListOf() } += list
            }
        }
        val firstMonth = starts.minOrNull()
            ?.let { logicalDateOf(Instant.fromEpochMilliseconds(it), settings) }
            ?.let { LocalDate(it.year, it.month, 1) }
        return HistoryMonth(
            month = first,
            now = now,
            settings = settings,
            days = byDate.keys.sortedDescending().map { HistoryDay(it, byDate.getValue(it)) },
            firstMonth = firstMonth,
            showPeople = showsPeople(people.values.count { it.archivedAt == null }),
        )
    }
}
