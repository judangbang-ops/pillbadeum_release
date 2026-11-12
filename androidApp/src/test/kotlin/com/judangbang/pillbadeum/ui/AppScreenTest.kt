package com.judangbang.pillbadeum.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.buildDidITakeDatabase
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.restore.InstallMarker
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt
import java.util.TimeZone as JavaTimeZone
import kotlinx.datetime.TimeZone

/**
 * App screen check without an emulator (TASK-015): the shared [App] drawn by Android's Compose, text layout and fonts
 * (Robolectric with native graphics, Android 15, Korean locale, a 360dp wide phone) on a real in-memory database.
 * It reads the merged semantics tree, which is what TalkBack reads, and saves each case as
 * `androidApp/build/app-render/<case>.png` with the tree beside it as `<case>.txt`.
 *
 * REQ-018: with one active person no badge is drawn and TalkBack reads no person; from two people on, both are back.
 * The badge is found by its color: the person's color appears in the row only where the badge is drawn.
 *
 * Not a phone: no TalkBack itself, no Galaxy fonts, no system bars. The emulator captures cover those.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private class TestClock(var now: Instant) : Clock {
        override fun now(): Instant = now
    }

    private class MemoryMarker : InstallMarker {
        private var id: String? = null
        override suspend fun read(): String? = id
        override suspend fun write(deviceId: String) {
            id = deviceId
        }
    }

    private val seoul = TimeZone.of("Asia/Seoul")
    private val clock = TestClock(at("2026-11-12T08:00"))
    private lateinit var savedZone: JavaTimeZone
    private lateinit var db: DidITakeDatabase
    private lateinit var core: DidITakeCore
    private lateinit var activity: ComponentActivity

    private fun at(localDateTime: String): Instant = Instant.parse("${localDateTime}:00+09:00")

    @Before
    fun setUp() {
        // The app shows times in the phone's zone (REQ-082); this phone is in Seoul.
        savedZone = JavaTimeZone.getDefault()
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("Asia/Seoul"))
        val builder = Room.inMemoryDatabaseBuilder<DidITakeDatabase>(ApplicationProvider.getApplicationContext<Application>())
        db = buildDidITakeDatabase(builder, Dispatchers.IO, AndroidSQLiteDriver())
        core = runBlocking { DidITakeCore.open(db, MemoryMarker(), clock) { seoul } }
    }

    @After
    fun tearDown() {
        db.close()
        JavaTimeZone.setDefault(savedZone)
    }

    /** 나's 아침약 taken at 08:02 and 비타민D not yet, seen at 09:30. */
    private fun seedMine() = runBlocking {
        val me = core.people.list().single()
        val morning = create(IntakeDraft(me.id, "아침약", slotLabel = SlotLabel.MORNING))
        create(IntakeDraft(me.id, "비타민D"))
        clock.now = at("2026-11-12T08:02")
        assertTrue(core.record(morning, DoseSource.APP, null) is RecordResult.Recorded)
        clock.now = at("2026-11-12T09:30")
    }

    private suspend fun create(draft: IntakeDraft): String =
        (core.saveIntake.create(draft) as SaveIntakeResult.Saved).intakeId

    private fun addMom(): String = runBlocking {
        val mom = core.people.add("엄마", PersonColors.PALETTE[1])
        create(IntakeDraft(mom.id, "혈압약", slotLabel = SlotLabel.BEDTIME))
    }

    @Test
    fun one_person_has_no_badge_and_talkback_reads_no_name() {
        seedMine()
        show()

        row("아침약, 복용함, 오늘 08:02")
        row("비타민D, 미완료, 기록 없음")
        assertEquals("no badge in either row", 0, colorIn(row("아침약, 복용함, 오늘 08:02"), PersonColors.PALETTE[0]))
        assertEquals("no badge in either row", 0, colorIn(row("비타민D, 미완료, 기록 없음"), PersonColors.PALETTE[0]))
        assertTrue("nothing reads a person", spokenTexts().none { it.startsWith("나 ") })
        save("home-one-person")
    }

    @Test
    fun with_family_the_badges_and_the_person_come_back() {
        seedMine()
        addMom()
        show()

        val mine = row("나 아침약, 복용함, 오늘 08:02")
        row("나 비타민D, 미완료, 기록 없음")
        val moms = row("엄마 혈압약, 미완료, 기록 없음")
        assertTrue("나's badge is drawn", colorIn(mine, PersonColors.PALETTE[0]) > 0)
        assertTrue("엄마's badge is drawn", colorIn(moms, PersonColors.PALETTE[1]) > 0)
        assertEquals("only her own color in her row", 0, colorIn(moms, PersonColors.PALETTE[0]))
        save("home-family")
    }

    @Test
    fun the_item_form_folds_the_person_field_for_one_person() {
        seedMine()
        show()
        compose.onNodeWithText("항목 추가").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("+ 가족 추가").assertExists()
        assertEquals("no 대상자 label", 0, compose.onAllNodesWithText("대상자").fetchSemanticsNodes().size)
        assertEquals("no person chip", 0, compose.onAllNodesWithText("나").fetchSemanticsNodes().size)
        save("form-one-person")

        // [+ 가족 추가] opens the person fields; the added person is selected for the item (TASK-015).
        compose.onNodeWithText("+ 가족 추가").performClick()
        compose.waitForIdle()
        val fields = compose.onAllNodes(hasSetTextAction())
        assertEquals("name, alias and the new person's name", 3, fields.fetchSemanticsNodes().size)
        fields[2].performTextInput("엄마")
        compose.onNodeWithText("추가").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("대상자").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("나").assertExists()
        compose.onNodeWithText("엄마").assertExists()
        save("form-family-added")

        fields[0].performTextInput("혈압약")
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(10_000) { spokenTexts().any { it.startsWith("엄마 혈압약") } }
        row("엄마 혈압약, 미완료, 기록 없음")
        row("나 아침약, 복용함, 오늘 08:02")
    }

    // -----------------------------------------------------------------------------------------------------------

    /** Starts the app on a fresh activity and waits until the first screen has read the database. */
    private fun show() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { App(core, onDataChanged = {}) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("기준", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The row TalkBack reads as [sentence], found in the merged tree. */
    private fun row(sentence: String): SemanticsNodeInteraction =
        compose.onNodeWithContentDescription(sentence).assertExists()

    private fun spokenTexts(): List<String> =
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("has a description") { node ->
            node.config.contains(SemanticsProperties.ContentDescription)
        }).fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.ContentDescription] }

    /**
     * The window drawn into a bitmap by the view tree itself. Compose's `captureToImage` waits for a frame that
     * Robolectric never draws on its own, so the views draw here on a software canvas, as the widget check does.
     */
    private fun screenshot(): Bitmap {
        compose.waitForIdle()
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    /** Pixels of [hex] (`#RRGGBB`) inside [node]. */
    private fun colorIn(node: SemanticsNodeInteraction, hex: String): Int {
        val box = node.fetchSemanticsNode().boundsInWindow
        val shot = screenshot()
        val left = box.left.roundToInt().coerceIn(0, shot.width)
        val top = box.top.roundToInt().coerceIn(0, shot.height)
        val width = box.right.roundToInt().coerceIn(left, shot.width) - left
        val height = box.bottom.roundToInt().coerceIn(top, shot.height) - top
        check(width > 0 && height > 0) { "empty row bounds $box" }
        val pixels = IntArray(width * height)
        shot.getPixels(pixels, 0, width, left, top, width, height)
        val target = PersonColors.argb(hex)
        return pixels.count { it == target }
    }

    /** Saves the whole screen and its merged semantics tree under `build/app-render/`. */
    private fun save(case: String) {
        val bitmap = screenshot()
        val dir = File("build/app-render").apply { mkdirs() }
        File(dir, "$case.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "$case.txt").writeText(compose.onRoot().printToString(maxDepth = Int.MAX_VALUE) + "\n")
    }
}
