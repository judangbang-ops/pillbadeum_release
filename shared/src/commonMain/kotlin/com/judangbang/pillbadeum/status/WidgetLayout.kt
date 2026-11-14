package com.judangbang.pillbadeum.status

import kotlin.math.ceil
import kotlin.math.max

/**
 * How the home widget arranges a [WidgetModel] in one size (TASK-014, SCR-001#overflow, REQ-026, REQ-078). The
 * frame and tile sizes are the TASK-044 drawing's (09 BoxWidget, `docs/05-ui/states/README.md` section 2).
 *
 * Glance can neither measure text nor tell how far a list is scrolled, so the widget decides once per size from
 * estimated text widths:
 * - tiles per row: two while a tile's text width holds the longest piece a line cannot break, a weekly item's
 *   date and time at the name size (`11/02(월) 08:10` = [LONGEST_PIECE_EM] × the name size), otherwise one;
 * - the header: the count moves to a second line when chip, date and count do not fit on one;
 * - a person's name moves above the item name when both do not fit on the name line;
 * - [Plan.overflow]: the rows are taller than the list area, so the down arrow shows while that holds (REQ-078).
 *   The estimate leans towards showing it.
 *
 * Sizes are dp. Text widths use the phone fonts' metrics (Roboto digits and punctuation, Noto Sans CJK Hangul at
 * [HANGUL_EM] as measured in the TASK-044 drawing), lines are [LINE] em apart and break only at spaces: the widget
 * draws its text through [keepWords]. This is an estimate for Pixel fonts; the emulator captures of TASK-014 and the
 * Galaxy check (TASK-032) test it.
 */
object WidgetLayout {
    /** dp sizes of the widget's 16sp and 14sp text at the phone's font scale (Android 14 grows large fonts nonlinearly). */
    data class Fonts(val body: Float, val bodySm: Float) {
        companion object {
            /** Font size 100%. */
            val DEFAULT: Fonts = Fonts(body = 16f, bodySm = 14f)
        }
    }

    data class Plan(
        /** Tiles per row: 2 or 1. */
        val columns: Int,
        /** The count goes to a second header line. */
        val headerWraps: Boolean,
        /** Intake ids whose person name goes on its own line above the item name. */
        val whoAbove: Set<String>,
        /** The rows are taller than the list area: show the down arrow (REQ-078). */
        val overflow: Boolean,
    )

    const val PAD_H: Float = 12f

    /** Below the list. Above, the top's own padding ([TOP_PAD_V]) takes the widget's top padding. */
    const val PAD_V: Float = 10f

    /** Between the restore band and the list, between tiles and between rows (`space.xs`). */
    const val GAP: Float = 6f

    /**
     * A button's least height (`size.target`, REQ-067). The top (the header, or the tap message in its place) and the
     * bands are buttons that open the app or the person picker (TASK-030: the header was 28dp tall).
     */
    const val TARGET: Float = 48f

    /**
     * Above and below the header or the tap message, inside the top's button: the widget's top padding (the day chip
     * stays 10dp from the top, as drawn) and the gap above the list. The header row is at least [TARGET] less these.
     */
    const val TOP_PAD_V: Float = 8f

    /** The header row's least height, so the top is [TARGET] tall: 32dp, the 28dp day chip in the middle. */
    const val TOP_MIN: Float = TARGET - 2 * TOP_PAD_V
    const val HEADER_PAD_H: Float = 4f
    const val HEADER_GAP: Float = 8f
    const val HEADER_LINE_GAP: Float = 2f
    const val CHIP_MIN: Float = 28f
    const val CHIP_PAD_H: Float = 6f

    /** The message and restore bands (`radius.button`, padding `space.xs` / `space.md`); a band alone is [TARGET] tall at least. */
    const val BAND_PAD_H: Float = 12f
    const val BAND_PAD_V: Float = 6f
    const val TILE_PAD_START: Float = 12f
    const val TILE_PAD_END: Float = 8f
    const val TILE_PAD_V: Float = 8f
    const val TILE_MIN_HEIGHT: Float = 64f
    const val TILE_LINE_GAP: Float = 2f
    const val ICON: Float = 24f
    const val ICON_GAP: Float = 6f
    const val WHO_GAP: Float = 5f

