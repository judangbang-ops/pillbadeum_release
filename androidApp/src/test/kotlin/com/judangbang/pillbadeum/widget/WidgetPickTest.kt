package com.judangbang.pillbadeum.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.restore.InstallState
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.PickOption
import com.judangbang.pillbadeum.status.WidgetChoice
import com.judangbang.pillbadeum.status.WidgetLayout
import com.judangbang.pillbadeum.status.WidgetPick
import com.judangbang.pillbadeum.ui.PickState
import com.judangbang.pillbadeum.ui.WidgetPickScreen
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.File
import java.util.TimeZone as JavaTimeZone

/**
 * TASK-012 without a launcher: the person picker (SCR-002) started the way a launcher starts it, with a widget id
 * bound to this app's provider, on the real container (database in memory, [TestApp]); the one-time fill for widgets
 * placed before the picker (RR-74); and the widget tap that records nothing while the widget has to be set up again
 * (SPEC-009). Screens are saved as `androidApp/build/app-render/pick-<case>.png` with their semantics tree.
 *
 * Not a launcher: whether a launcher really drops a widget whose picker was canceled, the Android 12 Reconfigure
 * button and a restore from a backup are left for the emulator and the phones (TASK-032).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPickTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private lateinit var savedZone: JavaTimeZone

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

    // ---------------------------------------------------------------------------------------------
    // SCR-002 started by the launcher

    @Test
    fun one_person_places_the_widget_as_everyone_without_showing_the_picker() {
        mine("아침약")
        val id = place(7)
        val activity = open(id)

        compose.waitUntil(10_000) { activity.isFinishing }
        assertEquals("placed", Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(id, shadowOf(activity).resultIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertEquals(WidgetChoice.Everyone, choiceOf(id))
        assertEquals("the question never showed", 0, compose.onAllNodesWithText(words(Labels.PICK_TITLE)).fetchSemanticsNodes().size)
    }

    @Test
    fun two_people_pick_mom_and_the_widget_keeps_her() {
        val mom = family()
        val id = place(8)
        val activity = open(id)

        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("엄마, 약 1개").fetchSemanticsNodes().isNotEmpty() }
        save(activity, "pick-family")
        compose.onNodeWithText(words(Labels.PICK_TITLE)).assertExists()
        compose.onNodeWithContentDescription("전체, 나·엄마의 약 3개").assertExists()
        compose.onNodeWithContentDescription("나, 약 2개").assertExists()

        compose.onNodeWithContentDescription("엄마, 약 1개").performClick()
        compose.waitUntil(10_000) { activity.isFinishing }
        assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)
        assertEquals(WidgetChoice.Person(mom), choiceOf(id))
    }

    @Test
    fun back_does_not_place_the_widget() {
        family()
        val back = open(place(9))
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("엄마, 약 1개").fetchSemanticsNodes().isNotEmpty() }
        back.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(10_000) { back.isFinishing }
        assertEquals("back: not placed", Activity.RESULT_CANCELED, shadowOf(back).resultCode)
        assertNull(choiceOf(9))
    }

    @Test
    fun cancel_does_not_place_the_widget() {
        family()
        val cancel = open(place(10))
        compose.waitUntil(10_000) { compose.onAllNodesWithText(words(Labels.PICK_CANCEL)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(words(Labels.PICK_CANCEL)).performClick()
        compose.waitUntil(10_000) { cancel.isFinishing }
        assertEquals("[취소]: not placed", Activity.RESULT_CANCELED, shadowOf(cancel).resultCode)
        assertNull(choiceOf(10))
    }

    @Test
    fun a_widget_id_that_is_not_this_apps_is_refused() {
        family()
        val activity = open(99)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test
    fun the_picker_at_200_percent_scrolls_instead_of_cutting() {
        family()
        RuntimeEnvironment.setFontScale(2f)
        val activity = open(place(12))
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("엄마, 약 1개").fetchSemanticsNodes().isNotEmpty() }
        save(activity, "pick-family-200")
        // The foot with [취소] stays on screen; the list scrolls above it.
        compose.onNodeWithText(words(Labels.PICK_CANCEL)).assertExists()
    }

    @Test
    fun loading_as_drawn() {
        val loading = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        loading.setContent { WidgetPickScreen(PickState.Loading, onPick = {}, onCancel = {}) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(words(Labels.LOADING)).fetchSemanticsNodes().isNotEmpty() }
        save(loading, "pick-loading")
    }

    @Test
    fun save_failure_as_drawn() {
        val options = listOf(
            WidgetPick.everyoneOnly().copy(detail = "나·엄마의 약 3개"),
            PickOption(WidgetChoice.Person("me"), "나", "약 2개", PersonColors.palette(PersonColors.PALETTE[0]), "나"),
            PickOption(WidgetChoice.Person("mom"), "엄마", "약 1개", PersonColors.palette(PersonColors.PALETTE[1]), "엄"),
        )
        val error = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        error.setContent { WidgetPickScreen(PickState.Ready(options, error = Labels.PICK_SAVE_FAILED), onPick = {}, onCancel = {}) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(words(Labels.PICK_SAVE_FAILED)).fetchSemanticsNodes().isNotEmpty() }
        save(error, "pick-error")
    }

    // ---------------------------------------------------------------------------------------------
    // RR-74 and SPEC-009

    @Test
    fun rr74_widgets_placed_before_the_picker_show_everyone_and_only_once() = runBlocking {
        val before = place(21)
        val chosen = place(22)
        WidgetChoices.write(app, glance(chosen), WidgetChoice.Person("p"))

        // The update's first start opens the core, which fills them in before any widget draws.
        app.container.core()
        assertEquals(WidgetChoice.Everyone, choiceOf(before))
        assertEquals("a choice is kept", WidgetChoice.Person("p"), choiceOf(chosen))

        // Only once: a widget that later has no choice (restored) is not filled in again.
        updateAppWidgetState(app, glance(before)) { it.remove(WidgetChoices.KEY) }
        WidgetChoices.prepare(app, InstallState.Normal)
        assertNull(choiceOf(before))
    }

    @Test
    fun after_a_restore_nothing_is_filled_in_on_this_start_or_the_next() = runBlocking {
        val restored = place(31)
        WidgetChoices.prepare(app, InstallState.Restored(previousDeviceId = "old-phone"))
        assertNull(choiceOf(restored))
        WidgetChoices.prepare(app, InstallState.Normal)
        assertNull("the next start does not fill it in either", choiceOf(restored))
    }

    @Test
    fun spec009_a_widget_to_set_up_again_records_nothing() = runBlocking {
        family()
        val core = app.container.core()
        val board = core.loadStatusBoard()
        val vitamin = board.items.single { it.name == "비타민D" }
        val id = place(41)
        // No choice and two people: what a restored widget is (REQ-079).
        tap(id, vitamin.intakeId, vitamin.window.periodKey)
        assertNull("no record", core.loadStatusBoard().items.single { it.intakeId == vitamin.intakeId }.judgment)

        WidgetChoices.write(app, glance(id), WidgetChoice.Everyone)
        tap(id, vitamin.intakeId, vitamin.window.periodKey)
        assertTrue("set up: the tap records", core.loadStatusBoard().items.single { it.intakeId == vitamin.intakeId }.isTaken)
    }

    // ---------------------------------------------------------------------------------------------

    private fun mine(name: String): String = runBlocking {
        val me = app.container.core().people.list().single()
        (app.container.core().saveIntake.create(IntakeDraft(me.id, name, slotLabel = SlotLabel.MORNING)) as SaveIntakeResult.Saved).intakeId
    }

    /**
     * 나 (the first start, 09:20): 아침약, 비타민D. 엄마 (added at 09:25): 혈압약. Seen at 09:30. Returns 엄마's id.
     * People are listed oldest first; added at one instant they would fall back to the random part of their ids.
     */
    private fun family(): String = runBlocking {
        app.clock.now = seoul("09:20")
        val core = app.container.core()
        val me = core.people.list().single()
        core.saveIntake.create(IntakeDraft(me.id, "아침약", slotLabel = SlotLabel.MORNING))
        core.saveIntake.create(IntakeDraft(me.id, "비타민D"))
        app.clock.now = seoul("09:25")
        val mom = core.people.add("엄마", PersonColors.PALETTE[1])
        core.saveIntake.create(IntakeDraft(mom.id, "혈압약", slotLabel = SlotLabel.BEDTIME))
        app.clock.now = seoul("09:30")
        mom.id
    }

    private fun seoul(time: String): Instant = Instant.parse("2026-11-12T$time:00+09:00")

    /** Binds [id] to this app's widget provider, as a launcher does before it starts the picker. */
    private fun place(id: Int): Int {
        val platform = AppWidgetManager.getInstance(app)
        val manager = shadowOf(platform)
        val provider = ComponentName(app, DidITakeWidgetReceiver::class.java)
        if (platform.getInstalledProvidersForPackage(app.packageName, null).none { it.provider == provider }) {
            manager.addInstalledProvider(AppWidgetProviderInfo().apply { this.provider = provider })
        }
        manager.bindAppWidgetId(id, provider)
        return id
    }

    private fun open(id: Int): ComponentActivity {
        val intent = Intent(app, WidgetPickActivity::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        val controller: ActivityController<WidgetPickActivity> = Robolectric.buildActivity(WidgetPickActivity::class.java, intent)
        return controller.setup().get()
    }

    private fun glance(id: Int): GlanceId = GlanceAppWidgetManager(app).getGlanceIdBy(id)

    private fun choiceOf(id: Int): WidgetChoice? = runBlocking { WidgetChoices.read(app, glance(id)) }

    /** A tap on a tile, as the widget sends it. Refreshing the widgets afterwards needs WorkManager, absent here. */
    private suspend fun tap(id: Int, intakeId: String, periodKey: String) {
        val parameters = actionParametersOf(
            ActionParameters.Key<String>("intake_id") to intakeId,
            ActionParameters.Key<String>("rendered_period_key") to periodKey,
            ActionParameters.Key<String>("name") to "비타민D",
        )
        runCatching { RecordFromWidget().onAction(app, glance(id), parameters) }
    }

    private fun words(text: String): String = WidgetLayout.keepWords(text)

    /** Saves the window and its semantics tree under `build/app-render/`, beside the app screens. */
    private fun save(activity: ComponentActivity, case: String) {
        compose.waitForIdle()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/app-render").apply { mkdirs() }
        File(dir, "$case.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "$case.txt").writeText(compose.onRoot().printToString(maxDepth = Int.MAX_VALUE) + "\n")
    }
}
