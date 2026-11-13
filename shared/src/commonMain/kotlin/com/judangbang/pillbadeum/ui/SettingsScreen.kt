package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.judangbang.pillbadeum.domain.ReminderSettings
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.status.ArchivedItem
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.SettingsView
import com.judangbang.pillbadeum.status.baseZoneChoices
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** The privacy policy's public address (REQ-063). TASK-035 decides it; until then its row says `준비 중`. */
internal val PRIVACY_POLICY_URL: String? = null

/** What SCR-009 shows: `#loading`, a read error, or the settings (`#normal`). */
internal sealed interface SettingsState {
    data object Loading : SettingsState

    /** The settings could not be read: `[다시 시도]` (Claude's design, as SCR-008#error). */
    data object Failed : SettingsState

    data class Ready(val view: SettingsView) : SettingsState
}

/**
 * What the phone says for SCR-009, read with the settings each time they are read: on opening and on coming back to
 * the app (SCR-009#stale), so a screen lock set meanwhile changes the backup notice, and a permission given in the
 * system settings changes its row ([access], null until read, TASK-026).
 */
internal data class PhoneState(
    val appVersion: String,
    val backupUnsupported: Boolean,
    val screenLock: Boolean?,
    val access: ReminderAccess? = null,
)

/** What SCR-009's rows do (screens.md SCR-009 table of actions). */
internal class SettingsActions(
    val back: () -> Unit,
    val retry: () -> Unit,
    /** The system time picker for the day start; the screen asks P-5 before anything is saved. */
    val pickDayStart: (initial: LocalTime, onPicked: (LocalTime) -> Unit) -> Unit,
    /** Saves a new day start or base zone after the P-5 question (SPEC-033). */
    val saveWindow: (WindowSettings) -> Unit,
    /** Saves the common reminder settings (REQ-042, SPEC-031). */
    val saveReminders: (ReminderSettings) -> Unit,
    /** `잠금 화면에 약 이름 보이기` (SPEC-034). */
    val showNamesOnLock: (Boolean) -> Unit,
    /** SCR-010 */
    val people: () -> Unit,
    /** `[완전 삭제]` after its question (SPEC-025). */
    val delete: (ArchivedItem) -> Unit,
    /** The privacy policy in a browser (SPEC-036). */
    val openPrivacy: (String) -> Unit,
    /** `[설정 열기]` and `[허용하기]` of `알림이 오지 않을 때`: the system settings screen (SPEC-028, SPEC-029, SPEC-030). */
    val openAccess: (AccessSetting) -> Unit,
)

/**
 * SCR-009 설정, drawn as 09 Settings (TASK-020): the day settings, the common reminder settings and the lock-screen
 * names, what keeps reminders from coming (TASK-026), people and archived items, the backup notice and the app's
 * information (REQ-011, REQ-015, REQ-036, REQ-042, REQ-046, REQ-050, REQ-051, REQ-053, REQ-054, REQ-063, REQ-077,
 * REQ-089).
 *
 * Every change is saved at once, after its question where one is asked; a failure keeps the old value and says so in
 * the band under the bar ([message], SCR-009#error), where the quiet-hours notice shows too. Pickers and questions are
 * kept when the screen is recreated (BUG-003). [now] gives the offsets of the base-zone list.
 */
@Composable
internal fun SettingsScreen(
    state: SettingsState,
    phone: PhoneState,
    privacyUrl: String?,
    message: String?,
    now: () -> Instant,
    actions: SettingsActions,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar("설정", navigation = { BarIconButton("뒤로", close = false, actions.back) })
        // Above the rows, so a message about a row far down is seen (09 has no place for it; Claude's design).
        message?.let { Band(it, Modifier.padding(start = Ui.Side, end = Ui.Side, bottom = 8.dp), live = true) }
        val view = (state as? SettingsState.Ready)?.view
        if (state == SettingsState.Failed) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = Ui.Side, end = Ui.Side, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Words(
                    Labels.SETTINGS_LOAD_FAILED,
                    Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f),
                    Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                ActionButton("다시 시도", ButtonLook.Ink, actions.retry)
            }
            return@Column
        }
        SettingsBody(view, phone, privacyUrl, now, actions)
    }
}

