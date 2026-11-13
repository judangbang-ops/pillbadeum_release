package com.judangbang.pillbadeum.ui

import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.WidgetLayout

/**
 * How the first screen (SCR-004, 09 BoxHome) arranges its tiles at a screen width and font size. Sizes are dp.
 *
 * Two tiles a row while a tile's text width holds the longest piece a line cannot break: a weekly item's last dose
 * date and time in the tile's bold 14sp line (`11/02(월) 08:10`, joined by a no-break space). Otherwise one tile a
 * row, and the week box puts each name above its cells, as 09 BoxHome draws at font size 200%. Widths are the
 * widget's estimates ([WidgetLayout.width]); the render test checks them at 100%, 130% and 200%.
 */
internal object HomeLayout {
    /** `.body` side padding (`space.lg`). */
    const val SIDE: Float = 16f

    /** Between tiles (`space.sm`). */
    const val GAP: Float = 8f

    /** A tile's side padding (`space.md`). */
    const val TILE_PAD: Float = 12f

    /** The longest piece of a tile line that cannot break. */
    val LONGEST_PIECE: String = "11/02(월)${Labels.NBSP}08:10"

    /** Tiles a row on a screen [width] dp wide where 14sp text is [bodySm] dp. */
    fun columns(width: Float, bodySm: Float): Int {
        val text = (width - 2 * SIDE - GAP) / 2 - 2 * TILE_PAD
        return if (WidgetLayout.width(LONGEST_PIECE, bodySm, bold = true) <= text) 2 else 1
    }
}
