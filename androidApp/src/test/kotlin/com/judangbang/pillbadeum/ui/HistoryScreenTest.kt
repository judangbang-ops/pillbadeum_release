package com.judangbang.pillbadeum.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.TimeZone as JavaTimeZone

/**
 * SCR-008 지난 기록 (TASK-019) in the real app, the way [AppScreenTest] runs it: opened from the first screen's `[지난 기록]`
 * on Thursday 11/12 at 09:30 in Seoul, mostly with the week of [seedWeek]. Cells are found by what TalkBack reads
 * ([Labels.historySpoken]); lines by their date heading. Covered: the month newest first with everyone's cells, the person
 * picker (REQ-040), month limits, a cell opening SCR-007 and back to the same month, person and line, `#loading`,
 * `#empty`, `#error`, `#stale`, `#future`, an archived item, 200% text and a recreated activity. Each case is saved as
 * `build/app-render/history-*.png` with its semantics tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryScreenTest {
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

    /** SCR-008#normal (REQ-037~039, 09 History): everyone's cells two a row, newest line first, today marked. */
    @Test
    fun the_month_shows_everyones_cells_newest_first() {
        app.seedWeek(family = true)
        openHistory()
        compose.onNodeWithText("2026년 11월").assertExists()
        // Registered this month: no month before it, and none after this one.
        compose.onNodeWithContentDescription("이전 달 없음").assertIsNotEnabled()
        compose.onNodeWithContentDescription("다음 달 없음").assertIsNotEnabled()
        compose.onNodeWithText("전체").assertIsSelected()
        compose.onNodeWithText("엄마").assertIsNotSelected()
        val today = line("11월 12일 목요일, 오늘").fetchSemanticsNode().boundsInRoot
        assertTrue("newest first", line("11월 11일 수요일").fetchSemanticsNode().boundsInRoot.top > today.bottom)
        cell("엄마 혈압약, 아직")
        val morning = cell("나 아침약, 08:02 먹음").fetchSemanticsNode().boundsInRoot
        val vitamin = cell("나 비타민D, 건너뜀").fetchSemanticsNode().boundsInRoot
        assertTrue("two a row at 100%", vitamin.left > morning.right && vitamin.top == morning.top)
        compose.assertTargetsAtLeast48dp()
        save("history-normal")

        // A weekly item has one cell on its Monday (REQ-039); a day without a judgment is missed (REQ-038).
        scrollTo("11월 11일 수요일")
        anyCell("엄마 혈압약, 빠짐")
        scrollTo("11월 9일 월요일")
        cell("엄마 골다공증약, 11월 9일부터 15일까지, 아직")
        scrollTo("11월 2일 월요일")
        cell("엄마 골다공증약, 11월 2일부터 8일까지, 11월 2일 월요일 08:10 먹음")
        assertEquals("the first day", 0, compose.onAllNodesWithContentDescription("11월 1일 일요일").fetchSemanticsNodes().size)
        save("history-first-day")
    }

    /** REQ-040: the picker keeps one person's cells, read without the name; 전체 shows everyone's again. */
    @Test
    fun the_picker_keeps_one_persons_cells() {
        app.seedWeek(family = true)
        openHistory()
        compose.onNodeWithText("엄마").performClick()
        waitForCell("혈압약, 아직")
        compose.onNodeWithText("엄마").assertIsSelected()
        assertTrue(spoken().toString(), spoken().none { it.startsWith("아침약") || it.startsWith("나 ") || it.startsWith("엄마 ") })
        save("history-person")

        compose.onNodeWithText("전체").performClick()
        waitForCell("나 아침약, 08:02 먹음")
        cell("엄마 혈압약, 아직")
    }

    /**
     * 판정 탭 → SCR-007 with that cell's window (supplement P-1): a past dose is cancelled there. Back returns to the same
     * month, line and person (Claude's choice, TASK-019), and the line shows the change.
     */
    @Test
    fun a_cell_opens_its_window_and_back_returns_to_the_same_place() {
        val seed = app.seedWeek(family = true)
        runBlocking {
            app.takeAt(seed.morning, "2026-11-04T07:45")
            app.clock.now = at("2026-11-12T09:30")
        }
        val activity = openHistory()
        scrollTo("11월 4일 수요일")
        cell("나 아침약, 07:45 먹음").performClick()
        waitForText("11/04\u00a0(수) 기록\u00a0고치기")
        compose.onNodeWithText("07:45에 먹음").assertExists()
        compose.onNodeWithText("기록 취소").performScrollTo().performClick()
        compose.onNode(hasText("기록 취소") and hasAnyAncestor(isDialog())).performClick()
        waitForText("기록이 없어요")

        activity.onBackPressedDispatcher.onBackPressed()
        waitForCell("나 아침약, 빠짐")
        line("11월 4일 수요일").assertIsDisplayed()
        assertTrue("voided", rows(seed.morning).none { it.isActive && it.periodKey == "2026-11-04" })
        save("history-after-judge")

        // With one person chosen, the screen's back button returns to that person's month too.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("엄마"))
        compose.onNodeWithText("엄마").performClick()
        waitForCell("혈압약, 아직")
        scrollTo("11월 2일 월요일")
        cell("골다공증약, 11월 2일부터 8일까지, 11월 2일 월요일 08:10 먹음").performClick()
        waitForText("11/02\u00a0(월) 주 기록\u00a0고치기")
        compose.onNodeWithContentDescription("뒤로").performClick()
        waitForCell("골다공증약, 11월 2일부터 8일까지, 11월 2일 월요일 08:10 먹음")
        line("11월 2일 월요일").assertIsDisplayed()
        assertTrue(spoken().toString(), spoken().none { it.startsWith("아침약") || it.startsWith("비타민D") })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("엄마"))
        compose.onNodeWithText("엄마").assertIsSelected()

        // System back from the month goes to the first screen (REQ-094).
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Months go back to the first item's month and forward to this month (screens.md SCR-008 규칙); one person, no picker. */
    @Test
    fun months_go_from_the_first_items_month_to_this_month() {
        runBlocking {
            app.clock.now = at("2026-10-30T07:00")
            val me = core.people.list().single()
            val morning = (core.saveIntake.create(IntakeDraft(me.id, "아침약")) as SaveIntakeResult.Saved).intakeId
            app.takeAt(morning, "2026-10-31T08:15")
            app.clock.now = at("2026-11-12T09:30")
        }
        openHistory()
        compose.onNodeWithContentDescription("다음 달 없음").assertIsNotEnabled()
        compose.onNodeWithContentDescription("10월").assertIsEnabled().performClick()
        waitForText("2026년 10월")
        waitForCell("아침약, 08:15 먹음")
        cell("아침약, 빠짐")
        assertEquals("one person: no picker (REQ-018)", 0, compose.onAllNodesWithText("전체").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("이전 달 없음").assertIsNotEnabled()
        save("history-october")

        compose.onNodeWithContentDescription("11월").assertIsEnabled().performClick()
        waitForText("2026년 11월")
        waitForCell("아침약, 아직")
        line("11월 12일 목요일, 오늘").assertExists()
    }

    /** SCR-008#empty: nothing registered yet; no month to go to and no picker. */
    @Test
    fun a_month_without_items_says_so() {
        openHistory()
        waitForText(Labels.HISTORY_EMPTY)
        compose.onNodeWithContentDescription("이전 달 없음").assertIsNotEnabled()
        compose.onNodeWithContentDescription("다음 달 없음").assertIsNotEnabled()
        assertEquals(0, compose.onAllNodesWithText("전체").fetchSemanticsNodes().size)
        save("history-empty")
    }

    /** SCR-008#loading while the month is read (grey lines), and #error with [다시 시도] when a read fails. */
    @Test
    fun grey_lines_while_reading_and_try_again_after_a_failed_read() {
        app.seedWeek(family = true)
        open()
        // The database is held in a write so the month waits for it.
        val held = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val holder = thread {
            runBlocking {
                core.db.useWriterConnection { transactor ->
                    transactor.immediateTransaction {
                        held.countDown()
                        gate.await(20, TimeUnit.SECONDS)
                    }
                }
            }
        }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            compose.onNodeWithText("지난 기록").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(Labels.LOADING).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("2026년 11월").assertExists()
            save("history-loading")
        } finally {
            gate.countDown()
            holder.join(20_000)
        }
        waitForCell("나 아침약, 08:02 먹음")

        // A read that fails: the database is closed under the screen, so another person's month cannot be read.
        runBlocking { core.db.close() }
        compose.onNodeWithText("엄마").performClick()
        waitForText(Labels.LOAD_FAILED)
        compose.onNodeWithText("다시 시도").assertExists()
        compose.assertTargetsAtLeast48dp()
        save("history-error")
    }

    /** SCR-008#stale (SPEC-040): left open across 04:00, the month reads the new day, which becomes today's line. */
    @Test
    fun the_open_month_reads_the_new_day_at_the_day_start() {
        app.seedWeek(family = false)
        app.clock.now = at("2026-11-13T03:59")
        openHistory()
        line("11월 12일 목요일, 오늘").assertExists()

        app.clock.now = Instant.parse("2026-11-13T04:00:01+09:00")
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("11월 13일 금요일, 오늘").fetchSemanticsNodes().isNotEmpty() }
        line("11월 12일 목요일").assertExists()
        cell("아침약, 아직")
        save("history-new-day")
    }

    /** SCR-008#future (REQ-009, C-2): the cell says `! 시계 확인`; it opens SCR-007's question, and keeping it clears both. */
    @Test
    fun a_clock_warning_shows_on_its_cell_and_opens_the_question() {
        val seed = app.seedWeek(family = false)
        runBlocking {
            app.clock.now = at("2026-11-13T11:00")
            assertTrue(core.record(seed.vitamin, DoseSource.APP, null) is RecordResult.Recorded)
            app.clock.now = at("2026-11-13T09:00")
        }
        openHistory()
        cell("비타민D, 11:00 먹음, 시계 확인 필요")
        save("history-future")

        cell("비타민D, 11:00 먹음, 시계 확인 필요").performClick()
        waitForText("기록 시각이 휴대폰 시계와 맞지 않았어요 (금 11:00). 확인해 주세요")
        compose.onNodeWithText("그대로 두기").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("그대로 두기").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("뒤로").performClick()
        waitForCell("비타민D, 11:00 먹음")
        assertTrue(runBlocking { core.loadStatusBoard() }.clockWarnings.isEmpty())
    }

    /** An archived item's past cells stay (REQ-014) and cannot be changed: a tap says so at once, on this screen. */
    @Test
    fun an_archived_items_cell_says_it_cannot_be_changed() {
        val seed = app.seedWeek(family = true)
        runBlocking { assertTrue(core.archiveIntake(seed.pressure!!)) }
        openHistory()
        assertEquals("archived this morning: no cell today", 0, compose.onAllNodesWithContentDescription("엄마 혈압약, 아직").fetchSemanticsNodes().size)
        scrollTo("11월 10일 화요일")
        compose.onAllNodesWithContentDescription("엄마 혈압약, 21:40 먹음")[0].performClick()
        waitForText(Labels.GONE)
        compose.onNodeWithText("2026년 11월").assertExists()
        save("history-archived")
    }

    /** 200% text: one cell a row, every target 48 dp (REQ-067); the month and person survive a recreated activity (BUG-003). */
    @Test
    fun large_text_puts_one_cell_a_row_and_recreation_keeps_the_person() {
        app.seedWeek(family = true)
        RuntimeEnvironment.setFontScale(2f)
        openHistory()
        val morning = cell("나 아침약, 08:02 먹음").fetchSemanticsNode().boundsInRoot
        val vitamin = cell("나 비타민D, 건너뜀").fetchSemanticsNode().boundsInRoot
        assertTrue("one a row at 200%", vitamin.top >= morning.bottom && vitamin.left == morning.left)
        compose.assertTargetsAtLeast48dp()
        save("history-200")

        compose.onNodeWithText("엄마").performClick()
        waitForCell("혈압약, 아직")
        controller!!.recreate()
        waitForCell("혈압약, 아직")
        compose.onNodeWithText("엄마").assertIsSelected()
        compose.onNodeWithText("2026년 11월").assertExists()
    }

    // ---------------------------------------------------------------------------------------------------------------

    private fun open(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        return controller!!.get()
    }

    /** Opens the app and `[지난 기록]`, and waits for the month's list. */
    private fun openHistory(): MainActivity {
        val activity = open()
        compose.onNodeWithText("지난 기록").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().isNotEmpty() }
        return activity
    }

    /** A cell, by what TalkBack reads for it. */
    private fun cell(sentence: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(sentence).assertExists()

    /** One of the cells that read [sentence] (lines just outside the screen are drawn too). */
    private fun anyCell(sentence: String) =
        assertTrue(sentence, compose.onAllNodesWithContentDescription(sentence).fetchSemanticsNodes().isNotEmpty())

    /** A date line's heading: `11월 12일 목요일, 오늘`. */
    private fun line(date: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(date).assertExists()

    /** Scrolls the month's list to the line of [date]. */
    private fun scrollTo(date: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(date))
    }

    private fun waitForCell(sentence: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(sentence).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun spoken(): List<String> = compose.spokenTexts()

    private fun rows(intakeId: String): List<DoseRecordEntity> = runBlocking { core.db.doseRecordDao().listByIntake(intakeId) }

    private fun save(case: String) = compose.saveScreen(case, controller!!.get())
}
