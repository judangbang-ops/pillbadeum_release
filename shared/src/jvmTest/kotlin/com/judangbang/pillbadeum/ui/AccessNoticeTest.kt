package com.judangbang.pillbadeum.ui

import com.judangbang.pillbadeum.status.Labels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** SCR-004#permission (TASK-026): which band the first screen shows for reminders that cannot come. */
class AccessNoticeTest {
    private val allOn = ReminderAccess(notifications = true, exactAlarms = true, batteryUnrestricted = true)

    @Test
    fun the_first_screen_speaks_only_while_an_item_has_reminders_on() {
        val allOff = ReminderAccess(notifications = false, exactAlarms = false, batteryUnrestricted = false)
        assertNull(accessNotice(remindersOn = false, allOff), "no item has reminders on: nothing to say")
        assertNull(accessNotice(remindersOn = true, access = null), "the phone is not read yet")
        assertNull(accessNotice(remindersOn = true, allOn))
    }

    @Test
    fun notifications_that_are_off_come_first_and_alone() {
        assertEquals(AccessSetting.NOTIFICATIONS, accessNotice(true, allOn.copy(notifications = false)), "REQ-089")
        assertEquals(
            AccessSetting.NOTIFICATIONS,
            accessNotice(true, allOn.copy(notifications = false, exactAlarms = false)),
            "exact alarms change nothing until notifications are on",
        )
        assertEquals(AccessSetting.EXACT_ALARMS, accessNotice(true, allOn.copy(exactAlarms = false)), "REQ-050")
        assertNull(accessNotice(true, allOn.copy(batteryUnrestricted = false)), "battery use is only in the settings (SCR-009)")
    }

    /** REQ-048: no sentence about reminders that cannot come promises minutes; the battery note names the app. */
    @Test
    fun req48_no_access_sentence_promises_minutes() {
        val sentences = listOf(
            Labels.NOTIFICATIONS_OFF,
            Labels.EXACT_ALARMS_OFF,
            Labels.NOTIFICATIONS_OFF_NOTE,
            Labels.EXACT_ALARMS_OFF_NOTE,
            Labels.BATTERY_NOTE,
            Labels.ACCESS_OPEN_FAILED,
        )
        sentences.forEach { assertFalse(Regex("[0-9]+\\s*분").containsMatchIn(it), it) }
        assertEquals(
            "제한되어 있어 알림이 늦을 수 있어요. 열리는 화면에서 DidITake을(를) 찾아 배터리 최적화를 꺼 주세요.",
            Labels.BATTERY_NOTE,
        )
    }
}
