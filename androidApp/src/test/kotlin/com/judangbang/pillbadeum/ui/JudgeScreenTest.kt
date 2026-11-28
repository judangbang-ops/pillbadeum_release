package com.judangbang.pillbadeum.ui

import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.AndroidPhone
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.VoidReasons
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.TimeZone as JavaTimeZone

/**
 * SCR-007 판정 고치기 (TASK-018) in the real app, the way [AppScreenTest] runs it: opened from a tile's `[⋯]` on Thursday
 * 11/12 at 09:30 in Seoul, every judgment's buttons, the picker, the results and the rows they leave (REQ-006, REQ-029~034).
 * Past windows are reached here by a time edit that moves the dose to yesterday, which the screen follows; opening one
 * from SCR-008 is in [HistoryScreenTest]. Each case is saved as `build/app-render/judge-*.png` with its semantics tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JudgeScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val core: DidITakeCore get() = runBlocking { app.container.core() }
    private lateinit var savedZone: JavaTimeZone
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        savedZone = JavaTimeZone.getDefault()
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("Asia/Seoul"))
    }

    @After
    fun tearDown() {
        JavaTimeZone.setDefault(savedZone)
        RuntimeEnvironment.setFontScale(1f)
    }

    /** Background state changes still render an Android dialog on the UI thread. */
    @Test
    fun background_state_change_renders_dialog_on_main_thread() {
        val showDialog = mutableStateOf(false)
        val dialogShown = AtomicBoolean(false)
        val initialApplied = AtomicBoolean(false)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.setContent {
            if (!showDialog.value) SideEffect { initialApplied.set(true) }
            if (showDialog.value) {
                SideEffect {
                    assertEquals("dialog composition must run on the Android main looper", Looper.getMainLooper(), Looper.myLooper())
                    dialogShown.set(true)
                }
                Dialog(onDismissRequest = {}) {}
            }
        }
        compose.waitUntil(10_000) { initialApplied.get() }
        runBlocking {
            withContext(Dispatchers.Default) {
                showDialog.value = true
                Snapshot.sendApplyNotifications()
            }
        }
        compose.waitUntil(10_000) { dialogShown.get() }
    }

    /** A completed write keeps judge actions disabled until the remaining update work completes. */
    @Test
    fun judge_actions_are_disabled_while_write_updates_are_pending() {
        app.seedWeek(family = true)
        val updateStarted = CompletableDeferred<Unit>()
        val finishUpdate = CompletableDeferred<Unit>()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        activity.setContent {
            App(
                open = { app.container.core() },
                onDataChanged = {
                    updateStarted.complete(Unit)
                    finishUpdate.await()
                    app.container.afterWrite()
                },
                phone = AndroidPhone(activity),
            )
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("혈압약 판정 고치기").performClick()
        waitFor("오늘 기록")
        click("지금 먹었어")
        compose.waitUntil(10_000) { updateStarted.isCompleted }
        compose.onNodeWithText("지금 먹었어").assertIsNotEnabled()
        compose.onNodeWithText("먹은 시각 고르기").assertIsNotEnabled()
        compose.onNodeWithText("건너뜀").assertIsNotEnabled()
        finishUpdate.complete(Unit)
        waitFor("09:30에 먹음")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("기록 취소") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("기록 취소").assertIsEnabled()
    }

    /** A saved judgment remains unavailable until its own refresh completes, and recovers after a read error. */
    @Test
    fun stale_judge_actions_stay_disabled_during_reload_and_error_can_retry() {
        app.seedWeek(family = true)
        val shouldHold = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failOnce = AtomicBoolean(true)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        activity.setContent {
            App(
                open = { app.container.core() },
                onDataChanged = { shouldHold.set(true) },
                phone = AndroidPhone(activity),
                loadJudge = { c, intakeId, versionId, periodKey ->
                    if (shouldHold.get()) {
                        started.complete(Unit)
                        release.await()
                        if (failOnce.getAndSet(false)) throw IllegalStateException("test read failure")
                    }
                    c.loadJudgeCell(intakeId, versionId, periodKey)
                },
            )
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("혈압약 판정 고치기").performClick()
        waitFor("오늘 기록")
        click("지금 먹었어")
        compose.waitUntil(10_000) { started.isCompleted }
        compose.onNodeWithText("지금 먹었어").assertIsNotEnabled()
        release.complete(Unit)
        waitFor("다시 시도")
        assertEquals(0, compose.onAllNodesWithText("지금 먹었어").fetchSemanticsNodes().size)
        compose.onNodeWithText("다시 시도").performClick()
        waitFor("09:30에 먹음")
        compose.onNodeWithText("기록 취소").assertIsEnabled()
    }

    /** A saved record refreshes its judgment even when the unrelated board refresh fails. */
    @Test
    fun board_reload_failure_after_save_refreshes_judgment_before_enabling_actions() {
        val seed = app.seedWeek(family = true)
        val failNextBoard = AtomicBoolean(false)
        val boardFailed = CompletableDeferred<Unit>()
        val judgeStarted = CompletableDeferred<Unit>()
        val releaseJudge = CompletableDeferred<Unit>()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        activity.setContent {
            App(
                open = { app.container.core() },
                onDataChanged = { failNextBoard.set(true) },
                phone = AndroidPhone(activity),
                loadBoard = { c ->
                    if (failNextBoard.getAndSet(false)) {
                        boardFailed.complete(Unit)
                        throw IllegalStateException("test board read failure")
                    }
                    c.loadStatusBoard()
                },
                loadJudge = { c, intakeId, versionId, periodKey ->
                    if (boardFailed.isCompleted && !releaseJudge.isCompleted) {
                        judgeStarted.complete(Unit)
                        releaseJudge.await()
                    }
                    c.loadJudgeCell(intakeId, versionId, periodKey)
                },
            )
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("혈압약 판정 고치기").performClick()
        waitFor("오늘 기록")
        click("지금 먹었어")
        compose.waitUntil(10_000) { boardFailed.isCompleted }
        compose.waitUntil(10_000) { judgeStarted.isCompleted }
        compose.onNodeWithText("지금 먹었어").assertIsNotEnabled()
        releaseJudge.complete(Unit)
        waitFor("09:30에 먹음")
        compose.onNodeWithText("기록 취소").assertIsEnabled()
        assertEquals(1, rows(seed.pressure!!).count { it.isActive && it.periodKey == "2026-11-12" })
    }

    /** A history cell remains editable when only the separate home board reload fails. */
    @Test
    fun history_judgment_can_open_when_only_board_refresh_fails() {
        app.seedWeek(family = true)
        val failNextBoard = AtomicBoolean(false)
        val boardFailed = CompletableDeferred<Unit>()
        val refresh = mutableIntStateOf(0)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        activity.setContent {
            App(
                open = { app.container.core() },
                onDataChanged = {},
                phone = AndroidPhone(activity),
                refreshKey = refresh.intValue,
                loadBoard = { c ->
                    if (failNextBoard.getAndSet(false)) {
                        boardFailed.complete(Unit)
                        throw IllegalStateException("test board read failure")
                    }
                    c.loadStatusBoard()
                },
            )
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("지난 기록").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("지난 기록").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("나 아침약, 08:02 먹음").fetchSemanticsNodes().isNotEmpty()
        }
        failNextBoard.set(true)
        compose.runOnIdle { refresh.intValue++; Snapshot.sendApplyNotifications() }
        compose.waitUntil(10_000) { boardFailed.isCompleted }
        compose.onNodeWithContentDescription("나 아침약, 08:02 먹음").performClick()
        waitFor("08:02에 먹음")
        compose.onNodeWithText("기록 취소").assertIsEnabled()
    }

    @Test
    fun review_resume_does_not_enable_old_judgment_during_delayed_board_read() {
        val seed = app.seedWeek(family = true)
        val holdBoard = AtomicBoolean(false)
        val boardStarted = CompletableDeferred<Unit>()
        val releaseBoard = CompletableDeferred<Unit>()
        val refresh = mutableIntStateOf(0)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        activity.setContent {
            App(
                open = { app.container.core() },
                onDataChanged = {},
                phone = AndroidPhone(activity),
                refreshKey = refresh.intValue,
                loadBoard = { c ->
                    if (holdBoard.get()) {
                        boardStarted.complete(Unit)
                        releaseBoard.await()
                    }
                    c.loadStatusBoard()
                },
            )
        }
        waitFor("지난 기록")
        compose.onNodeWithContentDescription("혈압약 판정 고치기").performClick()
        waitFor("오늘 기록")
        compose.onNodeWithText("지금 먹었어").assertIsEnabled()
        runBlocking { assertTrue(core.record(seed.pressure!!, DoseSource.WIDGET, "2026-11-12") is RecordResult.Recorded) }
        holdBoard.set(true)
        compose.runOnIdle { refresh.intValue++; Snapshot.sendApplyNotifications() }
        try {
            compose.waitUntil(10_000) { boardStarted.isCompleted }
            assertEquals(
                "old no-dose action must not be usable after refresh begins",
                0,
                compose.onAllNodes(hasText("지금 먹었어") and isEnabled()).fetchSemanticsNodes().size,
            )
        } finally {
            releaseBoard.complete(Unit)
        }
    }

    @Test
    fun review_retry_does_not_lock_fresh_judgment_again_when_board_fails_later() {
        app.seedWeek(family = true)
        val failFirstJudge = AtomicBoolean(true)
        val holdBoard = AtomicBoolean(false)
        val boardStarted = CompletableDeferred<Unit>()
        val releaseBoard = CompletableDeferred<Unit>()
        val boardFailed = CompletableDeferred<Unit>()
        val judgeRepeated = CompletableDeferred<Unit>()
        val releaseJudge = CompletableDeferred<Unit>()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        activity.setContent {
            App(
                open = { app.container.core() },
                onDataChanged = {},
                phone = AndroidPhone(activity),
                loadBoard = { c ->
                    if (holdBoard.get()) {
                        boardStarted.complete(Unit)
                        releaseBoard.await()
                        boardFailed.complete(Unit)
                        throw IllegalStateException("test unrelated board read failure")
                    }
                    c.loadStatusBoard()
                },
                loadJudge = { c, intakeId, versionId, periodKey ->
                    if (failFirstJudge.getAndSet(false)) throw IllegalStateException("test initial cell read failure")
                    if (releaseBoard.isCompleted) {
                        judgeRepeated.complete(Unit)
                        releaseJudge.await()
                    }
                    c.loadJudgeCell(intakeId, versionId, periodKey)
                },
            )
        }
        waitFor("지난 기록")
        compose.onNodeWithContentDescription("아침약 판정 고치기").performClick()
        waitFor("다시 시도")
        holdBoard.set(true)
        compose.onNodeWithText("다시 시도").performClick()
        try {
            compose.waitUntil(10_000) { boardStarted.isCompleted }
            waitFor("08:02에 먹음")
            compose.onNodeWithText("기록 취소").assertIsEnabled()
            releaseBoard.complete(Unit)
            compose.waitUntil(10_000) { boardFailed.isCompleted }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(100)
            compose.waitForIdle()
            assertFalse("board completion must not request another cell read", judgeRepeated.isCompleted)
            compose.onNodeWithText("기록 취소").assertIsEnabled()
        } finally {
            releaseBoard.complete(Unit)
            releaseJudge.complete(Unit)
        }
    }

    /** A delayed successful cell read replaces the stale judgment before enabling its actions. */
    @Test
    fun successful_delayed_judge_reload_enables_only_the_updated_actions() {
        val seed = app.seedWeek(family = true)
        val hold = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        activity.setContent {
            App(
                open = { app.container.core() },
                onDataChanged = { hold.set(true) },
                phone = AndroidPhone(activity),
                loadJudge = { c, intakeId, versionId, periodKey ->
                    if (hold.get()) { started.complete(Unit); release.await() }
                    c.loadJudgeCell(intakeId, versionId, periodKey)
                },
            )
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("혈압약 판정 고치기").performClick()
        waitFor("오늘 기록")
        click("지금 먹었어")
        compose.waitUntil(10_000) { started.isCompleted }
        compose.onNodeWithText("지금 먹었어").assertIsNotEnabled()
        release.complete(Unit)
        waitFor("09:30에 먹음")
        compose.onNodeWithText("기록 취소").assertIsEnabled()
        assertEquals(1, rows(seed.pressure!!).count { it.isActive && it.periodKey == "2026-11-12" })
    }

    /** Once a chosen time is submitted, the picker keeps that choice until the write finishes. */
    @Test
    fun picker_cannot_change_while_the_write_is_pending() {
        app.seedWeek(family = true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        activity.setContent {
            App(
                open = { app.container.core() },
                onDataChanged = { started.complete(Unit); release.await() },
                phone = AndroidPhone(activity),
            )
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("혈압약 판정 고치기").performClick()
        waitFor("오늘 기록")
        click("먹은 시각 고르기")
        pick("8시")
        pick("40분")
        click("이 시각으로 기록")
        compose.waitUntil(10_000) { started.isCompleted }
        cell("8시").assertIsNotEnabled()
        cell("45분").assertIsNotEnabled()
        compose.onNodeWithText("이 시각으로 기록").assertIsNotEnabled()
        release.complete(Unit)
        waitFor("08:40에 먹음")
    }

    /** A time edit that offers two dates keeps its date choice fixed while saving. */
    @Test
    fun picker_date_cannot_change_while_the_write_is_pending() {
        app.seedWeek(family = false)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        activity.setContent {
            App(
                open = { app.container.core() },
                onDataChanged = { started.complete(Unit); release.await() },
                phone = AndroidPhone(activity),
            )
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("아침약 판정 고치기").performClick()
        waitFor("08:02에 먹음")
        click("먹은 시각 고치기")
        waitFor("이 시각으로 고치기")
        pick("40분")
        click("이 시각으로 고치기")
        compose.waitUntil(10_000) { started.isCompleted }
        cell("11월 11일 수요일").assertIsNotEnabled()
        cell("8시").assertIsNotEnabled()
        cell("45분").assertIsNotEnabled()
        compose.onNodeWithText("이 시각으로 고치기").assertIsNotEnabled()
        release.complete(Unit)
        waitFor("08:40에 먹음")
    }

    /** SCR-007#normal in today's window: [지금 먹었어] records now (REQ-006), and a time edit to yesterday moves the dose. */
    @Test
    fun today_records_now_and_a_time_edit_moves_the_dose_to_yesterday() {
        val seed = app.seedWeek(family = true)
        openJudge("혈압약")
        compose.onNodeWithText("엄마").assertExists()
        compose.onNodeWithText("아직 기록이 없어요").assertExists()
        compose.onNodeWithContentDescription("11월 12일 목요일 04:00부터 11월 13일 금요일 04:00까지").assertExists()
        compose.onNodeWithContentDescription("매일").assertExists()
        compose.onNodeWithText("먹은 시각은 오늘 04:00부터 지금(09:30)까지 고를 수 있어요. 다른 날은 지난 기록에서 열어요.").assertExists()
        listOf("지금 먹었어", "먹은 시각 고르기", "건너뜀").forEach { compose.onNodeWithText(it).assertExists() }
        compose.assertTargetsAtLeast48dp()
        save("judge-none-today")

        click("지금 먹었어")
        waitFor("09:30에 먹음")
        listOf("먹은 시각 고치기", "기록 취소").forEach { compose.onNodeWithText(it).assertExists() }
        assertEquals(0, compose.onAllNodesWithText("지금 먹었어").fetchSemanticsNodes().size)
        save("judge-taken-today")

        // The stored time is chosen first; another date and hour move the dose (supplement P-6).
        click("먹은 시각 고치기")
        waitFor("이 시각으로 고치기")
        cell("9시").assertIsSelected()
        cell("30분").assertIsSelected()
        pick("11월 11일 수요일")
        pick("21시")
        pick("0분")
        compose.onNodeWithText("11/11 (수) 21:00").assertExists()
        save("judge-picker-edit")
        click("이 시각으로 고치기")
        waitFor("11/11 (수) 기록 고치기")
        compose.onNodeWithText("21:00에 먹음").assertExists()
        compose.onNodeWithText(Labels.TIME_EDITED).assertExists()
        save("judge-moved")

        val rows = rows(seed.pressure!!)
        val old = rows.single { it.takenAt == at("2026-11-12T09:30").toEpochMilliseconds() }
        assertEquals("the old row is kept, voided by the edit", VoidReasons.TIME_EDIT, old.voidReason)
        val new = rows.single { it.isActive && it.periodKey == "2026-11-11" }
        assertEquals(at("2026-11-11T21:00").toEpochMilliseconds(), new.takenAt)
        assertEquals("the input time is kept apart (REQ-030)", at("2026-11-12T09:30").toEpochMilliseconds(), new.recordedAt)
        assertTrue("today is empty again", rows.none { it.isActive && it.periodKey == "2026-11-12" })
    }

    /** REQ-031: a time edit into a day that already has a dose is not saved and says which day; the dose stays. */
    @Test
    fun a_time_edit_into_a_day_with_a_dose_is_refused() {
        val seed = app.seedWeek(family = false)
        openJudge("아침약")
        compose.onNodeWithText("08:02에 먹음").assertExists()
        click("먹은 시각 고치기")
        waitFor("이 시각으로 고치기")
        // 08:00 is chosen first (08:02 floored): confirming it unchanged would move the dose (Fable review of PR #19).
        compose.onNodeWithText("이 시각으로 고치기").assertIsNotEnabled()
        pick("11월 11일 수요일")
        pick("8시")
        pick("30분")
        compose.onNodeWithText("이 시각으로 고치기").assertIsEnabled()
        click("이 시각으로 고치기")
        waitFor("11/11에 이미 기록됨 (08:00)")
        compose.onNodeWithText("오늘 기록").assertExists()
        waitFor("08:02에 먹음")
        val rows = rows(seed.morning)
        assertTrue("nothing voided", rows.all { it.isActive })
        assertEquals(at("2026-11-12T08:02").toEpochMilliseconds(), rows.single { it.periodKey == "2026-11-12" }.takenAt)
    }

    /** A past window's buttons (TASK-044 drawings): no [지금 먹었어]; a picked time, a skip, a skip turned into a dose. */
    @Test
    fun a_past_window_records_a_picked_time_and_turns_a_skip_into_a_dose() {
        val seed = app.seedWeek(family = true)
        moveTodaysDoseToYesterday("혈압약")

        // [기록 취소] asks first, then leaves the row voided (REQ-029).
        click("기록 취소")
        compose.onNodeWithText("기록을 취소할까요?").assertExists()
        compose.onNodeWithText("혈압약의 기록(21:00)을 무효로 바꿔요. 기록은 지워지지 않고 이력에 남아요.").assertExists()
        compose.onNode(hasText("기록 취소") and hasAnyAncestor(isDialog())).performClick()
        waitFor("기록이 없어요")
        compose.onNodeWithText("혈압약 기록을 취소했어요. 기록은 무효로 남아요").assertExists()
        assertEquals("no [지금 먹었어] in a past window (P-2)", 0, compose.onAllNodesWithText("지금 먹었어").fetchSemanticsNodes().size)
        compose.onNodeWithText("먹은 시각은 11/11 (수) 04:00부터 11/12 (목) 04:00 전까지 고를 수 있어요.").assertExists()
        save("judge-none-past")

        // Only the window's own times: 11/11 04:00 to 11/12 03:55. Nothing is chosen at first (REQ-030, REQ-032).
        click("먹은 시각 고르기")
        waitFor("이 시각으로 기록")
        compose.onNodeWithText("이 시각으로 기록").assertIsNotEnabled()
        compose.onNodeWithText("시와 분을 골라 주세요").assertExists()
        assertEquals("11/11 starts at 04:00", 0, compose.onAllNodesWithContentDescriptionExact("3시").size)
        pick("22시")
        pick("15분")
        save("judge-picker-record")
        click("이 시각으로 기록")
        waitFor("22:15에 먹음")

        click("기록 취소")
        compose.onNode(hasText("기록 취소") and hasAnyAncestor(isDialog())).performClick()
        waitFor("기록이 없어요")
        click("건너뜀")
        waitFor("먹었어로 바꾸기")
        compose.onNodeWithText("건너뜀 취소").assertExists()
        compose.onNodeWithText(
            "먹었어로 바꾸면 먹은 시각을 먼저 골라요. 11/11 (수) 04:00부터 11/12 (목) 04:00 전까지예요.",
        ).assertExists()
        save("judge-skipped-past")

        // REQ-034: the skip is voided and the dose stored in one go, at the picked time.
        click("먹었어로 바꾸기")
        waitFor("이 시각으로 바꾸기")
        pick("23시")
        pick("0분")
        click("이 시각으로 바꾸기")
        waitFor("23:00에 먹음")
        val rows = rows(seed.pressure!!).filter { it.periodKey == "2026-11-11" }
        assertEquals(VoidReasons.CHANGED, rows.single { it.kind == DoseKind.SKIPPED }.voidReason)
        assertEquals(at("2026-11-11T23:00").toEpochMilliseconds(), rows.single { it.isActive }.takenAt)
        assertEquals("voided: the moved dose and the dose at 22:15", 2, rows.count { it.kind == DoseKind.TAKEN && !it.isActive })
    }

    /** SCR-007#locked (REQ-032, P-5): after the day start changes, yesterday's window shows no buttons. */
    @Test
    fun a_day_before_a_settings_change_is_locked() {
        app.seedWeek(family = true)
        moveTodaysDoseToYesterday("혈압약")
        runBlocking { core.settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(5, 0))) }
        controller!!.pause().resume()
        waitFor("${Labels.LOCKED}.")
        compose.onNodeWithText(Labels.LOCKED_NOTE).assertExists()
        // Only the date: the day's hours were the old day start's (04:00), which is not kept.
        compose.onNodeWithContentDescription("11월 11일 수요일").assertExists()
        listOf("먹은 시각 고치기", "기록 취소", "건너뜀").forEach {
            assertEquals("no $it", 0, compose.onAllNodesWithText(it).fetchSemanticsNodes().size)
        }
        save("judge-locked")
    }

    /** SCR-004#skipped → SCR-007: today's skip turns into a dose now (REQ-034, TC-019) and can be cancelled. */
    @Test
    fun todays_skip_turns_into_a_dose_now() {
        val seed = app.seedWeek(family = false)
        openJudge("비타민D")
        compose.onNodeWithText("09:10에 건너뜀").assertExists()
        compose.onNodeWithText("먹었어로 바꾸면 지금 시각으로 기록해요.").assertExists()
        compose.onNodeWithText("건너뜀 취소").assertExists()
        assertEquals("one person: no name on the tile", 0, compose.onAllNodesWithText("나").fetchSemanticsNodes().size)
        save("judge-skipped-today")

        click("먹었어로 바꾸기")
        waitFor("09:30에 먹음")
        val rows = rows(seed.vitamin).filter { it.periodKey == "2026-11-12" }
        assertEquals(VoidReasons.CHANGED, rows.single { it.kind == DoseKind.SKIPPED }.voidReason)
        assertEquals(at("2026-11-12T09:30").toEpochMilliseconds(), rows.single { it.isActive }.takenAt)

        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.waitUntil(10_000) { compose.spokenTexts().any { it.startsWith("비타민D, 복용함, 오늘 09:30") } }
    }

    /** A weekly item's window is its week; a time on another day carries its date. */
    @Test
    fun a_weekly_window_offers_every_day_of_the_week_so_far() {
        val seed = app.seedWeek(family = true)
        openJudge("골다공증약")
        compose.onNodeWithContentDescription("주 1회 월요일").assertExists()
        compose.onNodeWithContentDescription("11월 9일 월요일부터 11월 15일 일요일까지").assertExists()
        click("먹은 시각 고르기")
        waitFor("이 시각으로 기록")
        listOf("11월 9일 월요일", "11월 10일 화요일", "11월 11일 수요일", "11월 12일 목요일").forEach { cell(it) }
        cell("11월 12일 목요일").assertIsSelected()
        pick("11월 10일 화요일")
        pick("21시")
        pick("0분")
        click("이 시각으로 기록")
        waitFor("11/10 (화) 21:00에 먹음")
        save("judge-weekly-taken")
        assertEquals(at("2026-11-10T21:00").toEpochMilliseconds(), rows(seed.bone!!).single { it.isActive && it.periodKey == "2026-11-09" }.takenAt)
    }

    /** SCR-007#future (REQ-009): the warning and its answers beside the dose; [그대로 두기] keeps it. */
    @Test
    fun a_clock_warning_is_answered_here() {
        val seed = app.seedWeek(family = false)
        runBlocking {
            app.clock.now = at("2026-11-13T11:00")
            assertTrue(core.record(seed.vitamin, DoseSource.APP, null) is RecordResult.Recorded)
            app.clock.now = at("2026-11-13T09:00")
        }
        openJudge("비타민D")
        compose.onNodeWithText("11:00에 먹음").assertExists()
        compose.onNodeWithText("기록 시각이 휴대폰 시계와 맞지 않았어요 (금 11:00). 확인해 주세요").assertExists()
        assertEquals("one [기록 취소], the warning's", 1, compose.onAllNodesWithText("기록 취소").fetchSemanticsNodes().size)
        compose.onNodeWithText("먹은 시각 고치기").assertExists()
        compose.assertTargetsAtLeast48dp()
        save("judge-future")

        click("그대로 두기")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("그대로 두기").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("기록 취소").assertExists()
        assertTrue(runBlocking { core.loadStatusBoard() }.clockWarnings.isEmpty())
    }

    /** SCR-007#already: recorded from the widget while the screen was open. */
    @Test
    fun a_dose_recorded_elsewhere_first_is_already_there() {
        val seed = app.seedWeek(family = true)
        openJudge("혈압약")
        runBlocking { assertTrue(core.record(seed.pressure!!, DoseSource.WIDGET, "2026-11-12") is RecordResult.Recorded) }
        click("지금 먹었어")
        waitFor("이미 기록됨 (09:30)")
        // The cell is read again after the refused write, so its judgment follows.
        waitFor("09:30에 먹음")
        compose.onNodeWithText("먹은 시각 고치기").assertExists()
        assertEquals(1, rows(seed.pressure!!).count { it.isActive && it.periodKey == "2026-11-12" })
        save("judge-already")
    }

    /** SCR-007#gone: archived meanwhile, the write changes nothing and the screen closes. */
    @Test
    fun an_item_archived_meanwhile_closes_the_screen() {
        val seed = app.seedWeek(family = true)
        openJudge("혈압약")
        runBlocking { assertTrue(core.archiveIntake(seed.pressure!!)) }
        click("건너뜀")
        waitFor(Labels.GONE)
        compose.onNodeWithText("11월 12일 목요일").assertExists()
        assertTrue(rows(seed.pressure!!).none { it.periodKey == "2026-11-12" })
    }

    /** SCR-007#stale: left open across 04:00 the screen closes by itself, and a tap after 04:00 records nothing. */
    @Test
    fun todays_window_closes_when_the_day_ends() {
        val seed = app.seedWeek(family = true)
        app.clock.now = at("2026-11-13T03:59")
        openJudge("혈압약")
        app.clock.now = Instant.parse("2026-11-13T04:00:01+09:00")
        compose.mainClock.advanceTimeBy(60_000)
        waitFor(Labels.DAY_CHANGED)
        compose.onNodeWithText("11월 13일 금요일").assertExists()

        app.clock.now = at("2026-11-13T03:59")
        controller!!.pause().resume()
        waitFor("11월 12일 목요일")
        openJudge("혈압약", reopen = false)
        app.clock.now = Instant.parse("2026-11-13T04:00:30+09:00")
        click("건너뜀")
        waitFor(Labels.STALE)
        assertTrue("nothing written", rows(seed.pressure!!).none { it.periodKey == "2026-11-12" })
    }

    /** 200% text: hours four to a row, every target 48 dp (REQ-067); the choice survives a recreated activity (BUG-003). */
    @Test
    fun the_picker_fits_large_text_and_survives_recreation() {
        app.seedWeek(family = true)
        RuntimeEnvironment.setFontScale(2f)
        openJudge("혈압약")
        click("먹은 시각 고르기")
        waitFor("이 시각으로 기록")
        pick("8시")
        pick("40분")
        compose.assertTargetsAtLeast48dp()
        save("judge-picker-200")
        val eight = compose.onNodeWithContentDescription("8시").fetchSemanticsNode().boundsInRoot
        val nine = compose.onNodeWithContentDescription("9시").fetchSemanticsNode().boundsInRoot
        assertTrue("four to a row: 08 and 09 are on different rows", nine.top >= eight.bottom || nine.left > eight.left)

        controller!!.recreate()
        waitFor("이 시각으로 기록")
        cell("8시").assertIsSelected()
        cell("40분").assertIsSelected()
        // At 200% the confirm button can sit beneath the system navigation area; scroll the following button into view first.
        compose.onNodeWithText("취소").performScrollTo()
        click("이 시각으로 기록")
        waitFor("08:40에 먹음")
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Opens the app (unless [reopen] is false) and the `[⋯]` of [name]'s tile. */
    private fun openJudge(name: String, reopen: Boolean = true) {
        if (reopen) {
            controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        compose.onNodeWithContentDescription("$name 판정 고치기").performClick()
        waitFor("오늘 기록")
    }

    /** Today's [name] dose recorded now and its time moved to yesterday 21:00: the screen shows yesterday. */
    private fun moveTodaysDoseToYesterday(name: String) {
        openJudge(name)
        click("지금 먹었어")
        waitFor("먹은 시각 고치기")
        click("먹은 시각 고치기")
        waitFor("이 시각으로 고치기")
        pick("11월 11일 수요일")
        pick("21시")
        pick("0분")
        click("이 시각으로 고치기")
        waitFor("21:00에 먹음")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun click(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText(text).assertIsDisplayed().performClick()
    }

    /** A date, hour or minute of the picker, by what TalkBack reads (`21시`, `40분`, `11월 11일 수요일`). */
    private fun cell(description: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(description).assertExists()

    private fun pick(description: String) {
        cell(description).performScrollTo().performClick()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithContentDescriptionExact(text: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(text)).fetchSemanticsNodes()

    private fun rows(intakeId: String): List<DoseRecordEntity> = runBlocking { core.db.doseRecordDao().listByIntake(intakeId) }

    private fun save(case: String) = compose.saveScreen(case, controller!!.get())
}
