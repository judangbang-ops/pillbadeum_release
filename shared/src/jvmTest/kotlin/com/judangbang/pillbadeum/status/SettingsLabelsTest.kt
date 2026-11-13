package com.judangbang.pillbadeum.status

import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.domain.ReminderSettings
import com.judangbang.pillbadeum.testing.seoul
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** SCR-009's words (TASK-020, 09 Settings, SPEC-031, SPEC-033~036). */
class SettingsLabelsTest {
    @Test
    fun values_read_as_09_draws_them() {
        assertEquals("04:00", Labels.clock(LocalTime(4, 0)))
        assertEquals("4시", Labels.clockSpoken(LocalTime(4, 0)))
        assertEquals("22시 30분", Labels.clockSpoken(LocalTime(22, 30)))
        assertEquals("60분", Labels.interval(60.minutes))
        assertEquals("3번", Labels.perDay(3))
        assertEquals("22:00 ~ 07:00", Labels.quiet(ReminderSettings()))
        assertEquals("22시부터 7시까지", Labels.quietSpoken(ReminderSettings()))
        val none = ReminderSettings(quietStart = LocalTime(0, 0), quietEnd = LocalTime(0, 0))
        assertEquals("없음", Labels.quiet(none))
        assertEquals("없음", Labels.quietSpoken(none))
        assertEquals("2명", Labels.peopleCount(2))
        assertEquals("1개", Labels.itemCount(1))
    }

    @Test
    fun the_quiet_notice_counts_repeats_and_items() {
        // screens.md SCR-009, 2026-11-12 review C6.
        assertEquals("방해 금지 시간과 겹쳐 다시 알림이 모두 4번 줄어요 (알림을 켠 항목 2개)", Labels.quietSaved(4, 2))
    }

    @Test
    fun the_backup_notice_follows_the_android_version_and_the_screen_lock() {
        // REQ-054, SPEC-035, RR-68 (no screen lock), RR-70 (Android 8.x).
        assertEquals("화면 잠금과 Google 백업이 켜져 있으면 복용 기록이 백업돼요.", Labels.backup(oldAndroid = false, screenLock = true))
        assertEquals("화면 잠금이 없어 복용 기록이 백업되지 않아요.", Labels.backup(oldAndroid = false, screenLock = false))
        assertEquals("이 휴대폰은 Android 버전이 낮아 복용 기록이 백업되지 않아요.", Labels.backup(oldAndroid = true, screenLock = false))
        assertEquals(Labels.BACKUP_OLD_ANDROID, Labels.backup(oldAndroid = true, screenLock = true))
        // SPEC-035: a screen lock that cannot be read gets the general notice only.
        assertEquals(Labels.BACKUP_ON, Labels.backup(oldAndroid = false, screenLock = null))
    }

    @Test
    fun the_questions_say_what_a_change_does() {
        // Supplement P-5, SPEC-033.
        val p5 = "바꾼 때부터 적용돼요. 지난 기록의 날짜는 그대로예요. 바꾸기 전에 끝난 날의 기록은 고칠 수 없게 돼요."
        assertEquals("06:00부터 하루가 시작돼요. $p5", Labels.dayStartQuestion(LocalTime(6, 0)))
        assertEquals("날짜를 Asia/Tokyo 기준으로 셈해요. $p5", Labels.zoneQuestion("Asia/Tokyo"))
        // SPEC-025, with the object particle of the name (both when the name does not end in Korean, as elsewhere).
        assertEquals("철분제를 완전히 지울까요?", Labels.deleteQuestion("철분제"))
        assertEquals("아침약을 완전히 지울까요?", Labels.deleteQuestion("아침약"))
        assertEquals("비타민D을(를) 완전히 지울까요?", Labels.deleteQuestion("비타민D"))
        assertEquals("아침약을 완전히 지웠어요", Labels.deleted("아침약"))
    }

    @Test
    fun app_information_and_names() {
        // REQ-036, SPEC-036.
        assertEquals("의료기기가 아니며 질병을 진단·치료·예방하지 않아요.", Labels.SETTINGS_MEDICAL)
        assertEquals("브라우저를 열 수 없어요. 주소: https://example.invalid/privacy", Labels.noBrowser("https://example.invalid/privacy"))
        val mom = PersonEntity(id = "p", name = "엄마", color = "#4E79A7", createdAt = 0, updatedAt = 0, deviceId = "d")
        assertEquals("엄마 혈압약", Labels.archivedName(ArchivedItem("i", "혈압약", mom), withPerson = true))
        assertEquals("혈압약", Labels.archivedName(ArchivedItem("i", "혈압약", mom), withPerson = false))
    }

    @Test
    fun zone_offsets_are_the_ones_now() {
        val now = seoul("2026-11-12T09:30")
        assertEquals("UTC+09:00", Labels.utcOffset(TimeZone.of("Asia/Seoul"), now))
        assertEquals("UTC+05:30", Labels.utcOffset(TimeZone.of("Asia/Kolkata"), now))
        // New York left summer time on 11/01.
        assertEquals("UTC-05:00", Labels.utcOffset(TimeZone.of("America/New_York"), now))
        assertEquals("UTC-04:00", Labels.utcOffset(TimeZone.of("America/New_York"), seoul("2026-07-01T09:00")))
        assertEquals("UTC+00:00", Labels.utcOffset(TimeZone.of("Europe/London"), now))
    }
}
