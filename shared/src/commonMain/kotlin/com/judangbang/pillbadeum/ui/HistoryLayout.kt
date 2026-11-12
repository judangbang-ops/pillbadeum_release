package com.judangbang.pillbadeum.ui

import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.status.HistoryCell
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.WidgetLayout

/**
 * How SCR-008 (09 History) arranges a date line at a screen width and font size. Sizes are dp.
 *
 * Two cells a row while a cell's text width holds the longest name of the 09 board in bold (`골다공증약`), as 09 draws at
 * 100%; otherwise one cell a row, so a name breaks only at spaces. The date column is 44 dp, or as wide as `오늘` or a
 * two-digit day needs with large text. Widths are the widget's estimates ([WidgetLayout.width]).
 */
internal object HistoryLayout {
    /** `.body` side padding (`space.lg`). */
    const val SIDE: Float = 16f

    /** `.dt` width. */
    const val DATE: Float = 44f

    /** Between the date column and the cells (`.day` gap, `space.md`). */
    const val DAY_GAP: Float = 12f

    /** Between cells (`.cps` gap, `space.xs`). */
    const val CELL_GAP: Float = 6f

    /** A cell's side padding (`.cp`, `space.md`). */
    const val CELL_PAD: Float = 12f

    /** The longest word of the 09 board's names. */
    const val LONGEST_WORD: String = "골다공증약"

    /** The date column's width where 14sp text is [bodySm] dp and 20sp is [titleMd] dp. */
    fun dateWidth(bodySm: Float, titleMd: Float): Float =
        maxOf(DATE, WidgetLayout.width(Labels.TODAY, bodySm, bold = true), WidgetLayout.width("30", titleMd, bold = true))

    /** Cells a row on a screen [width] dp wide. */
    fun columns(width: Float, bodySm: Float, titleMd: Float): Int {
        val cells = width - 2 * SIDE - dateWidth(bodySm, titleMd) - DAY_GAP
        val text = (cells - CELL_GAP) / 2 - 2 * CELL_PAD
        return if (WidgetLayout.width(LONGEST_WORD, bodySm, bold = true) <= text) 2 else 1
    }

    /** [cells] in rows of [columns], in their order; a weekly cell takes a row of its own (09 History `.wk`). */
    fun rows(cells: List<HistoryCell>, columns: Int): List<List<HistoryCell>> {
        val rows = mutableListOf<List<HistoryCell>>()
        var row = mutableListOf<HistoryCell>()
        for (cell in cells) {
            if (cell.rule is ScheduleRule.Weekly) {
                if (row.isNotEmpty()) rows += row
                row = mutableListOf()
                rows += listOf(cell)
                continue
            }
            row += cell
            if (row.size == columns) {
                rows += row
                row = mutableListOf()
            }
        }
        if (row.isNotEmpty()) rows += row
        return rows
    }
}
