package com.judangbang.pillbadeum.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.data.SettingEntity
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.design.PersonPalette
import com.judangbang.pillbadeum.design.Tokens
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.restore.CheckInstall
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import com.judangbang.pillbadeum.usecase.WindowCommand
import com.judangbang.pillbadeum.usecase.WindowWriteResult
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
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
import java.io.File
import kotlin.math.roundToInt
import java.util.TimeZone as JavaTimeZone

/**
 * The app's screens without an emulator (TASK-015, TASK-016, TASK-017): [MainActivity] on the real container
 * ([TestApp], database in memory, a clock the test sets), drawn by Android's Compose, text layout and fonts (Robolectric
 * with native graphics, Android 15, Korean locale, a 360dp wide phone). It reads the merged semantics tree, which is what
 * TalkBack reads, and saves each case as `androidApp/build/app-render/<case>.png` with the tree beside it as `<case>.txt`.
 *
 * Covered: SCR-004 normal, empty, error, skipped, future (one clock warning and many), restored and stale (04:00
 * passing with the app open), tiles a row at 100%, 130% and 200%, targets of at least 48 dp (REQ-067), system back
 * (REQ-094, BUG-001), a recreated activity keeping the form (BUG-003), the home intent of the widget and reminders, and
 * SCR-006's person field, colors and empty name.
 * Not a phone: no TalkBack itself, no Galaxy fonts, no system bars. The emulator covers those.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val core: DidITakeCore get() = runBlocking { app.container.core() }
    private lateinit var savedZone: JavaTimeZone
    private var controller: ActivityController<MainActivity>? = null

    private fun at(localDateTime: String): Instant = Instant.parse("${localDateTime}:00+09:00")

    @Before
    fun setUp() {
        // The app shows times in the phone's zone (REQ-082); this phone is in Seoul.
        savedZone = JavaTimeZone.getDefault()
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("Asia/Seoul"))
    }

    @After
    fun tearDown() {
        JavaTimeZone.setDefault(savedZone)
        RuntimeEnvironment.setFontScale(1f)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Data: registered on Monday 11/02, seen on Thursday 11/12 at 09:30 (09 BoxHome)

    private suspend fun create(draft: IntakeDraft): String = (core.saveIntake.create(draft) as SaveIntakeResult.Saved).intakeId

    private suspend fun takeAt(intakeId: String, at: String) {
        app.clock.now = at(at)
        assertTrue(core.record(intakeId, DoseSource.APP, null) is RecordResult.Recorded)
    }

    private suspend fun skipAt(intakeId: String, at: String) {
        app.clock.now = at(at)
        val now = app.clock.now()
        val version = checkNotNull(core.db.scheduleVersionDao().findEffective(intakeId, now.toEpochMilliseconds()))
        val key = core.loadStatusBoard().items.single { it.intakeId == intakeId }.window.periodKey
        val result = core.recordInWindow.record(
            WindowCommand(intakeId, version.id, key, DoseKind.SKIPPED, deviceZoneId = "Asia/Seoul", renderedAt = now),
        )
        assertTrue("$result", result is WindowWriteResult.Recorded)
    }

    private class Seed(val morning: String, val vitamin: String, val pressure: String?, val bone: String?)

    /** 나's 아침약 (taken Mon–Thu) and 비타민D (taken Mon–Wed, skipped Thu); with [family] 엄마's 혈압약 and 골다공증약. */
    private fun seed(family: Boolean): Seed = runBlocking {
        app.clock.now = at("2026-11-02T07:00")
        val me = core.people.list().single()
        val mom = if (family) core.people.add("엄마", PersonColors.PALETTE[1]) else null
        val morning = create(IntakeDraft(me.id, "아침약", slotLabel = SlotLabel.MORNING))
        val vitamin = create(IntakeDraft(me.id, "비타민D"))
        val pressure = mom?.let { create(IntakeDraft(it.id, "혈압약")) }
        val bone = mom?.let { create(IntakeDraft(it.id, "골다공증약", rule = ScheduleRule.Weekly(DayOfWeek.MONDAY))) }
        bone?.let { takeAt(it, "2026-11-02T08:10") }
        for (day in listOf("09", "10", "11")) {
            takeAt(morning, "2026-11-${day}T08:00")
            takeAt(vitamin, "2026-11-${day}T09:00")
        }
        pressure?.let {
            takeAt(it, "2026-11-09T21:40")
            takeAt(it, "2026-11-10T21:40")
        }
        takeAt(morning, "2026-11-12T08:02")
        skipAt(vitamin, "2026-11-12T09:10")
        app.clock.now = at("2026-11-12T09:30")
        Seed(morning, vitamin, pressure, bone)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // SCR-004

    @Test
    fun one_person_reads_no_person() {
        seed(family = false)
        open()

        tile("아침약, 복용함, 오늘 08:02")
        tile("비타민D, 건너뜀")
        compose.onNodeWithContentDescription("아침약 이번 주: 월 화 수 목 복용").assertExists()
        compose.onNodeWithContentDescription("비타민D 이번 주: 월 화 수 복용, 목 건너뜀").assertExists()
        assertTrue("nothing reads a person", spokenTexts().none { it.startsWith("나 ") })
        save("home-one-person")
    }

    @Test
    fun the_first_screen_shows_today_and_the_week_box() {
        seed(family = true)
        open()

        compose.onNodeWithText("11월 12일 목요일").assertExists()
        compose.onNodeWithText("1 / 4 완료 · 건너뜀 1 · 하루 시작 04:00").assertExists()
        val morning = tile("나 아침약, 복용함, 오늘 08:02")
        tile("나 비타민D, 건너뜀")
        val pressure = tile("엄마 혈압약, 미완료, 마지막 복용 11월 10일 화요일 21:40")
        tile("엄마 골다공증약, 이번 주 미완료, 마지막 복용 11월 2일 월요일 08:10")
        compose.onNodeWithContentDescription("엄마 혈압약 이번 주: 월 화 복용, 수 빠짐, 목 아직").assertExists()
        compose.onNodeWithContentDescription("나 비타민D 이번 주: 월 화 수 복용, 목 건너뜀").assertExists()

        // A taken tile in the person's color, a pending one in the person's tint (09 BoxHome).
        assertTrue("나's color", colorIn(morning, Tokens.PEOPLE[0].solid) > 0)
        assertTrue("엄마's tint", colorIn(pressure, Tokens.PEOPLE[1].tint) > 0)
        assertEquals("엄마's tile has none of 나's color", 0, colorIn(pressure, Tokens.PEOPLE[0].solid))

        // SCR-004#skipped: [⋯] only. Pending tiles have [먹었어], the taken one [기록 취소] (REQ-033).
        assertEquals(2, compose.onAllNodesWithText("먹었어").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("기록 취소").fetchSemanticsNodes().size)
        assertEquals(4, compose.onAllNodesWithContentDescription("판정 고치기", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText(Labels.MEDICAL_NOTE).assertExists()
        assertTargetsAtLeast48dp()
        save("home-family")
    }

    /** Two tiles a row at 100%; from 130% on (Android 14 draws 14sp at 18.8 dp) one, as 09 BoxHome does at 200%. */
    @Test
    fun tiles_go_one_a_row_with_large_text() {
        seed(family = true)
        val first = "나 아침약, 복용함, 오늘 08:02"
        val second = "나 비타민D, 건너뜀"
        open()
        assertEquals("side by side at 100%", bounds(first).top, bounds(second).top, 0.5f)
        for (scale in listOf(1.3f, 2f)) {
            controller?.pause()?.stop()?.destroy()
            RuntimeEnvironment.setFontScale(scale)
            open()
            assertTrue("one a row at $scale", bounds(second).top >= bounds(first).bottom)
            compose.onNodeWithContentDescription("엄마 혈압약 이번 주: 월 화 복용, 수 빠짐, 목 아직").assertExists()
            save("home-family-${(scale * 100).roundToInt()}")
        }
    }

    @Test
    fun an_empty_first_screen_asks_for_the_first_item() {
        open()
        compose.onNodeWithText("아직 복용 항목이 없어요.").assertExists()
        compose.onNodeWithText("[항목 추가]로 등록해 주세요.").assertExists()
        compose.onNodeWithText("하루 시작 04:00").assertExists()
        compose.onNodeWithText(Labels.MEDICAL_NOTE).assertExists()
        save("home-empty")
    }

    @Test
    fun the_restore_notice_stays_until_confirmed() {
        seed(family = true)
        runBlocking {
            core.db.settingDao().upsert(SettingEntity(SettingKeys.RESTORE_NOTICE_PENDING, CheckInstall.PENDING))
            core.db.settingDao().upsert(
                SettingEntity(SettingKeys.RESTORE_NOTICE_LAST_RECORD_AT, at("2026-11-09T21:40").toEpochMilliseconds().toString()),
            )
        }
        open()
        val notice = "백업에서 복원했어요. 마지막 기록은 11/09 21:40이에요. 그 뒤의 기록은 없을 수 있어요. 한 약은 한 휴대폰에서 기록해 주세요"
        compose.onNodeWithText(notice).assertExists()
        save("home-restored")

        compose.onNodeWithText("확인했어요").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(notice).fetchSemanticsNodes().isEmpty() }
        assertEquals(null, runBlocking { core.loadStatusBoard().restoreNotice })
    }

    /** SCR-004#future (REQ-009, RR-64~66): stays the next day; [그대로 두기] keeps the dose, [기록 취소] voids it. */
    @Test
    fun clock_warnings_stay_until_answered() {
        val seed = seed(family = false)
        // Friday's doses saved while the phone clock was two hours ahead, then the clock is right again.
        runBlocking {
            app.clock.now = at("2026-11-13T11:00")
            assertTrue(core.record(seed.morning, DoseSource.APP, null) is RecordResult.Recorded)
            assertTrue(core.record(seed.vitamin, DoseSource.APP, null) is RecordResult.Recorded)
            app.clock.now = at("2026-11-13T09:00")
        }
        open()
        val warning = "기록 시각이 휴대폰 시계와 맞지 않았어요 (금 11:00). 확인해 주세요"
        assertEquals(2, compose.onAllNodesWithText(warning).fetchSemanticsNodes().size)
        tile("아침약, 복용함, 오늘 11:00, 시계 확인 필요")
        save("home-future")

        // The next day the warnings are still there (RR-64).
        controller?.pause()?.stop()?.destroy()
        app.clock.now = at("2026-11-14T09:00")
        open()
        assertEquals(2, compose.onAllNodesWithText(warning).fetchSemanticsNodes().size)

        compose.onAllNodesWithText("그대로 두기")[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("기록 취소").performClick()
        compose.onNodeWithText("기록을 취소할까요?").assertExists()
        compose.onNode(hasText("기록 취소") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isEmpty() }

        val board = runBlocking { core.loadStatusBoard() }
        assertTrue(board.clockWarnings.isEmpty())
        val friday = at("2026-11-13T11:00").toEpochMilliseconds()
        assertEquals("one dose kept, one voided", 1, board.items.count { it.lastTaken?.takenAt == friday })
    }

    /** Supplement C-3 한계: a clock set far back flags many good doses. They are listed two at a time and kept at once. */
    @Test
    fun many_clock_warnings_are_kept_at_once() {
        val seed = seed(family = true)
        runBlocking {
            app.clock.now = at("2026-11-13T11:00")
            for (id in listOfNotNull(seed.morning, seed.vitamin, seed.pressure, seed.bone)) {
                assertTrue(core.record(id, DoseSource.APP, null) is RecordResult.Recorded)
            }
            app.clock.now = at("2026-11-13T09:00")
        }
        open()
        val warning = "기록 시각이 휴대폰 시계와 맞지 않았어요 (금 11:00). 확인해 주세요"
        compose.onNodeWithText("시계가 맞지 않았던 기록이 4개 있어요. 아래에 2개씩 보여요.").assertExists()
        assertEquals(2, compose.onAllNodesWithText(warning).fetchSemanticsNodes().size)
        assertTargetsAtLeast48dp()
        save("home-future-many")

        compose.onNodeWithText("모두 그대로 두기").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("모두 그대로 두기").assertDoesNotExist()
        val board = runBlocking { core.loadStatusBoard() }
        assertTrue(board.clockWarnings.isEmpty())
        assertEquals("all four doses kept", 4, board.items.count { it.isTaken })
    }

    @Test
    fun a_read_error_offers_to_try_again() {
        var attempts = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            App(
                open = { if (attempts++ == 0) error("database not readable") else app.container.core() },
                onDataChanged = {},
            )
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(Labels.LOAD_FAILED).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("no [지난 기록] without a database", 0, compose.onAllNodesWithText("지난 기록").fetchSemanticsNodes().size)
        save("home-error", activity)

        compose.onNodeWithText("다시 시도").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("아직 복용 항목이 없어요.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, attempts)
    }

    /** SCR-004#stale (TASK-017, SPEC-040): left open across 04:00, the first screen reads the new day by itself. */
    @Test
    fun the_open_first_screen_turns_to_the_new_day_at_the_day_start() {
        seed(family = false)
        app.clock.now = at("2026-11-13T03:59")
        open()
        compose.onNodeWithText("11월 12일 목요일").assertExists()
        assertTrue(spokenTexts().toString(), spokenTexts().any { it.startsWith("아침약, 복용함") })

        app.clock.now = Instant.parse("2026-11-13T04:00:01+09:00")
        compose.mainClock.advanceTimeBy(60_000)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("11월 13일 금요일").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(spokenTexts().toString(), spokenTexts().any { it.startsWith("아침약, 미완료") })
        save("home-new-day")
    }

    /** SCR-004#stale: [먹었어] drawn before 04:00 and pressed after it records nothing, says so and shows the new day. */
    @Test
    fun a_tap_drawn_before_the_day_start_records_nothing() {
        seed(family = true)
        app.clock.now = at("2026-11-13T03:59")
        open()
        compose.onNodeWithText("11월 12일 목요일").assertExists()

        app.clock.now = Instant.parse("2026-11-13T04:00:30+09:00")
        compose.onAllNodesWithText("먹었어")[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("11월 13일 금요일").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(Labels.STALE).assertExists()
        assertEquals("nothing recorded on the new day", 0, runBlocking { core.loadStatusBoard() }.doneCount)
        save("home-stale-tap")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Moving between screens

    /** TC-021, BUG-001 (REQ-094, SPEC-021). */
    @Test
    fun system_back_returns_to_the_first_screen_and_closes_the_app_there() {
        val activity = open()
        compose.onNodeWithText("항목 추가").performClick()
        nameField().performTextInput("혈압약")
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("아직 복용 항목이 없어요.").fetchSemanticsNodes().isNotEmpty() }
        assertFalse("still open", activity.isFinishing)
        assertTrue("nothing saved", runBlocking { core.loadStatusBoard().items.isEmpty() })

        compose.onNodeWithText("지난 기록").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("준비 중인 화면이에요.").fetchSemanticsNodes().isNotEmpty() }
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("아직 복용 항목이 없어요.").fetchSemanticsNodes().isNotEmpty() }

        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue("back on the first screen leaves the app", activity.isFinishing)
    }

    /** BUG-003: the screen and what was typed survive a recreated activity. */
    @Test
    fun the_form_survives_the_screen_being_recreated() {
        open()
        compose.onNodeWithText("항목 추가").performClick()
        nameField().performTextInput("골다공증약")
        compose.onNodeWithText("주 1회").performClick()
        compose.onNodeWithText("수").performClick()

        controller!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText("골다공증약")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("수요일부터 화요일 사이에 한 번 먹으면 완료예요.").assertExists()
        save("form-recreated")

        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { spokenTexts().any { it.startsWith("골다공증약, 이번 주 미완료") } }
        val item = runBlocking { core.loadStatusBoard().items.single() }
        assertEquals(ScheduleRule.Weekly(DayOfWeek.WEDNESDAY), item.rule)
    }

    /** The widget's header and the reminders open SCR-004, whatever screen the app was left on. */
    @Test
    fun the_home_intent_opens_the_first_screen() {
        seed(family = false)
        open()
        compose.onNodeWithContentDescription("비타민D 판정 고치기").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("오늘 기록").fetchSemanticsNodes().isNotEmpty() }

        controller!!.newIntent(MainActivity.homeIntent(app))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("11월 12일 목요일").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("오늘 기록").fetchSemanticsNodes().size)
    }

    @Test
    fun the_item_form_folds_the_person_field_for_one_person() {
        seed(family = false)
        open()
        compose.onNodeWithText("항목 추가").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("+ 가족 추가").assertExists()
        assertEquals("no question", 0, compose.onAllNodesWithText("누구 약인가요?").fetchSemanticsNodes().size)
        assertEquals("no person cell", 0, compose.onAllNodesWithText("나").fetchSemanticsNodes().size)
        save("form-one-person")

        // [+ 가족 추가] opens the person fields; the added person is selected for the item (TASK-015).
        compose.onNodeWithText("+ 가족 추가").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("한 약은 한 휴대폰에서 기록해 주세요").assertExists()
        compose.onNode(hasSetTextAction() and hasContentDescription("가족 이름")).performTextInput("엄마")
        // The colors are read by name and are 48 dp targets (09 PersonAdd, REQ-067).
        compose.onNodeWithContentDescription("보라").performClick()
        compose.onNodeWithContentDescription("보라").assertIsSelected()
        compose.onNodeWithContentDescription("주황").assertIsNotSelected()
        assertTargetsAtLeast48dp()
        save("form-person-add")
        compose.onNodeWithText("추가").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("엄마") and isSelected()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(PersonColors.PALETTE[3], runBlocking { core.people.list() }.single { it.name == "엄마" }.color)
        compose.onNodeWithText("누구 약인가요?").assertExists()
        compose.onNodeWithText("나").assertIsNotSelected()
        save("form-family-added")

        nameField().performTextInput("혈압약")
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { spokenTexts().any { it.startsWith("엄마 혈압약") } }
        tile("엄마 혈압약, 미완료, 기록 없음")
        tile("나 아침약, 복용함, 오늘 08:02")
    }

    @Test
    fun an_empty_name_is_asked_for_under_the_field() {
        open()
        compose.onNodeWithText("항목 추가").performClick()
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText(Labels.NAME_REQUIRED).assertExists()
        assertTrue(runBlocking { core.loadStatusBoard().items.isEmpty() })
        save("form-error")
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Starts the app the way the launcher does and waits until the first screen has read the database. */
    private fun open(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        return controller!!.get()
    }

    private fun nameField(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasContentDescription("약 이름"))

    /** REQ-067 (TC-056): everything that can be pressed is at least 48 × 48 dp, measured unclipped. */
    private fun assertTargetsAtLeast48dp() {
        val min = with(compose.density) { 48.dp.roundToPx() }
        val small = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.size.width < min || it.size.height < min }
            .map { node ->
                val name = node.config.getOrNull(SemanticsProperties.ContentDescription) ?: node.config.getOrNull(SemanticsProperties.Text)
                "$name ${node.size}"
            }
        assertEquals("targets under 48 dp", emptyList<String>(), small)
    }

    /** The tile TalkBack reads as [sentence], found in the merged tree. */
    private fun tile(sentence: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(sentence).assertExists()

    private fun bounds(sentence: String) = tile(sentence).fetchSemanticsNode().boundsInRoot

    private fun spokenTexts(): List<String> =
        compose.onAllNodes(SemanticsMatcher("has a description") { it.config.contains(SemanticsProperties.ContentDescription) })
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.ContentDescription] }

    /**
     * The window drawn into a bitmap by the view tree itself. Compose's `captureToImage` waits for a frame that
     * Robolectric never draws on its own, so the views draw here on a software canvas, as the widget check does.
     */
    private fun screenshot(activity: ComponentActivity): Bitmap {
        compose.waitForIdle()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** Pixels of the opaque color [argb] inside [node]. */
    private fun colorIn(node: SemanticsNodeInteraction, argb: Long): Int {
        val box = node.fetchSemanticsNode().boundsInWindow
        val shot = screenshot(controller!!.get())
        val left = box.left.roundToInt().coerceIn(0, shot.width)
        val top = box.top.roundToInt().coerceIn(0, shot.height)
        val width = box.right.roundToInt().coerceIn(left, shot.width) - left
        val height = box.bottom.roundToInt().coerceIn(top, shot.height) - top
        check(width > 0 && height > 0) { "empty bounds $box" }
        val pixels = IntArray(width * height)
        shot.getPixels(pixels, 0, width, left, top, width, height)
        val target = PersonColors.argb(PersonPalette.hex(argb))
        return pixels.count { it == target }
    }

    /** Saves the whole screen and its merged semantics tree under `build/app-render/`. */
    private fun save(case: String, activity: ComponentActivity = controller!!.get()) {
        val bitmap = screenshot(activity)
        val dir = File("build/app-render").apply { mkdirs() }
        File(dir, "$case.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "$case.txt").writeText(compose.onRoot().printToString(maxDepth = Int.MAX_VALUE) + "\n")
    }
}
