package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Canvas as DrawCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.HealthCardSummary
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

enum class CardExportFormat(val label: String, val extension: String, val mimeType: String) {
    PDF("PDF", "pdf", "application/pdf"),
    PNG("사진(PNG)", "png", "image/png"),
}

interface CardExportPage {
    val width: Int
    val height: Int
    val description: String
    fun draw(canvas: DrawCanvas)
}

interface CardExportPreview {
    val format: CardExportFormat
    val filename: String
    val pages: List<CardExportPage>
    val notice: String?
}

/** Platform document rendering and the system share dialog (SCR-015). */
interface CardExporter {
    suspend fun prepare(card: HealthCardSummary, madeOn: LocalDate, format: CardExportFormat): CardExportPreview
    suspend fun share(preview: CardExportPreview, onChooserOpening: () -> Unit)
}

@Composable
internal fun CardExportScreen(
    core: DidITakeCore?,
    personId: String,
    exporter: CardExporter?,
    refreshKey: Int,
    openFailed: Boolean,
    onRetryOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var format by rememberSaveable { mutableStateOf(CardExportFormat.PDF) }
    var source by remember { mutableStateOf<Pair<HealthCardSummary, LocalDate>?>(null) }
    var sending by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var readFailed by remember { mutableStateOf(false) }
    var refreshedAt by remember { mutableStateOf<Int?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val requested = source
    val preparation = remember(requested, format, retry, exporter) { mutableStateOf<Result<CardExportPreview>?>(null) }
    var shareFailed by remember(requested, format, retry) { mutableStateOf(false) }
    val shown = preparation.value?.getOrNull()
    val writeFailed = preparation.value?.isFailure == true || shareFailed

    LaunchedEffect(core, exporter, refreshKey, retry) {
        if (exporter == null) {
            onClose()
            return@LaunchedEffect
        }
        if (core == null) return@LaunchedEffect
        try {
            val card = core.loadHealthCard(personId)
            if (card == null || !card.canShowOrExport()) {
                onClose()
                return@LaunchedEffect
            }
            val madeOn = core.clock.now().toLocalDateTime(core.windowSettings().baseZone).date
            currentCoroutineContext().ensureActive()
            val previous = source
            // A chooser return resumes the activity. Changes to card UI metadata do not change the document.
            if (previous == null || previous.second != madeOn || !previous.first.sameExportContent(card)) {
                source = card to madeOn
            }
            readFailed = false
            refreshedAt = refreshKey
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            source = null
            readFailed = true
        }
    }
    LaunchedEffect(preparation) {
        val (card, madeOn) = requested ?: return@LaunchedEffect
        val renderer = exporter ?: return@LaunchedEffect
        // Each request owns its result. A cancelled renderer can finish after the next request starts.
        try {
            val preview = renderer.prepare(card, madeOn, format)
            currentCoroutineContext().ensureActive()
            preparation.value = Result.success(preview)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            preparation.value = Result.failure(e)
        }
    }

    val failed = openFailed || readFailed
    val loading = !failed && (requested == null || preparation.value == null)
    Column(Modifier.fillMaxSize()) {
        TopBar(Labels.CARD_EXPORT, navigation = { BarIconButton("닫기", close = true, onClick = onClose) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ExportBody(
                format = format,
                formatEnabled = !sending,
                onFormat = { option ->
                    if (!sending && option != format) {
                        format = option
                    }
                },
                preview = shown,
                failed = failed,
                loading = loading,
                onRetry = { if (core == null) onRetryOpen() else retry++ },
            )
            if (writing) {
                Box(
                    Modifier.matchParentSize().background(Ui.Surface).padding(horizontal = Ui.Side),
                    contentAlignment = Alignment.Center,
                ) { ExportLoading() }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.LineSubtle))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Ui.Side, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (writeFailed) {
                Band("파일을 만들지 못했어요. 휴대폰 저장 공간을 확인해 주세요", live = true)
            }
            Words("앱이 직접 보내지 않아요. 고른 앱이 보내요", Ui.type(14, color = Ui.Muted, center = true), Modifier.fillMaxWidth())
            ActionButton(
                "보내기",
                ButtonLook.Ink,
                enabled = !loading && !sending && !failed && refreshedAt == refreshKey &&
                    (shown?.pages?.isNotEmpty() == true || writeFailed),
                onClick = {
                    if (!sending && !failed && exporter != null && requested != null && source == requested &&
                        refreshedAt == refreshKey && preparation.value != null
                    ) {
                        if (shown == null) {
                            retry++
                        } else if (shown.pages.isNotEmpty()) {
                            sending = true
                            writing = true
                            shareFailed = false
                            scope.launch {
                                try {
                                    val (card, madeOn) = requested
                                    val today = checkNotNull(core).let {
                                        it.clock.now().toLocalDateTime(it.windowSettings().baseZone).date
                                    }
                                    val current = if (madeOn == today) shown else exporter.prepare(card, today, format)
                                    exporter.share(current) { writing = false }
                                    if (source == requested && madeOn != today) source = card to today
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    shareFailed = true
                                } finally {
                                    writing = false
                                    sending = false
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

private fun HealthCardSummary.sameExportContent(other: HealthCardSummary) =
    person.name == other.person.name && lastEditedDate == other.lastEditedDate &&
        entries == other.entries && medications == other.medications

@Composable
private fun ExportBody(
    format: CardExportFormat,
    formatEnabled: Boolean,
    onFormat: (CardExportFormat) -> Unit,
    preview: CardExportPreview?,
    failed: Boolean,
    loading: Boolean,
    onRetry: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Ui.Side)) {
        item {
            Row(
                Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CardExportFormat.entries.forEach { option ->
                    ActionButton(
                        option.label,
                        if (option == format) ButtonLook.Ink else ButtonLook.Line,
                        onClick = { onFormat(option) },
                        modifier = Modifier.weight(1f).semantics { selected = option == format },
                        enabled = formatEnabled,
                    )
                }
            }
        }
        if (failed) {
            item {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardReadFailed(onRetry)
                }
            }
        } else if (loading) {
            item { ExportLoading() }
        } else if (preview != null) {
            preview.notice?.let { notice ->
                item { Band(notice, Modifier.padding(top = 12.dp), live = true) }
            }
            if (preview.pages.isNotEmpty()) {
                item { Box(Modifier.height(12.dp)) }
                itemsIndexed(preview.pages) { index, page ->
                    ExportPaper(page, first = index == 0, last = index == preview.pages.lastIndex)
                }
            }
            item {
                CardNote(
                    "파일 이름: ${preview.filename} (대상자 이름은 넣지 않아요)",
                    Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ExportLoading() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 12.dp).clip(Ui.Tile).background(Ui.Sunken)
            .padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        CardNote("만드는 중…")
    }
}

@Composable
private fun ExportPaper(page: CardExportPage, first: Boolean, last: Boolean) {
    val top = if (first) 18.dp else 0.dp
    val bottom = if (last) 18.dp else 0.dp
    val shape = RoundedCornerShape(top, top, bottom, bottom)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(Ui.Sunken)
            .padding(start = 16.dp, end = 16.dp, top = if (first) 16.dp else 12.dp, bottom = if (last) 16.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier.widthIn(max = 220.dp).fillMaxWidth().aspectRatio(page.width.toFloat() / page.height)
                .background(Ui.Surface).border(1.dp, Ui.LineStrong)
                .semantics { contentDescription = page.description },
        ) {
            drawIntoCanvas { canvas ->
                canvas.save()
                try {
                    canvas.scale(size.width / page.width, size.height / page.height)
                    page.draw(canvas)
                } finally {
                    canvas.restore()
                }
            }
        }
    }
}
