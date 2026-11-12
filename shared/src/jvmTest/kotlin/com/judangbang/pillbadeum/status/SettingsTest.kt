package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.domain.QuietNotice
import com.judangbang.pillbadeum.domain.ReminderSettings
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.quietDrops
import com.judangbang.pillbadeum.domain.quietNotice
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.ArchiveIntake
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.ManagePeople
import com.judangbang.pillbadeum.usecase.ManageReminders
import com.judangbang.pillbadeum.usecase.ManageSettings
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.SaveIntake
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import com.judangbang.pillbadeum.usecase.SaveReminderResult
import com.judangbang.pillbadeum.usecase.SaveReminderSettingsResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/**
 * SCR-009 (TASK-020): what the settings screen reads ([LoadSettings]), the one quiet-hours notice of the common settings
 * (REQ-077, 2026-11-12 review C6) and the base-zone list. Seen on Thursday 11/12 09:30 in Seoul.
 */
class SettingsTest {
    private val fx = DbFixture(start = seoul("2026-11-12T09:30"))
    private val people = ManagePeople(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val saveIntake = SaveIntake(fx.db, fx.clock, fx.ids, TEST_DEVICE_ID)
    private val archive = ArchiveIntake(fx.db, fx.clock)
    private val settings = ManageSettings(fx.db, fx.clock)
    private val reminders = ManageReminders(fx.db, fx.clock, TEST_DEVICE_ID)
    private val load = LoadSettings(fx.db)

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun item(personId: String, name: String): String =
        assertIs<SaveIntakeResult.Saved>(saveIntake.create(IntakeDraft(personId, name))).intakeId

    private suspend fun remindedAt(start: String): String {
        val id = item(people.ensureDefault().first().id, "약 $start")
        assertIs<SaveReminderResult.Saved>(reminders.saveRule(id, enabled = true, start = LocalTime.parse(start)))
        return id
    }

    @Test
    fun the_defaults_show_before_anything_is_stored() = runTest {
        people.ensureDefault()
        val view = load()
        assertEquals(WindowSettings(), view.window)
        assertEquals(ReminderSettings(), view.reminders)
        assertFalse(view.showNamesOnLock, "REQ-046: off by default")
        assertEquals(1, view.people)
        assertFalse(view.showPeople)
        assertEquals(emptyList(), view.archived, "SCR-009#empty: no archived row")
    }

    @Test
    fun stored_values_and_archived_items_newest_first_with_their_person() = runTest {
        val me = people.ensureDefault().single()
        val mom = people.add("엄마", PersonColors.PALETTE[1])
        val morning = item(me.id, "아침약")
        val pressure = item(mom.id, "혈압약")
        item(me.id, "비타민D")
        fx.at("2026-11-12T10:00")
        assertTrue(archive(morning))
        fx.at("2026-11-12T11:00")
        assertTrue(archive(pressure))
        settings.saveWindowSettings(WindowSettings(LocalTime(6, 0), TimeZone.of("Asia/Tokyo")))
        settings.setShowNamesOnLock(true)
        reminders.saveSettings(ReminderSettings(interval = 30.minutes, maxPerDay = 5))

        val view = load()
        assertEquals(WindowSettings(LocalTime(6, 0), TimeZone.of("Asia/Tokyo")), view.window)
        assertEquals(ReminderSettings(interval = 30.minutes, maxPerDay = 5), view.reminders)
        assertTrue(view.showNamesOnLock)
        assertEquals(2, view.people)
        assertTrue(view.showPeople)
        assertEquals(listOf("엄마 혈압약", "나 아침약"), view.archived.map { Labels.archivedName(it, withPerson = view.showPeople) })

        // An archived person is not counted, and their archived item stays in the list.
        people.archive(mom.id)
        val after = load()
        assertEquals(1, after.people)
        assertEquals(listOf("혈압약", "아침약"), after.archived.map { Labels.archivedName(it, withPerson = after.showPeople) })
    }

    @Test
    fun quiet_drops_count_what_the_notice_says() {
        val window = WindowSettings()
        val defaults = ReminderSettings()
        // SPEC-031: 21:00 every 60 minutes, 3 a day, quiet 22:00~07:00 → 22:00 and 23:00 are dropped.
        assertEquals(QuietNotice.Reduced(2), quietNotice(LocalTime(21, 0), window, defaults))
        assertEquals(2, quietDrops(LocalTime(21, 0), window, defaults))
        // Starting in quiet hours: the first rings, both repeats are dropped.
        assertEquals(QuietNotice.FirstOnly, quietNotice(LocalTime(22, 30), window, defaults))
        assertEquals(2, quietDrops(LocalTime(22, 30), window, defaults))
        // 06:00 rings at 06:00, then 07:00 and 08:00 are outside: no notice, nothing dropped.
        assertEquals(null, quietNotice(LocalTime(6, 0), window, defaults))
        assertEquals(0, quietDrops(LocalTime(6, 0), window, defaults))
    }

    @Test
    fun c6_the_common_settings_report_the_repeats_dropped_over_every_item() = runTest {
        // REQ-077, review C6: "방해 금지 시간과 겹쳐 다시 알림이 모두 N번 줄어요 (알림을 켠 항목 K개)".
        val nine = remindedAt("21:00")
        val late = remindedAt("23:00")
        remindedAt("08:00")
        val result = assertIs<SaveReminderSettingsResult.Saved>(reminders.saveSettings(ReminderSettings(interval = 30.minutes)))
        // 21:00 → 21:30 rings, 22:00 is quiet; 23:00 → 23:30 and 00:00 are quiet; 08:00 → 08:30, 09:00 ring.
        assertEquals(mapOf(nine to QuietNotice.Reduced(1), late to QuietNotice.FirstOnly), result.notices)
        assertEquals(3, result.dropped)
        assertEquals("방해 금지 시간과 겹쳐 다시 알림이 모두 3번 줄어요 (알림을 켠 항목 2개)", Labels.quietSaved(result.dropped, result.notices.size))

        // Quiet hours from 00:00 to 07:00: only 23:00's 00:00 is dropped.
        val later = assertIs<SaveReminderSettingsResult.Saved>(
            reminders.saveSettings(ReminderSettings(interval = 30.minutes, quietStart = LocalTime(0, 0), quietEnd = LocalTime(7, 0))),
        )
        assertEquals(mapOf(late to QuietNotice.Reduced(1)), later.notices)
        assertEquals(1, later.dropped)

        // No quiet hours: nothing to say.
        val none = assertIs<SaveReminderSettingsResult.Saved>(
            reminders.saveSettings(ReminderSettings(quietStart = LocalTime(0, 0), quietEnd = LocalTime(0, 0))),
        )
        assertEquals(emptyMap(), none.notices)
        assertEquals(0, none.dropped)
    }

    @Test
    fun the_base_zone_list_puts_the_zone_in_use_first_and_names_each_zone_once() {
        val list = baseZoneChoices(current = "Asia/Tokyo", device = "Europe/London")
        assertEquals(listOf("Asia/Tokyo", "Europe/London", "Asia/Seoul"), list.take(3))
        assertEquals(list.size, list.toSet().size, "each zone once")
        assertTrue("America/New_York" in list && "Asia/Kolkata" in list && "Pacific/Auckland" in list)
        assertTrue(list.none { it.startsWith("Etc/") || it.startsWith("US/") || it == "UTC" || '/' !in it }, "no aliases")
        val rest = list.drop(3)
        assertEquals(rest.sorted(), rest, "the rest by name")

        // The zone in use, the phone's and the default are the same: listed once, first.
        val seoul = baseZoneChoices(current = "Asia/Seoul", device = "Asia/Seoul")
        assertEquals("Asia/Seoul", seoul.first())
        assertEquals(1, seoul.count { it == "Asia/Seoul" })
    }
}
