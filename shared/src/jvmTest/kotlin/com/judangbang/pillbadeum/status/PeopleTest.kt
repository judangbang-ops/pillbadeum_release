package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.ArchiveIntake
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.ManagePeople
import com.judangbang.pillbadeum.usecase.PersonChange
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.SaveIntake
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * SCR-010 (TASK-021): what the people screen reads ([LoadPeople]), the color a new person starts with and the screen's
 * words (09 People, PersonAdd, screens.md SCR-010). Seen on Thursday 11/12 in Seoul.
 */
class PeopleTest {
    private val fx = DbFixture(start = seoul("2026-11-12T09:00"))
    private val people = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val save = SaveIntake(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val archiveItem = ArchiveIntake(fx.db, fx.clock)
    private val load = LoadPeople(fx.db)

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun item(personId: String, name: String): String =
        assertIs<SaveIntakeResult.Saved>(save.create(IntakeDraft(personId, name))).intakeId

    @Test
    fun the_list_counts_the_items_that_are_not_archived_themselves() = runTest {
        // 09 People: 나 약 2개, 엄마 약 2개; 보관됨: 아빠 약 1개.
        val me = people.ensureDefault().single()
        item(me.id, "아침약")
        item(me.id, "비타민D")
        fx.at("2026-11-12T09:05")
        val mom = people.add("엄마", PersonColors.PALETTE[1])
        item(mom.id, "혈압약")
        item(mom.id, "골다공증약")
        assertTrue(archiveItem(item(mom.id, "감기약")), "an item archived on its own is not counted")
        fx.at("2026-11-12T09:10")
        val dad = people.add("아빠", PersonColors.PALETTE[2])
        item(dad.id, "철분제")
        fx.at("2026-11-12T09:20")
        assertEquals(PersonChange.Saved, people.archive(dad.id))

        val view = load()
        assertEquals(listOf("나" to 2, "엄마" to 2), view.active.map { it.person.name to it.items })
        assertEquals(listOf("아빠" to 1), view.archived.map { it.person.name to it.items })
        assertTrue(view.canArchive)
    }

    @Test
    fun archived_people_newest_first_and_the_last_active_person_cannot_be_archived() = runTest {
        val me = people.ensureDefault().single()
        fx.at("2026-11-12T09:05")
        val mom = people.add("엄마", PersonColors.PALETTE[1])
        fx.at("2026-11-12T09:10")
        val dad = people.add("아빠", PersonColors.PALETTE[2])
        fx.at("2026-11-12T10:00")
        people.archive(dad.id)
        fx.at("2026-11-12T11:00")
        people.archive(mom.id)

        val view = load()
        assertEquals(listOf("엄마", "아빠"), view.archived.map { it.person.name }, "most recently archived first")
        assertEquals(listOf(me.id), view.active.map { it.person.id })
        assertFalse(view.canArchive, "REQ-093: one person left")

        // Restored, 엄마 is back among the active people in her old place (oldest first).
        assertEquals(PersonChange.Saved, people.restore(mom.id))
        val after = load()
        assertEquals(listOf("나", "엄마"), after.active.map { it.person.name })
        assertEquals(listOf("아빠"), after.archived.map { it.person.name })
        assertTrue(after.canArchive)
    }

    @Test
    fun a_new_person_starts_with_the_first_color_nobody_has() {
        val (blue, orange, green) = PersonColors.PALETTE
        assertEquals(blue, PersonColors.leastUsed(emptyList()))
        assertEquals(orange, PersonColors.leastUsed(listOf(blue)))
        // 09 PersonAdd: 나 파랑 and 엄마 주황 leave 초록 chosen.
        assertEquals(green, PersonColors.leastUsed(listOf(blue, orange)))
        assertEquals(orange, PersonColors.leastUsed(listOf(blue, green)))
        // An old color counts as the token drawn in its place (#3F7D58 is drawn blue).
        assertEquals(orange, PersonColors.leastUsed(listOf("#3F7D58")))
        // All six taken: the first of the least used.
        assertEquals(blue, PersonColors.leastUsed(PersonColors.PALETTE))
        assertEquals(orange, PersonColors.leastUsed(PersonColors.PALETTE + blue))
        assertEquals(blue, PersonColors.canonical("#3F7D58"))
        assertEquals(orange, PersonColors.canonical(orange.lowercase()))
    }

    @Test
    fun the_words_of_the_list() {
        assertEquals("사람마다 색이 있어요. 위젯과 앱에서 이 색으로 누구 약인지 알아봐요.", Labels.PEOPLE_NOTE)
        assertEquals("약 2개", Labels.personItems(2, archived = false))
        assertEquals("약 1개 · 지난 기록은 남아요", Labels.personItems(1, archived = true))
        assertEquals("엄마를 되살렸어요", Labels.personRestored("엄마"))
    }

    @Test
    fun the_words_of_the_form() {
        val me = person("나", PersonColors.PALETTE[0])
        val mom = person("엄마", PersonColors.PALETTE[1])
        // 09 PersonAdd, with who uses each color.
        assertEquals("이미 쓰는 색: 파랑(나), 주황(엄마). 같은 색도 고를 수 있어요.", Labels.colorsInUse(listOf(me, mom)))
        assertEquals("이미 쓰는 색: 파랑(나·아빠). 같은 색도 고를 수 있어요.", Labels.colorsInUse(listOf(me, person("아빠", PersonColors.PALETTE[0]))))
        assertEquals("이미 쓰는 색: 파랑(나). 같은 색도 고를 수 있어요.", Labels.colorsInUse(listOf(person("나", "#3F7D58"))))
        assertNull(Labels.colorsInUse(emptyList()), "nobody else: no note")
        assertEquals(listOf("파랑", "주황", "초록", "보라", "장미", "청록"), PersonColors.PALETTE.map(Labels::colorName))

        // REQ-074 with the name being typed (09 PersonAdd), and before one is.
        assertEquals("한 약은 한 휴대폰에서 기록해 주세요. 아빠 약을 아빠 휴대폰에서도 기록하면 두 기록이 서로 보이지 않아요.", Labels.onePhoneFor(" 아빠 "))
        assertEquals("한 약은 한 휴대폰에서 기록해 주세요. 같은 약을 두 휴대폰에서 기록하면 두 기록이 서로 보이지 않아요.", Labels.onePhoneFor(""))

        // screens.md SCR-010 [보관].
        assertEquals("엄마를 보관할까요?", Labels.archivePersonQuestion("엄마"))
        assertEquals("엄마의 항목 3개가 위젯·알림에서 빠져요. 지난 기록은 남아요", Labels.archivePersonNote("엄마", 3))
        assertEquals("위젯·알림에서 빠지는 항목은 없어요. 지난 기록은 남아요", Labels.archivePersonNote("엄마", 0))
        assertEquals("동생을 보관했어요. 지난 기록은 남아요", Labels.personArchived("동생"))
        assertEquals("Mom을(를) 보관할까요?", Labels.archivePersonQuestion("Mom"))
    }

    private fun person(name: String, color: String) =
        PersonEntity(id = name, name = name, color = color, createdAt = 0, updatedAt = 0, deviceId = TEST_DEVICE_ID)
}
