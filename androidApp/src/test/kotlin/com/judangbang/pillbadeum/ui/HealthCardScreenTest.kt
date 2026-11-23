package com.judangbang.pillbadeum.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.AndroidPhone
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.ProtectCardRecents
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.data.SettingEntity
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.CardEntryFields
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.IntakeMedicationDraft
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.PersonChange
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.SaveCardEntryResult
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** SCR-011 and SCR-012 through the app router, with the Room database and a fixed Seoul clock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HealthCardScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val core: DidITakeCore get() = runBlocking { app.container.core() }
    private lateinit var controller: ActivityController<CardTestActivity>
    private val personName = "가상 엄마"
    private val title get() = "${personName}의 건강 카드"
    private val procedure = CardEntryFields.Procedure("쓸개 수술", "2019")
    private val allergy = CardEntryFields.Allergy("페니실린 계열", "두드러기")
    private val medication = "가상정 10mg 1정 · 매일 아침"

    @After
    fun finish() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun tc113_card_renders_sections_actions_and_unnamed_medications() = runBlocking {
        val person = createPerson()
        seedCard(person)
        open(person, height = 1600)
        assertCardLayout(stacked = false)
        text("비타민 · 매일 (약 이름 미입력)").assertIsDisplayed()
        text("마지막으로 고친 날 2026-11-23").assertIsDisplayed()
        render("Card")
    }

    @Test
    fun tc113_card200_stacks_actions_add_and_delete() = runBlocking {
        val person = createPerson()
        seedCard(person)
        open(person, height = 3200, scale = 2f)
        assertCardLayout(stacked = true)
        render("Card200")
    }

    @Test
    fun tc113_empty_card_omits_the_three_actions() = runBlocking {
        val me = core.people.list().single()
        open(me.id, name = me.name, height = 840)
        assertEmptyCard()
        render("CardEmpty")
    }

    @Test
    fun tc113_archived_nonempty_card_keeps_entries_and_has_no_medication_links() = runBlocking {
        val person = createPerson()
        seedCard(person)
        assertEquals(PersonChange.Saved, core.people.archive(person))
        open(person, height = 1660)
        text("보관됨").assertIsDisplayed()
        text("보관한 대상자예요. 카드는 그대로 쓸 수 있어요").assertIsDisplayed()
        text(medication).assert(!hasClickAction())
        text("약은 대상자를 되살린 뒤 복용 항목에서 고쳐요").assertIsDisplayed()
        text("보여 주기").assertIsEnabled()
        text("응급 대비 문장").assertIsEnabled()
        render("CardArchived")

        add("알레르기")
        field("원인").performTextReplacement("예시 음식")
        save()
        waitForCard()
        assertTrue(entries(person).any { it.fields == CardEntryFields.Allergy("예시 음식") })
    }

    @Test
    fun tc113_archived_empty_card_prefers_empty_and_keeps_only_the_badge() = runBlocking {
        val person = createPerson()
        assertEquals(PersonChange.Saved, core.people.archive(person))
        open(person, height = 840)
        text("보관됨").assertIsDisplayed()
        text("보관한 대상자예요. 카드는 그대로 쓸 수 있어요").assertDoesNotExist()
        assertEmptyCard()
        render("CardArchivedEmpty")
    }

    @Test
    fun tc113_procedure_only_card_disables_emergency_text() = runBlocking {
        val person = createPerson()
        seedEntry(person, procedure)
        open(person, height = 1080)
        text("보여 주기").assertIsEnabled()
        text("내보내기").assertIsEnabled()
        text("응급 대비 문장").assertIsNotEnabled()
        text("응급 대비 문장에 넣을 내용이 없어요").assertIsDisplayed()
        render("CardProcedureOnly")
    }

    @Test
    fun tc113_card_load_failure_retries_and_resume_reloads_the_card() = runBlocking {
        val person = createPerson()
        val intent = Intent(app, CardTestActivity::class.java)
            .putExtra("person", person)
            .putExtra("failFirstOpen", true)
        controller = Robolectric.buildActivity(CardTestActivity::class.java, intent).setup()
        desc(Labels.LOADING).assertIsDisplayed()
        controller.get().firstOpenGate.complete(Unit)
        waitFor(Labels.CARD_LOAD_FAILED)
        tap("다시 시도")
        waitForCard()
        assertEmptyCard()

        controller.pause()
        seedEntry(person, allergy)
        controller.resume()
        waitFor("보여 주기")
        desc("알레르기, 페니실린 계열, 반응 두드러기").assertExists()
    }

    @Test
    fun tc090_full_section_displays_the_limit_and_removing_an_entry_restores_add() = runBlocking {
        val person = createPerson()
        repeat(20) { seedEntry(person, procedure.copy(name = "가상 수술 ${it + 1}")) }
        open(person)
        desc("수술·시술 + 더하기").assertDoesNotExist()
        text("20칸까지 적을 수 있어요").performScrollTo().assertIsDisplayed()
        desc("가상 수술 1 칸 지우기").performScrollTo().performClick()
        text("지우기").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("수술·시술 + 더하기").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(19, entries(person).size)
    }

    @Test
    fun tc100_and_tc113_changed_notice_is_closed_by_acknowledgment_and_reopens_on_edit() = runBlocking {
        val person = createPerson()
        seedCard(person)
        textCopyThenEdit(person)
        open(person, height = 1850)
        val changed = "카드가 바뀌었어요. 휴대폰 긴급 정보에 넣은 문장도 다시 넣어 주세요"
        text(changed).assertIsDisplayed()
        compose.onAllNodesWithText("응급 대비 문장")[0].performClick()
        assertEquals(null, core.db.healthCardDao().findByPerson(person)?.staleNoticeClosedAt)
        text(changed).assertIsDisplayed()
        render("CardRecopy")
        text("다 넣었어요").performClick()
        waitUntilGone(changed)
        assertEquals(app.clock.now.toEpochMilliseconds(), core.db.healthCardDao().findByPerson(person)?.staleNoticeClosedAt)

        add("알레르기")
        field("원인").performTextReplacement("예시 음식")
        save()
        waitFor(changed)
        assertEquals(null, core.db.healthCardDao().findByPerson(person)?.staleNoticeClosedAt)
    }

    @Test
    fun tc100_restored_notice_uses_its_own_text_and_empty_notice_has_only_close() = runBlocking {
        val person = createPerson()
        seedEntry(person, allergy)
        textCopyThenEdit(person)
        val cards = core.db.healthCardDao()
        val copied = checkNotNull(cards.findByPerson(person))
        core.db.settingDao().upsert(SettingEntity(SettingKeys.RESTORED_AT, copied.editedAt.toString()))
        open(person, height = 1080)
        val restored = "백업에서 복원했어요. 이 휴대폰의 긴급 정보에도 문장을 넣어 주세요"
        val empty = "카드 내용이 없어요. 휴대폰 긴급 정보에 넣은 문장도 지워 주세요"
        text(restored).assertIsDisplayed()
        text("다 넣었어요").performClick()
        waitUntilGone(restored)
        tap("카드 모두 지우기")
        text("지우기").performClick()
        waitFor(empty)
        text("다 넣었어요").assertDoesNotExist()
        assertEmptyCard()
        text("닫기").performClick()
        waitUntilGone(empty)
        assertNotNull(cards.findByPerson(person)?.staleNoticeClosedAt)
    }

    @Test
    fun tc114_card_entry_renders_with_no_counter_for_a_short_input() {
        renderProcedure(stacked = false)
    }

    @Test
    fun tc114_card_entry200_stacks_date_choices_with_save_reachable() {
        renderProcedure(stacked = true)
    }

    @Test
    fun tc090_and_tc114_guardian_required_phone_error_keeps_the_other_fields() = runBlocking {
        val person = createPerson()
        open(person)
        add("보호자 연락처")
        field("이름").performTextReplacement("홍가상")
        field("관계").performTextReplacement("딸")
        save()
        text("전화번호를 입력해 주세요").assertIsDisplayed()
        field("이름").assert(hasText("홍가상"))
        field("관계").assert(hasText("딸"))
        assertTrue(entries(person).isEmpty())
        render("CardEntryError")
    }

    @Test
    fun tc090_two_entries_save_trimmed_and_editing_keeps_the_original_id() = runBlocking {
        val person = createPerson()
        open(person)
        add("알레르기")
        field("원인").performTextReplacement("   ")
        save()
        text("원인을 입력해 주세요").assertIsDisplayed()
        field("원인").performTextReplacement("  페니실린\n계열  ")
        save()
        waitForCard()
        add("수술·시술")
        field("이름").performTextReplacement("쓸개 수술")
        field("시기, 연도").performTextReplacement("2019")
        text("저장").performSemanticsAction(SemanticsActions.OnClick) { save ->
            save()
            save()
        }
        waitForCard()
        assertEquals(listOf(CardEntryFields.Allergy("페니실린 계열"), procedure), entries(person).map { it.fields })
        val id = entries(person).last().id
        desc("수술·시술, 쓸개 수술, 2019년").performScrollTo().performClick()
        waitFor("수술·시술 고치기")
        field("이름").assert(hasText("쓸개 수술")).performTextReplacement("가상 수술")
        save()
        waitForCard()
        assertEquals(id, entries(person).last().id)
        assertEquals(procedure.copy(name = "가상 수술"), entries(person).last().fields)
    }

    @Test
    fun tc090_and_tc114_date_bounds_use_the_base_zone_and_preserve_partial_inputs() = runBlocking {
        val person = createPerson()
        open(person, height = 1000)
        add("수술·시술")
        field("이름").performTextReplacement("쓸개 수술")
        tap("연·월·일")
        field("시기, 연도").performTextReplacement("2026")
        field("시기, 월").performTextReplacement("11")
        field("시기, 일").performTextReplacement("24")
        save()
        text("오늘보다 뒤의 날은 적을 수 없어요").assertIsDisplayed()
        assertTrue(entries(person).isEmpty())
        render("CardEntryFutureDate")

        tap("연도만")
        field("시기, 연도").performTextReplacement("1899")
        save()
        text("1900년부터 적을 수 있어요").assertIsDisplayed()
        render("CardEntryEarlyDate")
        tap("연·월·일")
        field("시기, 연도").assert(hasText("1899")).performTextReplacement("2026")
        field("시기, 월").assert(hasText("11"))
        field("시기, 일").assert(hasText("24")).performTextReplacement("23")
        save()
        waitForCard()
        add("수술·시술")
        field("이름").performTextReplacement("예시 수술")
        field("시기, 연도").performTextReplacement("1900")
        save()
        waitForCard()
        assertEquals(listOf("2026-11-23", "1900"), entries(person).map { (it.fields as CardEntryFields.Procedure).performedOn })
    }

    @Test
    fun tc114_counter_at_45_code_points_and_paste_at_50_are_rendered_separately() = runBlocking {
        val person = createPerson()
        open(person)
        add("알레르기")
        val cause = field("원인")
        cause.performTextReplacement("가".repeat(43) + "🌕")
        text("/50", substring = true).assertDoesNotExist()
        cause.performTextReplacement("가".repeat(44) + "🌕")
        text("45/50").assertIsDisplayed()
        cause.performTextReplacement("가".repeat(47) + "🌕")
        text("48/50").assertIsDisplayed()
        render("CardEntryCounter")
        val filled = "가".repeat(49) + "🌕"
        cause.performTextReplacement(filled + "나")
        cause.assert(hasText(filled))
        text("50자까지 적을 수 있어요").assertIsDisplayed()
        render("CardEntryPasteLimit")
        field("반응").performTextReplacement("예시 첫 줄\n예시 둘째 줄")
        save()
        waitForCard()
        assertEquals(CardEntryFields.Allergy(filled, "예시 첫 줄\n예시 둘째 줄"), entries(person).single().fields)
    }

    @Test
    fun tc114_editor_recreation_keeps_input_and_deleted_entry_returns_to_card() = runBlocking {
        val person = createPerson()
        val id = seedEntry(person, procedure)
        open(person)
        desc("수술·시술, 쓸개 수술, 2019년").performScrollTo().performClick()
        field("이름").performTextReplacement("예시 수술")
        controller.recreate()
        waitFor("수술·시술 고치기")
        field("이름").assert(hasText("예시 수술"))
        core.deleteCardEntries.one(person, procedure.kind, id, confirmed = true)
        save()
        waitFor(Labels.CARD_ENTRY_GONE)
        waitForCard()
        assertTrue(entries(person).isEmpty())
    }

    @Test
    fun tc114_deleted_entry_is_detected_when_reloading_after_recreation() = runBlocking {
        val person = createPerson()
        val id = seedEntry(person, allergy)
        open(person)
        desc("알레르기, 페니실린 계열, 반응 두드러기").performScrollTo().performClick()
        waitFor("알레르기 고치기")
        core.deleteCardEntries.one(person, allergy.kind, id, confirmed = true)
        controller.recreate()
        waitFor(Labels.CARD_ENTRY_GONE)
        waitForCard()
    }

    @Test
    fun tc091_single_delete_can_be_cancelled_and_only_removes_the_confirmed_entry() = runBlocking {
        val person = createPerson()
        seedEntry(person, allergy)
        seedEntry(person, procedure)
        open(person)
        val delete = desc("페니실린 계열 칸 지우기")
        delete.performScrollTo().performClick()
        text("이 칸을 지울까요?").assertIsDisplayed()
        text("지운 칸은 되살릴 수 없어요").assertIsDisplayed()
        text("취소").performClick()
        assertEquals(listOf(allergy, procedure), entries(person).map { it.fields })
        delete.performClick()
        text("지우기").performClick()
        compose.waitUntil(10_000) { entries(person).size == 1 }
        assertEquals(procedure, entries(person).single().fields)
        text("되살리기").assertDoesNotExist()
    }

    @Test
    fun tc092_clear_preserves_intakes_medications_judgments_and_another_person() = runBlocking {
        val person = createPerson()
        val me = core.people.list().first { it.id != person }
        val items = (1..2).map { createIntake(person, "가상약 $it", SlotLabel.MORNING) }
        seedEntry(me.id, allergy)
        val first = items.first()
        for (day in 1..10) {
            app.clock.now = at("2026-11-${day.toString().padStart(2, '0')}T09:00")
            // The intakes begin before this date (see createIntake).
            assertTrue(core.record(first, DoseSource.APP, null) is RecordResult.Recorded)
        }
        setToday()
        seedEntry(person, allergy)
        seedEntry(person, procedure)
        seedEntry(person, CardEntryFields.Guardian("홍가상", "010-0000-0000"))
        val judgments = core.db.doseRecordDao().listByIntake(first)
        val medications = items.map { core.saveIntake.loadMedications(it) }
        open(person)
        tap("카드 모두 지우기")
        text("복용 항목·약·기록은 그대로예요", substring = true).assertIsDisplayed()
        text("취소").performClick()
        assertEquals(3, entries(person).size)
        tap("카드 모두 지우기")
        text("지우기").performClick()
        waitUntilGone("카드 모두 지우기")
        assertTrue(entries(person).isEmpty())
        assertEquals(listOf(allergy), entries(me.id).map { it.fields })
        assertTrue(items.all { core.db.intakeDao().findById(it) != null })
        assertEquals(medications, items.map { core.saveIntake.loadMedications(it) })
        assertEquals(10, judgments.size)
        assertEquals(judgments, core.db.doseRecordDao().listByIntake(first))
        text("지금 먹는 약").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tc103_item_save_returns_to_the_same_card() = runBlocking {
        openItemFromCard()
        field("약 1, 약 이름").performScrollTo().performTextReplacement("보기정")
        save()
        waitForCard()
        text("보기정 1정 · 매일 아침").assertIsDisplayed()
    }

    @Test
    fun tc103_item_close_returns_to_the_same_card() = runBlocking {
        openItemFromCard()
        desc("닫기").performClick()
        waitForCard()
        text(medication).assertIsDisplayed()
    }

    @Test
    fun tc103_item_back_returns_to_the_same_card_after_recreation() = runBlocking {
        openItemFromCard()
        controller.recreate()
        waitFor("항목 수정")
        back()
        waitForCard()
        text(medication).assertIsDisplayed()
    }

    @Test
    fun tc103_item_gone_returns_to_the_same_card_and_reloads() = runBlocking {
        val item = openItemFromCard()
        assertTrue(core.archiveIntake(item))
        save()
        waitFor(Labels.GONE)
        waitForCard()
        text(medication).assertDoesNotExist()
    }

    @Test
    fun tc103_item_quiet_hours_confirmation_returns_to_the_same_card() = runBlocking {
        openItemFromCard()
        tap("자기 전")
        desc("알림 받기").performScrollTo().performClick()
        save()
        waitFor(Labels.ITEM_SAVED)
        text("확인").performClick()
        waitForCard()
        text("가상정 10mg 1정 · 매일 자기 전").assertIsDisplayed()
    }

    @Test
    fun r16_card_and_editor_suppress_recents_until_returning_home() = runBlocking {
        val person = createPerson()
        open(person)
        assertEquals(listOf(false), controller.get().recentsEnabled)
        add("알레르기")
        assertEquals(listOf(false), controller.get().recentsEnabled)
        assertEquals(0, controller.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        back()
        waitForCard()
        assertEquals(listOf(false), controller.get().recentsEnabled)
        back()
        waitFor("지난 기록")
        assertEquals(listOf(false, true), controller.get().recentsEnabled)
    }

    private fun renderProcedure(stacked: Boolean) = runBlocking {
        val person = createPerson()
        open(person, height = if (stacked) 820 else 800, scale = if (stacked) 2f else 1f)
        add("수술·시술")
        text("받은 수술이나 시술과 그때. 예: 쓸개 수술, 2019년").assertIsDisplayed()
        field("이름").performTextReplacement("쓸개 수술")
        field("시기, 연도").performTextReplacement("2019")
        field("이름").performClick()
        text("5/50").assertDoesNotExist()
        val year = bounds(text("연도만"))
        val month = bounds(text("연·월"))
        val day = bounds(text("연·월·일"))
        if (stacked) {
            assertTrue(month.top >= year.bottom && day.top >= month.bottom)
        } else {
            assertEquals(year.top, month.top, 1f)
            assertTrue(month.left >= year.right && day.left >= month.right)
        }
        text("저장").assertIsDisplayed()
        render(if (stacked) "CardEntry200" else "CardEntry")
    }

    private fun assertCardLayout(stacked: Boolean) {
        val primary = bounds(text("보여 주기"))
        val emergency = bounds(text("응급 대비 문장"))
        val export = bounds(text("내보내기"))
        val allergyTitle = bounds(text("알레르기"))
        val add = bounds(desc("알레르기 + 더하기"))
        val allergyRow = bounds(desc("알레르기, 페니실린 계열, 반응 두드러기"))
        val delete = bounds(desc("페니실린 계열 칸 지우기"))
        assertTrue(emergency.top >= primary.bottom)
        if (stacked) {
            assertTrue(export.top >= emergency.bottom)
            assertTrue(add.top >= allergyTitle.bottom)
            assertTrue(delete.top >= allergyRow.bottom)
        } else {
            assertEquals(emergency.top, export.top, 1f)
            assertTrue(add.left >= allergyTitle.right)
            assertTrue(delete.left >= allergyRow.right)
        }
        assertTrue(bounds(text("지금 먹는 약")).top > export.bottom)
        text("카드 모두 지우기").assertIsDisplayed()
    }

    private fun assertEmptyCard() {
        text("아직 적은 내용이 없어요.").assertIsDisplayed()
        text("아래에서 칸을 적거나, 복용 항목에 약을 적으면 여기에 보여요").assertIsDisplayed()
        text("보여 주기").assertDoesNotExist()
        text("응급 대비 문장").assertDoesNotExist()
        text("내보내기").assertDoesNotExist()
        text("카드 모두 지우기").assertDoesNotExist()
        desc("보호자 연락처 + 더하기").assertExists()
    }

    private suspend fun createPerson(): String {
        setToday()
        return core.people.add(personName, PersonColors.PALETTE[1]).id
    }

    private fun setToday() {
        app.clock.now = at("2026-11-23T09:00")
    }

    private suspend fun seedCard(person: String) {
        createIntake(person, "아침약", SlotLabel.MORNING, medicines = listOf(
            IntakeMedicationDraft("가상정 10mg", "1정"), IntakeMedicationDraft("보기정 500mg", "반 알"),
        ))
        createIntake(person, "저녁약", SlotLabel.EVENING)
        createIntake(person, "비타민", null, medicines = emptyList())
        seedEntry(person, allergy)
        seedEntry(person, CardEntryFields.Condition("고혈압", "2015"))
        seedEntry(person, CardEntryFields.Condition("골다공증", "2023-03"))
        seedEntry(person, procedure)
        seedEntry(person, CardEntryFields.CareProvider("가상내과의원", "내과", "이가상", "02-000-0000"))
        seedEntry(person, CardEntryFields.Guardian("홍가상", "010-0000-0000", "딸"))
    }

    private suspend fun createIntake(
        person: String,
        name: String,
        slot: SlotLabel?,
        medicines: List<IntakeMedicationDraft> = listOf(IntakeMedicationDraft("가상정 10mg", "1정")),
    ): String {
        val oldTime = app.clock.now
        app.clock.now = at("2026-10-31T08:00")
        return try {
            val draft = IntakeDraft(person, name, slotLabel = slot, medications = medicines)
            (core.saveIntake.create(draft) as SaveIntakeResult.Saved).intakeId
        } finally {
            app.clock.now = oldTime
        }
    }

    private suspend fun seedEntry(person: String, fields: CardEntryFields): String =
        (core.saveCardEntry.add(person, core.saveCardEntry.newEntryId(), fields) as SaveCardEntryResult.Saved).id

    private suspend fun textCopyThenEdit(person: String) {
        val original = entries(person).first()
        app.clock.now = at("2026-11-20T09:00")
        core.healthCardNoticeActions.recordCopy(person)
        setToday()
        core.saveCardEntry.edit(person, original.id, original.fields)
    }

    private fun entries(person: String) = runBlocking { checkNotNull(core.loadHealthCard(person)).entries }

    private suspend fun openItemFromCard(): String {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val person = createPerson()
        val item = createIntake(person, "아침약", SlotLabel.MORNING)
        open(person)
        tap(medication)
        waitFor("항목 수정")
        assertFalse(controller.get().isFinishing)
        return item
    }

    private fun open(person: String, name: String = personName, height: Int = 800, scale: Float = 1f) {
        RuntimeEnvironment.setQualifiers("+h${height}dp")
        RuntimeEnvironment.setFontScale(scale)
        val intent = Intent(app, CardTestActivity::class.java).putExtra("person", person)
        controller = Robolectric.buildActivity(CardTestActivity::class.java, intent).setup()
        waitFor(Labels.healthCardTitle(name))
        waitForCardContent()
    }

    private fun text(value: String, substring: Boolean = false) = compose.onNodeWithText(value, substring = substring)
    private fun desc(value: String) = compose.onNodeWithContentDescription(value)
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasContentDescription(label))
    private fun bounds(node: SemanticsNodeInteraction): Rect = node.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    private fun tap(value: String) { text(value).performScrollTo().performClick() }
    private fun add(kind: String) { desc("$kind + 더하기").performScrollTo().performClick(); waitFor("$kind 적기") }
    private fun save() { text("저장").performClick() }
    private fun back() { controller.get().onBackPressedDispatcher.onBackPressed() }
    private fun waitForCard() { waitFor(title); waitForCardContent() }
    private fun waitForCardContent() = compose.waitUntil(10_000) {
        compose.onAllNodesWithContentDescription("알레르기 + 더하기").fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitFor(value: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun waitUntilGone(value: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isEmpty() }

    private fun render(name: String) {
        compose.assertTargetsAtLeast48dp()
        compose.saveScreen(name, controller.get())
    }
}

/** Installs the real App and Android privacy effect, opening the card without the TASK-061 entry point. */
class CardTestActivity : ComponentActivity() {
    val recentsEnabled = mutableListOf<Boolean>()
    val firstOpenGate = CompletableDeferred<Unit>()
    private var resumeCount by mutableIntStateOf(0)
    private var attemptedOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TestApp).container
        val phone = AndroidPhone(this) {}
        setContent {
            App(
                open = {
                    if (intent.getBooleanExtra("failFirstOpen", false) && !attemptedOpen) {
                        firstOpenGate.await()
                        attemptedOpen = true
                        error("fixture: database opening failed")
                    }
                    container.core()
                },
                onDataChanged = { container.afterWrite() },
                phone = phone,
                backHandler = { enabled, action -> BackHandler(enabled, action) },
                refreshKey = resumeCount,
                initialHealthCardPersonId = intent.getStringExtra("person"),
                protectCardRecents = { visible -> ProtectCardRecents(visible) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    override fun setRecentsScreenshotEnabled(enabled: Boolean) {
        super.setRecentsScreenshotEnabled(enabled)
        recentsEnabled.add(enabled)
    }
}
