package com.judangbang.pillbadeum.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.design.Tokens
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.WidgetChoice
import com.judangbang.pillbadeum.status.WidgetModel
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.PersonChange
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.widget.DidITakeWidgetReceiver
import com.judangbang.pillbadeum.widget.WidgetChoices
import com.judangbang.pillbadeum.widget.renderWidget
import com.judangbang.pillbadeum.widget.shownTexts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone as JavaTimeZone

/**
 * SCR-010 대상자 관리 (TASK-021) in the real app, opened from the settings' `대상자 관리` on Thursday 11/12 in Seoul. Lines
 * are found by what TalkBack reads (`엄마, 약 2개`). Covered: 09 People's list with `보관됨`, adding a person with the
 * one-phone advice and an empty name (SPEC-004, REQ-074), changing a name and color (SPEC-026), archiving after the
 * question with no `[보관]` for the last person (SPEC-027, REQ-093), restoring without a question and a second restore
 * that only reads the list again (SPEC-044), the widget that chose 엄마 before and after (REQ-079, drawn as the widget
 * draws it), `#loading`, `#error`, 200% text, a recreated form and back. Each screen is saved as
 * `build/app-render/people-*.png` with its semantics tree, each widget as `build/widget-render/people-*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PeopleScreenTest {
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

    /** SCR-010#normal (09 People): each person with their items, `[대상자 추가]`, and 아빠 under `보관됨`. */
    @Test
    fun the_list_as_09_draws_it() {
        seedFamily()
        archivedDad()
        openPeople(count = 2)

        compose.onNodeWithText(Labels.PEOPLE_NOTE).assertExists()
        line("나, 약 2개")
        line("엄마, 약 2개")
        compose.onNodeWithText("대상자 추가").assertExists()
        compose.onNodeWithText("보관됨").assertExists()
        val dad = line("아빠, 약 1개 · 지난 기록은 남아요").fetchSemanticsNode().boundsInRoot
        val restore = line("아빠 되살리기").fetchSemanticsNode().boundsInRoot
        assertTrue("[되살리기] beside the name at 100%: $dad $restore", restore.top < dad.bottom && restore.left > dad.right)
        compose.assertTargetsAtLeast48dp()
        save("people-normal")
    }

    /**
     * TC-004, SPEC-004, REQ-074, REQ-085: `[대상자 추가]` shows the one-phone advice with the name being typed, starts on the
     * first color nobody has, asks for an empty name under the field and saves nothing; the same name is saved again.
     */
    @Test
    fun spec004_adding_a_person_asks_for_a_name_and_says_one_phone() {
        openPeople(count = 1)
        line("나, 약 0개")
        assertEquals("no archived people", 0, compose.onAllNodesWithText("보관됨").fetchSemanticsNodes().size)

        compose.onNodeWithText("대상자 추가").performClick()
        waitForText("대상자 추가")
        compose.onNodeWithText(Labels.onePhoneFor("")).assertExists()
        compose.onNodeWithContentDescription("주황").assertIsSelected()
        compose.onNodeWithContentDescription("파랑").assertIsNotSelected()
        compose.onNodeWithText("이미 쓰는 색: 파랑(나). 같은 색도 고를 수 있어요.").assertExists()
        compose.assertTargetsAtLeast48dp()
        save("people-add")

        compose.onNodeWithText("저장").performClick()
        waitForText(Labels.NAME_REQUIRED)
        assertEquals("nothing saved", 1, runBlocking { core.people.list() }.size)
        save("people-add-empty")

        nameField().performTextInput("아빠")
        compose.onNodeWithText(Labels.onePhoneFor("아빠")).assertExists()
        assertEquals(0, compose.onAllNodesWithText(Labels.NAME_REQUIRED).fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("초록").performClick()
        compose.onNodeWithContentDescription("초록").assertIsSelected()
        compose.onNodeWithText("저장").performClick()
        waitForLine("아빠, 약 0개")
        assertEquals(PersonColors.PALETTE[2], runBlocking { core.people.list() }.single { it.name == "아빠" }.color)

        // The same name is saved too; its color tells them apart (screens.md SCR-010).
        compose.onNodeWithText("대상자 추가").performClick()
        nameField().performTextInput("아빠")
        compose.onNodeWithContentDescription("주황").assertIsSelected()
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("아빠, 약 0개").fetchSemanticsNodes().size == 2 }
        save("people-added")

        controller!!.get().onBackPressedDispatcher.onBackPressed()
        waitForLine("대상자 관리, 3명")
    }

    /** TC-026, SPEC-026, REQ-086: 엄마 becomes 어머니 in 보라, on the first screen and on the widget; an empty name is not saved. */
    @Test
    fun spec026_a_new_name_and_color_show_on_the_first_screen_and_the_widget() {
        seedFamily()
        openPeople(count = 2)
        line("엄마, 약 2개").performClick()
        waitForText("대상자 수정")
        compose.onNode(hasSetTextAction() and hasText("엄마")).assertExists()
        compose.onNodeWithContentDescription("주황").assertIsSelected()
        compose.onNodeWithText("이미 쓰는 색: 파랑(나). 같은 색도 고를 수 있어요.").assertExists()
        assertEquals("REQ-074 is for adding only", 0, compose.onAllNodesWithText(Labels.ONE_PHONE, substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("보관").assertExists()
        compose.assertTargetsAtLeast48dp()
        save("people-edit")

        nameField().performTextClearance()
        compose.onNodeWithText("저장").performClick()
        waitForText(Labels.NAME_REQUIRED)
        assertEquals(listOf("나", "엄마"), runBlocking { core.people.list() }.map { it.name })

        nameField().performTextInput("어머니")
        compose.onNodeWithContentDescription("보라").performClick()
        compose.onNodeWithText("저장").performClick()
        waitForLine("어머니, 약 2개")
        assertEquals(PersonColors.PALETTE[3], runBlocking { core.people.list() }.single { it.name == "어머니" }.color)

        // The widget's tiles and the first screen's say 어머니, in her new color.
        val pressure = widget(WidgetChoice.Everyone).buttons.single { it.title == "혈압약" }
        assertEquals("어머니" to Tokens.PEOPLE[3], pressure.who to pressure.palette)
        back()
        back()
        compose.waitUntil(10_000) { compose.spokenTexts().any { it.startsWith("어머니 혈압약") } }
    }

    /**
     * TC-027, SPEC-027, REQ-017, REQ-079, REQ-093: `[보관]` asks first (`[아니요]` keeps her); archived, 엄마 moves under
     * `보관됨`, her items leave the first screen, the widget that chose her asks to be set up again, and 나, now alone, has
     * no `[보관]`.
     */
    @Test
    fun spec027_archiving_asks_first_and_her_widget_asks_to_be_set_up_again() {
        seedFamily()
        val mom = person("엄마")
        val widget = placeWidget(61, WidgetChoice.Person(mom))
        openPeople(count = 2)

        line("엄마, 약 2개").performClick()
        waitForText("대상자 수정")
        compose.onNodeWithText("보관").performScrollTo().performClick()
        inDialog("엄마를 보관할까요?").assertExists()
        inDialog(Labels.archivePersonNote("엄마", 2)).assertExists()
        save("people-archive-ask")
        inDialog("아니요").performClick()
        compose.waitForIdle()
        assertEquals("[아니요] keeps her", 2, runBlocking { core.people.list() }.size)

        compose.onNodeWithText("보관").performScrollTo().performClick()
        inDialog("보관").performClick()
        waitForText(Labels.personArchived("엄마"))
        line("나, 약 2개")
        line("엄마, 약 2개 · 지난 기록은 남아요")
        line("엄마 되살리기")
        save("people-archived")

        // REQ-079: the widget does not fall back to everyone; it asks to be set up again.
        val model = widgetFor(widget)
        assertEquals(Labels.RECONFIGURE_ARCHIVED, model.reconfigure)
        assertTrue(model.buttons.isEmpty())
        assertTrue(shownTexts(renderWidget("people-widget-archived", model, null, PORTRAIT, 1f)).contains(Labels.RECONFIGURE_ARCHIVED))

        // REQ-093: 나 is the last active person.
        line("나, 약 2개").performClick()
        waitForText("대상자 수정")
        assertEquals("no [보관]", 0, compose.onAllNodesWithText("보관").fetchSemanticsNodes().size)
        compose.onNodeWithText(Labels.LAST_PERSON).assertExists()
        save("people-last-person")

        // The first screen: 엄마's items are gone and nobody is named (REQ-017, REQ-018).
        back()
        back()
        back()
        compose.waitUntil(10_000) { compose.spokenTexts().any { it.startsWith("아침약") } }
        assertTrue(compose.spokenTexts().none { it.contains("혈압약") || it.contains("엄마") })
    }

    /**
     * TC-088, SPEC-044, REQ-095, REQ-018: `[되살리기]` asks nothing; 엄마 is back with her items on the first screen and on
     * the widget that chose her. 아빠, restored meanwhile elsewhere, only makes the list read again.
     */
    @Test
    fun spec044_restoring_brings_her_items_and_her_widget_back() {
        seedFamily()
        val mom = person("엄마")
        val dad = archivedDad()
        app.clock.now = at("2026-11-12T09:40")
        assertEquals(PersonChange.Saved, runBlocking { core.people.archive(mom) })
        val widget = placeWidget(62, WidgetChoice.Person(mom))
        assertEquals(Labels.RECONFIGURE_ARCHIVED, widgetFor(widget).reconfigure)
        app.clock.now = at("2026-11-12T09:50")
        openPeople(count = 1)
        line("엄마, 약 2개 · 지난 기록은 남아요")
        line("아빠, 약 1개 · 지난 기록은 남아요")

        // Restored behind the screen's back: the tap changes nothing and only reads the list again.
        assertEquals(PersonChange.Saved, runBlocking { core.people.restore(dad) })
        line("아빠 되살리기").performClick()
        waitForLine("아빠, 약 1개")
        assertEquals(0, compose.onAllNodesWithText(Labels.personRestored("아빠")).fetchSemanticsNodes().size)

        line("엄마 되살리기").performClick()
        waitForText(Labels.personRestored("엄마"))
        line("엄마, 약 2개")
        assertEquals("no one archived", 0, compose.onAllNodesWithText("보관됨").fetchSemanticsNodes().size)
        save("people-restored")

        // The widget that chose her shows her items again, with nothing done to the widget.
        val model = widgetFor(widget)
        assertNull(model.reconfigure)
        assertEquals(listOf("혈압약", "골다공증약"), model.buttons.map { it.title })
        assertTrue(shownTexts(renderWidget("people-widget-restored", model, null, PORTRAIT, 1f)).contains("혈압약"))

        back()
        back()
        compose.waitUntil(10_000) { compose.spokenTexts().any { it.startsWith("엄마 혈압약") } }
    }

    /** WBS TASK-021: at 200% `[되살리기]` goes under the name, where the name starts, and stays on the screen (09 README 4.1). */
    @Test
    fun large_text_puts_restore_under_the_name() {
        seedFamily()
        archivedDad()
        RuntimeEnvironment.setFontScale(2f)
        openPeople(count = 2)
        val dad = line("아빠, 약 1개 · 지난 기록은 남아요").performScrollTo().fetchSemanticsNode().boundsInRoot
        val restore = line("아빠 되살리기").performScrollTo().fetchSemanticsNode().boundsInRoot
        val screen = compose.onAllNodes(isRoot()).fetchSemanticsNodes().first().boundsInRoot
        assertTrue("under the name: $dad $restore", restore.top >= dad.bottom)
        assertEquals("where the name starts", dad.left, restore.left, 1f)
        assertTrue("on the screen: $restore in $screen", restore.right <= screen.right)
        compose.assertTargetsAtLeast48dp()
        save("people-200")

        compose.onNodeWithText("대상자 추가").performScrollTo().performClick()
        waitForText(Labels.onePhoneFor(""))
        compose.assertTargetsAtLeast48dp()
        save("people-add-200")
    }

    /** SCR-010#loading while the people are read (grey lines), and the read error with [다시 시도]. */
    @Test
    fun grey_lines_while_reading_and_try_again_after_a_failed_read() {
        openSettings()
        // The database is held in a write so the people wait for it.
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
            line("대상자 관리, 1명").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(Labels.LOADING).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("no button while reading", 0, compose.onAllNodesWithText("대상자 추가").fetchSemanticsNodes().size)
            save("people-loading")
        } finally {
            gate.countDown()
            holder.join(20_000)
        }
        waitForLine("나, 약 0개")

        // Opened again on a database that cannot be read.
        back()
        waitForLine("대상자 관리, 1명")
        runBlocking { core.db.close() }
        line("대상자 관리, 1명").performScrollTo().performClick()
        waitForText(Labels.PEOPLE_LOAD_FAILED)
        compose.onNodeWithText("다시 시도").assertExists()
        compose.assertTargetsAtLeast48dp()
        save("people-error")
    }

    /** SCR-010#error: a person who cannot be saved says so, and what was typed stays. */
    @Test
    fun a_failed_save_keeps_what_was_typed() {
        openPeople(count = 1)
        compose.onNodeWithText("대상자 추가").performClick()
        nameField().performTextInput("아빠")
        runBlocking { core.db.close() }
        compose.onNodeWithText("저장").performClick()
        waitForText(Labels.SAVE_FAILED)
        compose.onNode(hasSetTextAction() and hasText("아빠")).assertExists()
        save("people-save-failed")
    }

    /**
     * SCR-010 when 엄마 was archived after her form opened: saving changes nothing and says so, and her form brought back
     * from a saved screen goes back to the list with the same words.
     */
    @Test
    fun a_person_archived_meanwhile_is_left_as_she_is() {
        seedFamily()
        openPeople(count = 2)
        line("엄마, 약 2개").performClick()
        waitForText("대상자 수정")
        assertEquals(PersonChange.Saved, runBlocking { core.people.archive(person("엄마")) })
        nameField().performTextClearance()
        nameField().performTextInput("어머니")
        compose.onNodeWithText("저장").performClick()
        waitForText(Labels.PERSON_GONE)
        line("엄마, 약 2개 · 지난 기록은 남아요")
        assertEquals("the archived name stays", listOf("엄마"), runBlocking { core.people.listArchived() }.map { it.name })

        line("엄마 되살리기").performClick()
        waitForLine("엄마, 약 2개")
        line("엄마, 약 2개").performClick()
        waitForText("대상자 수정")
        assertEquals(PersonChange.Saved, runBlocking { core.people.archive(person("엄마")) })
        controller!!.recreate()
        waitForText(Labels.PERSON_GONE)
        line("엄마, 약 2개 · 지난 기록은 남아요")
    }

    /** REQ-094, SPEC-021, BUG-003: the form keeps what was typed when recreated; back saves nothing and returns step by step. */
    @Test
    fun a_recreated_form_keeps_its_input_and_back_returns_step_by_step() {
        val activity = openPeople(count = 1)
        compose.onNodeWithText("대상자 추가").performClick()
        nameField().performTextInput("아빠")
        compose.onNodeWithContentDescription("보라").performClick()
        controller!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasText("아빠")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("보라").assertIsSelected()

        controller!!.get().onBackPressedDispatcher.onBackPressed()
        waitForLine("나, 약 0개")
        assertEquals("back saves nothing", 1, runBlocking { core.people.list() }.size)
        back()
        waitForLine("대상자 관리, 1명")
        back()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(activity.isFinishing)
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** [seedWeek] with the database opened first on 11/02, so 나 is older than 엄마 and listed first, as in 09 People. */
    private fun seedFamily(): Seed {
        app.clock.now = at("2026-11-02T06:00")
        runBlocking { app.container.core() } // 나 is made as the database opens
        return app.seedWeek(family = true)
    }

    private fun person(name: String): String = runBlocking { core.people.list() }.single { it.name == name }.id

    /** 아빠 with 철분제, added at 09:31 and archived at 09:32. Returns his id. */
    private fun archivedDad(): String = runBlocking {
        val before = app.clock.now
        app.clock.now = at("2026-11-12T09:31")
        val dad = core.people.add("아빠", PersonColors.PALETTE[2])
        core.saveIntake.create(IntakeDraft(dad.id, "철분제"))
        app.clock.now = at("2026-11-12T09:32")
        assertEquals(PersonChange.Saved, core.people.archive(dad.id))
        app.clock.now = maxOf(before, at("2026-11-12T09:32"))
        dad.id
    }

    private fun openSettings(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("설정").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("대상자 관리, ", substring = true).fetchSemanticsNodes().any { node ->
            node.config[SemanticsProperties.ContentDescription].none { it.endsWith(Labels.LOADING) }
        } }
        return controller!!.get()
    }

    /** Opens the app, `[설정]` and `대상자 관리` with [count] people, and waits for the list. */
    private fun openPeople(count: Int): MainActivity {
        val activity = openSettings()
        line("대상자 관리, ${count}명").performScrollTo().performClick()
        waitForText("대상자 추가")
        return activity
    }

    /** Binds widget [id] to this app's provider with [choice], as SCR-002 leaves it. */
    private fun placeWidget(id: Int, choice: WidgetChoice): Int {
        val platform = AppWidgetManager.getInstance(app)
        val provider = ComponentName(app, DidITakeWidgetReceiver::class.java)
        if (platform.getInstalledProvidersForPackage(app.packageName, null).none { it.provider == provider }) {
            shadowOf(platform).addInstalledProvider(AppWidgetProviderInfo().apply { this.provider = provider })
        }
        shadowOf(platform).bindAppWidgetId(id, provider)
        runBlocking { WidgetChoices.write(app, GlanceAppWidgetManager(app).getGlanceIdBy(id), choice) }
        return id
    }

    /** What widget [id] draws now: its stored choice over the database, as the widget computes it. */
    private fun widgetFor(id: Int): WidgetModel = runBlocking {
        widget(WidgetChoices.read(app, GlanceAppWidgetManager(app).getGlanceIdBy(id)))
    }

    private fun widget(choice: WidgetChoice?): WidgetModel = runBlocking {
        WidgetModel.from(core.loadStatusBoard(), TimeZone.of("Asia/Seoul"), choice)
    }

    /** A line or button, by what TalkBack reads for it. */
    private fun line(sentence: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(sentence).assertExists()

    private fun waitForLine(sentence: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(sentence).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun nameField(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasContentDescription("이름"))

    private fun inDialog(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun back() {
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
    }

    private fun save(case: String) = compose.saveScreen(case, controller!!.get())

    private companion object {
        /** A 4x2 widget on a Pixel 5 in portrait (TASK-044 drawing). */
        val PORTRAIT = DpSize(350.dp, 213.dp)
    }
}
