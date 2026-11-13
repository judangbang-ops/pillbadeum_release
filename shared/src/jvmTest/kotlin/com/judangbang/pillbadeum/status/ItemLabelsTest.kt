package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.domain.QuietNotice
import com.judangbang.pillbadeum.domain.ReminderSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/** SCR-006's reminder words (TASK-023, 09 ItemForm, screens.md SCR-006#permission and #quiet, SPEC-031). */
class ItemLabelsTest {
    @Test
    fun quiet_notices_read_as_screens_md_writes_them() {
        assertEquals("방해 금지 시간(22:00~07:00)과 겹쳐 다시 알림이 2번 줄어요", Labels.itemQuiet(QuietNotice.Reduced(2), ReminderSettings()))
        assertEquals("방해 금지 시간이라 첫 알림만 울리고 다시 알림은 없어요", Labels.itemQuiet(QuietNotice.FirstOnly, ReminderSettings()))
        val late = ReminderSettings(quietStart = LocalTime(23, 30), quietEnd = LocalTime(6, 0))
        assertEquals("방해 금지 시간(23:30~06:00)과 겹쳐 다시 알림이 1번 줄어요", Labels.itemQuiet(QuietNotice.Reduced(1), late))
    }

    @Test
    fun reminder_rows_read_as_09_draws_them() {
        assertEquals("그 주의 월요일에만 울려요.", Labels.reminderWeekly(DayOfWeek.MONDAY))
        assertEquals("그 주의 수요일에만 울려요.", Labels.reminderWeekly(DayOfWeek.WEDNESDAY))
        assertEquals("첫 알림 시각, 21시", Labels.reminderStartSpoken(LocalTime(21, 0)))
        assertEquals("첫 알림 시각, 7시 30분", Labels.reminderStartSpoken(LocalTime(7, 30)))
        assertEquals("알림 권한이 없어 알림이 오지 않아요", Labels.ITEM_NOTIFICATIONS_OFF)
    }
}
