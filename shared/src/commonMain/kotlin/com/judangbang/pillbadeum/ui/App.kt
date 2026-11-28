package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.status.HistoryMonth
import com.judangbang.pillbadeum.status.JudgeCell
import com.judangbang.pillbadeum.status.JudgeLoad
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.PeopleView
import com.judangbang.pillbadeum.status.SettingsView
import com.judangbang.pillbadeum.status.StatusBoard
import com.judangbang.pillbadeum.status.showsPeople
import com.judangbang.pillbadeum.usecase.CardEntry
import com.judangbang.pillbadeum.usecase.CardEntryKind
import com.judangbang.pillbadeum.usecase.ClockFlagResult
import com.judangbang.pillbadeum.usecase.DeleteIntakeResult
import com.judangbang.pillbadeum.usecase.IntakeMedicationDraft
import com.judangbang.pillbadeum.usecase.PersonChange
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.HealthCardSummary
import com.judangbang.pillbadeum.usecase.SaveCardEntryResult
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import com.judangbang.pillbadeum.usecase.SaveReminderSettingsResult
import com.judangbang.pillbadeum.usecase.SaveSettingsResult
import com.judangbang.pillbadeum.usecase.VoidResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The app's screens, one at a time (07 sitemap). [key] is what is saved when the screen is recreated (BUG-003).
 * [back] is where system back goes (REQ-094, SPEC-021): every screen to the first, the people screen to settings and its
 * person form to the people screen (TASK-021), SCR-007 opened from SCR-008 back to that month (TASK-019), SCR-011 back to
 * the screen that opened it: the first screen, the people screen or the person form (SPEC-054, TASK-061), the intro's
 * second page to its first (TASK-022), and null on the first screen and on the intro's first page, where back closes the
 * app.
 */
internal sealed interface Route {
    val key: String
    val back: Route?

    /** SCR-004 */
    data object Home : Route {
        override val key = "home"
        override val back: Route? = null
    }

    /** SCR-006; [intakeId] null adds a new item. */
    data class Item(val intakeId: String?, val from: Route = Home) : Route {
        override val key get() = "item:${intakeId ?: ""}" + if (from == Home) "" else "@${from.key}"
        override val back: Route get() = from
    }

    /**
     * SCR-007 for one cell: a schedule version's window with its period key (supplement P-1, TASK-018). [from] is the
     * SCR-008 month it was opened from: back returns there, to the same person and line (Claude's choice, TASK-019).
     */
    data class Judge(val intakeId: String, val versionId: String, val periodKey: String, val from: History? = null) : Route {
        override val key get() = "judge:$intakeId:$versionId:$periodKey" + (from?.let { "@${it.key}" } ?: "")
        override val back: Route get() = from ?: Home
    }

    /** SCR-008 for the month of [month] (its first day) and one person, or everyone with [personId] null (TASK-019). */
    data class History(val month: LocalDate, val personId: String?) : Route {
        override val key get() = "history:$month:${personId ?: ""}"
        override val back: Route get() = Home
    }

    /** SCR-009 (TASK-020) */
    data object Settings : Route {
        override val key = "settings"
        override val back: Route = Home
    }

    /** SCR-010 (TASK-021) */
    data object People : Route {
        override val key = "people"
        override val back: Route = Settings
    }

    /** SCR-010's form for one person, or a new one with [personId] null; back returns to the list (TASK-021). */
    data class Person(val personId: String?) : Route {
        override val key get() = "person:${personId ?: ""}"
        override val back: Route get() = People
    }

    /** SCR-011~015 suppress the Android recent-apps snapshot while visible. */
    sealed interface HealthCardRoute : Route

    data class HealthCard(val personId: String, val from: Route = Home) : HealthCardRoute {
        override val key get() = "card:$personId" + if (from == Home) "" else "@${from.key}"
        override val back get() = from
    }

    /** SCR-013. Save the route, never the contents of the card. */
    data class CardShow(override val back: HealthCard) : HealthCardRoute {
        override val key get() = "card-show@${back.key}"
    }

    data class CardEditor(
        val kind: CardEntryKind,
        val entryId: String,
        val editing: Boolean,
        override val back: HealthCard,
    ) : HealthCardRoute {
        override val key get() = "card-entry:${kind.name}:$entryId:$editing@${back.key}"
    }

    data class Emergency(override val back: HealthCard) : HealthCardRoute {
        override val key get() = "card-emergency@${back.key}"
    }

    data class CardExport(override val back: HealthCard) : HealthCardRoute {
        override val key get() = "card-export@${back.key}"
    }

    /**
     * SCR-005, the first-run intro on its [page] (0 or 1, TASK-022). Back on the first page closes the app without
     * finishing the intro, so the next start shows it again (Claude's choice).
     */
    data class Welcome(val page: Int) : Route {
        override val key get() = "welcome:$page"
        override val back: Route? get() = if (page > 0) Welcome(page - 1) else null
    }

