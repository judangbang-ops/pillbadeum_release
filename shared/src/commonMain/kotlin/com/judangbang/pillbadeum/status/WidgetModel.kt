package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.design.PersonPalette
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.usecase.PersonColors
import kotlinx.datetime.TimeZone

/**
 * Everything the home widget draws, as plain values. The widget never computes windows or
 * statuses itself; it renders this and sends [WidgetButton.periodKey] back on tap. How the tiles are
 * arranged for a size is [WidgetLayout].
 */
data class WidgetModel(
    val title: String,
    /** `11/11 (수)`: the base date this widget was rendered for (record-rules.md section 7). */
    val dateLabel: String,
    /** `1 / 3 완료`, `1 / 3 완료 · 건너뜀 1` (supplement S-1). */
    val countLabel: String,
    /** `수`: the base date's weekday, in the header's dark chip (09 BoxWidget). */
    val weekday: String,
    /** `DidITake · 11/11` beside the chip. */
    val headerDate: String,
    /** [countLabel] in the header's two weights: bold `1 / 3`, then `완료 · 건너뜀 1` ([Labels.countParts]). */
    val countDone: String,
    val countRest: String,
    val buttons: List<WidgetButton>,
    /** `복원됨 · 마지막 기록 11/09 21:40 · 앱에서 확인` until [확인했어요] (SCR-001#restored), else null. */
    val restoreLine: String? = null,
    /** Two or more active people: person names are drawn (REQ-018). */
    val showPeople: Boolean = true,
) {
    companion object {
        /**
         * [zone] is the display zone, the phone's own (`TimeZone.currentSystemDefault()`): times are shown there,
         * while the board's windows and base date stay in the base zone (REQ-082, TASK-043).
         */
        fun from(board: StatusBoard, zone: TimeZone): WidgetModel {
            val (countDone, countRest) = Labels.countParts(board)
            return WidgetModel(
                title = Labels.APP_NAME,
                dateLabel = Labels.date(board.baseDate),
                countLabel = Labels.count(board),
                weekday = Labels.weekday(board.baseDate.dayOfWeek),
                headerDate = Labels.widgetDate(board.baseDate),
                countDone = countDone,
                countRest = countRest,
                buttons = board.items.map { item ->
                    WidgetButton(
                        intakeId = item.intakeId,
                        periodKey = item.window.periodKey,
                        title = item.displayName,
                        who = item.person.name.takeIf { board.showPeople },
                        palette = PersonColors.palette(item.person.color),
                        state = when {
                            item.isTaken -> TileState.TAKEN
                            item.isSkipped -> TileState.SKIPPED
                            else -> TileState.PENDING
                        },
                        lines = Labels.widgetLines(item, board.now, zone),
                        largeFirstLine = item.judgment == null && !item.clockWarning && item.rule is ScheduleRule.Weekly,
                        description = Labels.spoken(item, board.now, zone, withPerson = board.showPeople),
                        clockWarning = item.clockWarning,
                    )
                },
                restoreLine = board.restoreNotice?.let { Labels.restoreWidgetLine(it, zone) },
                showPeople = board.showPeople,
            )
        }
    }
}

/** What a widget tile shows (SCR-001, supplement S-3, 09 BoxWidget). */
enum class TileState {
    /** Person-tinted tile with a dashed ring. A tap records a dose. */
    PENDING,

    /** Tile filled with the person's color and a check. A tap changes nothing (`Already`). */
    TAKEN,

    /** Grey tile, grey disc with a minus and `건너뜀`, counted apart (S-1, S-3). A tap changes nothing and says so. */
    SKIPPED,
}

data class WidgetButton(
    val intakeId: String,
    /** Period key of the window this button was rendered for; sent back as renderedPeriodKey. */
    val periodKey: String,
    val title: String,
    /** The person's name before [title] (`엄마`), or null when only one person is active (REQ-018, TASK-015). */
    val who: String?,
    /** The person's token colors: tile tint, text and lid (09 tokens `color.person.n`). */
    val palette: PersonPalette,
    val state: TileState,
    /**
     * Lines under the name: `08:02 먹음`, `건너뜀`, or the last dose `마지막 복용 어제 22:00` with an optional second
     * line `어제 건너뜀` (S-2), or `! 앱에서 확인` for a clock warning (C-2). Date and time are joined by a no-break
     * space, so a line breaks only between words.
     */
    val lines: List<String>,
    /** A pending weekly item: the first line is drawn at the name's size (W-2, REQ-075, RR-59). */
    val largeFirstLine: Boolean = false,
    /** TalkBack sentence (S-4, W-3). */
    val description: String,
    /** Draw `!`: this window's judgment was recorded while the clock was ahead (SCR-001#clock, C-2). */
    val clockWarning: Boolean = false,
) {
    /** The window is judged (a dose or a skip). */
    val done: Boolean get() = state != TileState.PENDING
}
