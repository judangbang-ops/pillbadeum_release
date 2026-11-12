package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.data.SettingEntity
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.seoul
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** Settings save and "applies from the change" (TASK-009, REQ-011, REQ-053, supplement P-5). */
class ManageSettingsTest {
    private val fx = DbFixture()
    private val settings = ManageSettings(fx.db, fx.clock)

    @AfterTest
    fun tearDown() = fx.close()

    @Test
    fun rr15_dayStartChangedAtDawn_appliesFromChange_keepsOldKeys_storesChangeTime() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:00")
        val yesterday = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11")).record
        fx.at("2026-11-12T03:00") // day start 04:00: still the 2026-11-11 window
        assertIs<RecordResult.Already>(fx.record(intake, renderedKey = "2026-11-11"))

        fx.at("2026-11-12T03:30")
        assertNull(settings.windowSettingsChangedAt())
        assertEquals(SaveSettingsResult.Changed, settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(2, 0))))
        assertEquals(seoul("2026-11-12T03:30"), settings.windowSettingsChangedAt())
        assertEquals(LocalTime(2, 0), settings.windowSettings().dayStart)

        // 03:40 is now in the 2026-11-12 window (day start 02:00), which is empty: a new judgment.
        fx.at("2026-11-12T03:40")
        val today = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-12")).record
        assertEquals("2026-11-12", today.periodKey)
        assertEquals(listOf("2026-11-11", "2026-11-12"), fx.activeRows(intake).map { it.periodKey }, "old key kept")
        assertEquals(yesterday, fx.history(intake).first(), "the earlier row is untouched")
    }

    @Test
    fun saving_the_same_values_writes_nothing() = runTest {
        fx.at("2026-11-11T09:00")
        assertEquals(SaveSettingsResult.Unchanged, settings.saveWindowSettings(WindowSettings()))
        assertNull(settings.windowSettingsChangedAt())
        assertNull(fx.db.settingDao().get(SettingKeys.DAY_START), "defaults are not written")

        settings.saveWindowSettings(WindowSettings(baseZone = TimeZone.of("Asia/Tokyo")))
        fx.at("2026-11-11T10:00")
        assertEquals(
            SaveSettingsResult.Unchanged,
            settings.saveWindowSettings(WindowSettings(baseZone = TimeZone.of("Asia/Tokyo"))),
        )
        assertEquals(seoul("2026-11-11T09:00"), settings.windowSettingsChangedAt(), "first change time kept")
    }

    @Test
    fun base_zone_change_keeps_past_period_keys() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-11T08:00")
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))
        fx.at("2026-11-11T12:00")
        assertEquals(
            SaveSettingsResult.Changed,
            settings.saveWindowSettings(WindowSettings(baseZone = TimeZone.of("America/Los_Angeles"))),
        )
        assertEquals("America/Los_Angeles", settings.windowSettings().baseZone.id)
        assertEquals(seoul("2026-11-11T12:00"), settings.windowSettingsChangedAt())
        assertEquals(listOf("2026-11-11"), fx.activeRows(intake).map { it.periodKey })
    }

    @Test
    fun day_start_with_seconds_is_rejected() = runTest {
        assertEquals(SaveSettingsResult.Invalid, settings.saveWindowSettings(WindowSettings(dayStart = LocalTime(4, 0, 30))))
        assertNull(fx.db.settingDao().get(SettingKeys.DAY_START))
        assertNull(settings.windowSettingsChangedAt())
    }

    @Test
    fun a_malformed_stored_value_is_repaired_by_a_valid_save() = runTest {
        fx.db.settingDao().upsert(SettingEntity(SettingKeys.DAY_START, "broken"))
        assertEquals(SaveSettingsResult.Changed, settings.saveWindowSettings(WindowSettings()))
        assertEquals(WindowSettings.DEFAULT_DAY_START, settings.windowSettings().dayStart)
    }

    @Test
    fun names_on_lock_screen_default_off() = runTest {
        assertFalse(settings.showNamesOnLock())
        assertEquals(SaveSettingsResult.Unchanged, settings.setShowNamesOnLock(false))
        assertEquals(SaveSettingsResult.Changed, settings.setShowNamesOnLock(true))
        assertTrue(settings.showNamesOnLock())
        assertEquals(SaveSettingsResult.Changed, settings.setShowNamesOnLock(false))
        assertFalse(settings.showNamesOnLock())
    }
}
