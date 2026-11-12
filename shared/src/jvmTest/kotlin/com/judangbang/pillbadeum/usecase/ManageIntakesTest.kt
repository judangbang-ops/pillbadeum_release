package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.data.loadOrCreateDeviceId
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.ScheduleType
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.status.LoadStatusBoard
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek

class ManageIntakesTest {
    private val fx = DbFixture()
    private val saveIntake = SaveIntake(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val archiveIntake = ArchiveIntake(fx.db, fx.clock)
    private val people = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val board = LoadStatusBoard(fx.db, fx.clock)

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun me(): String = people.ensureDefault().single().id

    @Test
    fun ensureDefault_creates_me_once() = runTest {
        val first = people.ensureDefault()
        val second = people.ensureDefault()
        assertEquals(listOf("나"), first.map { it.name })
        assertEquals(first, second)
        assertEquals(PersonColors.DEFAULT, first.single().color)
    }

    @Test
    fun addPerson_rejects_blank_name_and_bad_color() = runTest {
        assertFailsWith<IllegalArgumentException> { people.add("  ", PersonColors.DEFAULT) }
        assertFailsWith<IllegalArgumentException> { people.add("엄마", "orange") }
        people.ensureDefault()
        people.add(" 엄마 ", "#B5562B")
        assertEquals(listOf("나", "엄마"), people.list().map { it.name })
    }

    @Test
    fun deviceId_is_created_once_and_reused() = runTest {
        val first = fx.db.settingDao().loadOrCreateDeviceId(fx.ids)
        fx.at("2026-11-12T08:00")
        assertEquals(first, fx.db.settingDao().loadOrCreateDeviceId(fx.ids))
    }

    @Test
    fun create_writes_intake_and_first_schedule_version() = runTest {
        val saved = assertIs<SaveIntakeResult.Saved>(
            saveIntake.create(IntakeDraft(me(), "  아침약 ", widgetAlias = " ", slotLabel = SlotLabel.MORNING)),
        )
        val intake = fx.db.intakeDao().findById(saved.intakeId)!!
        assertEquals("아침약", intake.name)
        assertNull(intake.widgetAlias, "blank alias is stored as null")
        assertEquals(SlotLabel.MORNING, intake.slotLabel)
        assertEquals(TEST_DEVICE_ID, intake.deviceId)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(intake.id))

        val version = fx.db.scheduleVersionDao().listByIntake(saved.intakeId).single()
        assertEquals(ScheduleType.DAILY, version.type)
        assertEquals(seoul("2026-11-11T08:00").toEpochMilliseconds(), version.effectiveFrom)
        assertNull(version.effectiveTo)
    }

    @Test
    fun create_invalid_input_writes_nothing() = runTest {
        val person = me()
        assertEquals(
            SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.BLANK_NAME),
            saveIntake.create(IntakeDraft(person, "   ")),
        )
        assertEquals(
            SaveIntakeResult.Invalid(SaveIntakeResult.Invalid.UNKNOWN_PERSON),
            saveIntake.create(IntakeDraft("no-such-person", "아침약")),
        )
        assertTrue(fx.db.intakeDao().listActive().isEmpty())
    }

    @Test
    fun weekly_draft_drops_slot_label() = runTest {
        val saved = assertIs<SaveIntakeResult.Saved>(
            saveIntake.create(
                IntakeDraft(me(), "주사제 A", rule = ScheduleRule.Weekly(DayOfWeek.MONDAY), slotLabel = SlotLabel.MORNING),
            ),
        )
        assertNull(fx.db.intakeDao().findById(saved.intakeId)!!.slotLabel)
    }

    @Test
    fun edit_with_new_schedule_closes_old_version_and_adds_new_one() = runTest {
        val person = me()
        val id = assertIs<SaveIntakeResult.Saved>(saveIntake.create(IntakeDraft(person, "비타민"))).intakeId

        fx.at("2026-11-12T09:00")
        val saved = assertIs<SaveIntakeResult.Saved>(
            saveIntake.update(id, IntakeDraft(person, "비타민 D", rule = ScheduleRule.Weekly(DayOfWeek.THURSDAY))),
        )
        assertTrue(saved.newScheduleVersion)

        val editAt = seoul("2026-11-12T09:00").toEpochMilliseconds()
        val versions = fx.db.scheduleVersionDao().listByIntake(id)
        assertEquals(2, versions.size)
        assertEquals(editAt, versions[0].effectiveTo, "previous version is closed, not overwritten")
        assertEquals(ScheduleType.DAILY, versions[0].type)
        assertEquals(ScheduleType.WEEKLY, versions[1].type)
        assertEquals(editAt, versions[1].effectiveFrom)
        assertNull(versions[1].effectiveTo)
        assertEquals(2, fx.db.intakeDao().findById(id)!!.version)

        val status = board().items.single()
        assertEquals("비타민 D", status.name)
        assertEquals(ScheduleRule.Weekly(DayOfWeek.THURSDAY), status.rule)
    }

    @Test
    fun edit_with_same_schedule_keeps_the_version() = runTest {
        val person = me()
        val id = assertIs<SaveIntakeResult.Saved>(saveIntake.create(IntakeDraft(person, "비타민"))).intakeId
        fx.at("2026-11-11T09:00")
        val saved = assertIs<SaveIntakeResult.Saved>(
            saveIntake.update(id, IntakeDraft(person, "비타민", widgetAlias = "비타", slotLabel = SlotLabel.EVENING)),
        )
        assertFalse(saved.newScheduleVersion)
        assertEquals(1, fx.db.scheduleVersionDao().listByIntake(id).size)
        assertEquals("비타", fx.db.intakeDao().findById(id)!!.widgetAlias)
    }

    @Test
    fun edit_after_schedule_change_keeps_todays_judgment() = runTest {
        val person = me()
        val id = assertIs<SaveIntakeResult.Saved>(saveIntake.create(IntakeDraft(person, "아침약"))).intakeId
        fx.at("2026-11-11T08:02")
        assertIs<RecordResult.Recorded>(fx.record(id, renderedKey = "2026-11-11"))
        fx.at("2026-11-11T10:00")
        saveIntake.update(id, IntakeDraft(person, "아침약", rule = ScheduleRule.Weekly(DayOfWeek.WEDNESDAY)))

        // Same real window (Wednesday 04:00 onward): the existing judgment still counts.
        assertTrue(board().items.single().isDone)
        assertIs<RecordResult.Already>(fx.record(id, renderedKey = "2026-11-11"))
    }

    @Test
    fun archive_hides_item_but_keeps_records_and_rejects_widget_taps() = runTest {
        val person = me()
        val id = assertIs<SaveIntakeResult.Saved>(saveIntake.create(IntakeDraft(person, "아침약"))).intakeId
        val other = assertIs<SaveIntakeResult.Saved>(saveIntake.create(IntakeDraft(person, "비타민"))).intakeId
        fx.at("2026-11-11T08:02")
        assertIs<RecordResult.Recorded>(fx.record(id, renderedKey = "2026-11-11"))

        assertTrue(archiveIntake(id))
        assertFalse(archiveIntake(id), "second archive changes nothing")

        assertEquals(listOf(other), board().items.map { it.intakeId })
        assertEquals(1, fx.history(id).size, "records are kept")
        assertEquals(RecordResult.Gone, fx.record(id, renderedKey = "2026-11-11"))
        assertEquals(SaveIntakeResult.Gone, saveIntake.update(id, IntakeDraft(person, "아침약")))
    }
}