/** Which picker or question is open, kept as text when the screen is recreated (BUG-003). */
private object Open {
    const val INTERVAL = "interval"
    const val PER_DAY = "perDay"
    const val QUIET = "quiet"
    const val ZONE = "zone"
    const val DAY_START_ASK = "dayStart:"
    const val ZONE_ASK = "zoneAsk:"
    const val DELETE_ASK = "delete:"
}

@Composable
private fun SettingsBody(view: SettingsView?, phone: PhoneState, privacyUrl: String?, now: () -> Instant, actions: SettingsActions) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var archivedOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = Ui.Side, end = Ui.Side, top = 4.dp, bottom = 24.dp),
    ) {
        SectionTitle("하루 기준")
        ValueRow("하루 시작 시각", view?.window?.dayStart?.let(Labels::clock), view?.window?.dayStart?.let(Labels::clockSpoken)) {
            val current = view?.window ?: return@ValueRow
            actions.pickDayStart(current.dayStart) { picked ->
                // SPEC-033: the same time changes nothing, so there is nothing to ask.
                if (picked != current.dayStart) open = Open.DAY_START_ASK + Labels.clock(picked)
            }
        }
        ValueRow("기준 시간대", view?.window?.baseZone?.id) { open = Open.ZONE }

        SectionTitle("알림 (모든 약 같이)")
        ValueRow("다시 알림 간격", view?.reminders?.interval?.let(Labels::interval)) { open = Open.INTERVAL }
        ValueRow("하루 최대", view?.reminders?.maxPerDay?.let(Labels::perDay)) { open = Open.PER_DAY }
        ValueRow("방해 금지", view?.reminders?.let(Labels::quiet), view?.reminders?.let(Labels::quietSpoken)) { open = Open.QUIET }
        SwitchRow("잠금 화면에 약 이름 보이기", Labels.SHOW_NAMES_NOTE, view?.showNamesOnLock) { actions.showNamesOnLock(it) }

        // SCR-009#permission (TASK-026): each row says whether it is on; one that is off says what that does and opens
        // the system screen. The screen itself is never blocked. Shown also while no item has reminders on.
        SectionTitle(Labels.ACCESS_SECTION)
        val access = phone.access
        AccessRow(Labels.NOTIFICATIONS_ROW, access?.notifications, Labels.ACCESS_ON, Labels.NOTIFICATIONS_OFF_NOTE, Labels.OPEN_SETTINGS) {
            actions.openAccess(AccessSetting.NOTIFICATIONS)
        }
        AccessRow(Labels.EXACT_ALARMS_ROW, access?.exactAlarms, Labels.ACCESS_ON, Labels.EXACT_ALARMS_OFF_NOTE, Labels.ALLOW) {
            actions.openAccess(AccessSetting.EXACT_ALARMS)
        }
        AccessRow(Labels.BATTERY_ROW, access?.batteryUnrestricted, Labels.BATTERY_FREE, Labels.BATTERY_NOTE, Labels.OPEN_SETTINGS) {
            actions.openAccess(AccessSetting.BATTERY)
        }

        SectionTitle("가족과 보관")
        ValueRow("대상자 관리", view?.people?.let(Labels::peopleCount), chevron = true) { actions.people() }
        // SCR-009#empty: no archived item, no row.
        val archived = view?.archived.orEmpty()
        if (archived.isNotEmpty()) {
            ValueRow(
                "보관한 항목",
                Labels.itemCount(archived.size),
                chevron = true,
                expanded = archivedOpen,
            ) { archivedOpen = !archivedOpen }
            if (archivedOpen) {
                archived.forEach { item ->
                    ArchivedRow(item, withPerson = view?.showPeople == true) { open = Open.DELETE_ASK + item.intakeId }
                }
            }
        }

        SectionTitle("백업")
        Words(
            Labels.backup(phone.backupUnsupported, phone.screenLock),
            Ui.type(14, color = Ui.Muted),
            Modifier.padding(horizontal = 4.dp),
        )

        SectionTitle("앱 정보")
        ValueRow("버전", phone.appVersion.ifBlank { "-" }, onClick = null)
        if (privacyUrl != null) {
            LinkRow("개인정보처리방침") { actions.openPrivacy(privacyUrl) }
        } else {
            ValueRow("개인정보처리방침", Labels.NOT_READY, onClick = null)
        }
        Words(
            Labels.SETTINGS_MEDICAL,
            Ui.type(14, color = Ui.Muted),
            Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }

    if (view == null) return
    val shown = open ?: return
    val close = { open = null }
    when {
        shown == Open.INTERVAL -> ChoiceDialog(
            title = "다시 알림 간격",
            note = "기록하지 않으면 이 간격으로 다시 알려요.",
            options = ReminderSettings.INTERVALS,
            initial = view.reminders.interval,
            label = { d: Duration -> Labels.interval(d) },
            columns = 2,
            onSave = { save(view.reminders.copy(interval = it), view, actions); close() },
            onDismiss = close,
        )
        shown == Open.PER_DAY -> ChoiceDialog(
            title = "하루 최대",
            note = "첫 알림과 다시 알림을 모두 센 횟수예요.",
            options = ReminderSettings.MAX_PER_DAY_RANGE.toList(),
            initial = view.reminders.maxPerDay,
            label = { n: Int -> Labels.perDay(n) },
            columns = 3,
            onSave = { save(view.reminders.copy(maxPerDay = it), view, actions); close() },
            onDismiss = close,
        )
        shown == Open.QUIET -> QuietDialog(
            initial = view.reminders,
            onSave = { start, end -> save(view.reminders.copy(quietStart = start, quietEnd = end), view, actions); close() },
            onDismiss = close,
        )
        shown == Open.ZONE -> ZoneDialog(
            current = view.window.baseZone.id,
            device = TimeZone.currentSystemDefault().id,
            now = now,
            onPick = { id -> open = if (id == view.window.baseZone.id) null else Open.ZONE_ASK + id },
            onDismiss = close,
        )
        shown.startsWith(Open.DAY_START_ASK) -> {
            val picked = runCatching { LocalTime.parse(shown.removePrefix(Open.DAY_START_ASK)) }.getOrNull() ?: return close()
            ConfirmDialog(
                title = "하루 시작 시각을 바꿀까요?",
                body = Labels.dayStartQuestion(picked),
                confirm = "바꾸기",
                onConfirm = {
                    close()
                    actions.saveWindow(view.window.copy(dayStart = picked))
                },
                onDismiss = close,
            )
        }
        shown.startsWith(Open.ZONE_ASK) -> {
            val id = shown.removePrefix(Open.ZONE_ASK)
            val zone = runCatching { TimeZone.of(id) }.getOrNull() ?: return close()
            ConfirmDialog(
                title = "기준 시간대를 바꿀까요?",
                body = Labels.zoneQuestion(id),
                confirm = "바꾸기",
                onConfirm = {
                    close()
                    actions.saveWindow(view.window.copy(baseZone = zone))
                },
                onDismiss = close,
            )
        }
        shown.startsWith(Open.DELETE_ASK) -> {
            // Deleted or never there (an old saved state): nothing to ask.
            val item = view.archived.firstOrNull { it.intakeId == shown.removePrefix(Open.DELETE_ASK) } ?: return close()
            ConfirmDialog(
                title = Labels.deleteQuestion(item.name),
                body = Labels.DELETE_NOTE,
                confirm = "완전 삭제",
                onConfirm = {
                    close()
                    actions.delete(item)
                },
                onDismiss = close,
            )
        }
        else -> close()
    }
}

