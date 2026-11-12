package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.data.IntakeEntity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.SettingDao
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.domain.Window
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.logicalDateOf
import com.judangbang.pillbadeum.domain.mondayToSunday
import com.judangbang.pillbadeum.domain.windowOf
import com.judangbang.pillbadeum.restore.CheckInstall
import com.judangbang.pillbadeum.usecase.FlagClockMismatch
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
    /** Shown until [확인했어요] after a restore (supplement R-3, REQ-073); null otherwise. */
    val restoreNotice: RestoreNotice? = null,

    /**
     * Judgments recorded while the phone clock was ahead and not answered yet, oldest first (REQ-009,
     * supplement C-2). The app shows each one until [그대로 두기] or [기록 취소], even after its day comes.
     */
    val clockWarnings: List<ClockWarning> = emptyList(),
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
    /**
     * [judgment] has an unresolved clock flag. The widget then shows `!` and `앱에서 확인` on this button;
     * a tap still changes nothing (`Already`). Only the current window's judgment can set it (C-2).
     */
    val clockWarning: Boolean = false,
) {
    val isDone: Boolean get() = judgment != null

    /** Short name for the widget: the alias when set. */
    val displayName: String get() = widgetAlias ?: name

    /** When the next window opens (weekly: the next anchor day at the day start). */
    val nextWindowStart: Instant get() = window.end
}

/** One unanswered clock warning (supplement C-2, SCR-004#future). */
data class ClockWarning(
    val judgment: DoseRecordEntity,
    val intakeName: String,
    val person: PersonEntity,
)

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
 * Restore notice state (supplement R-2, R-3).
 *
 * @property lastRecordAt latest active judgment before the restore was detected; null when the
 *   restored database held none.
 */
/**
 * The pending restore notice. [lastRecordAt] is the latest restored judgment before the restore;
 * null with [hasRecords] = true means every restored judgment is after this phone's clock.
 */
data class RestoreNotice(val lastRecordAt: Instant?, val hasRecords: Boolean = lastRecordAt != null)

/** Reads the restore notice settings; null unless `restore_notice_pending` = 1. */
suspend fun SettingDao.loadRestoreNotice(): RestoreNotice? {
    if (get(SettingKeys.RESTORE_NOTICE_PENDING) != CheckInstall.PENDING) return null
    val value = get(SettingKeys.RESTORE_NOTICE_LAST_RECORD_AT)
    val last = value?.toLongOrNull()?.let(Instant::fromEpochMilliseconds)
    return RestoreNotice(last, hasRecords = last != null || value == CheckInstall.ONLY_LATER_RECORDS)
}

/**
 * Builds the [StatusBoard] from the database at the injected clock's time. Archived intakes are
 * excluded; their records stay in the database.
 *
 * Reading also raises clock flags first (supplement C-1: "when the app or widget reads judgments"), so
 * this is the one read path that writes.
 */
class LoadStatusBoard(
    private val db: DidITakeDatabase,
    private val clock: Clock,
) {
    private val flagClockMismatch = FlagClockMismatch(db, clock)

    suspend operator fun invoke(): StatusBoard {
        flagClockMismatch()
        val flagged = db.clockFlagDao().listFlaggedJudgments()
        val flaggedIds = flagged.mapTo(HashSet()) { it.id }
        val now = clock.now()
        val settings = db.settingDao().loadWindowSettings()
        val baseDate = logicalDateOf(now, settings)
        val week = mondayToSunday(baseDate)
        val people = db.personDao().listActive().associateBy { it.id }

        val intakes = db.intakeDao().listActive()
        val cells = ItemCells(db, settings, now)
        val items = intakes.mapNotNull { intake ->
            val person = people[intake.personId] ?: return@mapNotNull null
            val schedule = db.scheduleVersionDao().findEffective(intake.id, now.toEpochMilliseconds())
                ?: return@mapNotNull null
            val rule = ScheduleParams.toRule(schedule)
            val window = windowOf(rule, now, settings)
            val records = db.doseRecordDao()
            val judgment = records.findActiveIn(intake.id, window)
            IntakeStatus(
                intakeId = intake.id,
                name = intake.name,
                widgetAlias = intake.widgetAlias,
                slotLabel = intake.slotLabel,
                person = person,
                rule = rule,
                window = window,
                judgment = judgment,
                lastBefore = records.findLastActiveBefore(intake.id, window.start.toEpochMilliseconds()),
                weekCells = if (rule is ScheduleRule.Daily) weekCells(cells, intake, person, week, baseDate, flaggedIds) else emptyList(),
                clockWarning = judgment != null && judgment.id in flaggedIds,
            )
        }
        val shown = intakes.associateBy { it.id }
        val clockWarnings = flagged.mapNotNull { record ->
            val intake = shown[record.intakeId] ?: return@mapNotNull null
            val person = people[intake.personId] ?: return@mapNotNull null
            ClockWarning(judgment = record, intakeName = intake.name, person = person)
        }
        return StatusBoard(now, settings, baseDate, week, items, db.settingDao().loadRestoreNotice(), clockWarnings)
    }

    /**
     * Mon..Sun of a daily item, drawn with the same cells as the history screen (supplement P-8): a day
     * without a daily cell of this item (before registration, another schedule) is [DayState.NOT_SCHEDULED].
     */
    private suspend fun weekCells(
        cells: ItemCells,
        intake: IntakeEntity,
        person: PersonEntity,
        week: List<LocalDate>,
        today: LocalDate,
        flagged: Set<String>,
    ): List<DayCell> {
        val byDate = cells.build(intake, person, week.first(), minOf(week.last(), today), flagged)
        return week.map { date ->
            val cell = byDate[date]?.firstOrNull { it.rule is ScheduleRule.Daily }
            val state = when {
                date > today -> DayState.FUTURE
                cell == null -> DayState.NOT_SCHEDULED
                cell.judgment != null -> DayState.DONE
                date == today -> DayState.TODAY_PENDING
                else -> DayState.EMPTY
            }
            DayCell(date, state, isToday = date == today)
        }
    }
}
