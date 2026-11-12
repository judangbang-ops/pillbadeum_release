package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.domain.logicalDateOf
import com.judangbang.pillbadeum.usecase.RecordResult
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * Korean UI copy shared by the widget and the app (ui-mockup-v3 tone). Every time shown to the
 * user is rendered in the base zone, the same zone windows are computed in.
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

    /** `11/11 (수)`, `11/04 (수)` */
    fun date(date: LocalDate): String =
        "${date.month.ordinal + 1}/${date.day.toString().padStart(2, '0')} (${weekday(date.dayOfWeek)})"

    /** `08:02` */
    fun time(at: Instant, zone: TimeZone): String = hhmm(at.toLocalDateTime(zone))

    /** `n / m 완료` */
    fun count(board: StatusBoard): String = "${board.doneCount} / ${board.totalCount} 완료"

    /** `매일 아침`, `매일`, `주 1회 · 월` */
    fun schedule(rule: ScheduleRule, slot: SlotLabel?): String = when (rule) {
        ScheduleRule.Daily -> if (slot != null) "매일 ${slot(slot)}" else "매일"
        is ScheduleRule.Weekly -> "주 1회 · ${weekday(rule.anchor)}"
    }

    /**
     * A past instant relative to [now] by calendar date: `오늘 08:02`, `어제 21:40`, otherwise
     * `11/04 (수) 09:10`.
     */
    fun relative(at: Instant, now: Instant, zone: TimeZone): String {
        val local = at.toLocalDateTime(zone)
        val today = now.toLocalDateTime(zone).date
        val prefix = when (local.date) {
            today -> "오늘"
            today.minus(1, DateTimeUnit.DAY) -> "어제"
            else -> date(local.date)
        }
        return "$prefix ${hhmm(local)}"
    }

    /** `08:02` on the same calendar date as [now], else `월 08:30`. */
    fun stamp(at: Instant, now: Instant, zone: TimeZone): String {
        val local = at.toLocalDateTime(zone)
        return if (local.date == now.toLocalDateTime(zone).date) hhmm(local) else "${weekday(local.date.dayOfWeek)} ${hhmm(local)}"
    }

    /** Widget subtitle under each button. */
    fun widgetSubtitle(item: IntakeStatus, now: Instant, zone: TimeZone): String {
        val judgment = item.judgment
        if (judgment != null) {
            val at = Instant.fromEpochMilliseconds(judgment.takenAt)
            return when (item.rule) {
                ScheduleRule.Daily -> time(at, zone)
                is ScheduleRule.Weekly -> {
                    val local = at.toLocalDateTime(zone)
                    "${weekday(local.date.dayOfWeek)} ${hhmm(local)}"
                }
            }
        }
        return lastLabel(item.lastBefore, now, zone)
    }

    /** `마지막 어제 21:40` or `기록 없음`. */
    fun lastLabel(last: DoseRecordEntity?, now: Instant, zone: TimeZone): String =
        last?.let { "마지막 " + relative(Instant.fromEpochMilliseconds(it.takenAt), now, zone) } ?: NO_RECORD

    /** Accessibility text of a widget button: `아침약, 오늘 08:02 기록됨` / `아침약, 미완료`. */
    fun widgetDescription(item: IntakeStatus, now: Instant, zone: TimeZone): String {
        val judgment = item.judgment ?: return "${item.person.name} ${item.displayName}, 미완료"
        val at = Instant.fromEpochMilliseconds(judgment.takenAt)
        val base = "${item.person.name} ${item.displayName}, ${relative(at, now, zone)} 기록됨"
        return if (item.clockWarning) "$base. $CLOCK_CHECK_IN_APP" else base
    }

    /** Widget button line for a judgment with a clock warning (SCR-001#clock, supplement C-2). */
    const val CLOCK_CHECK_IN_APP: String = "! 앱에서 확인"

    /** App warning (SCR-004#future): `기록 시각이 휴대폰 시계와 맞지 않았어요 (금 08:00). 확인해 주세요`. */
    fun clockWarning(judgment: DoseRecordEntity, zone: TimeZone): String {
        val local = Instant.fromEpochMilliseconds(judgment.takenAt).toLocalDateTime(zone)
        return "기록 시각이 휴대폰 시계와 맞지 않았어요 (${weekday(local.dayOfWeek)} ${hhmm(local)}). 확인해 주세요"
    }

    /** Second line of an app row (ui-mockup-v3 app screen). */
    fun appDetail(item: IntakeStatus, board: StatusBoard): String {
        val zone = board.settings.baseZone
        val now = board.now
        val parts = mutableListOf(schedule(item.rule, item.slotLabel))
        val judgment = item.judgment
        when (item.rule) {
            ScheduleRule.Daily -> {
                if (judgment != null) {
                    parts += "${relative(Instant.fromEpochMilliseconds(judgment.takenAt), now, zone)} 기록"
                } else {
                    parts += "아직 안 먹음"
                    parts += lastLabel(item.lastBefore, now, zone)
                }
            }
            is ScheduleRule.Weekly -> {
                parts += "이번 창 ${date(logicalDateOf(item.window.start, board.settings))}~"
                if (judgment != null) {
                    parts += "${stamp(Instant.fromEpochMilliseconds(judgment.takenAt), now, zone)} 완료"
                } else {
                    parts += "아직"
                    parts += lastLabel(item.lastBefore, now, zone)
                }
                parts += "다음 창 ${date(logicalDateOf(item.nextWindowStart, board.settings))}"
            }
        }
        return parts.joinToString(" · ")
    }

    /**
     * Message for a record attempt (record-rules.md section 6 table). `null` means "no message":
     * the refreshed button itself shows the result.
     */
    fun recordMessage(result: RecordResult, name: String, now: Instant, zone: TimeZone): String? = when (result) {
        is RecordResult.Recorded -> null
        is RecordResult.Already ->
            "$name: 이미 기록됨 (${stamp(Instant.fromEpochMilliseconds(result.existing.takenAt), now, zone)}). 변경 없음"
        is RecordResult.Stale -> STALE
        RecordResult.Gone -> null
        RecordResult.Locked -> LOCKED
        is RecordResult.Error -> ERROR
    }

    private fun hhmm(local: LocalDateTime): String =
        "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}
