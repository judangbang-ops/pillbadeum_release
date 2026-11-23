package com.judangbang.pillbadeum.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.nativeCanvas
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.ui.CardExportFormat
import com.judangbang.pillbadeum.ui.CardExportPage
import com.judangbang.pillbadeum.ui.CardExportPreview
import com.judangbang.pillbadeum.usecase.CardEntryFields
import com.judangbang.pillbadeum.usecase.CardEntryKind
import com.judangbang.pillbadeum.usecase.HealthCardSummary
import java.io.IOException
import java.io.OutputStream
import kotlinx.datetime.LocalDate

internal const val A4_WIDTH = 595
internal const val A4_HEIGHT = 842
internal const val PNG_SCALE = 2
internal const val MAX_PNG_HEIGHT = 3368

private const val MARGIN = 38
private const val CONTENT_WIDTH = A4_WIDTH - 2 * MARGIN
private const val PAGE_BOTTOM = A4_HEIGHT - MARGIN
private const val HEADER_GAP = 9
private const val SECTION_GAP = 10

private val titlePaint = textPaint(30f, bold = true)
private val captionPaint = textPaint(16f, color = Color.DKGRAY)
private val headingPaint = textPaint(19f, bold = true, color = Color.DKGRAY)
private val rowPaint = textPaint(21f, bold = true)
private val detailPaint = textPaint(18f)
private val rulePaint = Paint().apply { color = Color.BLACK }
private val paperPaint = Paint().apply { color = Color.WHITE }

internal class CardDocument private constructor(
    override val format: CardExportFormat,
    override val filename: String,
    override val pages: List<DocumentPage>,
    override val notice: String?,
) : CardExportPreview {
    fun writeTo(output: OutputStream) {
        require(pages.isNotEmpty())
        when (format) {
            CardExportFormat.PDF -> {
                val pdf = PdfDocument()
                try {
                    pages.forEachIndexed { index, paper ->
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(paper.width, paper.height, index + 1).create())
                        try {
                            paper.drawOn(page.canvas)
                        } finally {
                            pdf.finishPage(page)
                        }
                    }
                    pdf.writeTo(output)
                } finally {
                    pdf.close()
                }
            }
            CardExportFormat.PNG -> {
                val page = pages.single()
                val bitmap = Bitmap.createBitmap(page.width * PNG_SCALE, page.height * PNG_SCALE, Bitmap.Config.ARGB_8888)
                try {
                    page.drawOn(Canvas(bitmap).apply { scale(PNG_SCALE.toFloat(), PNG_SCALE.toFloat()) })
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("PNG encoding failed")
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    companion object {
        fun prepare(card: HealthCardSummary, madeOn: LocalDate, format: CardExportFormat): CardDocument {
            require(card.canShowOrExport())
            val name = card.person.name
            val disclosure = paragraph(
                "${Labels.CARD_DISCLAIMER}. 적지 않은 종류는 보이지 않아요(없다는 뜻이 아니에요)",
                captionPaint,
            )
            val dates = paragraph(
                listOfNotNull(card.lastEditedDate?.let(Labels::cardLastEdited), "만든 날 $madeOn").joinToString(" · "),
                captionPaint,
            )
            val rows = card.rows()
            val title = paragraph(name, titlePaint)
            val header = listOf(title, disclosure, dates)
            val baseHeight = MARGIN + header.sumOf { it.height } + HEADER_GAP
            val (pages, notice) = when (format) {
                CardExportFormat.PDF -> {
                    val firstPass = paginate(rows, baseHeight, PAGE_BOTTOM)
                    if (firstPass.size == 1) {
                        listOf(page(A4_HEIGHT, header, baseHeight, firstPass.single())) to null
                    } else {
                        // Reserve room for the highest wrapped title so every repeated header fits its body.
                        var parts = firstPass
                        var headerHeight = baseHeight
                        var titles: List<DocumentRow>
                        while (true) {
                            titles = parts.indices.map { paragraph("$name · ${it + 1}/${parts.size}쪽", titlePaint) }
                            val needed = baseHeight - title.height + titles.maxOf { it.height }
                            if (needed <= headerHeight) break
                            headerHeight = needed
                            parts = paginate(rows, headerHeight, PAGE_BOTTOM)
                        }
                        parts.mapIndexed { index, body ->
                            page(A4_HEIGHT, listOf(titles[index], disclosure, dates), headerHeight, body)
                        } to "카드가 길어 ${parts.size}쪽이에요. 쪽마다 이름과 쪽 번호가 들어가요"
                    }
                }
                CardExportFormat.PNG -> {
                    val height = maxOf(A4_HEIGHT, baseHeight + rows.sumOf { it.height } + MARGIN)
                    when {
                        height > MAX_PNG_HEIGHT / PNG_SCALE -> emptyList<DocumentPage>() to "사진이 너무 길어요. PDF로 보내 주세요"
                        height > A4_HEIGHT -> listOf(page(height, header, baseHeight, rows)) to "세로로 긴 사진이에요"
                        else -> listOf(page(height, header, baseHeight, rows)) to null
                    }
                }
            }
            val filename = "pillbadeum-card-${madeOn.toString().replace("-", "")}.${format.extension}"
            return CardDocument(format, filename, pages, notice)
        }
    }
}

internal class DocumentPage(
    override val height: Int,
    private val header: List<DocumentRow>,
    private val bodyTop: Int,
    private val body: List<DocumentRow>,
) : CardExportPage {
    override val width get() = A4_WIDTH
    override val description get() = (header + body).joinToString("\n") { it.text }

    override fun draw(canvas: androidx.compose.ui.graphics.Canvas) = drawOn(canvas.nativeCanvas)

    fun drawOn(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)
        canvas.save()
        canvas.translate(MARGIN.toFloat(), MARGIN.toFloat())
        header.forEach { row ->
            row.draw(canvas)
            canvas.translate(0f, row.height.toFloat())
        }
        canvas.restore()
        canvas.save()
        canvas.translate(MARGIN.toFloat(), bodyTop.toFloat())
        body.forEach { row ->
            row.draw(canvas)
            canvas.translate(0f, row.height.toFloat())
        }
        canvas.restore()
    }
}

private fun page(height: Int, header: List<DocumentRow>, bodyTop: Int, rows: List<DocumentRow>) =
    DocumentPage(height, header, bodyTop, rows)

/**
 * Use measured visual lines, including font fallback (e.g. Korean) and explicit line breaks.
 * A section rule and heading stay with the first body line. Later lines can continue on the next page.
 */
private fun paginate(rows: List<DocumentRow>, top: Int, bottom: Int): List<List<DocumentRow>> {
    val capacity = bottom - top
    val pages = mutableListOf<List<DocumentRow>>()
    var page = mutableListOf<DocumentRow>()
    var used = 0
    rows.forEachIndexed { index, row ->
        val needed = row.height + if (row.sectionStart) rows[index + 1].height else 0
        check(needed <= capacity)
        if (used + needed > capacity) {
            pages.add(page)
            page = mutableListOf()
            used = 0
        }
        page.add(row)
        used += row.height
    }
    if (page.isNotEmpty()) pages.add(page)
    return pages
}

internal class DocumentRow(
    private val layout: StaticLayout,
    private val from: Int = 0,
    private val until: Int = layout.lineCount,
    val sectionStart: Boolean = false,
) {
    private val gap get() = if (sectionStart) SECTION_GAP else 0
    private val startY get() = layout.getLineTop(from)
    private val endY get() = layout.getLineTop(until)
    val height get() = gap + endY - startY
    val text: String get() = layout.text.subSequence(layout.getLineStart(from), layout.getLineEnd(until - 1)).toString().trimEnd()

    fun draw(canvas: Canvas) {
        if (sectionStart) canvas.drawRect(0f, 0f, CONTENT_WIDTH.toFloat(), 1.5f, rulePaint)
        canvas.save()
        canvas.translate(0f, (gap - startY).toFloat())
        canvas.clipRect(0, startY, CONTENT_WIDTH, endY)
        // Android's Layout reuses scratch paint during draw. Preview and file output can draw concurrently.
        synchronized(layout) { layout.draw(canvas) }
        canvas.restore()
    }
}

private fun textPaint(size: Float, bold: Boolean = false, color: Int = Color.BLACK) =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = size
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.color = color
    }

private fun layout(text: String, paint: TextPaint): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(paint), CONTENT_WIDTH)
        .setIncludePad(false)
        .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
        .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }
        .build()

