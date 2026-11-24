package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.EmergencyCopyRecord
import com.judangbang.pillbadeum.usecase.EmergencyLine
import com.judangbang.pillbadeum.usecase.EmergencySection
import com.judangbang.pillbadeum.usecase.EmergencyText
import com.judangbang.pillbadeum.usecase.HealthCardSummary
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val COPY = "복사"
private const val COPY_ALL = "모두 복사"
private const val COPY_FAILED = "복사하지 못했어요. 다시 눌러 주세요"
private const val COPY_NEXT = "이제 긴급 정보에 붙여 넣어 주세요."
private const val COPY_NEXT_LEGACY = "복사했어요. 긴급 정보에 붙여 넣어 주세요. " +
    "이 휴대폰에서는 복사한 글이 클립보드 미리보기에 보일 수 있어요."
private const val COPY_REMINDER = "휴대폰에 저장한 뒤 건강 카드로 돌아가 [다 넣었어요]를 눌러 주세요."
private const val KNOWN_SETTINGS = "갤럭시는 설정 → 안전 및 긴급 → 의료정보, " +
    "Pixel은 위급 상황 정보 앱 → 내 정보 → 의료 정보에서 열어요."
private const val OTHER_SETTINGS = "휴대폰 설정에서 '긴급 정보'나 '의료 정보'를 찾아 열어요."
private const val PASTE_INSTRUCTIONS = "칸마다 맞는 줄을 복사해 붙여 넣어 주세요. 붙여 넣은 뒤 저장해 주세요. " +
    "보호자는 휴대폰 긴급 연락처에서 연락처를 골라 따로 더해 주세요. 잠금 화면에 보이게 하려면 휴대폰 설정에서 켜 주세요."

private data class CopyFeedback(val section: EmergencySection?, val text: String)

