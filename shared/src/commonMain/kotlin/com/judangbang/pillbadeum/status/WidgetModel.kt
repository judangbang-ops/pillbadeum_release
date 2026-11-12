package com.judangbang.pillbadeum.status

/**
 * Everything the home widget draws, as plain values. The widget never computes windows or
 * statuses itself; it renders this and sends [WidgetButton.periodKey] back on tap.
 */
data class WidgetModel(
    val title: String,
    /** `11/11 (수)`: the base date this widget was rendered for (record-rules.md section 7). */
    val dateLabel: String,
    /** `n / m 완료` */
    val countLabel: String,
    val buttons: List<WidgetButton>,
) {
    companion object {
        fun from(board: StatusBoard): WidgetModel {
            val zone = board.settings.baseZone
            return WidgetModel(
                title = Labels.APP_NAME,
                dateLabel = Labels.date(board.baseDate),
                countLabel = Labels.count(board),
                buttons = board.items.map { item ->
                    WidgetButton(
                        intakeId = item.intakeId,
                        periodKey = item.window.periodKey,
                        title = item.displayName,
                        personInitial = item.person.name.take(1),
                        personColor = item.person.color,
                        done = item.isDone,
                        subtitle = if (item.clockWarning) Labels.CLOCK_CHECK_IN_APP else Labels.widgetSubtitle(item, board.now, zone),
                        description = Labels.widgetDescription(item, board.now, zone),
                        clockWarning = item.clockWarning,
                    )
                },
            )
        }
    }
}

data class WidgetButton(
    val intakeId: String,
    /** Period key of the window this button was rendered for; sent back as renderedPeriodKey. */
    val periodKey: String,
    val title: String,
    val personInitial: String,
    /** `#RRGGBB` */
    val personColor: String,
    val done: Boolean,
    val subtitle: String,
    val description: String,
    /** Draw `!`: this window's judgment was recorded while the clock was ahead (SCR-001#clock, C-2). */
    val clockWarning: Boolean = false,
)