    /** Below the last row while the list overflows, so the arrow does not cover it once scrolled to the end. */
    const val END_PAD: Float = 8f

    /** Line pitch in em: Noto Sans CJK's ascent plus descent, which Android's fallback line spacing applies to Korean. */
    const val LINE: Float = 1.47f

    /** `11/02(월) 08:10` in em: 113px at 16px in the TASK-044 drawing. */
    const val LONGEST_PIECE_EM: Float = 7.1f

    /** The arrow shows once the rows come within this of the list area's height. */
    const val OVERFLOW_MARGIN: Float = 4f

    /** A Hangul syllable's advance in em (Noto Sans CJK, `마지막 복용` = 68px at 14px in the TASK-044 drawing). */
    const val HANGUL_EM: Float = 0.92f

    fun plan(model: WidgetModel, width: Float, height: Float, fonts: Fonts, message: String?): Plan {
        val inner = width - 2 * PAD_H
        val columns = if (tileTextWidth(inner, 2) >= fonts.body * LONGEST_PIECE_EM) 2 else 1
        val headerWraps = headerOneLine(model, fonts) > inner - 2 * HEADER_PAD_H
        val textWidth = tileTextWidth(inner, columns)
        val whoAbove = model.buttons.filter { it.who != null && !whoFits(it, textWidth, fonts) }.map { it.intakeId }.toSet()

        val shown = if (message != null) bandHeight(message, inner, fonts) else headerHeight(model, headerWraps, inner, fonts)
        val top = 2 * TOP_PAD_V + max(TOP_MIN, shown)
        val restore = model.restoreLine?.let { max(TARGET, bandHeight(it, inner, fonts)) + GAP } ?: 0f
        val listHeight = height - PAD_V - top - restore
        val rows = model.buttons.chunked(columns).map { row ->
            row.maxOf { tileHeight(it, it.intakeId in whoAbove, textWidth, fonts) }
        }
        val content = rows.sum() + GAP * (rows.size - 1).coerceAtLeast(0)
        return Plan(columns, headerWraps, whoAbove, overflow = rows.isNotEmpty() && content > listHeight - OVERFLOW_MARGIN)
    }

    /** Width left for a tile's text: tile width less its padding. */
    fun tileTextWidth(inner: Float, columns: Int): Float =
        (inner - GAP * (columns - 1)) / columns - TILE_PAD_START - TILE_PAD_END

    private fun lineHeight(size: Float): Float = size * LINE

    private fun chipWidth(model: WidgetModel, fonts: Fonts): Float =
        max(CHIP_MIN, width(model.weekday, fonts.bodySm, bold = true) + 2 * CHIP_PAD_H)

    private fun chipHeight(fonts: Fonts): Float = max(CHIP_MIN, lineHeight(fonts.bodySm))

    private fun headerOneLine(model: WidgetModel, fonts: Fonts): Float =
        chipWidth(model, fonts) + HEADER_GAP + width(model.headerDate, fonts.bodySm, bold = true) + HEADER_GAP +
            width(model.countDone, fonts.bodySm, bold = true) + width(" " + model.countRest, fonts.bodySm)

    private fun headerHeight(model: WidgetModel, wraps: Boolean, inner: Float, fonts: Fonts): Float {
        if (!wraps) return chipHeight(fonts)
        val dateWidth = inner - 2 * HEADER_PAD_H - chipWidth(model, fonts) - HEADER_GAP
        val first = max(chipHeight(fonts), lines(model.headerDate, dateWidth, fonts.bodySm, bold = true) * lineHeight(fonts.bodySm))
        return first + HEADER_LINE_GAP + lineHeight(fonts.bodySm)
    }

    private fun bandHeight(text: String, inner: Float, fonts: Fonts): Float =
        2 * BAND_PAD_V + lines(text, inner - 2 * BAND_PAD_H, fonts.bodySm, bold = true) * lineHeight(fonts.bodySm)

    /** The person name, the gap and the item name fit on the name line beside the state icon. */
    private fun whoFits(button: WidgetButton, textWidth: Float, fonts: Fonts): Boolean =
        width(button.who.orEmpty(), fonts.bodySm, bold = true) + WHO_GAP + width(button.title, fonts.body, bold = true) <=
            textWidth - ICON - ICON_GAP

