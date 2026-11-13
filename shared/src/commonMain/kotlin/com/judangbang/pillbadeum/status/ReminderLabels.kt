package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.usecase.ReminderNotice
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * What one item's dose reminder says and offers (SCR-003, TASK-025). The Android notification is built
 * from it; [periodKey] goes into the record buttons (REQ-045) and [windowEnd] is when the reminder is
 * taken off at the latest (N-13).
 */
data class ReminderContent(
    val intakeId: String,
    val periodKey: String,
    /** The widget alias when set, else the item name (REQ-027); the title of a button's result. */
    val name: String,
    /** `아침약 먹어~!` */
    val title: String,
    /**
     * `마지막 복용 어제 08:02`, weekly `마지막 복용 어제(일) 22:00`, and a second line `어제 건너뜀` when a later skip
     * follows the last dose (W-2, W-4). The notification shows the first line folded and both lines unfolded.
     */
    val text: String,
    /** The person's name when the reminders cover more than one person, else null. */
    val person: String?,
    /** [오늘 건너뜀], or [이번 주 건너뜀] for a weekly item. */
    val skipLabel: String,
    /** [30분 뒤] is offered only while now + 30 minutes is before [windowEnd] (N-7, RR-85). */
    val canSnooze: Boolean,
    val windowEnd: Instant,
)

/**
 * Copy of the reminder notifications (SCR-003, SCR-003#lock, SCR-003#restored, TASK-025, TASK-040), kept
 * apart from [Labels] so the widget and app copy and this copy change separately. No line promises a delay
 * in minutes (supplement N-11, REQ-048); `30분 뒤` is the button the person chooses.
 */
object ReminderLabels {
    const val TAKEN: String = "먹었어"
    const val SNOOZE: String = "30분 뒤"
    const val SKIP_TODAY: String = "오늘 건너뜀"

    /** Weekly items (screens.md SCR-003 rules, approved 2026-11-13). */
    const val SKIP_THIS_WEEK: String = "이번 주 건너뜀"

    /** The one lock-screen notice while names are hidden, and the public version while shown (SPEC-013, N-13). */
    const val LOCK_TITLE: String = "복용 시간이에요"

    /** The one restore notice instead of dose reminders until [확인했어요] (SCR-003#restored, R-3). */
    const val RESTORED_TITLE: String = "백업에서 복원했어요"
    const val RESTORED_TEXT: String = "앱에서 확인해 주세요"

    /** A button pressed after the boundary: nothing written (SCR-003#stale, RR-23). */
    const val STALE: String = "날짜가 바뀌었어요. 앱에서 확인해 주세요"

    /** SCR-003#error. */
    const val ERROR: String = "기록하지 못했어요. 앱에서 확인해 주세요"

    /** Title of an item's reminder: the widget alias when set, else the name (REQ-027). */
    fun title(item: IntakeStatus): String = "${item.displayName} 먹어~!"

    /**
     * Body (SCR-003, REQ-021, supplement W-2, W-4): the lines of the widget button while the item is pending, the last
     * dose first (`마지막 복용 어제 22:00`, weekly with the weekday, or `기록 없음`) so a folded reminder shows it,
     * then a later skip (`어제 건너뜀`, `지난주 건너뜀`). Times and dates in [zone], the phone's (REQ-082, RR-58).
     */
    fun text(item: IntakeStatus, now: Instant, zone: TimeZone): String =
        listOfNotNull(Labels.lastTaken(item, now, zone), Labels.laterSkip(item, now, zone)).joinToString("\n")

    fun skip(rule: ScheduleRule): String = when (rule) {
        ScheduleRule.Daily -> SKIP_TODAY
        is ScheduleRule.Weekly -> SKIP_THIS_WEEK
    }

    /** SCR-003#already: `이미 기록됨 (08:02)` in [zone], the phone's (REQ-082), shown briefly and then closed. */
    fun already(existing: DoseRecordEntity, now: Instant, zone: TimeZone): String =
        "이미 기록됨 (${Labels.stamp(Instant.fromEpochMilliseconds(existing.takenAt), now, zone)})"

    /**
     * The reminder of [notice]'s item, from [board] read at the moment of showing, its times in [zone] (the phone's,
     * REQ-082; windows stay in the base zone). Null when the board no longer has that item waiting in the same window
     * (archived meanwhile, judged meanwhile, new window).
     *
     * The person's name goes in the notification header only when the board's items belong to more than one
     * person, so two people's same medicine is not mixed up (Claude default: SCR-003 names only the item).
     */
    fun content(board: StatusBoard, notice: ReminderNotice, zone: TimeZone): ReminderContent? {
        val item = board.items.firstOrNull { it.intakeId == notice.intakeId } ?: return null
        if (item.window.periodKey != notice.window.periodKey || item.judgment != null) return null
        val people = board.items.mapTo(HashSet()) { it.person.id }.size
        return ReminderContent(
            intakeId = item.intakeId,
            periodKey = item.window.periodKey,
            name = item.displayName,
            title = title(item),
            text = text(item, board.now, zone),
            person = item.person.name.takeIf { people > 1 },
            skipLabel = skip(item.rule),
            canSnooze = notice.canSnooze,
            windowEnd = item.window.end,
        )
    }
}