/** Saves [new] unless it is what is stored already (then the picker only closes). */
private fun save(new: ReminderSettings, view: SettingsView, actions: SettingsActions) {
    if (new != view.reminders) actions.saveReminders(new)
}

// Rows (box.css `.li`) -----------------------------------------------------------------------------------------------

/** A row's frame: at least 56 dp, a subtle line under it unless [line] is false (box.css `.li`; also SCR-006's reminder rows). */
internal fun Modifier.row(line: Boolean = true): Modifier = fillMaxWidth().heightIn(min = 56.dp).drawBehind {
    if (!line) return@drawBehind
    val w = 1.dp.toPx()
    drawLine(Ui.LineSubtle, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
}

/**
 * A name and its value (`.k`, `.v`), with a chevron when it opens something. While loading, [value] is null and a grey
 * cell stands in its place (SCR-009#loading); then nothing can be pressed. TalkBack reads `name, value` ([spoken] for the
 * value when given), with "펼쳐짐"/"접힘" for a row that opens below it ([expanded]).
 */
@Composable
private fun ValueRow(
    name: String,
    value: String?,
    spoken: String? = null,
    chevron: Boolean = false,
    expanded: Boolean? = null,
    onClick: (() -> Unit)?,
) {
    val sentence = if (value == null) "$name, ${Labels.LOADING}" else "$name, ${spoken ?: value}"
    var m = Modifier.row()
    if (onClick != null && value != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Box(
        m.clearAndSetSemantics {
            contentDescription = sentence
            if (expanded != null) stateDescription = if (expanded) "펼쳐짐" else "접힘"
        }.padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        EndOrBelow(
            start = { Words(name, Ui.type(16, lineHeight = 1.25f)) },
            end = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (value == null) {
                        Box(Modifier.size(width = 56.dp, height = 20.dp).clip(RoundedCornerShape(6.dp)).background(Ui.Sunken))
                    } else {
                        Words(value, Ui.type(16, color = Ui.Muted, lineHeight = 1.25f))
                    }
                    if (chevron) Chevron(if (expanded == true) 90f else 0f)
                }
            },
        )
    }
}

