package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.usecase.RecordResult
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Korean UI copy shared by the widget, the app and the reminders (09 mockup tone).
 *
 * Every `zone` parameter is the display zone, the phone's own zone (`TimeZone.currentSystemDefault()`):
 * times are shown there while windows and base dates stay in the base zone (REQ-082, SPEC-042, RR-58,
 * TASK-043). `오늘`·`어제` count calendar dates in the display zone, whatever the day start (supplement S-2,
 * RR-76).
 */
object Labels {
    const val APP_NAME: String = "DidITake"
    const val NO_RECORD: String = "기록 없음"
    const val STALE: String = "날짜가 바뀌었어요. 다시 눌러 주세요"
    const val ERROR: String = "기록하지 못했어요. 앱에서 확인해 주세요"
    const val EMPTY_WIDGET: String = "앱에서 복용 항목을 추가해 주세요"

    /** A cell that ended before the last day-start or base-zone change (supplement P-5, SCR-007#locked). */
    const val LOCKED: String = "설정을 바꾸기 전의 기록은 고칠 수 없어요"

    fun weekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "월"
        DayOfWeek.TUESDAY -> "화"
        DayOfWeek.WEDNESDAY -> "수"
        DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"
        DayOfWeek.SATURDAY -> "토"
        DayOfWeek.SUNDAY -> "일"
    }

    fun slot(slot: SlotLabel): String = when (slot) {
        SlotLabel.MORNING -> "아침"
        SlotLabel.NOON -> "점심"
        SlotLabel.EVENING -> "저녁"
        SlotLabel.BEDTIME -> "자기 전"
    }

    /** `월요일` (TalkBack). */
    fun weekdayName(day: DayOfWeek): String = weekday(day) + "요일"

    /**
     * Joins a date word and its time, so `11/10(화)` and `21:40` never land on two lines (TASK-044 state
     * drawings, screens.md SCR-001).
     */
    const val NBSP: String = "\u00a0"

    /** `11/11 (수)`, `11/04 (수)`: the base date in headers and window dates (kept as it was, S-2). */
    fun date(date: LocalDate): String = "${monthDay(date)} (${weekday(date.dayOfWeek)})"

    /** `11/09(월)`: a date inside the last-dose and skip lines, no space before the bracket (S-2). */
    fun shortDate(date: LocalDate): String = "${monthDay(date)}(${weekday(date.dayOfWeek)})"

    /**
     * The date word of supplement S-2's table for [date] seen on [today], both calendar dates in the display
     * zone: `오늘`, `어제`, otherwise `11/09(월)` ('그저께' is not used). A weekly item always carries the
     * weekday: `오늘(월)`, `어제(일)` (W-2).
     */
    fun dayWord(date: LocalDate, today: LocalDate, weekly: Boolean): String {
        val weekdayMark = if (weekly) "(${weekday(date.dayOfWeek)})" else ""
        return when (date) {
            today -> "오늘$weekdayMark"
            today.minus(1, DateTimeUnit.DAY) -> "어제$weekdayMark"
            else -> shortDate(date)
        }
    }

    /** `어제 22:00`, `11/09(월) 22:00`, weekly `어제(일) 22:00`, with a no-break space before the time (S-2). */
    fun moment(at: Instant, now: Instant, zone: TimeZone, weekly: Boolean): String {
        val local = at.toLocalDateTime(zone)
        return dayWord(local.date, now.toLocalDateTime(zone).date, weekly) + NBSP + hhmm(local)
    }

    /** `08:02` */
    fun time(at: Instant, zone: TimeZone): String = hhmm(at.toLocalDateTime(zone))

    /** `11/09 21:40` (restore notice, screens.md SCR-001#restored, SCR-004#restored) */
    fun monthDayTime(at: Instant, zone: TimeZone): String {
        val local = at.toLocalDateTime(zone)
        return "${local.date.month.ordinal + 1}/${local.date.day.toString().padStart(2, '0')} ${hhmm(local)}"
    }

    /** Same-phone advice (REQ-074), also on first start and when adding a person. */
    const val ONE_PHONE: String = "한 약은 한 휴대폰에서 기록해 주세요"

    /**
     * Widget line under the header while a restore notice is pending (SCR-001#restored):
     * `복원됨 · 마지막 기록 11/09 21:40 · 앱에서 확인`. With no restored record the middle part is
     * `기록 없음`; when every restored record is after this phone's clock there is no middle part
     * (Claude defaults, the screens do not cover these cases).
     */
    fun restoreWidgetLine(notice: RestoreNotice, zone: TimeZone): String = when {
        notice.lastRecordAt != null -> "복원됨 · 마지막 기록 ${monthDayTime(notice.lastRecordAt, zone)} · 앱에서 확인"
        notice.hasRecords -> "복원됨 · 앱에서 확인"
        else -> "복원됨 · $NO_RECORD · 앱에서 확인"
    }

    /**
     * App notice at the top of the first screen (SCR-004#restored, REQ-073, REQ-074). With no
     * restored record, or only records after this phone's clock, the second sentence changes
     * (Claude defaults).
     */
    fun restoreAppNotice(notice: RestoreNotice, zone: TimeZone): String {
        val last = when {
            notice.lastRecordAt != null ->
                "마지막 기록은 ${monthDayTime(notice.lastRecordAt, zone)}이에요. 그 뒤의 기록은 없을 수 있어요."
            notice.hasRecords -> "다른 휴대폰에서 한 기록은 없을 수 있어요."
            else -> "복원된 복용 기록이 없어요. 다른 휴대폰에서 한 기록은 없을 수 있어요."
        }
        return "백업에서 복원했어요. $last $ONE_PHONE"
    }

    /** `1 / 3 완료`, with skips counted apart: `1 / 3 완료 · 건너뜀 1` (supplement S-1, REQ-020, RR-35). */
    fun count(board: StatusBoard): String {
        val done = "${board.doneCount} / ${board.totalCount} 완료"
        return if (board.skippedCount > 0) "$done · 건너뜀 ${board.skippedCount}" else done
    }

    /** `매일 아침`, `매일`, `주 1회 · 월` */
    fun schedule(rule: ScheduleRule, slot: SlotLabel?): String = when (rule) {
        ScheduleRule.Daily -> if (slot != null) "매일 ${slot(slot)}" else "매일"
        is ScheduleRule.Weekly -> "주 1회 · ${weekday(rule.anchor)}"
    }

    /** `08:02` on the same calendar date as [now], else `월 08:30` (the time of an `Already` message). */
    fun stamp(at: Instant, now: Instant, zone: TimeZone): String {
        val local = at.toLocalDateTime(zone)
        return if (local.date == now.toLocalDateTime(zone).date) hhmm(local) else weekday(local.date.dayOfWeek) + NBSP + hhmm(local)
    }

    /**
     * `마지막 복용 어제 22:00`, `마지막 복용 11/10(화) 22:00`, weekly `마지막 복용 어제(일) 22:00`, or `기록 없음`
     * (supplement S-2, W-2, RR-36, RR-37, RR-59, RR-61, RR-62). Only a dose counts, never a skip. The same line
     * goes on the widget, the app row and the reminder body (W-4).
     */
    fun lastTaken(item: IntakeStatus, now: Instant, zone: TimeZone): String {
        val last = item.lastTaken ?: return NO_RECORD
        return "마지막 복용 " + moment(Instant.fromEpochMilliseconds(last.takenAt), now, zone, item.rule is ScheduleRule.Weekly)
    }

    /**
     * The second line when the latest judgment before this window is a skip later than the last dose (S-2,
     * W-2): `어제 건너뜀`, `11/09(월) 건너뜀`; weekly `지난주 건너뜀` for the window just before, else
     * `11/02(월) 건너뜀`. The date is the skipped window's first day. Null otherwise.
     */
    fun laterSkip(item: IntakeStatus, now: Instant, zone: TimeZone): String? {
        val first = item.laterSkip?.let { skipDay(it) } ?: return null
        val word = when (item.rule) {
            ScheduleRule.Daily -> dayWord(first, now.toLocalDateTime(zone).date, weekly = false)
            is ScheduleRule.Weekly -> if (first == item.windowDay.minus(7, DateTimeUnit.DAY)) "지난주" else shortDate(first)
        }
        return "$word 건너뜀"
    }

    /** A dose in the current window: `08:02 먹음`, weekly `월 08:02 먹음` (09 mockup BoxWidget). */
    fun taken(judgment: DoseRecordEntity, zone: TimeZone, weekly: Boolean): String {
        val local = Instant.fromEpochMilliseconds(judgment.takenAt).toLocalDateTime(zone)
        val time = if (weekly) weekday(local.date.dayOfWeek) + NBSP + hhmm(local) else hhmm(local)
        return "$time 먹음"
    }

    /** A skip in the current window: `건너뜀` (S-3, SCR-001#skipped). */
    const val SKIPPED: String = "건너뜀"

    /**
     * The lines under a widget button's name (SCR-001, S-2, S-3, W-2): `08:02 먹음`; `건너뜀`; while pending the
     * last dose and, when a later skip follows it, a second line `어제 건너뜀`. A judgment with an open clock
     * warning shows `! 앱에서 확인` instead (C-2).
     */
    fun widgetLines(item: IntakeStatus, now: Instant, zone: TimeZone): List<String> {
        val judgment = item.judgment
        return when {
            item.clockWarning -> listOf(CLOCK_CHECK_IN_APP)
            judgment == null -> listOfNotNull(lastTaken(item, now, zone), laterSkip(item, now, zone))
            judgment.kind == DoseKind.TAKEN -> listOf(taken(judgment, zone, item.rule is ScheduleRule.Weekly))
            else -> listOf(SKIPPED)
        }
    }

    /**
     * What TalkBack reads for a widget button or an app row (supplement S-4, W-3, REQ-068):
     * `엄마 혈압약, 복용함, 오늘 09:10`, `엄마 혈압약, 건너뜀`, `엄마 혈압약, 미완료, 마지막 복용 어제 22:00`,
     * weekly `엄마 골다공증약, 이번 주 미완료, 마지막 복용 어제 일요일 22:00`. Dates are read as words
     * (`11월 9일 월요일`) and a weekly item's always with the weekday. The person is read only when [withPerson]
     * (two or more active people, REQ-018). [name] is what the screen shows: the widget alias on the widget.
     * Claude's additions: a later skip is read after the last dose (`…, 어제 건너뜀`), and an open clock
     * warning ends the sentence with `앱에서 확인` (C-2).
     */
    fun spoken(item: IntakeStatus, now: Instant, zone: TimeZone, withPerson: Boolean, name: String = item.displayName): String {
        val who = if (withPerson) "${item.person.name} $name" else name
        val weekly = item.rule is ScheduleRule.Weekly
        val today = now.toLocalDateTime(zone).date
        val judgment = item.judgment
        val parts = when {
            judgment == null -> listOfNotNull(
                who,
                if (weekly) "이번 주 미완료" else "미완료",
                item.lastTaken?.let { "마지막 복용 " + spokenMoment(it.takenAt, today, zone, weekly) } ?: NO_RECORD,
                spokenLaterSkip(item, today),
            )
            judgment.kind == DoseKind.TAKEN -> listOf(who, "복용함", spokenMoment(judgment.takenAt, today, zone, weekly))
            else -> listOf(who, SKIPPED)
        }
        val sentence = parts.joinToString(", ")
        return if (item.clockWarning) "$sentence, 앱에서 확인" else sentence
    }

    /**
     * Older wording of the latest judgment of either kind: `마지막 어제 21:40`, `마지막 11/04 (수) 09:10`, or
     * `기록 없음`. Its output is kept exactly as it was, only for the reminder body (R3, PR #20), until that body
     * moves to [lastTaken] (TASK-041 W-4, TASK-043). New code uses [lastTaken].
     */
    fun lastLabel(last: DoseRecordEntity?, now: Instant, zone: TimeZone): String {
        if (last == null) return NO_RECORD
        val local = Instant.fromEpochMilliseconds(last.takenAt).toLocalDateTime(zone)
        val today = now.toLocalDateTime(zone).date
        val day = when (local.date) {
            today -> "오늘"
            today.minus(1, DateTimeUnit.DAY) -> "어제"
            else -> date(local.date)
        }
        return "마지막 $day ${hhmm(local)}"
    }

    /** Widget button line for a judgment with a clock warning (SCR-001#clock, supplement C-2). */
    const val CLOCK_CHECK_IN_APP: String = "! 앱에서 확인"

    /** App warning (SCR-004#future): `기록 시각이 휴대폰 시계와 맞지 않았어요 (금 08:00). 확인해 주세요`. */
    fun clockWarning(judgment: DoseRecordEntity, zone: TimeZone): String {
        val local = Instant.fromEpochMilliseconds(judgment.takenAt).toLocalDateTime(zone)
        return "기록 시각이 휴대폰 시계와 맞지 않았어요 (${weekday(local.dayOfWeek)} ${hhmm(local)}). 확인해 주세요"
    }

    /**
     * Text of an app row under its name (SCR-004, 09 mockup BoxHome): [RowText.detail] `매일 아침 · 오늘 08:02 먹음`,
     * `매일 · 건너뜀 09:10`, `매일 · 마지막 복용 어제 21:40`. A weekly row also names its windows, as the app's
     * first screen must (SPEC-015, REQ-028): `주 1회 · 월 · 이번 창 11/09 (월)~ · 다음 창 11/16 (월)` while
     * pending, with the last dose on [RowText.boldLine] (W-2, REQ-075), else `… 먹음 · 다음 창 11/16 (월)`. The
     * layout itself comes with the app screen (TASK-016).
     */
    fun appRow(item: IntakeStatus, board: StatusBoard, zone: TimeZone): RowText {
        val now = board.now
        val weekly = item.rule is ScheduleRule.Weekly
        val judgment = item.judgment
        val schedule = schedule(item.rule, item.slotLabel)
        val next = if (weekly) " · 다음 창 ${date(item.windowDay.plus(7, DateTimeUnit.DAY))}" else ""
        return when {
            judgment != null && judgment.kind == DoseKind.TAKEN ->
                RowText("$schedule · ${moment(Instant.fromEpochMilliseconds(judgment.takenAt), now, zone, weekly)} 먹음$next")
            judgment != null ->
                RowText("$schedule · $SKIPPED ${stamp(Instant.fromEpochMilliseconds(judgment.takenAt), now, zone)}$next")
            weekly -> RowText(
                detail = "$schedule · 이번 창 ${date(item.windowDay)}~$next",
                boldLine = lastTaken(item, now, zone),
                skipLine = laterSkip(item, now, zone),
            )
            else -> RowText("$schedule · ${lastTaken(item, now, zone)}", skipLine = laterSkip(item, now, zone))
        }
    }

    /**
     * Message for a record attempt (record-rules.md section 6 table). `null` means "no message":
     * the refreshed button itself shows the result.
     */
    fun recordMessage(result: RecordResult, name: String, now: Instant, zone: TimeZone): String? = when (result) {
        is RecordResult.Recorded -> null
        is RecordResult.Already -> {
            val at = stamp(Instant.fromEpochMilliseconds(result.existing.takenAt), now, zone)
            // A skip is changed in the app only (S-3, SCR-001#already, REQ-023).
            if (result.existing.kind == DoseKind.SKIPPED) "$name: 건너뜀으로 기록됨 ($at). 바꾸려면 앱에서" else "$name: 이미 기록됨 ($at). 변경 없음"
        }
        is RecordResult.Stale -> STALE
        RecordResult.Gone -> null
        RecordResult.Locked -> LOCKED
        is RecordResult.Error -> ERROR
    }

    private fun hhmm(local: LocalDateTime): String =
        "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"

    private fun monthDay(date: LocalDate): String = "${date.month.ordinal + 1}/${date.day.toString().padStart(2, '0')}"

    /** The skipped window's first day: its period key (P-8), or null for a key that is not a date. */
    private fun skipDay(skip: DoseRecordEntity): LocalDate? = runCatching { LocalDate.parse(skip.periodKey) }.getOrNull()

    /** `오늘 09:10`, `어제 22:00`, `11월 9일 월요일 22:00`; weekly `어제 일요일 22:00` (S-4, W-3). */
    private fun spokenMoment(takenAt: Long, today: LocalDate, zone: TimeZone, weekly: Boolean): String {
        val local = Instant.fromEpochMilliseconds(takenAt).toLocalDateTime(zone)
        return spokenDay(local.date, today, weekly) + " " + hhmm(local)
    }

    private fun spokenDay(date: LocalDate, today: LocalDate, weekly: Boolean): String {
        val name = weekdayName(date.dayOfWeek)
        return when (date) {
            today -> if (weekly) "오늘 $name" else "오늘"
            today.minus(1, DateTimeUnit.DAY) -> if (weekly) "어제 $name" else "어제"
            else -> "${date.month.ordinal + 1}월 ${date.day}일 $name"
        }
    }

    private fun spokenLaterSkip(item: IntakeStatus, today: LocalDate): String? {
        val first = item.laterSkip?.let { skipDay(it) } ?: return null
        val word = when (item.rule) {
            ScheduleRule.Daily -> spokenDay(first, today, weekly = false)
            is ScheduleRule.Weekly -> if (first == item.windowDay.minus(7, DateTimeUnit.DAY)) "지난주" else spokenDay(first, today, weekly = true)
        }
        return "$word 건너뜀"
    }
}

/** An app row's text under the item name (SCR-004). */
data class RowText(
    /** Schedule and the dose, skip or last dose: `매일 아침 · 오늘 08:02 먹음`. */
    val detail: String,
    /** A pending weekly item's last dose, drawn bold on its own line (W-2, REQ-075); null otherwise. */
    val boldLine: String? = null,
    /** `어제 건너뜀` when a skip came after the last dose (S-2); null otherwise. */
    val skipLine: String? = null,
)
