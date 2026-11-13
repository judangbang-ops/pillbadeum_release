package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.ReminderSettings
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.usecase.InvalidTimeReason
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.RecordResult
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.offsetAt
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
    const val LOADING: String = "불러오는 중…"

    /** What TalkBack reads for the widget's down arrow while its list overflows; the arrow has no text (REQ-078). */
    const val MORE: String = "더 있음"

    /** SCR-001#reconfigure: a widget restored without its choice while two or more people are active (REQ-079). */
    const val RECONFIGURE: String = "위젯을 다시 설정해 주세요"

    /** SCR-001#reconfigure when the widget's person is archived (SPEC-027). */
    const val RECONFIGURE_ARCHIVED: String = "대상자가 보관됐어요. 위젯을 다시 설정해 주세요"

    /** SCR-001#empty on a widget that shows one person: `엄마 항목이 없어요` (screens.md, proposal). */
    fun emptyFor(name: String): String = "$name 항목이 없어요"

    /** SCR-002 (09 WidgetPick). */
    const val PICK_TITLE: String = "이 위젯에 누구 약을 보일까요?"
    const val PICK_SUBTITLE: String = "고른 사람의 약만 이 위젯에 보여요."
    const val PICK_EVERYONE: String = "전체"
    const val PICK_CANCEL: String = "취소"

    /** SCR-002#error. */
    const val PICK_SAVE_FAILED: String = "저장하지 못했어요. 다시 골라 주세요"

    /** `나·엄마의 약 4개` under 전체 (09 WidgetPick). */
    fun pickEveryoneDetail(names: List<String>, count: Int): String =
        if (names.isEmpty()) "약 ${count}개" else "${names.joinToString("·")}의 약 ${count}개"

    /** `약 2개` under a person (09 WidgetPick). */
    fun pickPersonDetail(count: Int): String = "약 ${count}개"

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
     * [word] with the object particle its last letter takes: `아침약을`, `철분제를`. A name that does not end in a Korean
     * letter (`Vitamin`, `오메가3`) gets both, `Vitamin을(를)`.
     */
    fun withObject(word: String): String {
        val last = word.lastOrNull()
        if (last == null || last !in '가'..'힣') return "${word}을(를)"
        return word + if ((last - '가') % 28 == 0) "를" else "을"
    }

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
    fun count(board: StatusBoard): String = countParts(board).let { (done, rest) -> "$done $rest" }

    /**
     * [count] cut where the widget header changes weight (09 BoxWidget, TASK-014): the bold `1 / 3`, then `완료` or
     * `완료 · 건너뜀 1`.
     */
    fun countParts(board: StatusBoard): Pair<String, String> {
        val rest = if (board.skippedCount > 0) "완료 · 건너뜀 ${board.skippedCount}" else "완료"
        return "${board.doneCount} / ${board.totalCount}" to rest
    }

    /** `DidITake · 11/12`: app name and base date beside the weekday chip of the widget header (09 BoxWidget). */
    fun widgetDate(date: LocalDate): String = "$APP_NAME · ${monthDay(date)}"

    /** `매일 아침`, `매일`, `주 1회 월요일` (09 BoxHome). */
    fun schedule(rule: ScheduleRule, slot: SlotLabel?): String = when (rule) {
        ScheduleRule.Daily -> if (slot != null) "매일 ${slot(slot)}" else "매일"
        is ScheduleRule.Weekly -> "주 1회 ${weekdayName(rule.anchor)}"
    }

    /** `11월 12일 목요일`: the base date beside the weekday badge of the app's first screen (09 BoxHome). */
    fun longDate(date: LocalDate): String = "${date.month.ordinal + 1}월 ${date.day}일 ${weekdayName(date.dayOfWeek)}"

    /** `하루 시작 04:00` (09 BoxHome, HomeEmpty). */
    fun dayStart(board: StatusBoard): String = "하루 시작 ${board.settings.dayStart}"

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
    fun spoken(
        item: IntakeStatus,
        now: Instant,
        zone: TimeZone,
        withPerson: Boolean,
        name: String = item.displayName,
        inApp: Boolean = false,
    ): String {
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
        return when {
            !item.clockWarning -> sentence
            inApp -> "$sentence, 시계 확인 필요"
            else -> "$sentence, 앱에서 확인"
        }
    }

    /**
     * What TalkBack reads for a row of the app's week box (09 BoxHome): `혈압약 이번 주: 월 화 복용, 수 빠짐, 목 아직`.
     * Days in a row with the same state are read together, in week order; later days and days without a cell of this
     * item are left out. The person comes first with two or more active people (REQ-018): `엄마 혈압약 이번 주: …`.
     */
    fun weekSpoken(item: IntakeStatus, withPerson: Boolean): String {
        val who = if (withPerson) "${item.person.name} ${item.name}" else item.name
        val runs = mutableListOf<Pair<MutableList<String>, String>>()
        for (cell in item.weekCells) {
            val word = when (cell.state) {
                DayState.DONE -> "복용"
                DayState.SKIPPED -> SKIPPED
                DayState.EMPTY -> "빠짐"
                DayState.TODAY_PENDING -> "아직"
                DayState.FUTURE, DayState.NOT_SCHEDULED -> null
            }
            if (word == null) continue
            val last = runs.lastOrNull()
            if (last != null && last.second == word) last.first += weekday(cell.date.dayOfWeek)
            else runs += mutableListOf(weekday(cell.date.dayOfWeek)) to word
        }
        val days = runs.joinToString(", ") { (names, word) -> names.joinToString(" ") + " " + word }
        return "$who 이번 주: ${days.ifEmpty { NO_RECORD }}"
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

    /** The medical note of the app's first screen (REQ-036). */
    const val MEDICAL_NOTE: String = "기록 도구이며 복용 방법은 의사·약사와 상의해 주세요."

    // SCR-005, the first-run intro (09 Welcome1, Welcome2; TASK-022).

    const val INTRO_SKIP: String = "건너뛰기"
    const val INTRO_NEXT: String = "다음"
    const val INTRO_PIN: String = "위젯 추가"
    const val INTRO_START: String = "시작하기"

    /** Page 1's week-box picture, read as one sentence (09 Welcome1 `aria-label`). */
    const val INTRO_PICTURE: String = "요일 약통 그림: 월 화 수는 먹음, 목요일은 오늘"

    /** Page 1's headline; `돼요` never stands alone on the last line (09 Welcome1 `&nbsp;`). */
    const val INTRO_HEADLINE: String = "먹었는지 헷갈릴 때, 위젯 한 번이면${NBSP}돼요"
    const val INTRO_LEAD: String = "약을 먹고 홈 화면 위젯을 한 번 누르면 시각과 함께 남아요."

    /** The record-tool note (REQ-036): its bold first sentence uses the app's name, then [INTRO_TOOL_BODY]. */
    val INTRO_TOOL_TITLE: String get() = "${withTopic(APP_NAME)} 기록 도구예요."
    const val INTRO_TOOL_BODY: String = "복용 방법은 의사·약사와 상의해 주세요."

    /** The one-phone note (REQ-074): [ONE_PHONE] in bold, then this. */
    const val INTRO_ONE_PHONE_BODY: String = "휴대폰끼리는 기록이 이어지지 않아요."

    const val INTRO_PIN_TITLE: String = "홈 화면에 위젯을 놓아 주세요"
    const val INTRO_PIN_LEAD: String = "위젯에서 바로 누르면 앱을 열지 않아도 기록돼요."

    /** How to place the widget by hand (REQ-080), always shown; the launcher lists the widget under the app's name. */
    val INTRO_STEPS: List<String> get() = listOf("홈 화면의 빈 곳을 길게 누르기", "'위젯'에서 $APP_NAME 찾기", "4×2 크기로 끌어다 놓기")
    const val INTRO_STEPS_NOTE: String = "휴대폰마다 메뉴 이름이 조금 달라요."

    /** What TalkBack reads for the page marks: `2쪽 중 1쪽` (09 Welcome1 `aria-label`). */
    fun introPage(page: Int, pages: Int): String = "${pages}쪽 중 ${page}쪽"

    /**
     * [word] with the topic particle its last letter takes: `필받음은`, `약봉이는`. A word that does not end in a Korean
     * letter (`DidITake`) gets both, `DidITake은(는)`, as [withObject] does.
     */
    fun withTopic(word: String): String {
        val last = word.lastOrNull()
        if (last == null || last !in '가'..'힣') return "${word}은(는)"
        return word + if ((last - '가') % 28 == 0) "는" else "은"
    }

    /** SCR-004#error, with `[다시 시도]` (screens.md, proposal approved 2026-11-13). */
    const val LOAD_FAILED: String = "기록을 불러오지 못했어요"

    /** SCR-006#error: the name is empty (09 ItemFormError). */
    const val NAME_REQUIRED: String = "이름을 입력해 주세요"

    /** The mark on an app tile whose judgment has an open clock warning; the warning band above says more (SCR-004#future). */
    const val CLOCK_MARK: String = "! 시계 확인"

    /** In-app wording of [RecordResult.Error] (screens.md SCR-004: "기록하지 못했어요. 다시 눌러 주세요"). */
    const val TRY_AGAIN: String = "기록하지 못했어요. 다시 눌러 주세요"

    /**
     * The band over two or more clock warnings (SCR-004#future, supplement C-3): `시계가 맞지 않았던 기록이 2개 있어요.`,
     * and with more than [shown] `… 7개 있어요. 아래에 2개씩 보여요.`
     */
    fun clockWarnings(count: Int, shown: Int): String =
        "시계가 맞지 않았던 기록이 ${count}개 있어요." + if (count > shown) " 아래에 ${shown}개씩 보여요." else ""

    /** App warning (SCR-004#future): `기록 시각이 휴대폰 시계와 맞지 않았어요 (금 08:00). 확인해 주세요`. */
    fun clockWarning(judgment: DoseRecordEntity, zone: TimeZone): String {
        val local = Instant.fromEpochMilliseconds(judgment.takenAt).toLocalDateTime(zone)
        return "기록 시각이 휴대폰 시계와 맞지 않았어요 (${weekday(local.dayOfWeek)} ${hhmm(local)}). 확인해 주세요"
    }

    /**
     * Text of an app tile under its name (SCR-004, 09 BoxHome): [RowText.detail] `매일 아침 · 오늘 08:02 먹음`,
     * `매일 · 건너뜀 09:10`, `매일 · 마지막 복용 어제 21:40`. A pending weekly tile is `주 1회 월요일` with the last
     * dose on [RowText.boldLine] (W-2, REQ-075). A judged weekly tile names its next window, as the first screen must
     * (SPEC-015, REQ-028): `주 1회 월요일 · 오늘(월) 08:30 먹음` and [RowText.nextLine] `다음 창 11/16(월)`.
     */
    fun appRow(item: IntakeStatus, board: StatusBoard, zone: TimeZone): RowText {
        val now = board.now
        val weekly = item.rule is ScheduleRule.Weekly
        val judgment = item.judgment
        val schedule = schedule(item.rule, item.slotLabel)
        val next = if (weekly) "다음 창 ${shortDate(item.windowDay.plus(7, DateTimeUnit.DAY))}" else null
        return when {
            judgment != null && judgment.kind == DoseKind.TAKEN ->
                RowText("$schedule · ${moment(Instant.fromEpochMilliseconds(judgment.takenAt), now, zone, weekly)} 먹음", nextLine = next)
            judgment != null ->
                RowText("$schedule · $SKIPPED ${stamp(Instant.fromEpochMilliseconds(judgment.takenAt), now, zone)}", nextLine = next)
            weekly -> RowText(schedule, boldLine = lastTaken(item, now, zone), skipLine = laterSkip(item, now, zone))
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

    // -----------------------------------------------------------------------------------------------------------------
    // SCR-007 판정 고치기 (TASK-018, 09 Judge and JudgeTaken, TASK-044 drawings). A title's date is the window's first
    // day; times are on the phone's clock (REQ-082). Date, weekday and time are joined with [NBSP], so `11/11 (수) 04:00`
    // never breaks inside (TASK-044).

    /** SCR-006#gone and SCR-007#gone. */
    const val GONE: String = "이 항목은 보관됐거나 지워졌어요"

    /** SCR-007#stale: the screen was opened on the current window and that window ended; it closes to SCR-004. */
    const val DAY_CHANGED: String = "날짜가 바뀌었어요"

    /** SCR-007#locked, the line under the band (proposal, TASK-044). */
    const val LOCKED_NOTE: String = "하루 시작 시각이나 기준 시간대를 바꾸기 전에 끝난 날이에요."

    /** A time edit that saved (Claude's wording). */
    const val TIME_EDITED: String = "먹은 시각을 고쳤어요"

    /** A time edit to the stored time (`WindowWriteResult.Unchanged`, Claude's wording). */
    const val UNCHANGED: String = "바뀐 것이 없어요"

    /** `VoidResult.NotActive`: voided elsewhere meanwhile. */
    const val ALREADY_VOIDED: String = "이미 취소된 기록이에요"

    /** A past cell's write found the screen out of date (the settings changed after it was read); it reads again. */
    const val RELOADED: String = "화면을 새로 불러왔어요. 다시 눌러 주세요"

    /** `오늘 기록` for the current window; `11/11 (수) 기록 고치기`, weekly `11/02 (월) 주 기록 고치기` (TASK-044). */
    fun judgeTitle(cell: JudgeCell): String = when {
        cell.current -> "오늘 기록"
        cell.rule is ScheduleRule.Weekly -> "${dateKept(cell.day)} 주 기록${NBSP}고치기"
        else -> "${dateKept(cell.day)} 기록${NBSP}고치기"
    }

    /** `매일 · 저녁`, `매일`, `주 1회 · 월요일` (09 Judge). TalkBack reads [schedule] instead. */
    fun judgeCycle(rule: ScheduleRule, slot: SlotLabel?): String = when (rule) {
        ScheduleRule.Daily -> if (slot != null) "매일 · ${slot(slot)}" else "매일"
        is ScheduleRule.Weekly -> "주 1회 · ${weekdayName(rule.anchor)}"
    }

    /**
     * The window: daily `11/11 (수) 04:00 ~ 11/12 (목) 04:00` on the phone's clock, weekly its days `11/02 (월) ~ 11/08 (일)`.
     * A locked day shows only its date `11/11 (수)`: its hours were set by the day start before the change, which is not
     * kept, so today's day start would draw hours the day never had.
     */
    fun judgeWindow(cell: JudgeCell, zone: TimeZone): String = when (cell.rule) {
        ScheduleRule.Daily -> if (cell.locked) dateKept(cell.day) else "${dateTime(cell.window.start, zone)} ~ ${dateTime(cell.window.end, zone)}"
        is ScheduleRule.Weekly -> "${dateKept(cell.day)} ~ ${dateKept(cell.day.plus(6, DateTimeUnit.DAY))}"
    }

    /** What TalkBack reads for [judgeWindow]: `11월 11일 수요일 04:00부터 11월 12일 목요일 04:00까지`. */
    fun judgeWindowSpoken(cell: JudgeCell, zone: TimeZone): String = when (cell.rule) {
        ScheduleRule.Daily -> if (cell.locked) longDate(cell.day) else {
            val start = cell.window.start.toLocalDateTime(zone)
            val end = cell.window.end.toLocalDateTime(zone)
            "${longDate(start.date)} ${hhmm(start)}부터 ${longDate(end.date)} ${hhmm(end)}까지"
        }
        is ScheduleRule.Weekly -> "${longDate(cell.day)}부터 ${longDate(cell.day.plus(6, DateTimeUnit.DAY))}까지"
    }

    /**
     * The judgment under the window (09 Judge, TASK-044): `아직 기록이 없어요` in the current window, `기록이 없어요` in a past
     * one, `08:10에 먹음`, `건너뜀`. A skip shows its time only when it was pressed in its own window, as the widget and
     * the first screen do: a past window's skip stores the window start, which is not a time anyone chose (P-3). Its input
     * time tells them apart even after the day start changed.
     */
    fun judgeStatus(cell: JudgeCell, zone: TimeZone): String {
        val judgment = cell.judgment ?: return if (cell.current) "아직 기록이 없어요" else "기록이 없어요"
        val at = Instant.fromEpochMilliseconds(judgment.takenAt)
        return when (judgment.kind) {
            DoseKind.TAKEN -> "${cellTime(cell, at, zone)}에 먹음"
            DoseKind.SKIPPED -> if (judgment.takenAt != judgment.recordedAt) SKIPPED else "${cellTime(cell, at, zone)}에 건너뜀"
        }
    }

    /**
     * A time in [cell]: `08:10` on the window's first day; on another day, and always for a weekly item, with its date
     * `11/12 (목) 01:30` (Claude's choice: 09 draws only a first-day time).
     */
    fun cellTime(cell: JudgeCell, at: Instant, zone: TimeZone): String {
        val local = at.toLocalDateTime(zone)
        val first = cell.window.start.toLocalDateTime(zone).date
        return if (cell.rule is ScheduleRule.Daily && local.date == first) hhmm(local) else dateTime(at, zone)
    }

    /** The note under the buttons (09 Judge and JudgeTaken, TASK-044); the current window's skip note is Claude's. */
    fun judgeNote(cell: JudgeCell, zone: TimeZone): String {
        val span = "${dateTime(cell.window.start, zone)}부터 ${dateTime(cell.window.end, zone)} 전까지"
        return when {
            cell.locked -> LOCKED_NOTE
            cell.judgment == null && cell.current ->
                "먹은 시각은 ${nearTime(cell.window.start, cell.now, zone)}부터 지금(${time(cell.now, zone)})까지 고를 수 있어요. " +
                    "다른 날은 지난 기록에서 열어요."
            cell.judgment == null -> "먹은 시각은 ${span} 고를 수 있어요."
            cell.isTaken -> "기록을 취소해도 지워지지 않고 무효로 남아요."
            cell.cut -> "건너뜀을 취소해도 지워지지 않고 무효로 남아요."
            cell.current -> "먹었어로 바꾸면 지금 시각으로 기록해요."
            else -> "먹었어로 바꾸면 먹은 시각을 먼저 골라요. ${span}예요."
        }
    }

    /** The picker's note: `11/11 (수) 04:00부터 11/12 (목) 03:55까지 고를 수 있어요.`; a time edit says it can move the dose. */
    fun pickNote(range: PickRange, edit: Boolean): String {
        val first = range.first ?: return "고를 수 있는 시각이 아직 없어요."
        val last = range.last ?: first
        val span = "${dateTime(first, range.zone)}부터 ${dateTime(last, range.zone)}까지 고를 수 있어요."
        return if (edit) "$span 다른 날의 시각이면 그날 기록으로 옮겨요." else span
    }

    /** The picked time above the confirm button: `11/11 (수) 21:40`. */
    fun picked(at: Instant, zone: TimeZone): String = dateTime(at, zone)

    /**
     * SCR-007#already: `이미 기록됨 (08:02)`, a skip `이미 건너뜀으로 기록됨`. [dated] for a time edit stopped by another
     * judgment, with that judgment's day: `11/11에 이미 기록됨 (21:30)` (REQ-031).
     */
    fun judgeAlready(existing: DoseRecordEntity, dated: Boolean, now: Instant, zone: TimeZone): String {
        val at = Instant.fromEpochMilliseconds(existing.takenAt)
        val day = if (dated) runCatching { LocalDate.parse(existing.periodKey) }.getOrNull()?.let { "${monthDay(it)}에 " } ?: "" else ""
        return when (existing.kind) {
            DoseKind.TAKEN -> "${day}이미 기록됨 (${if (dated) time(at, zone) else stamp(at, now, zone)})"
            DoseKind.SKIPPED -> "${day}이미 건너뜀으로 기록됨"
        }
    }

    /** A picked time the record rules refuse (REQ-032); the picker offers none of these, so they come from a race. */
    fun invalidTime(reason: InvalidTimeReason): String = when (reason) {
        InvalidTimeReason.FUTURE -> "지금보다 뒤 시각은 고를 수 없어요"
        InvalidTimeReason.BEFORE_SCHEDULE -> "항목을 등록하기 전 시각은 고를 수 없어요"
        InvalidTimeReason.OUTSIDE_WINDOW, InvalidTimeReason.NOT_STARTED -> "고를 수 없는 시각이에요"
    }

    /** After `[기록 취소]` or `[건너뜀 취소]`: `아침약 기록을 취소했어요. 기록은 무효로 남아요` (SCR-004, SCR-007). */
    fun voided(name: String, skip: Boolean): String =
        "$name ${if (skip) "건너뜀을" else "기록을"} 취소했어요. 기록은 무효로 남아요"

    /** SCR-008's month (09 History): `2026년 11월`. */
    fun monthTitle(month: LocalDate): String = "${month.year}년 ${month.month.ordinal + 1}월"

    /** What TalkBack reads for a month button: the month it opens (09 History `10월`), with its year when that changes. */
    fun monthButton(target: LocalDate, shown: LocalDate): String =
        if (target.year == shown.year) "${target.month.ordinal + 1}월" else monthTitle(target)

    /** SCR-008#empty */
    const val HISTORY_EMPTY: String = "이 달에는 기록할 항목이 없었어요"

    /** A window that ended without an active judgment (REQ-038, 09 History). */
    const val MISSED: String = "빠짐"

    /** The current window, not judged yet (09 History). */
    const val PENDING: String = "아직"

    /** The base date's line in SCR-008 (09 History). */
    const val TODAY: String = "오늘"

    /** A weekly cell's days (REQ-039): `11/09~11/15`. */
    fun weekRange(cell: HistoryCell): String = cellDay(cell).let { "${monthDay(it)}~${monthDay(it.plus(6, DateTimeUnit.DAY))}" }

    /**
     * A history cell's judgment (09 History, S-5): the time of a dose, `08:02` on the window's first day and with its date
     * on another (`11/12 01:30`, the day start is 04:00 or the phone is in another zone), a weekly dose always with its
     * date (`11/10(화) 08:30`); `건너뜀`, `빠짐`, `아직`. A skip shows no time, as 09 draws it.
     */
    fun historyStatus(cell: HistoryCell, zone: TimeZone): String = when (cell.state) {
        CellState.TAKEN -> cell.judgment?.let { historyTime(cell, it.takenAt, zone) }.orEmpty()
        CellState.SKIPPED -> SKIPPED
        CellState.MISSED -> MISSED
        CellState.OPEN -> PENDING
    }

    /**
     * What TalkBack reads for a history cell (09 History): `엄마 혈압약, 21:40 먹음`, `비타민D, 건너뜀`, `혈압약, 빠짐`, a dose on
     * another day `아침약, 11월 12일 목요일 01:30 먹음`, weekly `엄마 골다공증약, 11월 9일부터 15일까지, 아직`. [withPerson] puts
     * the person first. An open clock warning ends it with `시계 확인 필요` (C-2), as the first screen's tiles do.
     */
    fun historySpoken(cell: HistoryCell, withPerson: Boolean, zone: TimeZone): String {
        val who = if (withPerson) "${cell.person.name} ${cell.intakeName}" else cell.intakeName
        val state = when (cell.state) {
            CellState.TAKEN -> cell.judgment?.let { "${spokenHistoryTime(cell, it.takenAt, zone)} 먹음" } ?: "먹음"
            CellState.SKIPPED -> SKIPPED
            CellState.MISSED -> MISSED
            CellState.OPEN -> PENDING
        }
        val days = if (cell.rule is ScheduleRule.Weekly) spokenWeekRange(cellDay(cell)) else null
        val sentence = listOfNotNull(who, days, state).joinToString(", ")
        return if (cell.clockWarning) "$sentence, 시계 확인 필요" else sentence
    }

    /** What TalkBack reads for a history line's date: `11월 12일 목요일`, the base date's `11월 12일 목요일, 오늘`. */
    fun historyDay(date: LocalDate, today: LocalDate): String = if (date == today) "${longDate(date)}, $TODAY" else longDate(date)

    // SCR-009 설정 (TASK-020, 09 Settings) ---------------------------------------------------------------------------

    /** SCR-009#error: a change that was not saved; the old value stays on the screen. */
    const val SAVE_FAILED: String = "저장하지 못했어요. 다시 눌러 주세요"

    /** The settings could not be read (Claude's design: SCR-009 names no read error, so it is said as SCR-008's). */
    const val SETTINGS_LOAD_FAILED: String = "설정을 불러오지 못했어요"

    /** What changing the day start or base zone does, asked before saving (supplement P-5, SPEC-033). */
    const val WINDOW_CHANGE_NOTE: String = "바꾼 때부터 적용돼요. 지난 기록의 날짜는 그대로예요. 바꾸기 전에 끝난 날의 기록은 고칠 수 없게 돼요."

    /** The question before a new day start: `06:00부터 하루가 시작돼요.` and [WINDOW_CHANGE_NOTE]. */
    fun dayStartQuestion(new: LocalTime): String = "${clock(new)}부터 하루가 시작돼요. $WINDOW_CHANGE_NOTE"

    /** The question before a new base zone: `날짜를 Asia/Tokyo 기준으로 셈해요.` and [WINDOW_CHANGE_NOTE]. */
    fun zoneQuestion(zoneId: String): String = "날짜를 $zoneId 기준으로 셈해요. $WINDOW_CHANGE_NOTE"

    /** A time of the settings screen, `04:00`, `22:30`. */
    fun clock(time: LocalTime): String = "${two(time.hour)}:${two(time.minute)}"

    /** What TalkBack reads for a time of the settings screen: `4시`, `22시 30분`. */
    fun clockSpoken(time: LocalTime): String = if (time.minute == 0) "${time.hour}시" else "${time.hour}시 ${time.minute}분"

    /** `다시 알림 간격` (09 Settings `60분`). */
    fun interval(interval: Duration): String = "${interval.inWholeMinutes}분"

    /** `하루 최대` (09 Settings `3번`). */
    fun perDay(count: Int): String = "${count}번"

    /** No quiet hours: the start and the end are the same (supplement N-4). */
    const val QUIET_NONE: String = "없음"

    /** `방해 금지` (09 Settings `22:00 ~ 07:00`), or [QUIET_NONE]. */
    fun quiet(reminders: ReminderSettings): String =
        if (reminders.quietStart == reminders.quietEnd) QUIET_NONE else "${clock(reminders.quietStart)} ~ ${clock(reminders.quietEnd)}"

    /** What TalkBack reads for [quiet]: `22시부터 7시까지`. */
    fun quietSpoken(reminders: ReminderSettings): String =
        if (reminders.quietStart == reminders.quietEnd) QUIET_NONE else "${clockSpoken(reminders.quietStart)}부터 ${clockSpoken(reminders.quietEnd)}까지"

    /**
     * After the common reminder settings were saved and quiet hours drop repeats (REQ-077, SPEC-031, 2026-11-12 review C6):
     * [dropped] in one day over [items] items with reminders on.
     */
    fun quietSaved(dropped: Int, items: Int): String = "방해 금지 시간과 겹쳐 다시 알림이 모두 ${dropped}번 줄어요 (알림을 켠 항목 ${items}개)"

    /** Under `잠금 화면에 약 이름 보이기` (09 Settings). */
    const val SHOW_NAMES_NOTE: String = "끄면 '복용 시간이에요'만 보여요."

    /** Said when the lock-screen names are switched on (SPEC-034: names show on the lock screen). */
    const val SHOW_NAMES_ON: String = "이제 잠금 화면 알림에 약 이름이 보여요"

    /** `대상자 관리 2명`, `보관한 항목 1개` (09 Settings). */
    fun peopleCount(count: Int): String = "${count}명"

    fun itemCount(count: Int): String = "${count}개"

    /** An archived item in the list: its name, after its person's while two or more people are active (REQ-018). */
    fun archivedName(item: ArchivedItem, withPerson: Boolean): String = if (withPerson) "${item.person.name} ${item.name}" else item.name

    /** The question before `[완전 삭제]` (REQ-015, SPEC-025). */
    fun deleteQuestion(name: String): String = "${withObject(name)} 완전히 지울까요?"

    const val DELETE_NOTE: String = "지난 기록도 함께 지워지고 되돌릴 수 없어요."

    fun deleted(name: String): String = "${withObject(name)} 완전히 지웠어요"

    /** `[완전 삭제]` failed: the item and its records are still there. */
    const val DELETE_FAILED: String = "지우지 못했어요. 다시 눌러 주세요"

    /** The item was deleted meanwhile (another tap, another screen). */
    const val DELETE_GONE: String = "이미 지워진 항목이에요"

    /** SPEC-035, REQ-054: the backup notice, by Android version and screen lock. */
    const val BACKUP_ON: String = "화면 잠금과 Google 백업이 켜져 있으면 복용 기록이 백업돼요."
    const val BACKUP_NO_LOCK: String = "화면 잠금이 없어 복용 기록이 백업되지 않아요."
    const val BACKUP_OLD_ANDROID: String = "이 휴대폰은 Android 버전이 낮아 복용 기록이 백업되지 않아요."

    /** The backup notice: 8.x first, then no screen lock; the general one when the lock cannot be read (SPEC-035). */
    fun backup(oldAndroid: Boolean, screenLock: Boolean?): String = when {
        oldAndroid -> BACKUP_OLD_ANDROID
        screenLock == false -> BACKUP_NO_LOCK
        else -> BACKUP_ON
    }

    /** The medical notice of the settings (REQ-036, SPEC-036). */
    const val SETTINGS_MEDICAL: String = "의료기기가 아니며 질병을 진단·치료·예방하지 않아요."

    /** `개인정보처리방침` before its address is decided (TASK-035). */
    const val NOT_READY: String = "준비 중"

    /** No browser could open the privacy policy: its address instead (SPEC-036). */
    fun noBrowser(url: String): String = "브라우저를 열 수 없어요. 주소: $url"

    // 알림이 오지 않을 때 (TASK-026: SCR-004#permission, SCR-009, 09 HomeRestored and Settings) -----------------------
    // No sentence promises minutes (REQ-048).

    /** SCR-004#permission (REQ-089): an item has reminders on, but the app's notifications or its channel are off. */
    const val NOTIFICATIONS_OFF: String = "알림이 꺼져 있어 알려 드릴 수 없어요"

    /** SCR-004#permission (REQ-050, SPEC-029): an item has reminders on, but exact alarms are not allowed. */
    const val EXACT_ALARMS_OFF: String = "알림이 늦을 수 있어요"

    /** The button to a system settings screen (SCR-004, SCR-009, 09 HomeRestored and Settings). */
    const val OPEN_SETTINGS: String = "설정 열기"

    /** The button to the exact-alarm screen (SCR-004, SCR-009, 09 Settings). */
    const val ALLOW: String = "허용하기"

    /** SCR-009's section between the reminder settings and the people (09 Settings). */
    const val ACCESS_SECTION: String = "알림이 오지 않을 때"

    const val NOTIFICATIONS_ROW: String = "알림 권한"
    const val EXACT_ALARMS_ROW: String = "정확한 시각 알람"
    const val BATTERY_ROW: String = "배터리 사용 제한"

    /** A row's value while nothing there keeps reminders from coming (09 Settings `.v.ok`). */
    const val ACCESS_ON: String = "켜짐"
    const val BATTERY_FREE: String = "제한 없음"

    /** Under a row that is off: what that does (SCR-009#permission: the row shows that it is off). */
    const val NOTIFICATIONS_OFF_NOTE: String = "꺼져 있어 알림이 오지 않아요."
    const val EXACT_ALARMS_OFF_NOTE: String = "꺼져 있어 알림이 늦을 수 있어요."

    /** Under the battery row while the app is optimized or restricted, with how to leave it out (SPEC-030, REQ-051). */
    val BATTERY_NOTE: String
        get() = "제한되어 있어 알림이 늦을 수 있어요. 열리는 화면에서 ${withObject(APP_NAME)} 찾아 배터리 최적화를 꺼 주세요."

    /** No system screen could be opened, not even the app's information (Claude's wording). */
    val ACCESS_OPEN_FAILED: String
        get() = "설정 화면을 열지 못했어요. 휴대폰 설정의 앱 목록에서 ${withObject(APP_NAME)} 찾아 주세요"

    // SCR-010 대상자 관리 (TASK-021, 09 People and PersonAdd) --------------------------------------------------------

    /** Under the bar of the people list (09 People). */
    const val PEOPLE_NOTE: String = "사람마다 색이 있어요. 위젯과 앱에서 이 색으로 누구 약인지 알아봐요."

    /** The people could not be read (Claude's design, as [SETTINGS_LOAD_FAILED]). */
    const val PEOPLE_LOAD_FAILED: String = "대상자를 불러오지 못했어요"

    /** A person's line in the list: `약 2개`; under `보관됨` `약 1개 · 지난 기록은 남아요` (09 People). */
    fun personItems(count: Int, archived: Boolean): String = if (archived) "약 ${count}개 · 지난 기록은 남아요" else "약 ${count}개"

    /** The six person colors by the names 09 PersonAdd reads them by, in [PersonColors.PALETTE] order. */
    val COLOR_NAMES: List<String> = listOf("파랑", "주황", "초록", "보라", "장미", "청록")

    /** A stored person color by name; an old color by the name of the token it is drawn with. */
    fun colorName(color: String): String = COLOR_NAMES[PersonColors.index(color)]

    /**
     * Under the colors of the person form (09 PersonAdd): the colors [others] use, each with who uses it, in the order of
     * the people, `이미 쓰는 색: 파랑(나), 주황(엄마). 같은 색도 고를 수 있어요.`; null when nobody else is there. 09 writes
     * `파랑·주황은 나·엄마가 쓰고 있어요.`; its particle would read `나가` with 나 alone (Claude's wording, TASK-021).
     */
    fun colorsInUse(others: List<PersonEntity>): String? {
        if (others.isEmpty()) return null
        val byColor = others.groupBy { PersonColors.index(it.color) }
        val used = byColor.entries.joinToString(", ") { (index, people) -> "${COLOR_NAMES[index]}(${people.joinToString("·") { it.name }})" }
        return "이미 쓰는 색: $used. 같은 색도 고를 수 있어요."
    }

    /**
     * The one-phone advice of the person form (REQ-074, 09 PersonAdd), with the name being typed: `한 약은 한 휴대폰에서
     * 기록해 주세요. 아빠 약을 아빠 휴대폰에서도 기록하면 두 기록이 서로 보이지 않아요.`. Before a name is typed it says `같은 약을
     * 두 휴대폰에서 기록하면`.
     */
    fun onePhoneFor(name: String): String {
        val who = name.trim()
        val how = if (who.isEmpty()) "같은 약을 두 휴대폰에서 기록하면" else "$who 약을 $who 휴대폰에서도 기록하면"
        return "$ONE_PHONE. $how 두 기록이 서로 보이지 않아요."
    }

    /** The question before archiving a person (screens.md SCR-010). */
    fun archivePersonQuestion(name: String): String = "${withObject(name)} 보관할까요?"

    /** `엄마의 항목 3개가 위젯·알림에서 빠져요. 지난 기록은 남아요` (screens.md SCR-010), or that none leaves. */
    fun archivePersonNote(name: String, items: Int): String =
        if (items > 0) "${name}의 항목 ${items}개가 위젯·알림에서 빠져요. 지난 기록은 남아요" else "위젯·알림에서 빠지는 항목은 없어요. 지난 기록은 남아요"

    fun personArchived(name: String): String = "${withObject(name)} 보관했어요. 지난 기록은 남아요"

    fun personRestored(name: String): String = "${withObject(name)} 되살렸어요"

    /** Under the form of the last active person, in place of `[보관]` (REQ-093). */
    const val LAST_PERSON: String = "대상자가 한 명뿐이라 보관할 수 없어요"

    /** The person was archived meanwhile. */
    const val PERSON_GONE: String = "이 대상자는 이미 보관됐어요"

    const val ARCHIVE_FAILED: String = "보관하지 못했어요. 다시 눌러 주세요"

    const val RESTORE_FAILED: String = "되살리지 못했어요. 다시 눌러 주세요"

    /** A zone's offset now, for the base-zone list: `UTC+09:00`, `UTC-03:00`, `UTC+05:30`. */
    fun utcOffset(zone: TimeZone, at: Instant): String {
        val seconds = zone.offsetAt(at).totalSeconds
        val sign = if (seconds < 0) "-" else "+"
        val minutes = kotlin.math.abs(seconds) / 60
        return "UTC$sign${two(minutes / 60)}:${two(minutes % 60)}"
    }

    private fun two(n: Int): String = n.toString().padStart(2, '0')

    /** `11/11 (수)` with a no-break space before the weekday. */
    private fun dateKept(date: LocalDate): String = "${monthDay(date)}$NBSP(${weekday(date.dayOfWeek)})"

    /** `11/11 (수) 04:00`, kept on one line. */
    private fun dateTime(at: Instant, zone: TimeZone): String {
        val local = at.toLocalDateTime(zone)
        return "${dateKept(local.date)}$NBSP${hhmm(local)}"
    }

    /** `오늘 04:00`, `어제 04:00`, else `11/09 (월) 04:00`, seen on [now]'s calendar date. */
    private fun nearTime(at: Instant, now: Instant, zone: TimeZone): String {
        val local = at.toLocalDateTime(zone)
        val today = now.toLocalDateTime(zone).date
        return when (local.date) {
            today -> "오늘$NBSP${hhmm(local)}"
            today.minus(1, DateTimeUnit.DAY) -> "어제$NBSP${hhmm(local)}"
            else -> dateTime(at, zone)
        }
    }

    private fun hhmm(local: LocalDateTime): String =
        "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"

    private fun monthDay(date: LocalDate): String = "${date.month.ordinal + 1}/${date.day.toString().padStart(2, '0')}"

    /** A history cell's line: its window's first day, the period key (P-8). */
    private fun cellDay(cell: HistoryCell): LocalDate = LocalDate.parse(cell.window.periodKey)

    private fun historyTime(cell: HistoryCell, takenAt: Long, zone: TimeZone): String {
        val local = Instant.fromEpochMilliseconds(takenAt).toLocalDateTime(zone)
        return when {
            cell.rule is ScheduleRule.Weekly -> shortDate(local.date) + NBSP + hhmm(local)
            local.date == cell.window.start.toLocalDateTime(zone).date -> hhmm(local)
            else -> "${monthDay(local.date)} ${hhmm(local)}"
        }
    }

    private fun spokenHistoryTime(cell: HistoryCell, takenAt: Long, zone: TimeZone): String {
        val local = Instant.fromEpochMilliseconds(takenAt).toLocalDateTime(zone)
        val firstDay = cell.rule is ScheduleRule.Daily && local.date == cell.window.start.toLocalDateTime(zone).date
        return if (firstDay) hhmm(local) else "${longDate(local.date)} ${hhmm(local)}"
    }

    /** `11월 9일부터 15일까지`, across months `10월 26일부터 11월 1일까지`. */
    private fun spokenWeekRange(start: LocalDate): String {
        val end = start.plus(6, DateTimeUnit.DAY)
        val until = if (end.month == start.month) "${end.day}일" else "${end.month.ordinal + 1}월 ${end.day}일"
        return "${start.month.ordinal + 1}월 ${start.day}일부터 ${until}까지"
    }

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

/** An app tile's lines under the item name (SCR-004), in this order. */
data class RowText(
    /** Schedule and the dose, skip or last dose: `매일 아침 · 오늘 08:02 먹음`; a pending weekly item's schedule alone. */
    val detail: String,
    /** A pending weekly item's last dose, drawn bold on its own line (W-2, REQ-075); null otherwise. */
    val boldLine: String? = null,
    /** `어제 건너뜀` when a skip came after the last dose (S-2); null otherwise. */
    val skipLine: String? = null,
    /** A judged weekly item's next window `다음 창 11/16(월)` (REQ-028); null otherwise. */
    val nextLine: String? = null,
) {
    /** The lines in drawing order, with whether each is bold. */
    val lines: List<Pair<String, Boolean>>
        get() = listOfNotNull(detail to false, boldLine?.let { it to true }, skipLine?.let { it to false }, nextLine?.let { it to false })
}