/**
 * A row with a switch (`.tg`): the whole row switches it, read as a switch with its name and note (SPEC-034). SCR-006's
 * `알림 받기` uses it too, without the line while the time row follows (09 ItemForm) and without a note for daily items.
 */
@Composable
internal fun SwitchRow(name: String, note: String?, on: Boolean?, line: Boolean = true, onChange: (Boolean) -> Unit) {
    var m = Modifier.row(line)
    if (on != null) m = m.toggleable(value = on, role = Role.Switch, onValueChange = onChange)
    Row(
        m.clearAndSetSemantics { contentDescription = if (on == null) "$name, ${Labels.LOADING}" else listOfNotNull(name, note).joinToString(". ") }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Words(name, Ui.type(16, lineHeight = 1.25f))
            note?.let { Words(it, Ui.type(14, color = Ui.Muted), Modifier.padding(top = 2.dp)) }
        }
        if (on == null) {
            Box(Modifier.size(width = 52.dp, height = 32.dp).clip(RoundedCornerShape(16.dp)).background(Ui.Sunken))
        } else {
            SwitchMark(on)
        }
    }
}

/**
 * A row of `알림이 오지 않을 때` (09 Settings): while [on], its name and [onValue] in ink (`.v.ok`); while off, the name with
 * [offNote] under it and [button] on the right (or under both when the text is large, as [EndOrBelow]). While the phone
 * is not read yet, a grey cell. TalkBack reads `알림 권한, 켜짐` or `알림 권한, 꺼져 있어 …`, and the button with the row's
 * name (`알림 권한 설정 열기`).
 */
@Composable
private fun AccessRow(name: String, on: Boolean?, onValue: String, offNote: String, button: String, onOpen: () -> Unit) {
    if (on != false) {
        val sentence = "$name, ${if (on == null) Labels.LOADING else onValue}"
        Box(
            Modifier.row().clearAndSetSemantics { contentDescription = sentence }.padding(horizontal = 4.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            EndOrBelow(
                start = { Words(name, Ui.type(16, lineHeight = 1.25f)) },
                end = {
                    if (on == null) {
                        Box(Modifier.size(width = 56.dp, height = 20.dp).clip(RoundedCornerShape(6.dp)).background(Ui.Sunken))
                    } else {
                        Words(onValue, Ui.type(16, Ui.SemiBold, lineHeight = 1.25f))
                    }
                },
            )
        }
        return
    }
    Box(Modifier.row().padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
        EndOrBelow(
            start = {
                Column(Modifier.clearAndSetSemantics { contentDescription = "$name, $offNote" }) {
                    Words(name, Ui.type(16, lineHeight = 1.25f))
                    Words(offNote, Ui.type(14, color = Ui.Muted), Modifier.padding(top = 2.dp))
                }
            },
            end = {
                ActionButton(button, ButtonLook.LineSmall, onOpen, Modifier.semantics { contentDescription = "$name $button" })
            },
        )
    }
}

/** An archived item and its `[완전 삭제]` (REQ-015), read `엄마 혈압약 완전 삭제`. */
@Composable
private fun ArchivedRow(item: ArchivedItem, withPerson: Boolean, onDelete: () -> Unit) {
    val name = Labels.archivedName(item, withPerson)
    Box(Modifier.row().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), contentAlignment = Alignment.CenterStart) {
        EndOrBelow(
            start = { Words(name, Ui.type(16, Ui.SemiBold, lineHeight = 1.25f)) },
            end = {
                ActionButton(
                    "완전 삭제",
                    ButtonLook.Danger.copy(wide = false),
                    onDelete,
                    Modifier.semantics { contentDescription = "$name 완전 삭제" },
                )
            },
        )
    }
}

