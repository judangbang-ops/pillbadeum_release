package com.judangbang.pillbadeum.ui

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.content.ClipboardManager
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.AndroidPhone
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.boundary.DayBoundaryAlarm
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.IntakeMedicationDraft
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import com.judangbang.pillbadeum.usecase.SaveReminderResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.util.TimeZone as JavaTimeZone

/**
 * SCR-009 설정 (TASK-020) in the real app, opened from the first screen's `[설정]` on Thursday 11/12 at 09:30 in Seoul.
 * Rows are found by what TalkBack reads (`하루 시작 시각, 4시`). Covered: 09 Settings' rows and values, a new day start
 * through the system time picker and the P-5 question (RR-15), the base zone from the list, the common reminder settings
 * with the quiet-hours notice (REQ-077, review C6), the lock-screen switch (SPEC-034), deleting an archived item for good
 * (SPEC-025), the backup notice with and without a screen lock and on Android 8.x (RR-68, RR-70), the privacy policy with
 * and without a browser (SPEC-036), `#loading`, `#error`, `#stale`, 200% text, a recreated activity and back. Each case is
 * saved as `build/app-render/settings-*.png` with its semantics tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val core: DidITakeCore get() = runBlocking { app.container.core() }
    private lateinit var savedZone: JavaTimeZone
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        savedZone = JavaTimeZone.getDefault()
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("Asia/Seoul"))
        screenLock(true)
    }

    @After
    fun tearDown() {
        JavaTimeZone.setDefault(savedZone)
        RuntimeEnvironment.setFontScale(1f)
    }

    /** SCR-009#normal (09 Settings, REQ-053, REQ-054, REQ-036, REQ-063): the defaults, two people, no archived row. */
    @Test
    fun the_rows_show_their_values_as_09_draws_them() {
        app.seedWeek(family = true)
        openSettings()
        row("하루 시작 시각, 4시")
        row("기준 시간대, Asia/Seoul")
        row("다시 알림 간격, 60분")
        row("하루 최대, 3번")
        row("방해 금지, 22시부터 7시까지")
        namesSwitch().assertIsOff()
        row("대상자 관리, 2명")
        assertEquals("SCR-009#empty: no archived row", 0, compose.onAllNodesWithContentDescription("보관한 항목", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText(Labels.BACKUP_ON).assertExists()
        // The row shows the package's versionName, which is 0.1.0.<versionCode> since TASK-055 (it differs per build)
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
        assertTrue("versionName $version is 0.1.0.<versionCode>", Regex("""0\.1\.0\.\d+""").matches(version))
        row("버전, $version")
        row("개인정보처리방침, 브라우저에서 열기")
        compose.onNodeWithText(Labels.SETTINGS_MEDICAL).assertExists()
        compose.assertTargetsAtLeast48dp()
        save("settings-normal")

        compose.onNodeWithText(Labels.SETTINGS_MEDICAL).performScrollTo()
        save("settings-bottom")
    }

    /**
     * TC-033, RR-15 (SPEC-033, supplement P-5): at 03:00, still 11/12's day, 04:00 → 06:00. The system picker starts at
     * 04:00 in 24 hours; the same time asks nothing; another asks P-5 first, and [아니요] keeps 04:00. Saved, it applies
     * from now: the judgments keep their period keys, 11/12 stays done, a second tap records nothing, the first screen and
     * the widgets' board say 06:00, and the day-boundary refresh moves to 06:00.
     */
    @Test
    fun rr15_a_new_day_start_is_asked_first_and_applies_from_now() {
        val seed = app.seedWeek(family = false)
        app.clock.now = at("2026-11-13T03:00")
        openSettings()

        row("하루 시작 시각, 4시").performClick()
        val first = timePicker()
        assertEquals(4, shadowOf(first).hourOfDay)
        assertEquals(0, shadowOf(first).minute)
        assertTrue("24 hours", shadowOf(first).is24HourView)
        pick(first, 4, 0)
        assertEquals("the same time asks nothing", 0, compose.onAllNodes(isDialog()).fetchSemanticsNodes().size)

        row("하루 시작 시각, 4시").performClick()
        pick(timePicker(), 6, 0)
        compose.onNodeWithText("하루 시작 시각을 바꿀까요?").assertExists()
        compose.onNodeWithText(Labels.dayStartQuestion(LocalTime(6, 0))).assertExists()
        save("settings-day-start-ask")
        compose.onNodeWithText("아니요").performClick()
        compose.waitForIdle()
        assertEquals(LocalTime(4, 0), runBlocking { core.windowSettings() }.dayStart)
        row("하루 시작 시각, 4시")

        row("하루 시작 시각, 4시").performClick()
        pick(timePicker(), 6, 0)
        compose.onNodeWithText("바꾸기").performClick()
        waitForRow("하루 시작 시각, 6시")
        runBlocking {
            assertEquals(LocalTime(6, 0), core.windowSettings().dayStart)
            assertEquals(at("2026-11-13T03:00"), core.settings.windowSettingsChangedAt())
            val keys = core.db.doseRecordDao().listByIntake(seed.morning).filter { it.isActive }.map { it.periodKey }.sorted()
            assertEquals(listOf("2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12"), keys)
            val board = core.loadStatusBoard()
            assertEquals(LocalTime(6, 0), board.settings.dayStart)
            assertTrue("11/12 is still done", board.items.single { it.intakeId == seed.morning }.isTaken)
            assertTrue(core.record(seed.morning, DoseSource.WIDGET, "2026-11-12") is RecordResult.Already)
        }
        assertEquals(at("2026-11-13T06:00").toEpochMilliseconds() + 5_000, boundaryAlarmAt())

        controller!!.get().onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작 06:00", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** SPEC-033: the base zone from the list only. The zone in use comes first and only closes it; another asks P-5. */
    @Test
    fun the_base_zone_is_picked_from_the_list_and_asked_first() {
        app.seedWeek(family = false)
        openSettings()
        row("기준 시간대, Asia/Seoul").performClick()
        val seoul = compose.onNodeWithContentDescription("Asia/Seoul, UTC+09:00 · 이 휴대폰")
        seoul.assertIsSelected()
        val height = seoul.fetchSemanticsNode().boundsInRoot.height / compose.density.density
        assertTrue("at 100% the offset stays beside the name: $height dp", height < 60f)
        compose.assertTargetsAtLeast48dp()
        save("settings-zone-list")
        seoul.performClick()
        compose.waitForIdle()
        assertEquals("the zone in use only closes the list", 0, compose.onAllNodes(isDialog()).fetchSemanticsNodes().size)

        row("기준 시간대, Asia/Seoul").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Asia/Tokyo, UTC+09:00"))
        compose.onNodeWithContentDescription("Asia/Tokyo, UTC+09:00").performClick()
        compose.onNodeWithText("기준 시간대를 바꿀까요?").assertExists()
        compose.onNodeWithText(Labels.zoneQuestion("Asia/Tokyo")).assertExists()
        compose.onNodeWithText("바꾸기").performClick()
        waitForRow("기준 시간대, Asia/Tokyo")
        assertEquals("Asia/Tokyo", runBlocking { core.windowSettings() }.baseZone.id)
    }

    /**
     * REQ-042, REQ-077, SPEC-031, review C6: two items remind at 21:00 and 23:00. Every 30 minutes drops 22:00 for the
     * first and both repeats of the second, said once over both; quiet hours 00:00~08:00 then drop one; one reminder a day
     * drops nothing, and the notice goes.
     */
    @Test
    fun c6_common_reminder_settings_say_how_many_repeats_quiet_hours_drop() {
        val seed = app.seedWeek(family = false)
        runBlocking {
            assertTrue(core.reminders.saveRule(seed.morning, enabled = true, start = LocalTime(21, 0)) is SaveReminderResult.Saved)
            assertTrue(core.reminders.saveRule(seed.vitamin, enabled = true, start = LocalTime(23, 0)) is SaveReminderResult.Saved)
        }
        openSettings()

        row("다시 알림 간격, 60분").performClick()
        inDialog("60분").assertIsSelected()
        compose.assertTargetsAtLeast48dp()
        save("settings-interval")
        inDialog("30분").performClick()
        inDialog("저장").performClick()
        waitForText(Labels.quietSaved(3, 2))
        row("다시 알림 간격, 30분")
        assertEquals(30.minutes, runBlocking { core.reminders.settings() }.interval)
        save("settings-quiet-notice")

        // [시작] is set first; [끝] switches the cells to the end.
        row("방해 금지, 22시부터 7시까지").performClick()
        compose.onNodeWithContentDescription("시작, 22시").assertIsSelected()
        compose.onNodeWithContentDescription("시작 0시").performScrollTo().performClick()
        compose.onNodeWithContentDescription("0시부터 7시까지").performScrollTo()
        compose.assertTargetsAtLeast48dp()
        save("settings-quiet")
        compose.onNodeWithContentDescription("끝, 7시").performScrollTo().performClick()
        compose.onNodeWithContentDescription("끝 7시").assertIsSelected()
        compose.onNodeWithContentDescription("끝 8시").performScrollTo().performClick()
        compose.onNodeWithContentDescription("0시부터 8시까지").performScrollTo()
        save("settings-quiet-end")
        inDialog("저장").performScrollTo().performClick()
        waitForText(Labels.quietSaved(1, 1))
        row("방해 금지, 0시부터 8시까지")

        // Right after a save, so the save's work after it may still be running: see openPicker.
        openPicker("하루 최대, 3번", "1번").performClick()
        inDialog("저장").performClick()
        waitForRow("하루 최대, 1번")
        assertEquals("nothing dropped, nothing said", 0, compose.onAllNodesWithText("방해 금지 시간과 겹쳐", substring = true).fetchSemanticsNodes().size)
        val saved = runBlocking { core.reminders.settings() }
        assertEquals(listOf(30L, 1L, 0L, 8L), listOf(saved.interval.inWholeMinutes, saved.maxPerDay.toLong(), saved.quietStart.hour.toLong(), saved.quietEnd.hour.toLong()))
    }

    /** SPEC-034, TC-034: the switch saves at once; switching it on says the names will show on the lock screen. */
    @Test
    fun the_lock_screen_switch_saves_and_says_names_will_show() {
        openSettings()
        namesSwitch().assertIsOff().performClick()
        waitForText(Labels.SHOW_NAMES_ON)
        namesSwitch().assertIsOn()
        assertTrue(runBlocking { core.settings.showNamesOnLock() })
        save("settings-names-on")

        namesSwitch().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(Labels.SHOW_NAMES_ON).fetchSemanticsNodes().isEmpty() }
        namesSwitch().assertIsOff()
        assertFalse(runBlocking { core.settings.showNamesOnLock() })
    }

    /**
     * TC-025, SPEC-025 (REQ-015): an archived item opens under `보관한 항목` with its person; [완전 삭제] asks first, [아니요]
     * keeps it, and then the item and its judgments are gone, and so is the row (SCR-009#empty).
     */
    @Test
    fun spec025_an_archived_item_is_deleted_for_good_after_its_question() {
        val seed = app.seedWeek(family = true)
        val medicationIds = runBlocking {
            val item = checkNotNull(core.db.intakeDao().findById(seed.morning))
            val draft = IntakeDraft(
                item.personId, item.name, slotLabel = item.slotLabel,
                medications = listOf(IntakeMedicationDraft("예시정"), IntakeMedicationDraft("가상정")),
            )
            assertTrue(core.saveIntake.update(seed.morning, draft) is SaveIntakeResult.Saved)
            assertTrue(core.archiveIntake(seed.morning))
            core.db.intakeMedicationDao().listByIntake(seed.morning).map { it.medicationId }
        }
        assertEquals(2, medicationIds.size)
        assertEquals(4, judgments(seed.morning))
        openSettings()
        val archived = row("보관한 항목, 1개").performScrollTo()
        archived.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "접힘")).performClick()
        archived.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "펼쳐짐"))
        compose.onNodeWithContentDescription("나 아침약 완전 삭제").performScrollTo()
        compose.onNodeWithText("나 아침약").assertExists()
        compose.assertTargetsAtLeast48dp()
        save("settings-archived")

        compose.onNodeWithContentDescription("나 아침약 완전 삭제").performScrollTo().performClick()
        compose.onNodeWithText("아침약을 완전히 지울까요?").assertExists()
        compose.onNodeWithText(Labels.DELETE_NOTE).assertExists()
        save("settings-delete-ask")
        compose.onNodeWithText("아니요").performClick()
        compose.waitForIdle()
        assertEquals("kept", 4, judgments(seed.morning))
        assertEquals(2, runBlocking { core.saveIntake.loadMedications(seed.morning).size })

        compose.onNodeWithContentDescription("나 아침약 완전 삭제").performScrollTo().performClick()
        inDialog("완전 삭제").performClick()
        waitForText(Labels.deleted("아침약"))
        assertEquals(0, compose.onAllNodesWithContentDescription("보관한 항목", substring = true).fetchSemanticsNodes().size)
        runBlocking {
            assertNull(core.db.intakeDao().findById(seed.morning))
            assertTrue(core.saveIntake.loadMedications(seed.morning).isEmpty())
            for (id in medicationIds) assertNull(core.db.medicationDao().findById(id))
        }
        assertEquals(0, judgments(seed.morning))
        save("settings-deleted")
    }

    /** RR-68 (SPEC-035): no screen lock, no backup; coming back with a lock set reads it again (SCR-009#stale). */
    @Test
    fun rr68_without_a_screen_lock_the_backup_is_off_until_one_is_set() {
        screenLock(false)
        openSettings()
        compose.onNodeWithText(Labels.BACKUP_NO_LOCK).performScrollTo()
        save("settings-no-lock")

        screenLock(true)
        controller!!.pause().resume()
        waitForText(Labels.BACKUP_ON)
        compose.onNodeWithText(Labels.BACKUP_NO_LOCK).assertDoesNotExist()
    }

    /** RR-70 (SPEC-035, supplement B-3): Android 8.x has no backup, whatever the screen lock. */
    @Test
    fun rr70_android_8_says_the_backup_is_off() {
        open()
        val sdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.O_MR1)
        try {
            compose.onNodeWithText("설정").performClick()
            waitForText(Labels.BACKUP_OLD_ANDROID)
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }
        compose.onNodeWithText(Labels.BACKUP_OLD_ANDROID).performScrollTo()
        save("settings-android-8")
    }

    /** SPEC-036: with an address, the policy opens in a browser; without a browser, the address is shown instead. */
    @Test
    fun spec036_the_privacy_policy_opens_in_a_browser_or_shows_its_address() {
        val url = "https://example.invalid/privacy"
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            App(open = { app.container.core() }, onDataChanged = { app.container.afterWrite() }, phone = AndroidPhone(activity), privacyPolicyUrl = url)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("설정").performClick()
        val policy = row("개인정보처리방침, 브라우저에서 열기").performScrollTo()
        policy.performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)

        shadowOf(app).checkActivities(true)
        policy.performClick()
        waitForText(Labels.noBrowser(url))
        compose.saveScreen("settings-no-browser", activity)
    }

    @Test
    fun tc098_the_mail_draft_contains_only_the_approved_fields_and_opens_once() {
        app.seedWeek(family = true)
        val activity = openSettings()
        val feedback = compose.onNode(hasText("의견 보내기") and hasClickAction()).performScrollTo()
        feedback.performClick()
        feedback.performClick()

        val draft = shadowOf(activity).nextStartedActivity
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        assertEquals(Intent.ACTION_SENDTO, draft.action)
        assertEquals("mailto:elddlwkd@gmail.com", draft.dataString)
        assertEquals(setOf(Intent.EXTRA_SUBJECT, Intent.EXTRA_TEXT), draft.extras!!.keySet())
        assertEquals("[필받음] 의견", draft.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(
            "앱 버전: ${info.versionName} (${info.longVersionCode})\n" +
                "Android 버전: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "기기 모델: ${Build.MANUFACTURER} ${Build.MODEL}",
            draft.getStringExtra(Intent.EXTRA_TEXT),
        )
        assertNull(draft.clipData)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun tc098_feedback_and_no_mail_boards_at_100_percent() = feedbackAndNoMail(1f, "100")

    @Test
    fun tc098_feedback_and_no_mail_boards_at_200_percent() = feedbackAndNoMail(2f, "200")

    private fun feedbackAndNoMail(scale: Float, percent: String) {
        val seed = app.seedWeek(family = true)
        runBlocking { assertTrue(core.archiveIntake(seed.morning)) }
        RuntimeEnvironment.setFontScale(scale)
        val activity = openSettings()
        shadowOf(app).checkActivities(true)
        val medical = compose.onNodeWithText(Labels.SETTINGS_MEDICAL).performScrollTo()
        val feedback = compose.onNode(hasText("의견 보내기") and hasClickAction())
        feedback.assert(hasText("메일 앱이 열려요. 앱 버전·Android 버전·기기 모델만 채워요."))
        val feedbackBounds = feedback.fetchSemanticsNode().boundsInRoot
        val policyBounds = row("개인정보처리방침, 브라우저에서 열기").fetchSemanticsNode().boundsInRoot
        assertTrue(policyBounds.bottom <= feedbackBounds.top)
        assertTrue(feedbackBounds.bottom <= medical.fetchSemanticsNode().boundsInRoot.top)
        assertTrue(feedbackBounds.height / compose.density.density >= 56f)
        compose.assertTargetsAtLeast48dp()
        save("settings-feedback-$percent")

        feedback.performClick()
        val title = inDialog("메일 앱을 열 수 없어요. 이 주소로 보내 주세요")
        val address = inDialog("elddlwkd@gmail.com")
        val copy = inDialog("복사")
        val close = inDialog("닫기")
        val rows = listOf(title, address, copy, close).map { it.fetchSemanticsNode().boundsInRoot }
        assertTrue(rows.zipWithNext().all { (above, below) -> above.bottom <= below.top })
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        assertTrue("bottom sheet", dialog.bottom - rows.last().bottom <= 24 * compose.density.density)
        compose.assertTargetsAtLeast48dp()
        save("settings-no-mail-$percent")

        copy.performClick()
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        assertEquals("elddlwkd@gmail.com", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertNull(ShadowToast.getTextOfLatestToast())
        val currentSdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.S_V2)
        try {
            copy.performClick()
            assertEquals("주소를 복사했어요", ShadowToast.getTextOfLatestToast())
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", currentSdk)
        }
        close.performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        feedback.assertExists()
    }

    /** SCR-009#loading while the settings are read (grey cells), and the read error with [다시 시도]. */
    @Test
    fun grey_cells_while_reading_and_try_again_after_a_failed_read() {
        app.seedWeek(family = false)
        open()
        // The database is held in a write so the settings wait for it.
        val held = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val holder = thread {
            runBlocking {
                core.db.useWriterConnection { transactor ->
                    transactor.immediateTransaction {
                        held.countDown()
                        gate.await(20, TimeUnit.SECONDS)
                    }
                }
            }
        }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            compose.onNodeWithText("설정").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("하루 시작 시각, ${Labels.LOADING}").fetchSemanticsNodes().isNotEmpty()
            }
            compose.assertTargetsAtLeast48dp()
            save("settings-loading")
        } finally {
            gate.countDown()
            holder.join(20_000)
        }
        waitForRow("하루 시작 시각, 4시")

        // Opened again on a database that cannot be read.
        controller!!.get().onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { core.db.close() }
        compose.onNodeWithText("설정").performClick()
        waitForText(Labels.SETTINGS_LOAD_FAILED)
        compose.onNodeWithText("다시 시도").assertExists()
        compose.assertTargetsAtLeast48dp()
        save("settings-error")
    }

    /** SCR-009#error: a change that cannot be saved says so and the old value stays. */
    @Test
    fun a_failed_save_keeps_the_old_value() {
        openSettings()
        runBlocking { core.db.close() }
        namesSwitch().performClick()
        waitForText(Labels.SAVE_FAILED)
        namesSwitch().assertIsOff()
        save("settings-save-failed")
    }

    /**
     * 200% text: a value too wide for its line goes under its name, short ones stay beside it, every target is 48 dp
     * (REQ-067); an open picker and what was chosen in it survive a recreated activity (BUG-003).
     */
    @Test
    fun large_text_puts_wide_values_under_their_names_and_keeps_an_open_picker() {
        app.seedWeek(family = true)
        RuntimeEnvironment.setFontScale(2f)
        openSettings()
        val quiet = row("방해 금지, 22시부터 7시까지").fetchSemanticsNode().boundsInRoot
        val perDay = row("하루 최대, 3번").fetchSemanticsNode().boundsInRoot
        val dp = compose.density.density
        assertTrue("the quiet hours under the name: ${quiet.height / dp} dp", quiet.height / dp >= 90f)
        assertTrue("3번 beside the name: ${perDay.height / dp} dp", perDay.height / dp < 70f)
        compose.assertTargetsAtLeast48dp()
        save("settings-200")

        // Start 23:00, then [끝] at 08:00: after the recreation the end's cells are still the ones shown.
        row("방해 금지, 22시부터 7시까지").performClick()
        compose.onNodeWithContentDescription("시작 23시").performScrollTo().performClick()
        compose.onNodeWithContentDescription("끝, 7시").performScrollTo().performClick()
        compose.onNodeWithContentDescription("끝 8시").performScrollTo().performClick()
        controller!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("끝 8시").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("끝 8시").assertIsSelected()
        compose.onNodeWithContentDescription("23시부터 8시까지").performScrollTo()
        compose.assertTargetsAtLeast48dp()
        save("settings-quiet-200")
    }

    /**
     * 200% text: in the base-zone list an offset goes under its zone, so `Asia/Seoul` keeps one line. The accessibility
     * check (run 43579503348) found it broken into a letter or two a line beside `UTC+09:00 · 이 휴대폰`. An offset also
     * goes under a zone with a word too wide to sit beside it: run 43582314689 and this render showed `Africa/Addis_Ababa`
     * broken into `Africa/`, `Addis_Aba`, `ba` beside `UTC+03:00`.
     *
     * This render is not the phone's in one way: Compose takes a text's min intrinsic width from `java.text.BreakIterator`,
     * which on a phone is ICU and breaks after '/', and on this JVM is the JDK's and does not. So here every zone counts
     * as one word and every offset goes under; on the emulator (run 43585038873) `Africa/Abidjan` keeps its offset
     * beside, `Africa/` over `Abidjan`, and only names like `Africa/Addis_Ababa` move it under.
     */
    @Test
    fun large_text_puts_zone_offsets_under_their_names() {
        app.seedWeek(family = false)
        RuntimeEnvironment.setFontScale(2f)
        openSettings()
        row("기준 시간대, Asia/Seoul").performScrollTo().performClick()
        val seoul = compose.onNodeWithContentDescription("Asia/Seoul, UTC+09:00 · 이 휴대폰").fetchSemanticsNode().boundsInRoot
        val height = seoul.height / compose.density.density
        // The name's line over the offset's, 4 dp between and 6 dp above and below: about 87 dp in this render; a font
        // that wraps the offset or breaks the name at '/' adds a line. Broken a letter or two a line, it was eight
        assertTrue("the offset under the name: $height dp", height in 80f..150f)
        val addis = "Africa/Addis_Ababa, UTC+03:00"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(addis))
        val addisHeight = compose.onNodeWithContentDescription(addis).fetchSemanticsNode().boundsInRoot.height / compose.density.density
        // `Africa/Addis_Ababa` (about 242 dp of the 288) on one line over its offset, like Seoul's; beside the offset its
        // three lines took 115 dp
        assertTrue("Addis_Ababa whole on one line, its offset under it: $addisHeight dp", addisHeight < 100f)
        compose.assertTargetsAtLeast48dp()
        save("settings-zone-200")
    }

    /** REQ-094, SPEC-021: SCR-010's back returns to settings, settings' back to the first screen. */
    @Test
    fun back_goes_from_people_to_settings_and_from_settings_to_the_first_screen() {
        val activity = openSettings()
        row("대상자 관리, 1명").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(Labels.PEOPLE_NOTE).fetchSemanticsNodes().isNotEmpty() }
        activity.onBackPressedDispatcher.onBackPressed()
        waitForRow("대상자 관리, 1명")
        activity.onBackPressedDispatcher.onBackPressed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(activity.isFinishing)
    }

    // ---------------------------------------------------------------------------------------------------------------

    private fun open(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        return controller!!.get()
    }

    /** Opens the app and `[설정]`, and waits for the values. */
    private fun openSettings(): MainActivity {
        val activity = open()
        compose.onNodeWithText("설정").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("하루 시작 시각, ", substring = true).fetchSemanticsNodes()
                .any { node -> node.config[SemanticsProperties.ContentDescription].none { it.endsWith(Labels.LOADING) } }
        }
        return activity
    }

    private fun screenLock(set: Boolean) = shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(set)

    /** A row, by what TalkBack reads for it. */
    private fun row(sentence: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(sentence).assertExists()

    private fun waitForRow(sentence: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(sentence).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun namesSwitch(): SemanticsNodeInteraction =
        compose.onNodeWithContentDescription("잠금 화면에 약 이름 보이기. ${Labels.SHOW_NAMES_NOTE}")

    /** A cell or button of the open picker. */
    private fun inDialog(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    /**
     * Taps the row read as [sentence] and returns the [cell] of the picker it opens, once the picker shows. A save's work
     * after it (widgets, reminders, reading the screen again) goes on off the main thread after its message shows, and
     * once a tap made right then left no picker to find (CI run 43696668379, first attempt: no `1번`). So this waits for
     * the picker, and if none shows within 3 seconds taps the row once more and says so on stderr (the test report keeps
     * it), so a lost first tap stays visible instead of failing the run.
     */
    private fun openPicker(sentence: String, cell: String): SemanticsNodeInteraction {
        val inPicker = hasText(cell) and hasAnyAncestor(isDialog())
        val shown = { compose.onAllNodes(inPicker).fetchSemanticsNodes().isNotEmpty() }
        row(sentence).performClick()
        try {
            compose.waitUntil(3_000, shown)
        } catch (_: ComposeTimeoutException) {
            System.err.println("openPicker: no picker with '$cell' 3 s after tapping '$sentence'; tapping it again")
            row(sentence).performClick()
            compose.waitUntil(10_000, shown)
        }
        return compose.onNode(inPicker)
    }

    /** The system time picker the row opened. */
    private fun timePicker(): TimePickerDialog {
        compose.waitForIdle()
        return ShadowDialog.getLatestDialog() as TimePickerDialog
    }

    /** Sets [picker] to [hour]:[minute] and presses its OK, as a person would. */
    private fun pick(picker: TimePickerDialog, hour: Int, minute: Int) {
        picker.updateTime(hour, minute)
        picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        compose.waitForIdle()
    }

    /** When the day-boundary refresh is set for (`DayBoundaryAlarm`). */
    private fun boundaryAlarmAt(): Long {
        val alarms = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
        return alarms.single { shadowOf(it.operation).savedIntent.action == DayBoundaryAlarm.ACTION }.triggerAtMs
    }

    private fun judgments(intakeId: String): Int = runBlocking { core.db.doseRecordDao().listByIntake(intakeId).size }

    private fun save(case: String) = compose.saveScreen(case, controller!!.get())
}