    companion object {
        fun parse(key: String): Route {
            if ('@' in key) {
                val from = parse(key.substringAfter('@'))
                val requested = key.substringBefore('@')
                if (requested == "card-show" && from is HealthCard) return CardShow(from)
                if (requested == "card-emergency" && from is HealthCard) return Emergency(from)
                if (requested == "card-export" && from is HealthCard) return CardExport(from)
                if (requested.startsWith("card-entry:") && from is HealthCard) {
                    val parts = requested.split(':')
                    val kind = CardEntryKind.entries.firstOrNull { it.name == parts.getOrNull(1) }
                    val id = parts.getOrNull(2)?.takeIf(String::isNotEmpty)
                    val editing = parts.getOrNull(3)?.toBooleanStrictOrNull()
                    return if (kind != null && id != null && editing != null) CardEditor(kind, id, editing, from) else from
                }
                return when (val route = parse(requested)) {
                    is Item -> route.copy(from = from)
                    is Judge -> route.copy(from = from as? History)
                    is HealthCard -> route.copy(from = from)
                    else -> route
                }
            }
            val parts = key.split(':')
            return when (parts[0]) {
                "item" -> Item(parts.getOrNull(1)?.takeIf { it.isNotEmpty() })
                "judge" -> if (parts.size == 4) Judge(parts[1], parts[2], parts[3]) else Home
                "history" -> parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?.let { History(LocalDate(it.year, it.month, 1), parts.getOrNull(2)?.takeIf(String::isNotEmpty)) }
                    ?: Home
                "settings" -> Settings
                "people" -> People
                "person" -> Person(parts.getOrNull(1)?.takeIf { it.isNotEmpty() })
                "card" -> parts.getOrNull(1)?.takeIf(String::isNotEmpty)?.let { HealthCard(it) } ?: Home
                "welcome" -> Welcome((parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, WELCOME_PAGES - 1))
                else -> Home
            }
        }

        val Saver: Saver<Route, String> = Saver(save = { it.key }, restore = { parse(it) })
    }
}

/**
 * Root of the app, shared by every platform.
 *
 * @param open opens the core (the database); a failure shows SCR-004#error with `[다시 시도]`.
 * @param onDataChanged called after every write (record, cancel, item, person, restore notice, settings) so the
 *   platform can redraw its widgets and plan its reminders again.
 * @param phone what the settings screen and the intro read from and ask of the phone (SCR-009, TASK-020; SCR-005, TASK-022).
 * @param privacyPolicyUrl the privacy policy's address (REQ-063); tests pass another one.
 * @param refreshKey bump to read the database again (on resume: the widget may have recorded meanwhile).
 * @param homeRequests bump to show the first screen (a notification or the widget opened the app, R3 memo); the intro
 *   stays until it is finished.
 * @param backHandler the platform's system back: called with whether back stays in the app, and what it does.
 * @param initialHealthCardPersonId starts directly at SCR-011 for app tests. The app itself opens the card from SCR-004's
 *   `[건강 카드]` and from SCR-010 (TASK-061).
 * @param protectCardRecents applies the platform's recents privacy setting while a health card screen is shown.
 * @param keepCardShowAwake keeps the platform screen on while the SCR-013 summary is displayed in the foreground.
 */