/** `개인정보처리방침` with the outside-link mark: opens the browser (SPEC-036). */
@Composable
private fun LinkRow(name: String, onClick: () -> Unit) {
    Row(
        Modifier.row().clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = "$name, 브라우저에서 열기" }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Words(name, Ui.type(16, lineHeight = 1.25f), Modifier.weight(1f))
        OutsideMark()
    }
}

/**
 * [start] and [end] on one line while [end] takes at most 45% of the width; else [end] goes under [start], so 200% text
 * keeps whole words (Claude's design: 09 draws the row at 100% only).
 */
@Composable
internal fun EndOrBelow(start: @Composable () -> Unit, end: @Composable () -> Unit) {
    Layout(contents = listOf(start, end), modifier = Modifier.fillMaxWidth()) { (startMeasurables, endMeasurables), constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val first = startMeasurables.single()
        val second = endMeasurables.single()
        val endWidth = second.maxIntrinsicWidth(Constraints.Infinity)
        if (endWidth <= width * 0.45f) {
            val endPlaced = second.measure(Constraints(maxWidth = endWidth.coerceAtMost(width)))
            val startPlaced = first.measure(Constraints(maxWidth = (width - endPlaced.width - gap).coerceAtLeast(0)))
            val height = maxOf(startPlaced.height, endPlaced.height)
            layout(width, height) {
                startPlaced.place(0, (height - startPlaced.height) / 2)
                endPlaced.place(width - endPlaced.width, (height - endPlaced.height) / 2)
            }
        } else {
            val startPlaced = first.measure(Constraints(maxWidth = width))
            val endPlaced = second.measure(Constraints(maxWidth = width))
            val between = 4.dp.roundToPx()
            layout(width, startPlaced.height + between + endPlaced.height) {
                startPlaced.place(0, 0)
                endPlaced.place(0, startPlaced.height + between)
            }
        }
    }
}