private fun paragraph(text: String, paint: TextPaint) = DocumentRow(layout(text, paint))

private fun MutableList<DocumentRow>.addLines(text: String, paint: TextPaint) {
    val layout = layout(text, paint)
    for (line in 0 until layout.lineCount) add(DocumentRow(layout, line, line + 1))
}

private fun HealthCardSummary.rows(): List<DocumentRow> = buildList {
    fun section(title: String) {
        add(DocumentRow(layout(title, headingPaint), sectionStart = true))
    }
    if (medications.isNotEmpty()) {
        section(Labels.CARD_MEDICATIONS)
        medications.forEach { addLines(it.text, rowPaint) }
    }
    val byKind = entries.groupBy { it.fields.kind }
    for (kind in CardEntryKind.entries) {
        val sectionEntries = byKind[kind] ?: continue
        section(Labels.cardEntryTitle(kind))
        for (entry in sectionEntries) {
            when (val fields = entry.fields) {
                is CardEntryFields.Allergy -> addLines(withDetail(fields.substance, fields.reaction?.let { "반응: $it" }), rowPaint)
                is CardEntryFields.Condition -> {
                    addLines(withDetail(fields.name, fields.diagnosedOn?.let(Labels::cardDate)), rowPaint)
                    fields.note?.let { addLines(it, detailPaint) }
                }
                is CardEntryFields.Procedure -> {
                    addLines(withDetail(fields.name, fields.performedOn?.let(Labels::cardDate)), rowPaint)
                    fields.place?.let { addLines(it, detailPaint) }
                }
                is CardEntryFields.ImplantedDevice -> {
                    addLines(withDetail(fields.name, fields.implantedOn?.let(Labels::cardDate)), rowPaint)
                    fields.note?.let { addLines(it, detailPaint) }
                }
                is CardEntryFields.CareProvider -> {
                    addLines(withDetail(fields.name, fields.department), rowPaint)
                    listOfNotNull(fields.doctor?.let { "담당 의사 $it" }, fields.phone).takeIf { it.isNotEmpty() }
                        ?.let { addLines(it.joinToString(" · "), detailPaint) }
                }
                is CardEntryFields.Guardian -> addLines("${withDetail(fields.name, fields.relation)} ${fields.phone}", rowPaint)
            }
        }
    }
}

private fun withDetail(title: String, detail: String?) = if (detail == null) title else "$title ($detail)"