@Composable
fun App(
    open: suspend () -> DidITakeCore,
    onDataChanged: suspend () -> Unit,
    phone: Phone,
    privacyPolicyUrl: String = PRIVACY_POLICY_URL,
    refreshKey: Int = 0,
    homeRequests: Int = 0,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
    initialHealthCardPersonId: String? = null,
    protectCardRecents: @Composable (visible: Boolean) -> Unit = {},
    keepCardShowAwake: @Composable () -> Unit = {},
    cardExporter: CardExporter? = null,
) {
    val scope = rememberCoroutineScope()
    var core by remember { mutableStateOf<DidITakeCore?>(null) }
    var board by remember { mutableStateOf<StatusBoard?>(null) }
    var people by remember { mutableStateOf<List<PersonEntity>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var retries by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var route by rememberSaveable(stateSaver = Route.Saver) {
        mutableStateOf<Route>(initialHealthCardPersonId?.let { Route.HealthCard(it) } ?: Route.Home)
    }
    var cardEntrySeed by remember { mutableStateOf<CardEntry?>(null) }
    var cardShowSeed by remember { mutableStateOf<HealthCardSummary?>(null) }
    var emergencySeed by remember { mutableStateOf<HealthCardSummary?>(null) }
    // Counts the reads, so a screen with data of its own (SCR-007, SCR-008) reads it again after every read of the board.
    var reads by remember { mutableIntStateOf(0) }
    // SCR-008 (TASK-019): the last month read, with the screen it was read for, and where its list was. Both stay while
    // SCR-007 is open on top, so back shows the same lines at the same place at once (and reads them again).
    var history by remember { mutableStateOf<Pair<Route.History, HistoryMonth>?>(null) }
    var historyFailed by remember { mutableStateOf(false) }
    var historyAttempts by remember { mutableIntStateOf(0) }
    var historyList by rememberSaveable(stateSaver = ListPlace) { mutableStateOf(LazyListState()) }
    // SCR-009 (TASK-020): the settings last read and the phone's state read with them. They stay while SCR-010 is open
    // on top, so back shows them at once (and reads them again).
    var settings by remember { mutableStateOf<SettingsView?>(null) }
    var phoneState by remember { mutableStateOf<PhoneState?>(null) }
    var settingsFailed by remember { mutableStateOf(false) }
    var settingsAttempts by remember { mutableIntStateOf(0) }
    // SCR-010 (TASK-021): the people last read. They stay while a person's form is open on top, so back shows them at once.
    var peopleView by remember { mutableStateOf<PeopleView?>(null) }
    var peopleFailed by remember { mutableStateOf(false) }
    var peopleAttempts by remember { mutableIntStateOf(0) }
    val personFormState = rememberSaveableStateHolder()
    // SCR-005 (TASK-022): closed in this activity, by [시작하기] or [건너뛰기]. It does not come back here even when saving
    // that failed or the database was not open yet; the next start shows it again then.
    var introClosed by rememberSaveable { mutableStateOf(false) }
    // Whether the launcher takes [위젯 추가]: read again on coming back to the app, off after a refused request.
    var canPin by remember(refreshKey) { mutableStateOf(phone.canPinWidget()) }
    // SCR-004#permission (TASK-026): read each time the first screen comes and on coming back to the app, since the
    // system settings change it (a person back from `[설정 열기]` sees the band gone).
    var access by remember { mutableStateOf<ReminderAccess?>(null) }
    val zone = TimeZone.currentSystemDefault()

    suspend fun reload() {
        val c = core ?: open().also { opened ->
            // SCR-005 on a first run (intro_seen_at absent, SPEC-002): before the first screen shows any data. A
            // recreated activity keeps the page it was on, and another screen is never covered.
            val seen = opened.settings.introSeen()
            if (!seen && !introClosed && route == Route.Home) route = Route.Welcome(0)
            if (seen && route is Route.Welcome) route = Route.Home
            core = opened
        }
        people = c.people.list()
        board = c.loadStatusBoard()
        failed = false
        reads++
    }

    suspend fun reloadOrFail() {
        try {
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    /** After a write: the widgets and reminders first, then this screen. */
    suspend fun afterWrite() {
        try {
            onDataChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The widget redraws on its own next time; the record itself is saved.
        }
        reloadOrFail()
    }

    /**
     * Runs one write at a time: buttons do nothing while one is running (REQ-001). A write that has started runs to
     * its end with [afterWrite] (widgets, the reminder alarm and notifications), even when the activity is recreated
     * meanwhile (rotation, font size, dark mode), which cancels [scope]: otherwise a saved write could leave a new
     * reminder without its alarm until the next write (PR #20 independent review). No write block waits for the screen.
     */
    fun write(failure: String = Labels.TRY_AGAIN, block: suspend (DidITakeCore) -> Unit) {
        val c = core ?: return
        if (busy) return
        busy = true
        scope.launch {
            withContext(NonCancellable) {
                try {
                    block(c)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    message = failure
                } finally {
                    busy = false
                }
            }
        }
    }

    fun go(to: Route) {
        val from = route
        if (from is Route.Person && (to !is Route.HealthCard || to.from != from)) {
            personFormState.removeState(from.key)
        }
        if (from == Route.People && to is Route.Person) personFormState.removeState(to.key)
        message = null
        cardEntrySeed = null
        cardShowSeed = null
        emergencySeed = null
        route = to
    }

    /** `[설정 열기]`·`[허용하기]` (SCR-004, SCR-009): the system screen, or a message when the phone has none. */
    fun openAccess(setting: AccessSetting) {
        message = if (phone.openAccessSettings(setting)) null else Labels.ACCESS_OPEN_FAILED
    }

    /** SCR-008 from the first line: opened from the first screen, or another month or person. */
    fun openHistory(to: Route.History) {
        history = null
        historyFailed = false
        historyList = LazyListState()
        go(to)
    }

    /** SCR-009 from the first screen: read anew. */
    fun openSettings() {
        settings = null
        settingsFailed = false
        go(Route.Settings)
    }

    /** SCR-010 from the settings: read anew. */
    fun openPeople() {
        peopleView = null
        peopleFailed = false
        go(Route.People)
    }

    /**
     * SCR-005's `[시작하기]` and `[건너뛰기]`: the first screen at once, and `intro_seen_at` saved behind it (SPEC-002). A save
     * that fails, or a database not open yet, leaves the intro for the next start.
     */
    fun finishIntro() {
        introClosed = true
        go(Route.Home)
        val c = core ?: return
        scope.launch {
            try {
                c.settings.markIntroSeen()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Shown again on the next start.
            }
        }
    }

    /** Reads SCR-010's people; a failure shows `#error` only while nothing has been read. */
    suspend fun readPeople(c: DidITakeCore) {
        try {
            peopleView = c.loadPeople()
            peopleFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            peopleFailed = true
        }
    }

    LaunchedEffect(refreshKey, retries) {
        message = null
        reloadOrFail()
    }
    LaunchedEffect(homeRequests) { if (homeRequests > 0 && route !is Route.Welcome) go(Route.Home) }
    // SCR-004#stale (TASK-017): left open across the day start, the app reads the new day at the boundary. It looks
    // at the clock at least once a minute, so a clock set forward is noticed too; coming back to the app reads again
    // anyway (refreshKey). A tap drawn before the boundary records nothing and says so (RecordResult.Stale).
    val staleAt = board?.staleAt
    LaunchedEffect(staleAt) {
        val at = staleAt ?: return@LaunchedEffect
        val clock = core?.clock ?: return@LaunchedEffect
        while (true) {
            val left = at - clock.now()
            if (left <= Duration.ZERO) break
            delay(left.coerceAtMost(1.minutes))
        }
        message = null
        reloadOrFail()
    }
    backHandler(route.back != null) { route.back?.let(::go) }
    protectCardRecents(route is Route.HealthCardRoute)

    Box(Modifier.fillMaxSize().background(Ui.Surface).safeDrawingPadding().imePadding()) {
        when (val current = route) {
            is Route.Welcome -> WelcomeScreen(
                page = current.page,
                canPin = canPin,
                actions = WelcomeActions(
                    next = { go(Route.Welcome(current.page + 1)) },
                    finish = { finishIntro() },
                    // The launcher shows its own confirmation; a refused request leaves the steps alone (SCR-005#error).
                    pin = { if (!phone.pinWidget()) canPin = false },
                ),
            )

            Route.Home -> {
                LaunchedEffect(refreshKey) { access = phone.reminderAccess() }
                var cardRequested by rememberSaveable { mutableStateOf(false) }
                HomeScreen(
                    state = when {
                        failed -> HomeState.Failed
                        else -> board?.let { HomeState.Ready(it) } ?: HomeState.Loading
                    },
                    message = message,
                    zone = zone,
                    access = access,
                    actions = HomeActions(
                        retry = {
                            failed = false
                            board = null
                            retries++
                        },
                        history = {
                            board?.baseDate?.let { today -> openHistory(Route.History(LocalDate(today.year, today.month, 1), null)) }
                        },
                        settings = { openSettings() },
                        card = { if (!busy) cardRequested = true },
                        add = { go(Route.Item(null, from = current)) },
                        edit = { go(Route.Item(it.intakeId, from = current)) },
                        more = { go(Route.Judge(it.intakeId, it.scheduleVersionId, it.window.periodKey)) },
                        record = { item ->
                            write { c ->
                                val result = c.record(item.intakeId, DoseSource.APP, item.window.periodKey)
                                message = if (result is RecordResult.Error) Labels.TRY_AGAIN else Labels.recordMessage(result, item.name, c.clock.now(), zone)
                                afterWrite()
                            }
                        },
                        void = { item ->
                            val judgment = item.judgment
                            if (judgment != null) {
                                write { c ->
                                    message = when (c.voidDose(judgment.id)) {
                                        VoidResult.Voided -> Labels.voided(item.name, skip = false)
                                        VoidResult.NotActive -> "이미 취소된 기록이에요"
                                        VoidResult.Locked -> Labels.LOCKED
                                    }
                                    afterWrite()
                                }
                            }
                        },
                        // SCR-004 [확인했어요]: a failed save keeps the notice (screens.md SCR-004).
                        acknowledgeRestore = {
                            write(failure = "저장하지 못했어요. 다시 눌러 주세요") { c ->
                                c.acknowledgeRestore()
                                message = null
                                afterWrite()
                            }
                        },
                        keepClock = { warning ->
                            write { c ->
                                c.resolveClockFlag.keep(warning.judgment.id)
                                message = null
                                afterWrite()
                            }
                        },
                        keepAllClocks = { warnings ->
                            write { c ->
                                warnings.forEach { c.resolveClockFlag.keep(it.judgment.id) }
                                message = null
                                afterWrite()
                            }
                        },
                        cancelClock = { warning ->
                            write { c ->
                                message = when (c.resolveClockFlag.cancel(warning.judgment.id)) {
                                    ClockFlagResult.Resolved -> Labels.voided(warning.intakeName, skip = warning.judgment.kind == DoseKind.SKIPPED)
                                    ClockFlagResult.NotFlagged -> null
                                    ClockFlagResult.Locked -> Labels.LOCKED
                                }
                                afterWrite()
                            }
                        },
                        openAccess = ::openAccess,
                    ),
                )
                val c = core
                if (cardRequested && c != null) {
                    HomeCardPicker(
                        core = c,
                        onOpen = { personId ->
                            cardRequested = false
                            go(Route.HealthCard(personId, from = current))
                        },
                        onDismiss = { cardRequested = false },
                        onFailure = {
                            cardRequested = false
                            message = Labels.CARD_LOAD_FAILED
                        },
                    )
                }
            }

            is Route.HealthCard -> key(current.key) {
                var card by remember { mutableStateOf<HealthCardSummary?>(null) }
                var cardFailed by remember { mutableStateOf(false) }
                var attempts by remember { mutableIntStateOf(0) }
                LaunchedEffect(core, reads, attempts) {
                    val c = core ?: return@LaunchedEffect
                    try {
                        card = c.loadHealthCard(current.personId)
                        cardFailed = card == null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        cardFailed = true
                    }
                }
                HealthCardScreen(
                    card = card,
                    personName = people.find { it.id == current.personId }?.name,
                    failed = cardFailed || (core == null && failed),
                    busy = busy,
                    message = message,
                    actions = HealthCardActions(
                        onClose = { go(current.back) },
                        onRetry = {
                            cardFailed = false
                            if (core == null) { failed = false; retries++ } else attempts++
                        },
                        onShow = {
                            card?.takeIf { it.canShowOrExport() }?.let { summary ->
                                go(Route.CardShow(current))
                                cardShowSeed = summary
                            }
                        },
                        onEmergency = {
                            val summary = card
                            if (summary != null && summary.canOpenEmergencyText()) {
                                go(Route.Emergency(current))
                                emergencySeed = summary
                            }
                        },
                        onExport = {
                            if (cardExporter != null && card?.canShowOrExport() == true) go(Route.CardExport(current))
                        },
                        onDismissNotice = {
                            write(Labels.CARD_NOTICE_ACTION_FAILED) { c ->
                                c.healthCardNoticeActions.closeNotice(current.personId)
                                message = null
                                attempts++
                            }
                        },
                        onAdd = { kind ->
                            core?.let { go(Route.CardEditor(kind, it.saveCardEntry.newEntryId(), editing = false, back = current)) }
                        },
                        onEdit = { entry ->
                            go(Route.CardEditor(entry.fields.kind, entry.id, editing = true, back = current))
                            cardEntrySeed = entry
                        },
                        onItem = { intakeId -> go(Route.Item(intakeId, from = current)) },
                        onDelete = { entry ->
                            write(Labels.CARD_DELETE_FAILED) { c ->
                                if (entry == null) {
                                    c.deleteCardEntries.all(current.personId, confirmed = true)
                                } else {
                                    c.deleteCardEntries.one(current.personId, entry.fields.kind, entry.id, confirmed = true)
                                }
                                message = null
                                attempts++
                            }
                        },
                    ),
                )
            }

            is Route.CardExport -> key(current.key) {
                CardExportScreen(
                    core = core,
                    personId = current.back.personId,
                    exporter = cardExporter,
                    refreshKey = refreshKey,
                    openFailed = core == null && failed,
                    onRetryOpen = { failed = false; retries++ },
                    onClose = { go(current.back) },
                )
            }

            is Route.CardShow -> key(current.key) {
                var card by remember { mutableStateOf(cardShowSeed) }
                val openedAt = remember { refreshKey.takeIf { cardShowSeed != null } }
                LaunchedEffect(core, refreshKey, failed && core == null) {
                    val c = core
                    if (c == null && !failed) return@LaunchedEffect
                    if (refreshKey == openedAt) return@LaunchedEffect
                    val summary = try {
                        c?.loadHealthCard(current.back.personId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    // Left meanwhile (닫기, a notification or the widget): the result must not take the user back here.
                    if (route != current) return@LaunchedEffect
                    when {
                        summary == null -> {
                            go(current.back)
                            message = Labels.CARD_LOAD_FAILED
                        }
                        !summary.canShowOrExport() -> go(current.back)
                        else -> card = summary
                    }
                }
                if (card != null) keepCardShowAwake()
                CardShowScreen(
                    card = card,
                    personName = people.find { it.id == current.back.personId }?.name,
                    onClose = { go(current.back) },
                )
            }

            is Route.Emergency -> key(current.key) {
                EmergencyRoute(
                    core = core,
                    personId = current.back.personId,
                    personName = people.find { it.id == current.back.personId }?.name,
                    seed = emergencySeed,
                    refreshKey = refreshKey,
                    openFailed = core == null && failed,
                    phone = phone,
                    onRetryOpen = { failed = false; retries++ },
                    onClose = { go(current.back) },
                )
            }

            is Route.CardEditor -> key(current.key) {
                var entry by remember { mutableStateOf(cardEntrySeed?.takeIf { it.id == current.entryId }) }
                var entryFailed by remember { mutableStateOf(false) }
                var attempts by remember { mutableIntStateOf(0) }
                var invalid by remember { mutableStateOf<SaveCardEntryResult.Invalid?>(null) }
                fun gone() {
                    go(current.back)
                    message = Labels.CARD_ENTRY_GONE
                }
                LaunchedEffect(core, attempts) {
                    val c = core ?: return@LaunchedEffect
                    if (!current.editing || entry != null) return@LaunchedEffect
                    try {
                        entry = c.loadHealthCard(current.back.personId)?.entries?.find {
                            it.id == current.entryId && it.fields.kind == current.kind
                        }
                        if (entry == null) gone()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        entryFailed = true
                    }
                }
                val waiting = core == null || (current.editing && entry == null)
                if (waiting || entryFailed) {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(
                            cardEditorTitle(current.kind, current.editing),
                            navigation = { BarIconButton("닫기", true) { go(current.back) } },
                        )
                        Column(Modifier.padding(horizontal = Ui.Side, vertical = 8.dp)) {
                            if (entryFailed || (core == null && failed)) {
                                CardReadFailed {
                                    entryFailed = false
                                    if (core == null) { failed = false; retries++ } else attempts++
                                }
                            } else CardLoading(sections = 3)
                        }
                    }
                } else {
                    CardEntryScreen(
                        kind = current.kind,
                        initial = entry?.fields,
                        busy = busy,
                        message = message,
                        invalid = invalid,
                        onChange = { field -> if (invalid?.field == field) invalid = null },
                        onClose = { go(current.back) },
                        onSave = { fields ->
                            write(Labels.CARD_SAVE_FAILED) { c ->
                                val result = if (current.editing) {
                                    c.saveCardEntry.edit(current.back.personId, current.entryId, fields)
                                } else {
                                    c.saveCardEntry.add(current.back.personId, current.entryId, fields)
                                }
                                if (route == current) {
                                    message = null
                                    invalid = null
                                    when (result) {
                                        is SaveCardEntryResult.Saved -> go(current.back)
                                        is SaveCardEntryResult.Invalid -> invalid = result
                                        SaveCardEntryResult.LimitReached -> message = Labels.CARD_ENTRY_LIMIT
                                        SaveCardEntryResult.Gone -> gone()
                                    }
                                }
                            }
                        },
                    )
                }
            }

            is Route.Item -> key(current.key) {
                // SCR-006#permission (TASK-023): read on opening, on coming back from the system settings and after the
                // permission window. While that window is open nothing is said under the switch.
                LaunchedEffect(refreshKey) { access = phone.reminderAccess() }
                var asking by remember { mutableStateOf(false) }
                // SCR-006#quiet (REQ-077): what quiet hours take from the reminder just saved, said before going back.
                var quiet by rememberSaveable { mutableStateOf<String?>(null) }
                var medications by remember {
                    mutableStateOf<List<IntakeMedicationDraft>?>(if (current.intakeId == null) emptyList() else null)
                }
                var medicationReadFailed by remember { mutableStateOf(false) }
                var medicationAttempts by remember { mutableStateOf(0) }
                var checkedOpening by remember { mutableStateOf(current.from !is Route.HealthCard) }
                LaunchedEffect(core, medicationAttempts) {
                    val id = current.intakeId ?: return@LaunchedEffect
                    val c = core ?: return@LaunchedEffect
                    try {
                        if (!checkedOpening) {
                            reload()
                            checkedOpening = true
                        }
                        medications = c.saveIntake.loadMedications(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        medicationReadFailed = true
                    }
                }
                val loaded = board
                val editing = current.intakeId?.let { id -> loaded?.items?.firstOrNull { it.intakeId == id } }
                val savedMedications = medications
                when {
                    // First, so an opening that cannot read the database still offers [다시 시도] (PR #71 review).
                    loaded == null && failed -> HomeScreen(HomeState.Failed, null, zone, homeOnlyRetry { failed = false; retries++ })
                    !checkedOpening && !medicationReadFailed -> Loading()
                    loaded == null -> Loading()
                    checkedOpening && current.intakeId != null && editing == null -> LaunchedEffect(current) {
                        // SCR-006#gone: archived or deleted meanwhile.
                        go(current.back)
                        message = Labels.GONE
                    }
                    medicationReadFailed -> HomeScreen(
                        HomeState.Failed, null, zone,
                        homeOnlyRetry {
                            medicationReadFailed = false
                            medicationAttempts++
                        },
                    )
                    savedMedications == null -> Loading()
                    else -> ItemScreen(
                        editing = editing,
                        savedMedications = savedMedications,
                        people = people,
                        message = message,
                        access = access.takeUnless { asking },
                        reminders = ItemReminderActions(
                            pickTime = { initial, onPicked -> phone.pickTime(initial, onPicked) },
                            switchedOn = {
                                if (!asking) {
                                    asking = phone.askNotifications {
                                        asking = false
                                        access = phone.reminderAccess()
                                    }
                                }
                            },
                            openSettings = { openAccess(AccessSetting.NOTIFICATIONS) },
                        ),
                        onAddPerson = { name, color, onAdded ->
                            write(failure = "저장하지 못했어요. 다시 눌러 주세요") { c ->
                                val added = c.people.add(name, color)
                                onAdded(added.id)
                                afterWrite()
                            }
                        },
                        onClose = { go(current.back) },
                        onSave = saveItem@ { draft ->
                            // A queued activation from a form already left must not save it again.
                            if (route !== current || quiet != null) return@saveItem
                            write(failure = "저장하지 못했어요. 다시 눌러 주세요") { c ->
                                val id = current.intakeId
                                val result = if (id == null) c.saveIntake.create(draft) else c.saveIntake.update(id, draft)
                                when (result) {
                                    is SaveIntakeResult.Saved -> {
                                        val saved = result.quiet
                                        if (saved == null) {
                                            go(current.back)
                                        } else {
                                            message = null
                                            quiet = Labels.itemQuiet(saved.notice, saved.settings)
                                        }
                                        afterWrite()
                                    }
                                    is SaveIntakeResult.Invalid -> message = when (result.reason) {
                                        SaveIntakeResult.Invalid.BLANK_NAME -> Labels.NAME_REQUIRED
                                        SaveIntakeResult.Invalid.UNKNOWN_PERSON -> "누구 약인지 다시 골라 주세요"
                                        else -> Labels.SAVE_FAILED
                                    }
                                    SaveIntakeResult.Gone -> {
                                        go(current.back)
                                        message = Labels.GONE
                                        afterWrite()
                                    }
                                }
                            }
                        },
                        onArchive = { item ->
                            write(failure = "보관하지 못했어요. 다시 눌러 주세요") { c ->
                                c.archiveIntake(item.intakeId)
                                go(current.back)
                                message = "${Labels.withObject(item.name)} 보관했어요. 지난 기록은 남아요"
                                afterWrite()
                            }
                        },
                    )
                }
                quiet?.let { text ->
                    // Saved already: `[확인]`, back or a tap outside all return to the opener (SCR-006#quiet).
                    NoticeDialog(Labels.ITEM_SAVED, text) {
                        quiet = null
                        go(current.back)
                    }
                }
            }

            is Route.Judge -> key(current.key) {
                var cell by remember { mutableStateOf<JudgeCell?>(null) }
                var cellFailed by remember { mutableStateOf(false) }
                var attempts by remember { mutableIntStateOf(0) }
                // When this screen first read its cell. A screen opened on the current window closes when that window
                // ends (SCR-007#stale), even across a recreated activity.
                var openedAt by rememberSaveable { mutableStateOf<Long?>(null) }

                fun openedCurrent(shown: JudgeCell): Boolean =
                    openedAt?.let { Instant.fromEpochMilliseconds(it) in shown.window } == true

                LaunchedEffect(core, reads, attempts) {
                    val c = core ?: return@LaunchedEffect
                    val result = try {
                        c.loadJudgeCell(current.intakeId, current.versionId, current.periodKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        cellFailed = true
                        return@LaunchedEffect
                    }
                    cellFailed = false
                    when (result) {
                        JudgeLoad.Gone -> {
                            go(current.back)
                            message = Labels.GONE
                        }
                        // A schedule change gave the window to another version (P-8): nothing left to fix here.
                        JudgeLoad.Missing -> go(current.back)
                        is JudgeLoad.Ready -> {
                            val read = result.cell
                            if (openedAt == null) openedAt = read.now.toEpochMilliseconds()
                            if (openedCurrent(read) && !read.current) {
                                go(current.back)
                                message = Labels.DAY_CHANGED
                            } else {
                                cell = read
                            }
                        }
                    }
                }

                /** Applies what a write led to (screens.md SCR-007 tables). */
                suspend fun settle(after: JudgeAfter, shown: JudgeCell, c: DidITakeCore) {
                    when (after) {
                        is JudgeAfter.Stay -> {
                            message = after.message
                            if (after.wrote) afterWrite() else reloadOrFail()
                        }
                        is JudgeAfter.Move -> {
                            message = after.message
                            route = Route.Judge(shown.intakeId, after.versionId, after.periodKey, from = current.from)
                            afterWrite()
                        }
                        is JudgeAfter.Home -> {
                            go(current.back)
                            message = after.message
                            afterWrite()
                        }
                        JudgeAfter.Stale -> {
                            if (openedCurrent(shown) && c.clock.now() >= shown.window.end) {
                                go(current.back)
                                message = Labels.STALE
                            } else {
                                message = Labels.RELOADED
                            }
                            reloadOrFail()
                        }
                    }
                }

                /** One write from this screen; a screen whose window ended meanwhile writes nothing (SCR-007#stale). */
                fun judge(block: suspend (DidITakeCore, JudgeCell) -> JudgeAfter) {
                    val shown = cell ?: return
                    write { c ->
                        val after = if (openedCurrent(shown) && c.clock.now() >= shown.window.end) JudgeAfter.Stale else block(c, shown)
                        settle(after, shown, c)
                    }
                }

                val shown = cell
                JudgeScreen(
                    state = when {
                        shown != null -> JudgeState.Ready(shown)
                        cellFailed || failed -> JudgeState.Failed
                        else -> JudgeState.Loading
                    },
                    message = message,
                    zone = zone,
                    clock = { (core?.clock ?: Clock.System).now() },
                    actions = JudgeActions(
                        back = { go(current.back) },
                        retry = {
                            failed = false
                            retries++
                            attempts++
                        },
                        recordNow = {
                            judge { c, x -> JudgeOutcomes.of(c.record(x.intakeId, DoseSource.APP, x.window.periodKey), c.clock.now(), zone) }
                        },
                        recordAt = { at ->
                            judge { c, x -> JudgeOutcomes.of(c.recordIn(x, DoseKind.TAKEN, takenAt = at), x, edit = false, c.clock.now(), zone) }
                        },
                        skip = {
                            judge { c, x -> JudgeOutcomes.of(c.recordIn(x, DoseKind.SKIPPED), x, edit = false, c.clock.now(), zone) }
                        },
                        editTime = { at ->
                            judge { c, x ->
                                val dose = x.judgment ?: return@judge JudgeAfter.Stale
                                JudgeOutcomes.of(c.editTime(x, dose.id, at), x, edit = true, c.clock.now(), zone)
                            }
                        },
                        takeInstead = { at ->
                            judge { c, x ->
                                val skip = x.judgment ?: return@judge JudgeAfter.Stale
                                JudgeOutcomes.of(c.recordIn(x, DoseKind.TAKEN, takenAt = at, replacing = skip.id), x, edit = false, c.clock.now(), zone)
                            }
                        },
                        void = {
                            judge { c, x ->
                                val judgment = x.judgment ?: return@judge JudgeAfter.Stale
                                JudgeOutcomes.of(c.voidDose(judgment.id), x)
                            }
                        },
                        keepClock = {
                            judge { c, x ->
                                val judgment = x.judgment ?: return@judge JudgeAfter.Stale
                                JudgeOutcomes.ofKeep(c.resolveClockFlag.keep(judgment.id))
                            }
                        },
                        cancelClock = {
                            judge { c, x ->
                                val judgment = x.judgment ?: return@judge JudgeAfter.Stale
                                JudgeOutcomes.ofCancel(c.resolveClockFlag.cancel(judgment.id), x)
                            }
                        },
                    ),
                )
            }

            is Route.History -> {
                // The chosen person while the picker is shown, else everyone (REQ-018, REQ-040).
                val chosen = current.personId?.takeIf { id -> showsPeople(people.size) && people.any { it.id == id } }
                LaunchedEffect(core, reads, historyAttempts, current) {
                    val c = core ?: return@LaunchedEffect
                    try {
                        history = current to c.loadHistory(current.month, chosen)
                        historyFailed = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        historyFailed = true
                    }
                }
                val shown = history?.takeIf { it.first == current }?.second
                HistoryScreen(
                    state = when {
                        shown != null -> HistoryState.Ready(shown)
                        historyFailed || (failed && core == null) -> HistoryState.Failed
                        else -> HistoryState.Loading
                    },
                    month = current.month,
                    personId = chosen,
                    people = people,
                    message = message,
                    zone = zone,
                    list = historyList,
                    actions = HistoryActions(
                        back = { go(Route.Home) },
                        retry = {
                            failed = false
                            historyFailed = false
                            retries++
                            historyAttempts++
                        },
                        month = { openHistory(current.copy(month = it)) },
                        person = { openHistory(current.copy(personId = it)) },
                        open = { cell ->
                            if (cell.archived) {
                                // SCR-007#gone at once: an archived item's past judgments are not changed (REQ-014).
                                message = Labels.GONE
                            } else {
                                go(Route.Judge(cell.intakeId, cell.scheduleVersionId, cell.window.periodKey, from = current))
                            }
                        },
                    ),
                )
            }

            Route.Settings -> {
                // Read on opening, after every write and on coming back to the app (SCR-009#stale): the screen lock may
                // have been set meanwhile in the system settings.
                LaunchedEffect(core, reads, settingsAttempts) {
                    val c = core ?: return@LaunchedEffect
                    phoneState = PhoneState(phone.appVersion, phone.backupUnsupported, phone.screenLock(), phone.reminderAccess())
                    try {
                        settings = c.loadSettings()
                        settingsFailed = false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        settingsFailed = true
                    }
                }
                val shown = settings
                SettingsScreen(
                    state = when {
                        shown != null -> SettingsState.Ready(shown)
                        settingsFailed || (failed && core == null) -> SettingsState.Failed
                        else -> SettingsState.Loading
                    },
                    phone = phoneState ?: PhoneState(phone.appVersion, phone.backupUnsupported, screenLock = null),
                    privacyUrl = privacyPolicyUrl,
                    message = message,
                    now = { (core?.clock ?: Clock.System).now() },
                    actions = SettingsActions(
                        back = { go(Route.Home) },
                        retry = {
                            failed = false
                            settingsFailed = false
                            retries++
                            settingsAttempts++
                        },
                        pickDayStart = { initial, onPicked -> phone.pickTime(initial, onPicked) },
                        saveWindow = { new ->
                            write(failure = Labels.SAVE_FAILED) { c ->
                                when (c.settings.saveWindowSettings(new)) {
                                    SaveSettingsResult.Changed -> {
                                        // Shown at once; the read after the write follows.
                                        settings = settings?.copy(window = new)
                                        message = null
                                        try {
                                            phone.windowSettingsChanged()
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            // The boundary refresh is set again on the next start; the setting is saved.
                                        }
                                        afterWrite()
                                    }
                                    SaveSettingsResult.Unchanged -> message = null
                                    SaveSettingsResult.Invalid -> message = Labels.SAVE_FAILED
                                }
                            }
                        },
                        saveReminders = { new ->
                            write(failure = Labels.SAVE_FAILED) { c ->
                                when (val result = c.reminders.saveSettings(new)) {
                                    is SaveReminderSettingsResult.Saved -> {
                                        settings = settings?.copy(reminders = new)
                                        // REQ-077: how many repeats quiet hours drop, over the items with reminders on.
                                        message = if (result.notices.isEmpty()) null else Labels.quietSaved(result.dropped, result.notices.size)
                                        afterWrite()
                                    }
                                    SaveReminderSettingsResult.Invalid -> message = Labels.SAVE_FAILED
                                }
                            }
                        },
                        showNamesOnLock = { on ->
                            write(failure = Labels.SAVE_FAILED) { c ->
                                if (c.settings.setShowNamesOnLock(on) == SaveSettingsResult.Changed) {
                                    settings = settings?.copy(showNamesOnLock = on)
                                    message = if (on) Labels.SHOW_NAMES_ON else null
                                    afterWrite()
                                }
                            }
                        },
                        people = { openPeople() },
                        delete = { item ->
                            write(failure = Labels.DELETE_FAILED) { c ->
                                message = when (c.deleteArchivedIntake(item.intakeId)) {
                                    DeleteIntakeResult.Deleted -> Labels.deleted(item.name)
                                    DeleteIntakeResult.Gone -> Labels.DELETE_GONE
                                    DeleteIntakeResult.NotArchived -> null
                                }
                                settings = settings?.let { view -> view.copy(archived = view.archived.filterNot { it.intakeId == item.intakeId }) }
                                afterWrite()
                            }
                        },
                        openPrivacy = { url -> message = if (phone.openUrl(url)) null else Labels.noBrowser(url) },
                        openFeedbackMail = phone::openFeedbackMail,
                        copyFeedbackAddress = phone::copyFeedbackAddress,
                        openAccess = ::openAccess,
                    ),
                )
            }

            Route.People -> {
                // Read on opening; a write here reads them again itself. Nothing else changes people (no widget does).
                LaunchedEffect(core, peopleAttempts) { core?.let { readPeople(it) } }
                val shown = peopleView
                PeopleScreen(
                    state = when {
                        shown != null -> PeopleState.Ready(shown)
                        peopleFailed || (failed && core == null) -> PeopleState.Failed
                        else -> PeopleState.Loading
                    },
                    message = message,
                    actions = PeopleActions(
                        back = { go(Route.Settings) },
                        retry = {
                            failed = false
                            peopleFailed = false
                            retries++
                            peopleAttempts++
                        },
                        add = { go(Route.Person(null)) },
                        edit = { go(Route.Person(it.person.id)) },
                        openArchivedCard = { if (!busy) go(Route.HealthCard(it.person.id, from = current)) },
                        restore = { row ->
                            write(failure = Labels.RESTORE_FAILED) { c ->
                                val restored = c.people.restore(row.person.id) == PersonChange.Saved
                                // The widgets and reminders first; the list shows the result once the write is over.
                                afterWrite()
                                readPeople(c)
                                // Restored already (another tap): the list is only read again (screens.md SCR-010).
                                if (restored) message = Labels.personRestored(row.person.name)
                            }
                        },
                    ),
                )
            }

            is Route.Person -> personFormState.SaveableStateProvider(current.key) {
                LaunchedEffect(core, peopleAttempts) { core?.let { readPeople(it) } }
                val view = peopleView
                val editing = current.personId?.let { id -> view?.active?.firstOrNull { it.person.id == id } }
                when {
                    view == null && (peopleFailed || (failed && core == null)) -> PeopleScreen(
                        PeopleState.Failed,
                        message,
                        PeopleActions(
                            back = { go(Route.People) },
                            retry = {
                                failed = false
                                peopleFailed = false
                                retries++
                                peopleAttempts++
                            },
                            add = {},
                            edit = {},
                            openArchivedCard = {},
                            restore = {},
                        ),
                    )
                    view == null -> Loading()
                    current.personId != null && editing == null -> LaunchedEffect(current) {
                        // Archived meanwhile, or an old saved screen.
                        go(Route.People)
                        message = Labels.PERSON_GONE
                    }
                    else -> PersonFormScreen(
                        editing = editing,
                        view = view,
                        message = message,
                        onClose = { go(Route.People) },
                        onOpenCard = { if (!busy) go(Route.HealthCard(it.person.id, from = current)) },
                        // The form stays until the write is over (widgets and reminders included), then the list shows it.
                        onSave = { name, color ->
                            write(failure = Labels.SAVE_FAILED) { c ->
                                val id = current.personId
                                val gone = if (id == null) {
                                    c.people.add(name, color)
                                    false
                                } else {
                                    c.people.update(id, name, color) != PersonChange.Saved
                                }
                                afterWrite()
                                readPeople(c)
                                go(Route.People)
                                if (gone) message = Labels.PERSON_GONE
                            }
                        },
                        onArchive = { row ->
                            write(failure = Labels.ARCHIVE_FAILED) { c ->
                                val result = c.people.archive(row.person.id)
                                afterWrite()
                                readPeople(c)
                                go(Route.People)
                                message = when (result) {
                                    PersonChange.Saved -> Labels.personArchived(row.person.name)
                                    PersonChange.LastPerson -> Labels.LAST_PERSON
                                    PersonChange.Gone -> Labels.PERSON_GONE
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Where SCR-008's list was (first line and offset), kept when the activity is recreated (BUG-003). */
private val ListPlace: Saver<LazyListState, List<Int>> = Saver(
    save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
    restore = { LazyListState(it[0], it[1]) },
)

/** Actions for SCR-004#error shown on another screen: only `[다시 시도]` does something. */
private fun homeOnlyRetry(retry: () -> Unit) = HomeActions(
    retry = retry,
    history = {},
    settings = {},
    add = {},
    edit = {},
    more = {},
    record = {},
    void = {},
    acknowledgeRestore = {},
    keepClock = {},
    keepAllClocks = {},
    cancelClock = {},
    openAccess = {},
)

@Composable
private fun Loading() {
    Words(Labels.LOADING, Ui.type(16, color = Ui.Muted), Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
}
