package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.StatusBoard
import com.judangbang.pillbadeum.usecase.ClockFlagResult
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import com.judangbang.pillbadeum.usecase.VoidResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/**
 * The app's screens, one at a time (07 sitemap). [key] is what is saved when the screen is recreated (BUG-003).
 * [back] is where system back goes (REQ-094, SPEC-021): every screen to the first, the people screen to settings,
 * and null on the first screen, where back closes the app.
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

    /** SCR-007 for one window of an item (TASK-018). */
    data class Judge(val intakeId: String, val periodKey: String) : Route {
        override val key get() = "judge:$intakeId:$periodKey"
        override val back: Route get() = Home
    }

    /** SCR-008 (TASK-019) */
    data object History : Route {
        override val key = "history"
        override val back: Route = Home
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
            val parts = key.split(':')
            return when (parts[0]) {
                "item" -> Item(parts.getOrNull(1)?.takeIf { it.isNotEmpty() })
                "judge" -> if (parts.size == 3) Judge(parts[1], parts[2]) else Home
                "history" -> History
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
 * @param onDataChanged called after every write (record, cancel, item, person, restore notice) so the platform can
 *   redraw its widgets and plan its reminders again.
 * @param refreshKey bump to read the database again (on resume: the widget may have recorded meanwhile).
 * @param homeRequests bump to show the first screen (a notification or the widget opened the app, R3 memo).
 * @param backHandler the platform's system back: called with whether back stays in the app, and what it does.
 */
@Composable
fun App(
    open: suspend () -> DidITakeCore,
    onDataChanged: suspend () -> Unit,
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
    val zone = TimeZone.currentSystemDefault()

    suspend fun reload() {
        val c = core ?: open().also { core = it }
        people = c.people.list()
        board = c.loadStatusBoard()
        failed = false
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
                    history = { go(Route.History) },
                    settings = { go(Route.Settings) },
                    add = { go(Route.Item(null)) },
                    edit = { go(Route.Item(it.intakeId)) },
                    more = { go(Route.Judge(it.intakeId, it.window.periodKey)) },
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
                                    VoidResult.Voided -> "${item.name} 기록을 취소했어요. 기록은 무효로 남아요"
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
                                ClockFlagResult.Resolved -> "${warning.intakeName} 기록을 취소했어요. 기록은 무효로 남아요"
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
                        message = "이 항목은 보관됐거나 지워졌어요"
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
                                        message = "이 항목은 보관됐거나 지워졌어요"
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

            // Filled by TASK-018 (SCR-007), TASK-019 (SCR-008), TASK-020 (SCR-009) and TASK-021 (SCR-010).
            is Route.Judge -> NotYet("오늘 기록") { go(Route.Home) }
            Route.History -> NotYet("지난 기록") { go(Route.Home) }
            Route.Settings -> NotYet("설정") { go(Route.Home) }
            Route.People -> NotYet("대상자") { go(Route.Settings) }
        }
    }
}

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
