package com.judangbang.pillbadeum.ui

import android.Manifest
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.usecase.ReminderChoice
import com.judangbang.pillbadeum.usecase.SaveReminderResult
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowDialog
import java.util.TimeZone as JavaTimeZone

/**
 * TASK-023, SCR-006's reminder part in the real app on Thursday 11/12 at 09:30 in Seoul (Android 15). Covered: `[알림 받기]`
 * asks for the notification permission when the app does not hold it (REQ-049, SPEC-028) and not when it does; a refusal
 * shows SCR-006#permission under the switch with `[설정 열기]` to the app's notification settings, and the item is saved
 * switched on all the same, so the first screen says it too (REQ-089); the first time follows the slot until one is picked
 * (supplement N-3); a 자기 전 save says what quiet hours take before going back (SCR-006#quiet, SPEC-031, REQ-077); an item
 * with its reminder on opens with it and can switch it off; the input survives a recreated activity (BUG-003); 200% text.
 * Each case is saved as `build/app-render/item-reminder-*.png` with its semantics tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class, qualifiers = "ko-rKR-w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ItemReminderTest {
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
    }

    @After
    fun tearDown() {
        JavaTimeZone.setDefault(savedZone)
        RuntimeEnvironment.setFontScale(1f)
    }

    /**
     * REQ-049, REQ-089 (SPEC-028): turning `[알림 받기]` on asks for POST_NOTIFICATIONS; nothing is said under the switch
     * while the system window is open. Refused: SCR-006#permission and `[설정 열기]`; saved switched on all the same, and
     * the first screen's band says it too (SCR-004#permission).
     */
    @Test
    fun req49_switching_on_asks_for_the_permission_and_a_refusal_says_so_under_the_switch() {
        notificationsAllowed(false)
        val activity = openNewItem()
        nameField().performTextInput("혈압약")
        switch().performScrollTo().performClick()

        val request = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        assertGone(Labels.ITEM_NOTIFICATIONS_OFF)

        answer(activity, request, granted = false)
        waitForText(Labels.ITEM_NOTIFICATIONS_OFF)
        compose.onNodeWithText("08:00").assertExists()
        compose.onNodeWithText(Labels.OPEN_SETTINGS).performScrollTo()
        compose.assertTargetsAtLeast48dp()
        save("item-reminder-permission")

        compose.onNodeWithText(Labels.OPEN_SETTINGS).performScrollTo().performClick()
        val settings = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.action)
        assertEquals(app.packageName, settings.getStringExtra(Settings.EXTRA_APP_PACKAGE))

        compose.onNodeWithText("저장").performClick()
        waitForText(Labels.NOTIFICATIONS_OFF)
        val item = runBlocking { core.loadStatusBoard().items.single() }
        assertEquals(ReminderChoice(true, LocalTime(8, 0)), item.reminder)
    }

    /** REQ-049: allowed in the system window, nothing is said under the switch. */
    @Test
    fun allowing_leaves_nothing_under_the_switch() {
        notificationsAllowed(false)
        val activity = openNewItem()
        switch().performScrollTo().performClick()
        answer(activity, shadowOf(activity).lastRequestedPermission, granted = true)
        compose.onNodeWithText("08:00").assertExists()
        assertGone(Labels.ITEM_NOTIFICATIONS_OFF)
    }

    /**
     * SPEC-031's acceptance on the item screen (TASK-023's done condition, RR-49): 자기 전 starts at 21:00, and with 60
     * minutes, 3 a day and quiet hours 22:00~07:00 the save says two repeats go; `[확인]` goes to the first screen. The
     * permission is held already, so nothing is asked.
     */
    @Test
    fun spec31_a_bedtime_save_says_what_quiet_hours_take_then_goes_home() {
        notificationsAllowed(true)
        val activity = openNewItem()
        nameField().performTextInput("수면제")
        compose.onNodeWithText("자기 전").performScrollTo().performClick()
        switch().performScrollTo().performClick()
        assertNull("held already: nothing asked", shadowOf(activity).lastRequestedPermission)
        compose.onNodeWithText("21:00").performScrollTo().assertExists()

        compose.onNodeWithText("저장").performClick()
        waitForText(Labels.ITEM_SAVED)
        inDialog("방해 금지 시간(22:00~07:00)과 겹쳐 다시 알림이 2번 줄어요").assertExists()
        save("item-reminder-quiet")
        assertEquals(ReminderChoice(true, LocalTime(21, 0)), runBlocking { core.loadStatusBoard().items.single().reminder })

        inDialog("확인").performClick()
        waitForText("지난 기록")
        assertGone(Labels.ITEM_SAVED)
    }

    /**
     * Supplement N-3: the first time follows the slot (없음 08:00, 저녁 18:00) until one is picked in the system picker;
     * a picked time stays when the slot changes. A weekly item says on which day it rings (09 ItemForm). All of it
     * survives a recreated activity (BUG-003).
     */
    @Test
    fun the_first_time_follows_the_slot_until_picked_and_survives_recreation() {
        notificationsAllowed(true)
        openNewItem()
        switch().performScrollTo().performClick()
        compose.onNodeWithText("08:00").assertExists()
        compose.onNodeWithText("저녁").performScrollTo().performClick()
        compose.onNodeWithText("18:00").assertExists()

        compose.onNodeWithContentDescription("첫 알림 시각, 18시").performScrollTo().performClick()
        pick(timePicker(), 19, 30)
        compose.onNodeWithText("19:30").assertExists()
        compose.onNodeWithText("점심").performScrollTo().performClick()
        compose.onNodeWithText("19:30").assertExists()

        // The note is read with the switch's row, as one sentence (SwitchRow).
        compose.onNodeWithText("주 1회").performScrollTo().performClick()
        val weekly = "${Labels.REMINDER_SWITCH}. ${Labels.reminderWeekly(DayOfWeek.MONDAY)}"
        compose.onNodeWithContentDescription(weekly).assertExists()

        controller!!.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(weekly).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("19:30").assertExists()
        compose.onNodeWithText(Labels.REMINDER_ELSEWHERE).performScrollTo()
        save("item-reminder-weekly")
    }

    /** An item with its reminder on opens with it, at its saved time; switched off and saved, the rule is off. */
    @Test
    fun an_item_with_its_reminder_on_opens_with_it_and_can_switch_it_off() {
        notificationsAllowed(true)
        val seed = app.seedWeek(family = false)
        runBlocking { assertTrue(core.reminders.saveRule(seed.morning, enabled = true, start = LocalTime(7, 30)) is SaveReminderResult.Saved) }
        open()
        compose.onAllNodes(editTile() and hasContentDescription("아침약", substring = true)).onFirst().performClick()
        compose.onNodeWithText("07:30").performScrollTo().assertExists()

        switch().performScrollTo().performClick()
        assertGone("07:30")
        compose.onNodeWithText("저장").performClick()
        waitForText("지난 기록")
        assertEquals(false, runBlocking { core.reminders.rule(seed.morning)?.enabled })
    }

    /**
     * 200% text: the reminder rows keep whole words and every target stays at least 48 dp (REQ-067). At 360 dp the time
     * button still fits beside its name; a narrower row puts it under the name ([EndOrBelow]).
     */
    @Test
    fun at_200_percent_the_reminder_rows_keep_their_targets() {
        RuntimeEnvironment.setFontScale(2f)
        notificationsAllowed(true)
        openNewItem()
        switch().performScrollTo().performClick()
        compose.onNodeWithText("08:00").assertExists()
        compose.onNodeWithText(Labels.REMINDER_ELSEWHERE).performScrollTo()
        compose.assertTargetsAtLeast48dp()
        save("item-reminder-200")
    }

    // ---------------------------------------------------------------------------------------------------------------

    private fun open(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("하루 시작", substring = true).fetchSemanticsNodes().isNotEmpty() }
        return controller!!.get()
    }

    private fun openNewItem(): MainActivity {
        val activity = open()
        compose.onNodeWithText("항목 추가").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasSetTextAction() and hasContentDescription("약 이름")).fetchSemanticsNodes().isNotEmpty() }
        return activity
    }

    /** Whether the app holds POST_NOTIFICATIONS; on Android 13 and later the app's notifications are off without it. */
    private fun notificationsAllowed(allowed: Boolean) {
        if (allowed) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS) else shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(allowed)
    }

    /** The system window's answer, handed to the activity as Android hands it. */
    @Suppress("DEPRECATION")
    private fun answer(activity: MainActivity, request: ShadowActivity.PermissionsRequest, granted: Boolean) {
        notificationsAllowed(granted)
        val result = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(result))
        compose.waitForIdle()
    }

    private fun nameField(): SemanticsNodeInteraction = compose.onNode(hasSetTextAction() and hasContentDescription("약 이름"))

    /** `[알림 받기]`, the one switch of the item screen. */
    private fun switch(): SemanticsNodeInteraction = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

    /** A first-screen tile, which opens SCR-006 for its item. */
    private fun editTile(): SemanticsMatcher =
        SemanticsMatcher("opens 항목 수정") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "항목 수정" }

    private fun inDialog(text: String): SemanticsNodeInteraction = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun timePicker(): TimePickerDialog {
        compose.waitForIdle()
        return ShadowDialog.getLatestDialog() as TimePickerDialog
    }

    private fun pick(picker: TimePickerDialog, hour: Int, minute: Int) {
        picker.updateTime(hour, minute)
        picker.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        compose.waitForIdle()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun assertGone(text: String) =
        assertEquals("$text is shown", 0, compose.onAllNodesWithText(text).fetchSemanticsNodes().size)

    private fun save(case: String) = compose.saveScreen(case, controller!!.get())
}