/** The board's chevron (`M9 5l7 7-7 7`, 20 dp, muted), turned down while its row is open. */
@Composable
internal fun Chevron(degrees: Float) {
    Canvas(Modifier.size(20.dp).rotate(degrees)) {
        val u = size.width / 24f
        val path = Path().apply {
            moveTo(9 * u, 5 * u)
            lineTo(16 * u, 12 * u)
            lineTo(9 * u, 19 * u)
        }
        drawPath(path, Ui.Muted, style = Stroke(2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The board's outside-link mark (18 dp, muted). */
@Composable
private fun OutsideMark() {
    Canvas(Modifier.size(18.dp)) {
        val u = size.width / 24f
        val path = Path().apply {
            moveTo(14 * u, 4 * u)
            lineTo(20 * u, 4 * u)
            lineTo(20 * u, 10 * u)
            moveTo(20 * u, 4 * u)
            lineTo(11 * u, 13 * u)
            moveTo(18 * u, 14 * u)
            lineTo(18 * u, 19 * u)
            quadraticTo(18 * u, 20 * u, 17 * u, 20 * u)
            lineTo(5 * u, 20 * u)
            quadraticTo(4 * u, 20 * u, 4 * u, 19 * u)
            lineTo(4 * u, 7 * u)
            quadraticTo(4 * u, 6 * u, 5 * u, 6 * u)
            lineTo(10 * u, 6 * u)
        }
        drawPath(path, Ui.Muted, style = Stroke(2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** box.css `.tg`: 52 × 32 with a 2 dp line; on, ink with a white knob on the right. */
@Composable
private fun SwitchMark(on: Boolean) {
    Canvas(Modifier.size(width = 52.dp, height = 32.dp)) {
        val line = 2.dp.toPx()
        val radius = size.height / 2
        val track = if (on) Ui.Ink else Ui.Surface
        val border = if (on) Ui.Ink else Ui.LineStrong
        drawRoundRect(track, cornerRadius = CornerRadius(radius))
        drawRoundRect(
            border,
            topLeft = Offset(line / 2, line / 2),
            size = Size(size.width - line, size.height - line),
            cornerRadius = CornerRadius(radius - line / 2),
            style = Stroke(line),
        )
        val knob: Color = if (on) Ui.OnSolid else Ui.LineStrong
        drawCircle(knob, radius = 10.dp.toPx(), center = Offset((if (on) 36.dp else 16.dp).toPx(), size.height / 2))
    }
}

// Pickers ------------------------------------------------------------------------------------------------------------

/** A dialog box (as [ConfirmDialog]) as wide as the screen allows, so 200% text keeps its cells. */
@Composable
private fun SettingsDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp)) {
            Box(Modifier.fillMaxWidth().clip(Ui.Tile).background(Ui.Surface)) { content() }
        }
    }
}

@Composable
private fun DialogButtons(onCancel: () -> Unit, save: String?, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        ActionButton("취소", ButtonLook.LineSmall, onCancel)
        if (save != null) ActionButton(save, ButtonLook.InkSmall, onSave)
    }
}

/** One of a few values (09 `.pick .c` cells), saved with `[저장]` (Claude's design: 09 draws no picker). */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    note: String,
    options: List<T>,
    initial: T,
    label: (T) -> String,
    columns: Int,
    onSave: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var index by rememberSaveable { mutableIntStateOf(options.indexOf(initial).coerceAtLeast(0)) }
    SettingsDialog(onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Words(title, Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f), heading = true)
            Words(note, Ui.type(14, color = Ui.Muted))
            PickGrid(options.indices.toList(), columns) { i, modifier ->
                PickCell(label(options[i]), i == index, modifier) { index = i }
            }
            DialogButtons(onDismiss, "저장") { onSave(options[index]) }
        }
    }
}

/**
 * Quiet hours (supplement N-4): a start and an end on the half hour. `[시작]` or `[끝]` above picks which one the hour
 * and `00`/`30` cells below set (SCR-007's cells), so one grid of hours fits a phone 360 dp wide with every cell 48 dp.
 * The same start and end mean none. The first reminder still rings in them.
 */
@Composable
private fun QuietDialog(initial: ReminderSettings, onSave: (LocalTime, LocalTime) -> Unit, onDismiss: () -> Unit) {
    var start by rememberSaveable { mutableIntStateOf(initial.quietStart.hour * 60 + initial.quietStart.minute) }
    var end by rememberSaveable { mutableIntStateOf(initial.quietEnd.hour * 60 + initial.quietEnd.minute) }
    var settingEnd by rememberSaveable { mutableStateOf(false) }
    val large = LocalDensity.current.fontScale >= 1.3f
    SettingsDialog(onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Words("방해 금지", Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f), heading = true)
            Words("이 시간에는 다시 알림이 울리지 않아요. 첫 알림은 울려요.", Ui.type(14, color = Ui.Muted))
            PickGrid(listOf(false, true), columns = 2) { isEnd, modifier ->
                EndCell(if (isEnd) "끝" else "시작", if (isEnd) end else start, selected = settingEnd == isEnd, modifier) {
                    settingEnd = isEnd
                }
            }
            if (settingEnd) HalfHours("끝", end, large) { end = it } else HalfHours("시작", start, large) { start = it }
            val chosen = ReminderSettings(quietStart = time(start), quietEnd = time(end))
            Words(
                if (start == end) "방해 금지 없음 (시작과 끝이 같아요)" else Labels.quiet(chosen),
                Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f),
                Modifier.padding(top = 8.dp, start = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                spoken = if (start == end) "방해 금지 없음" else Labels.quietSpoken(chosen),
            )
            DialogButtons(onDismiss, "저장") { onSave(time(start), time(end)) }
        }
    }
}