/** SCR-014 keeps the card seed in memory and rebuilds from the current card each time the app returns. */
@Composable
internal fun EmergencyRoute(
    core: DidITakeCore?,
    personId: String,
    personName: String?,
    seed: HealthCardSummary?,
    refreshKey: Int,
    openFailed: Boolean,
    phone: Phone,
    onRetryOpen: () -> Unit,
    onClose: () -> Unit,
) {
    var card by remember { mutableStateOf(seed) }
    var readFailed by remember { mutableStateOf(false) }
    var attempts by remember { mutableIntStateOf(0) }
    val emergency = remember(core, card) { card?.let { core?.buildEmergencyText?.invoke(it) } }

    LaunchedEffect(core, refreshKey, attempts) {
        if (core == null) return@LaunchedEffect
        try {
            val loaded = core.loadHealthCard(personId)
            if (loaded == null || !loaded.canOpenEmergencyText()) {
                onClose()
            } else {
                card = loaded
                readFailed = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            readFailed = true
        }
    }

    var copying by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<CopyFeedback?>(null) }
    val scope = rememberCoroutineScope()

    fun copy(section: EmergencySection?, text: String) {
        val c = core ?: return
        if (copying) return
        if (!phone.copyEmergencyText(text)) {
            feedback = CopyFeedback(section, COPY_FAILED)
            return
        }
        copying = true
        feedback = null
        // Finish recording after the clipboard write, even if the user immediately leaves this screen.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    val recorded = c.healthCardNoticeActions.recordCopy(personId)
                    val next = if (phone.showsClipboardPreview) COPY_NEXT else COPY_NEXT_LEGACY
                    feedback = CopyFeedback(section, if (recorded == EmergencyCopyRecord.NOTICE_REMAINS) {
                        "$next $COPY_REMINDER"
                    } else {
                        next
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    feedback = CopyFeedback(section, Labels.CARD_NOTICE_ACTION_FAILED)
                } finally {
                    copying = false
                }
            }
        }
    }

    val person = (card?.person?.name ?: personName)?.let(Labels::emergencyPerson)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stacked = LocalDensity.current.fontScale >= 1.5f || maxWidth < 320.dp
        Column(Modifier.fillMaxSize()) {
            TopBar(Labels.CARD_EMERGENCY, navigation = { BarIconButton("닫기", close = true, onClose) })
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(
                start = Ui.Side, end = Ui.Side, top = 4.dp, bottom = 24.dp,
            )) {
                person?.let {
                    Words(it, Ui.type(16, Ui.ExtraBold), Modifier.padding(horizontal = 4.dp))
                    CardNote("${Labels.CARD_EMERGENCY_HINT}이에요.", Modifier.padding(
                        start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp,
                    ))
                }
                when {
                    openFailed || readFailed -> CardReadFailed {
                        readFailed = false
                        if (core == null) onRetryOpen() else attempts++
                    }
                    emergency == null -> {
                        CardLoading(sections = 2)
                        ActionButton(COPY_ALL, ButtonLook.Ink, {}, Modifier.padding(top = 8.dp), enabled = false)
                    }
                    else -> EmergencyBody(
                        person = checkNotNull(person),
                        emergency = emergency,
                        knownPaths = phone.hasKnownEmergencySettings,
                        stacked = stacked,
                        enabled = !copying,
                        feedback = feedback,
                        copy = ::copy,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmergencyBody(
    person: String,
    emergency: EmergencyText,
    knownPaths: Boolean,
    stacked: Boolean,
    enabled: Boolean,
    feedback: CopyFeedback?,
    copy: (EmergencySection?, String) -> Unit,
) {
    DisableSelection {
        Words(emergency.text, Ui.type(16), Modifier.fillMaxWidth().clip(Ui.Tile).background(Ui.Sunken).padding(16.dp))
    }
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        CardNote(Labels.emergencyCharacterCount(emergency.characterCount))
    }
    ActionButton(COPY_ALL, ButtonLook.Ink, { copy(null, emergency.text) }, enabled = enabled)
    feedback?.takeIf { it.section == null }?.let { CopyHint(it.text, Modifier.padding(horizontal = 4.dp)) }

    Box(Modifier.padding(top = 20.dp, bottom = 6.dp)) { SectionTitle("넣는 곳") }
    Column(Modifier.padding(horizontal = 4.dp)) {
        Words(person, Ui.type(14))
        val paths = if (knownPaths) KNOWN_SETTINGS else OTHER_SETTINGS
        Words("$paths $PASTE_INSTRUCTIONS", Ui.type(14), Modifier.padding(top = 6.dp))
        Words("카드에서 지운 정보는 휴대폰 긴급 정보에서도 지워 주세요.", Ui.type(14), Modifier.padding(top = 6.dp))
        CardNote("앱은 대신 등록하지 않아요.", Modifier.padding(top = 6.dp))
    }

    Box(Modifier.padding(top = 20.dp, bottom = 6.dp)) { SectionTitle("칸별 복사") }
    for (line in emergency.lines) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.LineSubtle))
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp)) {
            EmergencyCopyRow(line, stacked, enabled) { copy(line.section, line.text) }
            feedback?.takeIf { it.section == line.section }?.let { CopyHint(it.text) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.LineSubtle))
}

@Composable
private fun EmergencyCopyRow(line: EmergencyLine, stacked: Boolean, enabled: Boolean, onCopy: () -> Unit) {
    val title = Labels.emergencySection(line.section)
    val text = @Composable {
        Words(title, Ui.type(15, Ui.ExtraBold, lineHeight = 1.25f), heading = true)
        DisableSelection { Words(line.text, Ui.type(16), Modifier.padding(top = 4.dp)) }
    }
    val button = @Composable {
        ActionButton(COPY, ButtonLook.LineSmall, onCopy, Modifier.widthIn(min = 48.dp), enabled, spoken = "$title $COPY")
    }
    if (stacked) {
        text()
        Box(Modifier.padding(top = 8.dp)) { button() }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) { text() }
            button()
        }
    }
}

/**
 * The hint goes under the pressed button. After copying a row at the bottom of the screen it would start below the
 * visible part, and on Android 12 and earlier it is the only sign that the copy worked, so it scrolls into view.
 */
@Composable
private fun CopyHint(text: String, modifier: Modifier = Modifier) {
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(text) { bringIntoView.bringIntoView() }
    Words(text, Ui.type(14, Ui.SemiBold), modifier.padding(top = 8.dp)
        .bringIntoViewRequester(bringIntoView)
        .semantics { liveRegion = LiveRegionMode.Polite })
}
