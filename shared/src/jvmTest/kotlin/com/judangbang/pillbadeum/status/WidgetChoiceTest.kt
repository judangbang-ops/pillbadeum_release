package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.ManagePeople
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.SaveIntake
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The widget's person choice (TASK-012): what a widget shows for each choice (SPEC-005, REQ-025), when it asks to be
 * set up again (REQ-079, SPEC-027, supplement R-3) and the person picker's lines (SCR-002, 09 WidgetPick).
 */
class WidgetChoiceTest {
    private val fx = DbFixture(start = seoul("2026-11-12T07:00"))
    private val load = LoadStatusBoard(fx.db, fx.clock)
    private val people = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val save = SaveIntake(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun item(personId: String, name: String): String =
        assertIs<SaveIntakeResult.Saved>(save.create(IntakeDraft(personId, name))).intakeId

    /** 나: 아침약 taken at 08:02 and 비타민D. 엄마: 혈압약. Seen at 09:30. Returns 엄마's id. */
    private suspend fun family(): String {
        val me = people.ensureDefault().single()
        val morning = item(me.id, "아침약")
        item(me.id, "비타민D")
        val mom = people.add("엄마", PersonColors.PALETTE[1])
        item(mom.id, "혈압약")
        fx.at("2026-11-12T08:02")
        val key = load().items.single { it.intakeId == morning }.window.periodKey
        assertIs<RecordResult.Recorded>(fx.record(morning, renderedKey = key, source = DoseSource.APP, kind = DoseKind.TAKEN))
        fx.at("2026-11-12T09:30")
        return mom.id
    }

    @Test
    fun the_choice_is_stored_as_text_and_nothing_stored_is_no_choice() {
        assertEquals("*", WidgetChoice.encode(WidgetChoice.Everyone))
        assertEquals(WidgetChoice.Everyone, WidgetChoice.decode("*"))
        val mom = WidgetChoice.Person("0190f5a2-7c00-7000-8000-000000000001")
        assertEquals(mom, WidgetChoice.decode(WidgetChoice.encode(mom)))
        assertNull(WidgetChoice.decode(null))
        assertNull(WidgetChoice.decode(""))
    }

    @Test
    fun spec005_a_widget_for_mom_shows_only_her_items_and_her_count() = runTest {
        val mom = family()
        val board = load()

        val everyone = WidgetModel.from(board, SEOUL, WidgetChoice.Everyone)
        assertEquals(listOf("아침약", "비타민D", "혈압약"), everyone.buttons.map { it.title })
        assertEquals("1 / 3 완료", everyone.countLabel)

        val moms = WidgetModel.from(board, SEOUL, WidgetChoice.Person(mom))
        assertEquals(listOf("혈압약"), moms.buttons.map { it.title })
        assertEquals("0 / 1" to "완료", moms.countDone to moms.countRest, "only her items are counted (REQ-025)")
        assertEquals("엄마", moms.buttons.single().who, "two people are active, so her name stays on the tile (REQ-018)")
        assertNull(moms.reconfigure)

        val mine = WidgetModel.from(board, SEOUL, WidgetChoice.Person(board.people.first().id))
        assertEquals(listOf("아침약", "비타민D"), mine.buttons.map { it.title })
        assertEquals("1 / 2 완료", mine.countLabel)
    }

    @Test
    fun a_person_without_items_gets_her_own_empty_line() = runTest {
        val me = people.ensureDefault().single()
        item(me.id, "아침약")
        val mom = people.add("엄마", PersonColors.PALETTE[1])

        val model = WidgetModel.from(load(), SEOUL, WidgetChoice.Person(mom.id))
        assertTrue(model.buttons.isEmpty())
        assertEquals("엄마 항목이 없어요", model.emptyText)
        assertEquals(Labels.EMPTY_WIDGET, WidgetModel.from(load(), SEOUL, WidgetChoice.Everyone).emptyText)
    }

    @Test
    fun req079_archiving_the_chosen_person_asks_to_set_the_widget_up_again() = runTest {
        val mom = family()
        people.archive(mom)

        // One person is left, and still the widget does not fall back to everyone (REQ-079, SPEC-027).
        val model = WidgetModel.from(load(), SEOUL, WidgetChoice.Person(mom))
        assertEquals(Labels.RECONFIGURE_ARCHIVED, model.reconfigure)
        assertTrue(model.buttons.isEmpty(), "no tiles")
        assertEquals("" to "", model.countDone to model.countRest, "no count")

        // Brought back, she is shown again: the widget kept its choice.
        people.restore(mom)
        assertEquals(listOf("혈압약"), WidgetModel.from(load(), SEOUL, WidgetChoice.Person(mom)).buttons.map { it.title })
    }

    @Test
    fun r3_a_widget_without_a_choice_shows_everyone_with_one_person_and_asks_from_two() = runTest {
        val me = people.ensureDefault().single()
        item(me.id, "아침약")
        val alone = WidgetModel.from(load(), SEOUL, choice = null)
        assertNull(alone.reconfigure, "one person: that person's items, as before (supplement R-3)")
        assertEquals(listOf("아침약"), alone.buttons.map { it.title })

        people.add("엄마", PersonColors.PALETTE[1])
        val two = WidgetModel.from(load(), SEOUL, choice = null)
        assertEquals(Labels.RECONFIGURE, two.reconfigure, "restored without a choice, two people (REQ-079)")
        assertTrue(two.buttons.isEmpty())
    }

    @Test
    fun the_view_rule_for_every_choice() = runTest {
        val mom = family()
        val active = load().people
        assertEquals(WidgetView.Items(null), WidgetChoice.Everyone.view(active))
        assertEquals(WidgetView.Items(active[1]), WidgetChoice.Person(mom).view(active))
        assertEquals(WidgetView.Reconfigure(archived = false), (null as WidgetChoice?).view(active))
        assertEquals(WidgetView.Reconfigure(archived = true), WidgetChoice.Person("gone").view(active))
        assertEquals(WidgetView.Items(null), (null as WidgetChoice?).view(active.take(1)))
    }

    @Test
    fun scr002_lists_everyone_then_each_person_with_the_items_the_widget_would_show() = runTest {
        val mom = family()
        val options = WidgetPick.options(load())

        assertEquals(listOf("전체", "나", "엄마"), options.map { it.title })
        assertEquals(listOf("나·엄마의 약 3개", "약 2개", "약 1개"), options.map { it.detail })
        assertEquals(listOf(null, "나", "엄"), options.map { it.letter })
        assertEquals(WidgetChoice.Person(mom), options[2].choice)
        assertEquals(PersonColors.palette(PersonColors.PALETTE[1]), options[2].palette)
        assertEquals("전체, 나·엄마의 약 3개", WidgetPick.spoken(options[0]))
        assertEquals("엄마, 약 1개", WidgetPick.spoken(options[2]))

        // An archived person is not offered.
        people.archive(mom)
        assertEquals(listOf("전체", "나"), WidgetPick.options(load()).map { it.title })
    }

    @Test
    fun the_lid_letter_keeps_a_surrogate_pair_whole() {
        assertEquals("엄", WidgetPick.firstLetter("엄마"))
        assertEquals("M", WidgetPick.firstLetter(" Mom"))
        assertEquals("👵", WidgetPick.firstLetter("👵할머니"))
        assertEquals("", WidgetPick.firstLetter(" "))
        assertEquals("전체", WidgetPick.everyoneOnly().title)
        assertEquals("전체", WidgetPick.spoken(WidgetPick.everyoneOnly()))
    }
}
