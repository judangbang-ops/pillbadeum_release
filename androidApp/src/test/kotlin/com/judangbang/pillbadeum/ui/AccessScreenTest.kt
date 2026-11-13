package com.judangbang.pillbadeum.ui

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.SaveReminderResult
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers
import java.util.TimeZone as JavaTimeZone

/**
 * TASK-026, what keeps reminders from coming, in the real app on Thursday 11/12 at 09:30 in Seoul (Android 15). The
 * phone's state is set through Robolectric's shadows: app notifications, the dose channel, exact alarms, battery
 * optimization and background restriction. Covered: the first screen's band only while an item has reminders on, with
 * notifications first (SCR-004#permission, TC-015, REQ-089, REQ-050); SCR-009's `알림이 오지 않을 때` rows, on or off,
 * with their buttons (SCR-009#permission, REQ-051); the system screens the buttons open (SPEC-028, SPEC-029, SPEC-030
 * and its exception); reading again on coming back (SCR-009#stale); Android 11; 200% text. Each case is saved as
 * `build/app-render/access-*.png` with its semantics tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessScreenTest {
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
        notifications(true)
        exactAlarms(true)
        battery(unrestricted = true)
    }

    @After
    fun tearDown() {
        JavaTimeZone.setDefault(savedZone)
        RuntimeEnvironment.setFontScale(1f)
    }

    /**
     * TC-015, REQ-089 (SPEC-028): 아침약 has reminders on and the app's notifications are off. The band says so and
     * `[설정 열기]` opens the app's notification settings; exact alarms, off too, wait until notifications are on. Back with
     * notifications on, the exact-alarm band comes (REQ-050, SPEC-029) and `[허용하기]` opens this app's exact-alarm
     * screen; back with them allowed, no band is left.
     */
    @Test
    fun req89_notifications_off_come_first_and_coming_back_reads_them_again() {
        val seed = app.seedWeek(family = false)
        remindersOn(seed.morning)
        notifications(false)
        exactAlarms(false)
        val activity = open()
        waitForText(Labels.NOTIFICATIONS_OFF)
        assertGone(Labels.EXACT_ALARMS_OFF)
        compose.assertTargetsAtLeast48dp()
        save("access-home-notifications-off")

        compose.onNodeWithText(Labels.OPEN_SETTINGS).performClick()
        val settings = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.action)
        assertEquals(app.packageName, settings.getStringExtra(Settings.EXTRA_APP_PACKAGE))

        notifications(true)
        comeBack()
        waitForText(Labels.EXACT_ALARMS_OFF)
        assertGone(Labels.NOTIFICATIONS_OFF)
        save("access-home-exact-off")
        compose.onNodeWithText(Labels.ALLOW).performClick()
        val exact = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, exact.action)
        assertEquals("package:${app.packageName}", exact.dataString)

        exactAlarms(true)
        comeBack()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(Labels.EXACT_ALARMS_OFF).fetchSemanticsNodes().isEmpty() }
        assertGone(Labels.NOTIFICATIONS_OFF)
    }

    /** SCR-004#permission: with everything off but no item with reminders on, the first screen says nothing. */
    @Test
    fun the_first_screen_says_nothing_while_no_item_has_reminders_on() {
        val seed = app.seedWeek(family = false)
        notifications(false)
        exactAlarms(false)
        open()
        compose.waitForIdle()
        assertGone(Labels.NOTIFICATIONS_OFF)
        assertGone(Labels.EXACT_ALARMS_OFF)

        remindersOn(seed.vitamin)
        comeBack()
        waitForText(Labels.NOTIFICATIONS_OFF)
    }

    /** SPEC-028: the app's notifications are on but the dose channel is switched off; `[설정 열기]` opens that channel. */
    @Test
    fun spec028_a_dose_channel_switched_off_opens_its_own_settings() {
        val seed = app.seedWeek(family = false)
        remindersOn(seed.morning)
        app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(DOSES_CHANNEL, "복용 알림", NotificationManager.IMPORTANCE_NONE))
        val activity = open()
        waitForText(Labels.NOTIFICATIONS_OFF)
        compose.onNodeWithText(Labels.OPEN_SETTINGS).performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started.action)
        assertEquals(DOSES_CHANNEL, started.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(app.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    /**
     * SCR-009 `알림이 오지 않을 때` (09 Settings, REQ-089, REQ-050, REQ-051): shown also while no item has reminders on.
     * Each row says on, or what being off does with its button; the buttons open the app's notification settings, its
     * exact-alarm screen and the battery optimization list (SPEC-030). Coming back reads the rows again (SCR-009#stale):
     * left out of battery optimization reads `제한 없음`, unless restricted in the background (Android 9 and later).
     * At 200% the buttons go under their text and every target stays 48 dp.
     */
    @Test
    fun settings_rows_say_on_or_off_and_open_the_system_screens() {
        app.seedWeek(family = false)
        val activity = openSettings()
        row("알림 권한, 켜짐").performScrollTo()
        row("정확한 시각 알람, 켜짐")
        row("배터리 사용 제한, 제한 없음")
        save("access-settings-on")

        notifications(false)
        exactAlarms(false)
        battery(unrestricted = false)
        comeBack()
        waitForRow("알림 권한, ${Labels.NOTIFICATIONS_OFF_NOTE}")
        row("정확한 시각 알람, ${Labels.EXACT_ALARMS_OFF_NOTE}")
        row("배터리 사용 제한, ${Labels.BATTERY_NOTE}")
        button("배터리 사용 제한 설정 열기").performScrollTo()
        compose.assertTargetsAtLeast48dp()
        save("access-settings-off")

        button("알림 권한 설정 열기").performScrollTo().performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        button("정확한 시각 알람 허용하기").performScrollTo().performClick()
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, shadowOf(activity).nextStartedActivity.action)
        button("배터리 사용 제한 설정 열기").performScrollTo().performClick()
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, shadowOf(activity).nextStartedActivity.action)

        battery(unrestricted = true)
        comeBack()
        waitForRow("배터리 사용 제한, 제한 없음")
        shadowOf(activity.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        comeBack()
        waitForRow("배터리 사용 제한, ${Labels.BATTERY_NOTE}")

        // The recreated activity has an ActivityManager of its own: optimized again, so the row stays restricted.
        battery(unrestricted = false)
        RuntimeEnvironment.setFontScale(2f)
        controller!!.recreate()
        waitForRow("알림 권한, ${Labels.NOTIFICATIONS_OFF_NOTE}")
        button("배터리 사용 제한 설정 열기").performScrollTo()
        compose.assertTargetsAtLeast48dp()
        save("access-settings-off-200")
        // Measured unclipped (the text may run past the bottom of the screen): the button stays beside the text while it
        // takes at most 45% of the row, as every row of SCR-009 does, and never covers it.
        val dp = activity.resources.displayMetrics.density
        val text = compose.onNodeWithContentDescription("배터리 사용 제한, ${Labels.BATTERY_NOTE}").fetchSemanticsNode()
        val open = compose.onNodeWithContentDescription("배터리 사용 제한 설정 열기").fetchSemanticsNode()
        val textRight = text.positionInRoot.x + text.size.width
        val textBottom = text.positionInRoot.y + text.size.height
        assertTrue(
            "200%: the button covers the text (button at ${open.positionInRoot.x / dp}, ${open.positionInRoot.y / dp} dp; " +
                "text ends at ${textRight / dp}, ${textBottom / dp} dp)",
            open.positionInRoot.x >= textRight || open.positionInRoot.y >= textBottom,
        )
    }

    /**
     * SPEC-030's exception: on a phone without the battery optimization screen the app's information opens. With not
     * even that, nothing opens and the screen says where to look (Claude's wording).
     */
    @Test
    fun spec030_without_the_screen_the_app_information_opens_or_the_screen_says_so() {
        app.seedWeek(family = false)
        battery(unrestricted = false)
        val activity = openSettings()
        shadowOf(app).checkActivities(true)
        button("배터리 사용 제한 설정 열기").performScrollTo().performClick()
        waitForText(Labels.ACCESS_OPEN_FAILED)
        assertNull(shadowOf(activity).nextStartedActivity)
        save("access-settings-no-screen")

        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
        shadowOf(app.packageManager).addResolveInfoForIntent(appInfo, settingsApp())
        button("배터리 사용 제한 설정 열기").performScrollTo().performClick()
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${app.packageName}", started.dataString)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(Labels.ACCESS_OPEN_FAILED).fetchSemanticsNodes().isEmpty() }
    }

    /** SPEC-029: before Android 12 exact alarms need no permission, so neither the band nor an off row shows. */
    @Test
    fun before_android_12_exact_alarms_are_always_allowed() {
        val seed = app.seedWeek(family = false)
        remindersOn(seed.morning)
        exactAlarms(false)
        open()
        waitForText(Labels.EXACT_ALARMS_OFF)
        val sdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.R)
        try {
            comeBack()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(Labels.EXACT_ALARMS_OFF).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("설정").performClick()
            waitForRow("정확한 시각 알람, 켜짐")
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }
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

    /** Leaves the app for the system settings and comes back to it (onPause, onResume). */
    private fun comeBack() {
        controller!!.pause().resume()
        compose.waitForIdle()
    }

    private fun notifications(on: Boolean) = shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(on)

    private fun exactAlarms(allowed: Boolean) = ShadowAlarmManager.setCanScheduleExactAlarms(allowed)

    private fun battery(unrestricted: Boolean) =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, unrestricted)

    private fun remindersOn(intakeId: String) = runBlocking {
        assertTrue(core.reminders.saveRule(intakeId, enabled = true, start = LocalTime(8, 0)) is SaveReminderResult.Saved)
    }

    /** The settings app as the one activity that opens an intent. */
    private fun settingsApp(): ResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = "com.android.settings"
            name = "com.android.settings.AppInfo"
            applicationInfo = ApplicationInfo().apply { packageName = "com.android.settings" }
        }
    }

    /** A row, by what TalkBack reads for it. */
    private fun row(sentence: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(sentence).assertExists()

    private fun button(description: String): SemanticsNodeInteraction = compose.onNodeWithContentDescription(description).assertExists()

    private fun waitForRow(sentence: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(sentence).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun assertGone(text: String) =
        assertEquals("$text is shown", 0, compose.onAllNodesWithText(text).fetchSemanticsNodes().size)

    private fun save(case: String) = compose.saveScreen(case, controller!!.get())

    private companion object {
        /** R3's `ReminderChannels.DOSES` (PR #20), read by `AndroidPhone`. */
        const val DOSES_CHANNEL = "dose_reminders"
    }
}
