package com.judangbang.pillbadeum.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** TC-119 exercises SCR-006 through the real app and Room at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ItemNameScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val core: DidITakeCore get() = runBlocking { app.container.core() }
    private lateinit var activity: MainActivity
    private val name get() = field("항목 이름")
    private val alias get() = field("위젯·알림에 보일 이름")
    private val limitedName = "가".repeat(29) + "💊"
    private val limitedAlias = "별".repeat(30)

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun tc119_name_and_alias_limits_and_counters_at_100_percent() = limitsAndCounters(1f)

    @Test
    fun tc119_name_and_alias_limits_and_counters_at_200_percent() = limitsAndCounters(2f)

    private fun limitsAndCounters(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        openNewItem()
        name.performTextReplacement("가".repeat(26))
        text("26/30").assertDoesNotExist()
        name.performTextReplacement("가".repeat(27))
        text("27/30").assertIsDisplayed()
        name.performTextReplacement("")
        name.performTextInput(limitedName + "나")
        name.assert(hasText(limitedName))
        text(Labels.INTAKE_NAME_LIMIT).assertIsDisplayed()
        text("30/30").assertIsDisplayed()
        compose.saveScreen("item-name-limit-${(scale * 100).toInt()}", activity)

        name.performTextReplacement(limitedName.dropLast(2))
        text("29/30").assertIsDisplayed()
        text(Labels.INTAKE_NAME_LIMIT).assertDoesNotExist()
        name.performTextReplacement(limitedName)
        name.performTextInput("나")
        name.assert(hasText(limitedName))
        // Change the text within the limit to clear the first notice before testing the alias.
        name.performTextReplacement(limitedName.dropLast(2))
        name.performTextReplacement(limitedName)

        alias.performScrollTo().performTextReplacement("별".repeat(26))
        text("26/30").assertDoesNotExist()
        alias.performTextReplacement("별".repeat(27))
        text("27/30").performScrollTo().assertIsDisplayed()
        alias.performTextReplacement("")
        alias.performTextInput(limitedAlias + "칭")
        alias.assert(hasText(limitedAlias))
        text(Labels.INTAKE_NAME_LIMIT).performScrollTo().assertIsDisplayed()
        compose.saveScreen("item-alias-limit-${(scale * 100).toInt()}", activity)

        saveItem()
        val row = runBlocking { core.db.intakeDao().listActive().single() }
        assertEquals(limitedName, row.name)
        assertEquals(limitedAlias, row.widgetAlias)
    }

    @Test
    fun tc119_blank_name_shows_error_and_trims_the_saved_inputs() {
        openNewItem()
        name.performTextReplacement("   ")
        alias.performTextReplacement("  가상 별칭  ")
        text("저장").performClick()
        text(Labels.NAME_REQUIRED).performScrollTo().assertIsDisplayed()
        alias.assert(hasText("  가상 별칭  "))
        assertTrue(runBlocking { core.db.intakeDao().listActive().isEmpty() })

        name.performTextReplacement("  가상 항목  ")
        saveItem()
        val row = runBlocking { core.db.intakeDao().listActive().single() }
        assertEquals("가상 항목", row.name)
        assertEquals("가상 별칭", row.widgetAlias)
    }

    @Test
    fun tc119_legacy_values_survive_a_schedule_edit_and_limit_only_when_edited() {
        val oldName = "가".repeat(40)
        val oldAlias = "별".repeat(40)
        val id = runBlocking {
            val person = core.people.ensureDefault().single()
            val saved = core.saveIntake.create(IntakeDraft(person.id, "가상 항목")) as SaveIntakeResult.Saved
            core.db.intakeDao().updateDetails(saved.intakeId, person.id, oldName, oldAlias, null, 0L)
            saved.intakeId
        }
        open()
        compose.onAllNodesWithText(oldName).onFirst().assertIsDisplayed()
        editItem()
        name.assert(hasText(oldName))
        alias.assert(hasText(oldAlias))
        assertEquals(2, compose.onAllNodesWithText("40/30").fetchSemanticsNodes().size)
        text(Labels.INTAKE_NAME_LIMIT).assertDoesNotExist()
        compose.saveScreen("item-name-legacy", activity)
        text("주 1회").performScrollTo().performClick()
        saveItem()
        assertEquals(oldName, runBlocking { core.db.intakeDao().findById(id)?.name })
        assertEquals(oldAlias, runBlocking { core.db.intakeDao().findById(id)?.widgetAlias })
        assertTrue(runBlocking { core.loadStatusBoard().items.single().rule is ScheduleRule.Weekly })
        compose.onAllNodesWithText(oldName).onFirst().assertIsDisplayed()

        editItem()
        name.performTextReplacement(oldName.dropLast(1))
        name.assert(hasText(oldName.take(30)))
        text(Labels.INTAKE_NAME_LIMIT).assertIsDisplayed()
        saveItem()
        assertEquals(oldName.take(30), runBlocking { core.db.intakeDao().findById(id)?.name })
        assertEquals(oldAlias, runBlocking { core.db.intakeDao().findById(id)?.widgetAlias })
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasContentDescription(label))

    private fun text(value: String) = compose.onNodeWithText(value)

    private fun waitForText(value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun open() {
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        waitForText("지난 기록")
    }

    private fun openNewItem() {
        open()
        text("항목 추가").performClick()
        waitForText("항목 이름")
    }

    private fun editItem() {
        compose.onNode(SemanticsMatcher("opens 항목 수정") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "항목 수정"
        }).performClick()
        waitForText("항목 이름")
    }

    private fun saveItem() {
        text("저장").performClick()
        waitForText("지난 기록")
    }
}
