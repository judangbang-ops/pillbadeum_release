package com.judangbang.pillbadeum.ui

import android.Manifest
import android.app.NotificationManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.IntakeMedicationDraft
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.ReminderChoice
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

/** Real SCR-006 and Room, with the three approved 09 medication boards rendered at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ItemMedicationScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val core: DidITakeCore get() = runBlocking { app.container.core() }
    private lateinit var controller: ActivityController<MainActivity>
    private val examples = listOf(IntakeMedicationDraft("가상정 10mg", "1정"), IntakeMedicationDraft("보기정 500mg", "반 알"))

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun item_form_meds_renders_side_by_side_at_100_percent() {
        RuntimeEnvironment.setQualifiers("+h1260dp")
        openWithItem(examples)
        edit()
        assertLayout(stacked = false)
        compose.saveScreen("ItemFormMeds", controller.get())
    }

    @Test
    fun item_form_meds200_stacks_the_inputs_and_remove_button() {
        RuntimeEnvironment.setQualifiers("+h2120dp")
        RuntimeEnvironment.setFontScale(2f)
        openWithItem(examples)
        edit()
        assertLayout(stacked = true)
        compose.saveScreen("ItemFormMeds200", controller.get())
    }

    @Test
    fun tc093_item_form_meds_error_preserves_other_inputs_and_stored_rows() {
        RuntimeEnvironment.setQualifiers("+h1280dp")
        val id = openWithItem(examples)
        edit()
        val before = stored(id)
        name(2).performTextReplacement("   ")
        compose.onNodeWithContentDescription("위젯·알림에 보일 이름").performTextReplacement("아침 약")
        text("저장").performClick()

        text("약 이름을 입력해 주세요").assertIsDisplayed()
        text("아침 약").assertExists()
        text("가상정 10mg").assertExists()
        text("반 알").assertExists()
        assertEquals(before, stored(id))
        assertEquals(null, runBlocking { core.db.intakeDao().findById(id)?.widgetAlias })

        // Clear the unrelated edit for the approved error board fixture.
        name(2).performTextReplacement("")
        compose.onNodeWithContentDescription("위젯·알림에 보일 이름").performTextReplacement("")
        compose.saveScreen("ItemFormMedsError", controller.get())
    }

    @Test
    fun tc093_add_edit_and_remove_return_to_the_same_today_list() {
        val id = openWithItem()
        val today = compose.spokenTexts()
        edit()
        for ((index, draft) in examples.withIndex()) {
            text("약 더하기").performScrollTo().performClick()
            name(index + 1).assertIsFocused().performTextReplacement(draft.name)
            quantity(index).performTextReplacement(draft.quantity)
        }
        text("저장").performClick()
        waitForToday()
        assertEquals(today, compose.spokenTexts())
        val before = stored(id)
        assertEquals(examples, before.map { it.copy(id = null) })

        edit()
        name(1).performScrollTo().performTextReplacement("예시정")
        quantity(0).performTextReplacement("두 알")
        compose.onNodeWithContentDescription("보기정 500mg 빼기").performScrollTo().performClick()
        text("저장").performClick()
        waitForToday()
        assertEquals(today, compose.spokenTexts())
        assertEquals(listOf(before.first().copy(name = "예시정", quantity = "두 알")), stored(id))
    }

    @Test
    fun recreation_keeps_the_draft_and_close_or_back_discards_it() {
        val id = openWithItem(examples)
        val before = stored(id)
        edit()
        name(1).performScrollTo().performTextReplacement("예시정")
        text("약 더하기").performScrollTo().performClick()
        name(3).performTextReplacement("가상정")
        controller.recreate()
        waitForText("가상정")
        text("예시정").assertExists()
        compose.onNodeWithContentDescription("닫기").performClick()
        waitForToday()
        assertEquals(before, stored(id))

        edit()
        compose.onNodeWithContentDescription("가상정 10mg 빼기").performScrollTo().performClick()
        compose.onNodeWithContentDescription("보기정 500mg 빼기").performScrollTo().performClick()
        controller.recreate()
        waitForText("항목 이름")
        name(1).assertDoesNotExist()
        controller.get().onBackPressedDispatcher.onBackPressed()
        waitForToday()
        assertFalse(controller.get().isFinishing)
        assertEquals(before, stored(id))
    }

    @Test
    fun ten_rows_replace_the_add_button_until_a_row_is_removed() {
        openWithItem(List(9) { IntakeMedicationDraft("예시정 ${it + 1}") })
        edit()
        text("약 더하기").performScrollTo().performClick()
        name(10).assertIsFocused()
        text("약은 10개까지 적을 수 있어요").assertExists()
        text("약 더하기").assertDoesNotExist()
        compose.onNodeWithContentDescription("약 10 빼기").performScrollTo().performClick()
        text("약 더하기").assertExists()
    }

    @Test
    fun two_rapid_save_taps_create_one_item_with_one_set_of_medications() {
        openWithItem()
        text("항목 추가").performClick()
        compose.onNode(hasSetTextAction() and hasContentDescription("항목 이름")).performTextReplacement("저녁약")
        text("약 더하기").performScrollTo().performClick()
        name(1).performTextReplacement("예시정")
        text("저장").performSemanticsAction(SemanticsActions.OnClick) { save ->
            save()
            save()
        }
        waitForToday()
        val items = runBlocking { core.db.intakeDao().listActive() }
        assertEquals(2, items.size)
        val created = items.single { it.name == "저녁약" }
        assertEquals(listOf("예시정"), stored(created.id).map { it.name })
    }

    @Test
    fun stale_save_callback_after_navigation_does_not_create_a_second_item() {
        openWithItem()
        text("항목 추가").performClick()
        compose.onNode(hasSetTextAction() and hasContentDescription("항목 이름")).performTextReplacement("저녁약")
        text("약 더하기").performScrollTo().performClick()
        name(1).performTextReplacement("예시정")
        text("저장").performSemanticsAction(SemanticsActions.OnClick) { save ->
            save()
            compose.waitUntil(10_000) { compose.spokenTexts().any { it.contains("저녁약") } }
            // Keep the original callback to model a delayed second activation of the departed form.
            save()
        }
        compose.waitForIdle()
        val items = runBlocking { core.db.intakeDao().listActive() }
        assertEquals(2, items.size)
        val created = items.single { it.name == "저녁약" }
        assertEquals(listOf("예시정"), stored(created.id).map { it.name })
    }

    @Test
    fun pasting_over_the_limits_keeps_whole_code_points_and_explains_the_limit() {
        val id = openWithItem()
        edit()
        text("약 더하기").performScrollTo().performClick()
        val name = "가".repeat(49) + "🌕"
        val quantity = "가".repeat(29) + "🌕"
        // Outer spaces, which saving drops, do not use up the limit (approved review 12).
        name(1).performTextReplacement(" $name")
        name(1).assert(hasText(" $name"))
        text("50자까지 적을 수 있어요").assertDoesNotExist()
        name(1).performTextReplacement(name)
        text("50자까지 적을 수 있어요").assertDoesNotExist()
        quantity(0).performTextReplacement(quantity)
        text("30자까지 적을 수 있어요").assertDoesNotExist()
        name(1).performTextReplacement("${name}다")
        name(1).assert(hasText(name))
        text("50자까지 적을 수 있어요").assertExists()
        quantity(0).performTextReplacement("${quantity}다")
        quantity(0).assert(hasText(quantity))
        text("30자까지 적을 수 있어요").assertExists()

        text("저장").performClick()
        waitForToday()
        assertEquals(IntakeMedicationDraft(name, quantity), stored(id).single().copy(id = null))
    }

    @Test
    fun the_schedule_note_shows_only_while_the_schedule_differs_from_the_saved_one() {
        openWithItem(examples)
        edit()
        val note = "주기를 바꾸면 지금부터 새 일정이 적용돼요. 이번 창의 기록은 그대로 인정돼요."
        text(note).assertDoesNotExist()
        text("주 1회").performScrollTo().performClick()
        text(note).performScrollTo().assertIsDisplayed()
        text("매일").performScrollTo().performClick()
        text(note).assertDoesNotExist()
    }

    @Test
    fun an_item_archived_during_editing_returns_to_the_opener_with_the_gone_notice() {
        val id = openWithItem(examples)
        edit()
        assertTrue(runBlocking { core.archiveIntake(id) })
        text("저장").performClick()
        waitForText(Labels.GONE)
        waitForText("아직 복용 항목이 없어요.")
        assertFalse(controller.get().isFinishing)
    }

    private fun assertLayout(stacked: Boolean) {
        val name = bounds(name(1))
        val quantity = bounds(quantity(0))
        val remove = bounds(compose.onNodeWithContentDescription("가상정 10mg 빼기"))
        if (stacked) {
            assertTrue(quantity.top >= name.bottom)
            assertTrue(remove.top >= quantity.bottom)
            assertEquals(name.width, quantity.width, 1f)
        } else {
            assertEquals(name.top, quantity.top, 1f)
            assertTrue(quantity.left >= name.right)
            assertTrue(remove.left >= quantity.right)
        }
        val second = bounds(name(2))
        assertTrue(second.top > remove.bottom)
        val add = bounds(text("약 더하기"))
        assertTrue(add.top > second.bottom)
        assertTrue(bounds(compose.onNodeWithContentDescription("알림 받기")).top >= add.bottom)
        val min = with(compose.density) { 48.dp.toPx() }
        for (hit in listOf(name, quantity, remove, add)) {
            assertTrue("expected 48 dp in both dimensions: $hit", hit.width >= min && hit.height >= min)
        }
        compose.assertTargetsAtLeast48dp()
    }

    private fun openWithItem(rows: List<IntakeMedicationDraft> = emptyList()): String {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val ready = core
        app.clock.now = at("2026-11-12T09:31")
        val id = runBlocking {
            val person = ready.people.add("엄마", PersonColors.PALETTE[1])
            val draft = IntakeDraft(
                person.id, "아침약", slotLabel = SlotLabel.MORNING, reminder = ReminderChoice(true, LocalTime(8, 0)),
                medications = rows,
            )
            (ready.saveIntake.create(draft) as SaveIntakeResult.Saved).intakeId
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        waitForToday()
        return id
    }

    private fun edit() {
        compose.onNode(SemanticsMatcher("opens 항목 수정") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "항목 수정"
        }).performClick()
        waitForText("항목 이름")
    }

    private fun waitForToday() {
        compose.waitUntil(10_000) { compose.spokenTexts().any { it.startsWith("엄마 아침약, 미완료") } }
    }

    private fun waitForText(value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun stored(id: String) = runBlocking { core.saveIntake.loadMedications(id) }

    private fun name(number: Int) = compose.onNode(hasSetTextAction() and hasContentDescription("약 $number, 약 이름"))

    private fun quantity(index: Int) = compose.onAllNodes(hasSetTextAction() and hasContentDescription("1회 투약량"))[index]

    private fun text(value: String) = compose.onNodeWithText(value)

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
}
