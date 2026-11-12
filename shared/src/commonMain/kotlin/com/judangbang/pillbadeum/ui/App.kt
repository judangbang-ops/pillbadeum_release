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
import com.judangbang.pillbadeum.status.SettingsView
import com.judangbang.pillbadeum.status.StatusBoard
import com.judangbang.pillbadeum.status.showsPeople
import com.judangbang.pillbadeum.usecase.ClockFlagResult
import com.judangbang.pillbadeum.usecase.DeleteIntakeResult
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import com.judangbang.pillbadeum.usecase.SaveReminderSettingsResult
import com.judangbang.pillbadeum.usecase.SaveSettingsResult
import com.judangbang.pillbadeum.usecase.VoidResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The app's screens, one at a time (07 sitemap). [key] is what is saved when the screen is recreated (BUG-003).
 * [back] is where system back goes (REQ-094, SPEC-021): every screen to the first, the people screen to settings,
 * SCR-007 opened from SCR-008 back to that month (TASK-019), and null on the first screen, where back closes the app.
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
    data class Item(val intakeId: String?) : Route {
        override val key get() = "item:${intakeId ?: ""}"
        override val back: Route get() = Home
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

    companion object {
        fun parse(key: String): Route {
            if ('@' in key) {
                val from = parse(key.substringAfter('@')) as? History
                return when (val route = parse(key.substringBefore('@'))) {
                    is Judge -> route.copy(from = from)
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
 * @param phone what the settings screen reads from and asks of the phone (SCR-009, TASK-020).
 * @param privacyPolicyUrl the privacy policy's address, null until TASK-035 decides it (REQ-063).
 * @param refreshKey bump to read the database again (on resume: the widget may have recorded meanwhile).
 * @param homeRequests bump to show the first screen (a notification or the widget opened the app, R3 memo).
 * @param backHandler the platform's system back: called with whether back stays in the app, and what it does.
 */
@Composable
fun App(
    open: suspend () -> DidITakeCore,
    onDataChanged: suspend () -> Unit,
    phone: Phone,
    privacyPolicyUrl: String? = PRIVACY_POLICY_URL,
    refreshKey: Int = 0,
    homeRequests: Int = 0,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    var core by remember { mutableStateOf<DidITakeCore?>(null) }
    var board by remember { mutableStateOf<StatusBoard?>(null) }
    var people by remember { mutableStateOf<List<PersonEntity>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var retries by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var route by rememberSaveable(stateSaver = Route.Saver) { mutableStateOf<Route>(Route.Home) }
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
    val zone = TimeZone.currentSystemDefault()

    suspend fun reload() {
        val c = core ?: open().also { core = it }
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

    /** Runs one write at a time: buttons do nothing while one is running (REQ-001). */
    fun write(failure: String = Labels.TRY_AGAIN, block: suspend (DidITakeCore) -> Unit) {
        val c = core ?: return
        if (busy) return
        busy = true
        scope.launch {
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

    fun go(to: Route) {
        message = null
        route = to
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

    LaunchedEffect(refreshKey, retries) {
        message = null
        reloadOrFail()
    }
    LaunchedEffect(homeRequests) { if (homeRequests > 0) go(Route.Home) }
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

    Box(Modifier.fillMaxSize().background(Ui.Surface).safeDrawingPadding().imePadding()) {
        when (val current = route) {
            Route.Home -> HomeScreen(
                state = when {
                    failed -> HomeState.Failed
                    else -> board?.let { HomeState.Ready(it) } ?: HomeState.Loading
                },
                message = message,
                zone = zone,
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
                    add = { go(Route.Item(null)) },
                    edit = { go(Route.Item(it.intakeId)) },
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
                ),
            )

            is Route.Item -> key(current.key) {
                val loaded = board
                val editing = current.intakeId?.let { id -> loaded?.items?.firstOrNull { it.intakeId == id } }
                when {
                    loaded == null && failed -> HomeScreen(HomeState.Failed, null, zone, homeOnlyRetry { failed = false; retries++ })
                    loaded == null -> Loading()
                    current.intakeId != null && editing == null -> LaunchedEffect(current) {
                        // SCR-006#gone: archived or deleted meanwhile.
                        go(Route.Home)
                        message = Labels.GONE
                    }
                    else -> ItemScreen(
                        editing = editing,
                        people = people,
                        message = message,
                        onAddPerson = { name, color, onAdded ->
                            write(failure = "저장하지 못했어요. 다시 눌러 주세요") { c ->
                                val added = c.people.add(name, color)
                                onAdded(added.id)
                                afterWrite()
                            }
                        },
                        onClose = { go(Route.Home) },
                        onSave = { draft ->
                            write(failure = "저장하지 못했어요. 다시 눌러 주세요") { c ->
                                val id = current.intakeId
                                val result = if (id == null) c.saveIntake.create(draft) else c.saveIntake.update(id, draft)
                                when (result) {
                                    is SaveIntakeResult.Saved -> {
                                        go(Route.Home)
                                        afterWrite()
                                    }
                                    is SaveIntakeResult.Invalid -> message = when (result.reason) {
                                        SaveIntakeResult.Invalid.BLANK_NAME -> Labels.NAME_REQUIRED
                                        else -> "누구 약인지 다시 골라 주세요"
                                    }
                                    SaveIntakeResult.Gone -> {
                                        go(Route.Home)
                                        message = Labels.GONE
                                        afterWrite()
                                    }
                                }
                            }
                        },
                        onArchive = { item ->
                            write(failure = "보관하지 못했어요. 다시 눌러 주세요") { c ->
                                c.archiveIntake(item.intakeId)
                                go(Route.Home)
                                message = "${Labels.withObject(item.name)} 보관했어요. 지난 기록은 남아요"
                                afterWrite()
                            }
                        },
                    )
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
                    phoneState = PhoneState(phone.appVersion, phone.backupUnsupported, phone.screenLock())
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
                        people = { go(Route.People) },
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
                    ),
                )
            }

            // Filled by TASK-021 (SCR-010).
            Route.People -> NotYet("대상자") { go(Route.Settings) }
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
)

@Composable
private fun Loading() {
    Words(Labels.LOADING, Ui.type(16, color = Ui.Muted), Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
}

/** A screen that a later task builds; back and its button return. */
@Composable
private fun NotYet(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, navigation = { BarIconButton("뒤로", close = false, onBack) })
        Words("준비 중인 화면이에요.", Ui.type(16, color = Ui.Muted), Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    }
}
