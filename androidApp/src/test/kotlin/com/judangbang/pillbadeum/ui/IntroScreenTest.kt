package com.judangbang.pillbadeum.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Looper
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.WidgetChoice
import com.judangbang.pillbadeum.status.WidgetView
import com.judangbang.pillbadeum.status.view
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.widget.DidITakeWidgetReceiver
import com.judangbang.pillbadeum.widget.WidgetChoices
import com.judangbang.pillbadeum.widget.WidgetPinnedReceiver
import kotlinx.coroutines.runBlocking
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
 * SCR-005 첫 실행 안내 (TASK-022) in the real app on a new database ([TestApp.introSeen] false), Thursday 11/12 09:30 in
 * Seoul. Covered: the two pages of 09 Welcome1 and Welcome2 with the record-tool and one-phone notes (REQ-036, REQ-074)
 * and 나 made on the first start (REQ-016); `[시작하기]` and `[건너뛰기]` that leave it for good (SPEC-002); `[위젯 추가]` on a
 * launcher that takes the request, the widget it places set to 전체 as SCR-002 does with one person (REQ-080, SPEC-005);
 * a launcher without it (SCR-005#error); back, a recreated activity and the home intent of the widget and reminders;
 * targets of 48 dp, what TalkBack reads and 200% text. Each page is saved as `build/app-render/intro-*.png` with its
 * semantics tree.
 *
 * Not a launcher: Robolectric places a requested widget at once, without the launcher's confirmation, and whether a real
 * launcher shows SCR-002 for it is left for the emulator and the phones (TASK-032).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntroScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val core: DidITakeCore get() = runBlocking { app.container.core() }
    private val provider: ComponentName get() = ComponentName(app, DidITakeWidgetReceiver::class.java)
    private lateinit var savedZone: JavaTimeZone
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        savedZone = JavaTimeZone.getDefault()
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("Asia/Seoul"))
        app.introSeen = false
        launcher(pins = true)
    }

    @After
    fun tearDown() {
        JavaTimeZone.setDefault(savedZone)
        RuntimeEnvironment.setFontScale(1f)
    }

    /** TC-002, SPEC-002, REQ-036, REQ-074, REQ-016: the first start shows page 1, then page 2, then the first screen. */
    @Test
    fun spec002_the_first_start_shows_the_intro_once() {
        open()
        waitForText(Labels.INTRO_LEAD)
        assertEquals("not the first screen yet", 0, compose.onAllNodesWithText("지난 기록").fetchSemanticsNodes().size)
        compose.onNodeWithText("${Labels.INTRO_TOOL_TITLE} ${Labels.INTRO_TOOL_BODY}").assertExists()
        compose.onNodeWithText("${Labels.ONE_PHONE}. ${Labels.INTRO_ONE_PHONE_BODY}").assertExists()
        compose.onNodeWithText(Labels.INTRO_HEADLINE).assertExists()
        line(Labels.INTRO_PICTURE)
        line("2쪽 중 1쪽")
        compose.onNodeWithText(Labels.INTRO_SKIP).assertExists()
        assertEquals(listOf("나"), runBlocking { core.people.list() }.map { it.name })
        compose.assertTargetsAtLeast48dp()
        save("intro-1")

        compose.onNodeWithText(Labels.INTRO_NEXT).performClick()
        waitForText(Labels.INTRO_PIN_TITLE)
        compose.onNodeWithText(Labels.INTRO_PIN_LEAD).assertExists()
        Labels.INTRO_STEPS.forEachIndexed { i, step -> compose.onNodeWithText("${i + 1}. $step").assertExists() }
        compose.onNodeWithText(Labels.INTRO_STEPS_NOTE).assertExists()
        line("2쪽 중 2쪽")
        compose.onNodeWithText(Labels.INTRO_PIN).assertExists()
        assertEquals("the widget picture is for the eye only", 0, compose.onAllNodesWithText("아침약").fetchSemanticsNodes().size)
        compose.assertTargetsAtLeast48dp()
        save("intro-2")

        compose.onNodeWithText(Labels.INTRO_START).performClick()
        waitForText("아직 복용 항목이 없어요.")
        compose.waitUntil(10_000) { runBlocking { core.settings.introSeen() } }

        // The next start opens on the first screen.
        restart()
        waitForText("아직 복용 항목이 없어요.")
        assertEquals(0, compose.onAllNodesWithText(Labels.INTRO_LEAD).fetchSemanticsNodes().size)
    }

    /** SPEC-002: `[건너뛰기]` on the first page goes to the first screen and saves the time, as `[시작하기]` does. */
    @Test
    fun skipping_leaves_the_intro_for_good() {
        open()
        waitForText(Labels.INTRO_LEAD)
        compose.onNodeWithText(Labels.INTRO_SKIP).performClick()
        waitForText("아직 복용 항목이 없어요.")
        compose.waitUntil(10_000) { runBlocking { core.settings.introSeen() } }
        assertEquals(
            app.clock.now().toEpochMilliseconds().toString(),
            runBlocking { core.db.settingDao().get(SettingKeys.INTRO_SEEN_AT) },
        )

        controller!!.recreate()
        waitForText("아직 복용 항목이 없어요.")
        assertEquals(0, compose.onAllNodesWithText(Labels.INTRO_LEAD).fetchSemanticsNodes().size)
    }

    /**
     * REQ-080, SPEC-002, SPEC-005: `[위젯 추가]` asks the launcher, and the widget it places shows everyone, saved as SCR-002
     * saves it with one person (the launcher shows no SCR-002 for a widget placed on request). The intro stays.
     */
    @Test
    fun req080_widget_add_places_the_widget_set_to_everyone() {
        // The callback reaches its receiver by name, as the launcher's does: not exported, and mutable so that the
        // launcher can add the widget's id. Robolectric registers only the manifest's receivers that have an intent
        // filter (this one has none), so it is registered here.
        val info = app.packageManager.getReceiverInfo(ComponentName(app, WidgetPinnedReceiver::class.java), PackageManager.ComponentInfoFlags.of(0))
        assertFalse("not exported", info.exported)
        assertFalse("mutable", WidgetPinnedReceiver.callback(app).isImmutable)
        app.registerReceiver(WidgetPinnedReceiver(), IntentFilter(), Context.RECEIVER_NOT_EXPORTED)

        openSecondPage()
        compose.onNodeWithText(Labels.INTRO_PIN).performClick()

        compose.waitUntil(10_000) { placed().size == 1 }
        val id = placed().single()
        compose.waitUntil(10_000) {
            // The callback is a broadcast on the main thread, which waiting for Compose alone does not run here.
            shadowOf(Looper.getMainLooper()).idle()
            choiceOf(id) == WidgetChoice.Everyone
        }
        // Still one widget's choice after a second person: it keeps showing everyone instead of asking to be set up.
        runBlocking { core.people.add("엄마", PersonColors.PALETTE[1]) }
        assertEquals(WidgetView.Items(null), choiceOf(id).view(runBlocking { core.people.list() }))

        compose.onNodeWithText(Labels.INTRO_PIN_TITLE).assertExists()
        compose.onNodeWithText(Labels.INTRO_START).assertExists()
    }

    /**
     * [WidgetChoices.choosePinned] apart from the button: a widget that has a choice keeps it, from two people on none is
     * given (the widget asks to be set up, SCR-001#reconfigure), and an id that is not this app's widget is left alone.
     */
    @Test
    fun a_placed_widget_gets_everyone_only_with_one_person() {
        val first = bind(71)
        assertEquals(WidgetChoice.Everyone, runBlocking { WidgetChoices.choosePinned(app, first) })
        assertEquals(WidgetChoice.Everyone, choiceOf(first))

        val mom = runBlocking { core.people.add("엄마", PersonColors.PALETTE[1]) }
        val kept = bind(72)
        runBlocking { WidgetChoices.write(app, GlanceAppWidgetManager(app).getGlanceIdBy(kept), WidgetChoice.Person(mom.id)) }
        assertEquals(WidgetChoice.Person(mom.id), runBlocking { WidgetChoices.choosePinned(app, kept) })

        val two = bind(73)
        assertNull(runBlocking { WidgetChoices.choosePinned(app, two) })
        assertNull(choiceOf(two))
        assertEquals(WidgetView.Reconfigure(archived = false), choiceOf(two).view(runBlocking { core.people.list() }))

        assertNull("not a widget", runBlocking { WidgetChoices.choosePinned(app, 99) })
    }

    /** SCR-005#error: without the launcher's support only the steps show, and `[시작하기]` is the dark button. */
    @Test
    fun a_launcher_without_the_request_shows_only_the_steps() {
        launcher(pins = false)
        openSecondPage()
        assertEquals(0, compose.onAllNodesWithText(Labels.INTRO_PIN).fetchSemanticsNodes().size)
        Labels.INTRO_STEPS.forEachIndexed { i, step -> compose.onNodeWithText("${i + 1}. $step").assertExists() }
        compose.onNodeWithText(Labels.INTRO_START).assertExists()
        save("intro-2-no-pin")
    }

    /** A request the launcher refuses after all leaves the steps alone, as SCR-005#error. */
    @Test
    fun a_refused_request_hides_the_button() {
        openSecondPage()
        launcher(pins = false)
        compose.onNodeWithText(Labels.INTRO_PIN).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(Labels.INTRO_PIN).fetchSemanticsNodes().isEmpty() }
        assertTrue("nothing placed", placed().isEmpty())
        compose.onNodeWithText(Labels.INTRO_START).assertExists()
    }

    /**
     * REQ-094: back goes from the second page to the first, and on the first closes the app without finishing the intro,
     * so the next start shows it again (Claude's choice).
     */
    @Test
    fun back_walks_the_pages_and_leaves_the_intro_for_the_next_start() {
        val activity = openSecondPage()
        activity.onBackPressedDispatcher.onBackPressed()
        waitForText(Labels.INTRO_LEAD)
        assertFalse(activity.isFinishing)

        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue("back on the first page leaves the app", activity.isFinishing)
        assertFalse("not finished", runBlocking { core.settings.introSeen() })

        restart()
        waitForText(Labels.INTRO_LEAD)
    }

    /**
     * BUG-003: a recreated activity keeps the page; the widget's header or a reminder opening the app keeps the intro.
     * A recreated activity whose database already says the intro was seen opens on the first screen instead.
     */
    @Test
    fun the_page_survives_recreation_and_the_home_intent() {
        openSecondPage()
        controller!!.recreate()
        waitForText(Labels.INTRO_PIN_TITLE)
        assertEquals(0, compose.onAllNodesWithText(Labels.INTRO_LEAD).fetchSemanticsNodes().size)

        controller!!.newIntent(MainActivity.homeIntent(app))
        compose.waitForIdle()
        compose.onNodeWithText(Labels.INTRO_PIN_TITLE).assertExists()
        assertEquals(0, compose.onAllNodesWithText("아직 복용 항목이 없어요.").fetchSemanticsNodes().size)

        runBlocking { core.settings.markIntroSeen() }
        controller!!.recreate()
        waitForText("아직 복용 항목이 없어요.")
        assertEquals(0, compose.onAllNodesWithText(Labels.INTRO_PIN_TITLE).fetchSemanticsNodes().size)
    }

    /** REQ-066: at 200% each page scrolls to its end between the bar and the buttons, which stay on screen. */
    @Test
    fun req066_large_text_reaches_the_end_of_each_page() {
        RuntimeEnvironment.setFontScale(2f)
        open()
        waitForText(Labels.INTRO_LEAD)
        compose.onNodeWithText(Labels.INTRO_NEXT).assertIsDisplayed()
        compose.onNodeWithText(Labels.INTRO_SKIP).assertIsDisplayed()
        save("intro-1-200")
        compose.onNodeWithText("${Labels.ONE_PHONE}. ${Labels.INTRO_ONE_PHONE_BODY}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(Labels.INTRO_NEXT).assertIsDisplayed()

        compose.onNodeWithText(Labels.INTRO_NEXT).performClick()
        waitForText(Labels.INTRO_PIN_TITLE)
        compose.onNodeWithText(Labels.INTRO_PIN).assertIsDisplayed()
        compose.onNodeWithText(Labels.INTRO_START).assertIsDisplayed()
        save("intro-2-200")
        compose.onNodeWithText(Labels.INTRO_STEPS_NOTE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(Labels.INTRO_START).assertIsDisplayed()
        compose.assertTargetsAtLeast48dp()
    }

    /** Whether the launcher takes a request to place a widget; this app's provider is installed either way. */
    private fun launcher(pins: Boolean) {
        val platform = AppWidgetManager.getInstance(app)
        if (platform.getInstalledProvidersForPackage(app.packageName, null).none { it.provider == provider }) {
            shadowOf(platform).addInstalledProvider(AppWidgetProviderInfo().apply { this.provider = this@IntroScreenTest.provider })
        }
        shadowOf(platform).setRequestPinAppWidgetSupported(pins)
    }

    /**
     * Binds widget [id] to this app's provider without a choice, as a launcher placing it on request leaves it. A
     * widget's saved choice outlives the test under Robolectric (Glance keeps it by the widget's id), so each test class
     * uses its own ids: `WidgetPickTest` 7 to 41, [PeopleScreenTest] 61 and 62, this one 71 to 73 (and 1, the id
     * Robolectric gives the first widget placed on request).
     */
    private fun bind(id: Int): Int {
        shadowOf(AppWidgetManager.getInstance(app)).bindAppWidgetId(id, provider)
        return id
    }

    private fun placed(): List<Int> = AppWidgetManager.getInstance(app).getAppWidgetIds(provider).toList()

    private fun choiceOf(id: Int): WidgetChoice? = runBlocking { WidgetChoices.read(app, GlanceAppWidgetManager(app).getGlanceIdBy(id)) }

    /** Starts the app the way the launcher does. */
    private fun open(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        return controller!!.get()
    }

    private fun openSecondPage(): MainActivity {
        val activity = open()
        waitForText(Labels.INTRO_LEAD)
        compose.onNodeWithText(Labels.INTRO_NEXT).performClick()
        waitForText(Labels.INTRO_PIN_TITLE)
        return activity
    }

    /** Closes the app and starts it again in the same process (same database). */
    private fun restart() {
        controller?.pause()?.stop()?.destroy()
        open()
    }

    /** A part TalkBack reads by its description. */
    private fun line(sentence: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(sentence).assertExists()

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun save(case: String) = compose.saveScreen(case, controller!!.get())
}