private fun time(minuteOfDay: Int): LocalTime = LocalTime(minuteOfDay / 60, minuteOfDay % 60)

/** `[시작 22:00]` / `[끝 07:00]`: which end the cells below set (TalkBack: `시작, 22시`). */
@Composable
private fun EndCell(name: String, minuteOfDay: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val (fill, border, ink) = if (selected) Triple(Ui.Ink, Ui.Ink, Ui.OnSolid) else Triple(Ui.Surface, Ui.LineStrong, Ui.Ink)
    Column(
        modifier.heightIn(min = 56.dp).clip(Ui.ButtonShape).background(fill).border(1.5.dp, border, Ui.ButtonShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = "$name, ${Labels.clockSpoken(time(minuteOfDay))}" }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Words(name, Ui.type(14, Ui.SemiBold, ink, lineHeight = 1.25f, center = true), Modifier.hiddenFromTalkBack())
        Words(Labels.clock(time(minuteOfDay)), Ui.type(20, Ui.ExtraBold, ink, lineHeight = 1.25f, center = true), Modifier.hiddenFromTalkBack())
    }
}

/**
 * One end of quiet hours: its hour, then `00` or `30` (`시작 22시`, `시작 30분`). The hours take six columns where each
 * cell keeps 48 dp, otherwise four or three, and at most four with large text (REQ-067).
 */
@Composable
private fun HalfHours(name: String, minuteOfDay: Int, large: Boolean, onChange: (Int) -> Unit) {
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    BoxWithConstraints {
        val fits = listOf(6, 4).firstOrNull { c -> (maxWidth - 6.dp * (c - 1)) / c >= 48.dp } ?: 3
        PickGrid((0..23).toList(), columns = if (large) minOf(fits, 4) else fits) { h, modifier ->
            TimeCell(two(h), "$name ${h}시", h == hour, enabled = true, modifier) { onChange(h * 60 + minute) }
        }
    }
    PickGrid(listOf(0, 30), columns = 2) { m, modifier ->
        TimeCell(two(m), "$name ${m}분", m == minute, enabled = true, modifier) { onChange(hour * 60 + m) }
    }
}

private fun two(n: Int): String = n.toString().padStart(2, '0')

/**
 * The base zone, from the list only (SPEC-033): the one in use, the phone's and Asia/Seoul first, then every region zone
 * by name, each with its offset now. Picking one asks P-5; the one in use only closes the list.
 */
@Composable
private fun ZoneDialog(current: String, device: String, now: () -> Instant, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val zones = remember(current, device) { baseZoneChoices(current, device) }
    val at = remember { now() }
    SettingsDialog(onDismiss) {
        Column(Modifier.heightIn(max = 640.dp).padding(vertical = 20.dp)) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Words("기준 시간대", Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f), heading = true)
                Words("날짜를 이 시간대로 셈해요.", Ui.type(14, color = Ui.Muted))
            }
            LazyColumn(Modifier.weight(1f, fill = false).padding(top = 8.dp)) {
                items(zones, key = { it }) { id ->
                    val offset = remember(id) { Labels.utcOffset(TimeZone.of(id), at) }
                    val detail = if (id == device) "$offset · 이 휴대폰" else offset
                    ZoneRow(id, detail, id == current) { onPick(id) }
                }
            }
            Box(Modifier.padding(horizontal = 20.dp)) { DialogButtons(onDismiss, save = null) {} }
        }
    }
}

@Composable
private fun ZoneRow(id: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .clearAndSetSemantics { contentDescription = "$id, $detail" }
            .background(if (selected) Ui.Sunken else Ui.Surface)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Words(id, Ui.type(16, if (selected) Ui.ExtraBold else FontWeight.Normal, lineHeight = 1.25f), Modifier.weight(1f))
        Words(detail, Ui.type(14, color = Ui.Muted, lineHeight = 1.25f))
    }
}