    private fun tileHeight(button: WidgetButton, whoAbove: Boolean, textWidth: Float, fonts: Fonts): Float {
        val nameWidth = textWidth - ICON - ICON_GAP
        var height = when {
            button.who == null -> max(ICON, lines(button.title, nameWidth, fonts.body, bold = true) * lineHeight(fonts.body))
            whoAbove -> max(ICON, lineHeight(fonts.bodySm)) + lines(button.title, textWidth, fonts.body, bold = true) * lineHeight(fonts.body)
            else -> max(ICON, lineHeight(fonts.body))
        }
        button.lines.forEachIndexed { index, line ->
            val large = index == 0 && button.largeFirstLine
            val size = if (large) fonts.body else fonts.bodySm
            height += TILE_LINE_GAP + lines(line, textWidth, size, bold = large) * lineHeight(size)
        }
        return max(TILE_MIN_HEIGHT, height + 2 * TILE_PAD_V)
    }

    /**
     * Lines [text] takes at [size] dp in [width] dp. A line breaks only at a space, so a date and time joined by a
     * no-break space stay together; a word wider than a whole line is cut wherever it must be.
     */
    fun lines(text: String, width: Float, size: Float, bold: Boolean = false): Int {
        val room = width / size
        if (room <= 0f) return 1
        var lines = 1
        var used = 0f
        for (word in text.split(' ')) {
            val w = em(word, bold)
            if (used > 0f && used + SPACE_EM + w <= room) {
                used += SPACE_EM + w
                continue
            }
            if (used > 0f) lines++
            val extra = (ceil(w / room).toInt() - 1).coerceAtLeast(0)
            lines += extra
            used = w - extra * room
        }
        return lines
    }

    /** Estimated width of [text] in dp at [size] dp. */
    fun width(text: String, size: Float, bold: Boolean = false): Float = em(text, bold) * size

    /** U+2060 WORD JOINER: no line break on either side, and no width. */
    const val WORD_JOINER: Char = '\u2060'

    /**
     * [text] with a [WORD_JOINER] between the letters of each Korean word, so the widget breaks lines only at spaces,
     * as [lines] assumes and the 09 mockup draws (`word-break: keep-all`). Android otherwise breaks Hangul between any
     * two syllables (`바꾸려면 앱에 | 서` in the TASK-014 render check), and a widget's text cannot ask for phrase
     * breaks (`setLineBreakWordStyle` is not a RemoteViews method). A word wider than a whole line is still cut.
     */
    fun keepWords(text: String): String {
        if (text.none(::isHangul)) return text
        val out = StringBuilder(text.length * 2)
        text.forEachIndexed { i, c ->
            if (i > 0) {
                val p = text[i - 1]
                if (!p.isWhitespace() && !c.isWhitespace() && (isHangul(p) || isHangul(c))) out.append(WORD_JOINER)
            }
            out.append(c)
        }
        return out.toString()
    }

    private fun isHangul(c: Char): Boolean = c in '가'..'힣' || c in 'ㄱ'..'ㆎ'

    private const val SPACE_EM: Float = 0.25f

    private fun em(text: String, bold: Boolean): Float = text.sumOf { em(it, bold).toDouble() }.toFloat()

    private fun em(c: Char, bold: Boolean): Float {
        if (isHangul(c)) return HANGUL_EM // a bold syllable keeps its full-width advance
        val latin = when (c) {
            WORD_JOINER -> 0f
            in '0'..'9' -> 0.5625f
            ' ', ' ' -> SPACE_EM
            '/' -> 0.41f
            '(', ')' -> 0.34f
            ':', '.', '·' -> 0.26f
            ',' -> 0.2f
            '!' -> 0.27f
            '-' -> 0.35f
            '?' -> 0.47f
            '+' -> 0.57f
            '~' -> 0.68f
            '%' -> 0.73f
            in 'A'..'Z' -> 0.65f
            in 'a'..'z' -> 0.53f
            else -> 1f
        }
        return if (bold) latin * 1.05f else latin
    }
}
