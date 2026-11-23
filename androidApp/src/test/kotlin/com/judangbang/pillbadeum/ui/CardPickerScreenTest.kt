package com.judangbang.pillbadeum.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.CardEntryFields
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.PersonChange
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.SaveCardEntryResult
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardPickerScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private lateinit var core: DidITakeCore
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var savedZone: TimeZone
    private lateinit var self: String
    private val pickerTitle = "누구의 건강 카드를 볼까요?"
    private val mother = "가상 엄마"
    private val daughter = "가상 딸"
    private val allergy = "가상 알레르기"
    private val allergyLine = "알레르기, $allergy"
    private val sameNameNotice = "이름이 같은 대상자가 있어요. 대상자 관리에서 이름을 바꾸면 헷갈리지 않아요"

    @Before
    fun setup(): Unit = runBlocking {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
        app.clock.now = at("2026-11-12T09:00")
        core = app.container.core()
        core.settings.markIntroSeen()
        self = core.people.list().single().id
    }

    @After
    fun finish() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        RuntimeEnvironment.setFontScale(1f)
        TimeZone.setDefault(savedZone)
    }

    @Test
    fun home_card_is_before_history_in_the_header(): Unit = runBlocking {
        seedHome()
        open()
        val card = bounds(text(Labels.HEALTH_CARD))
        val history = bounds(text("지난 기록"))
        val settings = bounds(text("설정"))
        assertEquals(history.center.y, card.center.y, 1f)
        assertEquals(history.center.y, settings.center.y, 1f)
        assertTrue(card.right <= history.left && history.right <= settings.left)
        render("HomeCard")
    }

    @Test
    fun home_card_200_moves_under_the_date_when_the_header_is_too_narrow(): Unit = runBlocking {
        seedHome()
        open(scale = 2f)
        val card = bounds(text(Labels.HEALTH_CARD))
        val date = bounds(text("11월 12일 목요일"))
        val history = bounds(text("지난 기록"))
        val settings = bounds(text("설정"))
        assertTrue(card.top >= date.bottom)
        assertTrue(card.bottom <= bounds(text("오늘")).top)
        assertEquals(history.center.y, settings.center.y, 1f)
        render("HomeCard200")
        tap(Labels.HEALTH_CARD)
        waitForText(pickerTitle)
    }

    @Test
    fun tc099_archived_card_retains_its_entry_and_restore_returns_the_person_to_the_picker(): Unit = runBlocking {
        val person = addPerson(mother)
        addItem(person, "가상 아침약")
        val entry = core.saveCardEntry.add(person, core.saveCardEntry.newEntryId(), CardEntryFields.Allergy(allergy))
        assertTrue(entry is SaveCardEntryResult.Saved)
        assertEquals(PersonChange.Saved, core.people.archive(person))
        open()
        tap(Labels.HEALTH_CARD)
        waitForText(Labels.healthCardTitle("나"))
        text(pickerTitle).assertDoesNotExist()
        val emptyTitle = "아직 적은 내용이 없어요."
        waitForText(emptyTitle)
        text(emptyTitle).assertIsDisplayed()
        back()
        openPeople(active = 1)
        val line = archivedLine(mother)
        assertEquals("건강 카드 보기", line.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        line.performScrollTo().performTouchInput { click() }
        waitForDesc(allergyLine)
        text(Labels.CARD_ARCHIVED_HINT).assertIsDisplayed()
        back()
        waitForText("대상자 추가")
        desc("$mother 되살리기").performScrollTo().performTouchInput { click() }
        waitForDesc("$mother, 약 1개")
        desc(allergyLine).assertDoesNotExist()
        back()
        waitForText("설정")
        back()
        tap(Labels.HEALTH_CARD)
        waitForText(pickerTitle)
        assertTrue(bounds(cardOption("나")).top < bounds(cardOption(mother)).top)
        cardOption(mother).performClick()
        waitForDesc(allergyLine)
        text(Labels.CARD_ARCHIVED_HINT).assertDoesNotExist()
        back()
        waitForText(Labels.HEALTH_CARD)
        text(pickerTitle).assertDoesNotExist()
    }

    @Test
    fun tc118_picker_is_at_the_bottom_and_closes_to_home(): Unit = runBlocking {
        seedHome()
        val archived = addPerson(daughter)
        assertEquals(PersonChange.Saved, core.people.archive(archived))
        open()
        tap(Labels.HEALTH_CARD)
        waitForText(pickerTitle)
        assertTrue(bounds(text(pickerTitle)).top > bounds(text("오늘")).bottom)
        assertTrue(bounds(cardOption("나")).top < bounds(cardOption(mother)).top)
        cardOption(daughter).assertDoesNotExist()
        text(sameNameNotice).assertDoesNotExist()
        render("HomeCardPick")
        tap("닫기")
        text(pickerTitle).assertDoesNotExist()
        tap(Labels.HEALTH_CARD)
        waitForText(pickerTitle)
        tapDialogBack()
        text(pickerTitle).assertDoesNotExist()
    }

    @Test
    fun tc099_and_tc118_same_name_options_read_items_in_home_order_and_open_the_selected_card(): Unit = runBlocking {
        val first = addPerson(mother)
        val second = addPerson(mother)
        addItem(first, "가상 아침약")
        assertTrue(core.archiveIntake(addItem(first, "가상 보관약")))
        addItem(first, "가상 저녁약")
        addItem(second, "가상 혈압약")
        open()
        tap(Labels.HEALTH_CARD)
        waitForText(pickerTitle)
        val firstOption = cardOption(mother, "항목: 가상 아침약 외 1")
        val secondOption = cardOption(mother, "항목: 가상 혈압약")
        assertTrue(bounds(firstOption).top < bounds(secondOption).top)
        assertTrue(bounds(secondOption).bottom <= bounds(text(sameNameNotice)).top)
        assertTrue(bounds(text(sameNameNotice)).bottom <= bounds(text("닫기")).top)
        render("HomeCardPickSame")
        secondOption.performClick()
        waitForText("가상 혈압약 · 매일 (약 이름 미입력)")
        text("가상 아침약 · 매일 (약 이름 미입력)").assertDoesNotExist()
    }

    @Test
    fun tc118_picker_redraws_when_a_person_is_archived_and_restored_while_open(): Unit = runBlocking {
        val first = addPerson(mother)
        val second = addPerson(mother)
        addItem(first, "가상 아침약")
        open()
        tap(Labels.HEALTH_CARD)
        waitForText(pickerTitle)
        cardOption(mother, "항목 없음").assertIsDisplayed()
        assertEquals(PersonChange.Saved, core.people.archive(second))
        waitForDesc(Labels.healthCardTitle(mother))
        cardOption(mother, "항목 없음").assertDoesNotExist()
        text(sameNameNotice).assertDoesNotExist()
        assertEquals(PersonChange.Saved, core.people.archive(first))
        compose.waitUntil(10_000) { !hasDesc(Labels.healthCardTitle(mother)) }
        cardOption("나").assertIsDisplayed()
        assertEquals(PersonChange.Saved, core.people.restore(second))
        waitForDesc(Labels.healthCardTitle(mother))
        assertEquals(PersonChange.Saved, core.people.restore(first))
        waitForDesc("${Labels.healthCardTitle(mother)}, 항목 없음")
        assertTrue(bounds(cardOption(mother, "항목: 가상 아침약")).top < bounds(cardOption(mother, "항목 없음")).top)
    }

    @Test
    fun people_card_and_restore_are_separate_targets_at_normal_size(): Unit = runBlocking {
        seedPeople()
        open()
        openPeople(active = 2)
        assertArchivedLayout(stacked = false)
        render("PeopleCard")
    }

    @Test
    fun people_card_200_stacks_restore_below_the_card_target(): Unit = runBlocking {
        seedPeople()
        open(scale = 2f)
        openPeople(active = 2)
        assertArchivedLayout(stacked = true)
        render("PeopleCard200")
    }

    @Test
    fun person_edit_card_is_above_the_name_and_back_restores_the_form_draft(): Unit = runBlocking {
        seedHome()
        open()
        openPeople(active = 2)
        desc("$mother, 약 2개").performClick()
        waitForText("대상자 수정")
        val name = compose.onNode(hasSetTextAction() and hasContentDescription("이름"))
        val button = text(Labels.healthCardTitle(mother))
        assertTrue(bounds(button).bottom <= bounds(name).top)
        render("PersonEditCard")
        name.performTextReplacement("가상 수정 중")
        button.performClick()
        waitForText(Labels.CARD_MEDICATIONS)
        controller.recreate()
        waitForText(Labels.CARD_MEDICATIONS)
        back()
        waitForText("대상자 수정")
        name.assertTextContains("가상 수정 중")
        back()
        waitForText("대상자 추가")
        desc("$mother, 약 2개").assertIsDisplayed()
    }

    private suspend fun addPerson(name: String): String {
        app.clock.now += 1.seconds
        return core.people.add(name, PersonColors.PALETTE[1]).id
    }

    private suspend fun addItem(person: String, label: String): String =
        (core.saveIntake.create(IntakeDraft(person, label)) as SaveIntakeResult.Saved).intakeId

    private suspend fun seedHome(): String {
        val person = addPerson(mother)
        addItem(self, "가상 아침약")
        addItem(self, "가상 비타민")
        addItem(person, "가상 혈압약")
        addItem(person, "가상 저녁약")
        return person
    }

    private suspend fun seedPeople() {
        seedHome()
        val archived = addPerson(daughter)
        addItem(archived, "가상 저녁약")
        assertEquals(PersonChange.Saved, core.people.archive(archived))
    }

    private fun open(scale: Float = 1f) {
        RuntimeEnvironment.setFontScale(scale)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        waitForText(Labels.HEALTH_CARD)
    }

    private fun openPeople(active: Int) {
        tap("설정")
        val title = "대상자 관리, ${active}명"
        waitForDesc(title)
        desc(title).performScrollTo().performClick()
        waitForText("대상자 추가")
    }

    private fun assertArchivedLayout(stacked: Boolean) {
        val row = bounds(archivedLine(daughter).performScrollTo())
        val restore = bounds(desc("$daughter 되살리기").assert(hasClickAction()))
        if (stacked) {
            assertTrue(row.bottom <= restore.top)
            assertEquals(row.left, restore.left, 1f)
        } else {
            assertTrue(row.right <= restore.left)
            assertEquals(row.center.y, restore.center.y, 1f)
        }
    }

    private fun archivedLine(name: String) =
        desc("$name, 약 1개 · 지난 기록은 남아요").assert(hasClickAction())

    private fun cardOption(name: String, detail: String? = null) =
        desc(listOfNotNull(Labels.healthCardTitle(name), detail).joinToString(", "))

    private fun tapDialogBack() {
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        dialog.onBackPressed()
        compose.waitForIdle()
    }

    private fun text(value: String) = compose.onNodeWithText(value)
    private fun desc(value: String) = compose.onNodeWithContentDescription(value)
    private fun bounds(node: SemanticsNodeInteraction): Rect = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    private fun tap(value: String) { text(value).performClick() }
    private fun back() { controller.get().onBackPressedDispatcher.onBackPressed() }
    private fun waitForText(value: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun hasDesc(value: String) = compose.onAllNodesWithContentDescription(value).fetchSemanticsNodes().isNotEmpty()
    private fun waitForDesc(value: String) = compose.waitUntil(10_000) { hasDesc(value) }

    private fun render(name: String) {
        compose.assertTargetsAtLeast48dp()
        compose.saveScreen(name, controller.get())
    }
}
