package com.judangbang.pillbadeum.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.ReminderLabels
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import com.judangbang.pillbadeum.usecase.SaveReminderResult
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.TimeZone as JavaTimeZone

/**
 * The reminder alarm and notifications on the real container (database in memory, [ReminderTestApp]) without an
 * emulator: one alarm (TASK-024, N-8), reminders with their buttons and the lock-screen notice (TASK-025, SPEC-013
 * method 5), what a button, a swipe or a names setting does to them (N-13), the restore notice (TASK-040, R-3) and
 * notifications switched off (SCR-003#permission).
 *
 * Robolectric keeps what the app hands to AlarmManager and NotificationManager. What the phone then does with it,
 * the lock screen, sound, grouping, Doze and a real reboot, is left for `scripts/emulator/reminder_check.py` and
 * the phones (TASK-032, TASK-052).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReminderTestApp::class)
class ReminderFlowTest {
    private val app: ReminderTestApp get() = ApplicationProvider.getApplicationContext()
    private val alarmManager get() = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val notificationManager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val receiver get() = ComponentName(app, ReminderReceiver::class.java)
    private val seoul = TimeZone.of("Asia/Seoul")
    private lateinit var savedZone: JavaTimeZone

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // The body's times follow the phone's zone (RR-58); the phone is in Seoul here, as the items.
        savedZone = JavaTimeZone.getDefault()
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("Asia/Seoul"))
    }

    @After
    fun tearDown() {
        JavaTimeZone.setDefault(savedZone)
    }

    // ---------------------------------------------------------------------------------------------
    // TASK-024: one alarm

    @Test
    fun one_alarm_for_every_item_exact_while_allowed_inexact_otherwise() {
        seed("PillA", "PillB")
        val alarm = reminderAlarm()
        assertEquals("first slot of both items (RR-54)", at(8, 0).ms, alarm.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue("rings in Doze", alarm.isAllowWhileIdle)
        assertEquals("exact", ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        val operation = shadowOf(alarm.operation)
        assertTrue(operation.isBroadcast)
        assertTrue("REQ-081", operation.isImmutable)
        assertEquals(receiver, operation.savedIntent.component)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        runBlocking { Reminders.replan(app) }
        val inexact = reminderAlarm()
        assertEquals(at(8, 0).ms, inexact.triggerAtMs)
        assertEquals("inexact", ShadowAlarmManager.WINDOW_HEURISTIC, inexact.windowLengthMs)
    }

    @Test
    fun allowing_exact_alarms_arms_the_same_alarm_exact_again() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        seed("PillA")
        assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, reminderAlarm().windowLengthMs)

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        listen()
        app.sendBroadcast(Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
        waitUntil("exact alarm after the permission broadcast") {
            reminderAlarms().singleOrNull()?.windowLengthMs == ShadowAlarmManager.WINDOW_EXACT
        }
        assertEquals(at(8, 0).ms, reminderAlarm().triggerAtMs)
    }

    // ---------------------------------------------------------------------------------------------
    // TASK-025: the reminders

    @Test
    fun the_alarm_shows_each_item_with_its_buttons_and_one_silent_lock_notice() {
        val (a, b) = seed("PillA", "PillB")
        app.clock.now = at(8, 0)
        // Through the receiver, as the alarm delivers it.
        listen()
        alarmManager.fireAlarm(reminderAlarm())
        waitUntil("two reminders and the lock notice") { doses().size == 2 && lockNotice() != null }

        for ((id, name) in listOf(a to "PillA", b to "PillB")) {
            val n = dose(id)!!.notification
            assertEquals(ReminderChannels.DOSES, n.channelId)
            assertEquals("hidden on the lock screen (method 5)", Notification.VISIBILITY_SECRET, n.visibility)
            assertNull(n.publicVersion)
            assertEquals("$name 먹어~!", n.title)
            assertEquals(listOf(ReminderLabels.TAKEN, ReminderLabels.SNOOZE, ReminderLabels.SKIP_TODAY), n.actions.map { it.title.toString() })
            val buttons = n.actions.map { shadowOf(it.actionIntent) }
            assertTrue("REQ-081", buttons.all { it.isBroadcast && it.isImmutable && it.savedIntent.component == receiver })
            assertEquals(
                listOf(ReminderReceiver.ACTION_TAKE, ReminderReceiver.ACTION_SNOOZE, ReminderReceiver.ACTION_SKIP),
                buttons.map { it.savedIntent.action },
            )
            assertEquals(id, ReminderNotifications.contentOf(buttons[0].savedIntent)!!.intakeId)
            assertEquals(ReminderReceiver.ACTION_DISMISSED, shadowOf(n.deleteIntent).savedIntent.action)
            assertEquals("goes at the window end, 04:00 (N-13)", (at(4, 0, day = 13) - at(8, 0)).inWholeMilliseconds, n.timeoutAfter)
            // The body opens the app on its first screen whatever screen it was left on, as the widget header does.
            val open = shadowOf(n.contentIntent)
            assertTrue(open.isActivity && open.isImmutable)
            assertEquals(ComponentName(app, MainActivity::class.java), open.savedIntent.component)
            assertTrue("SCR-004 (homeIntent)", open.savedIntent.getBooleanExtra(MainActivity.EXTRA_HOME, false))
        }

        val lock = lockNotice()!!
        assertNull(lock.tag)
        assertEquals(ReminderChannels.NOTICES, lock.notification.channelId)
        assertEquals(Notification.VISIBILITY_PUBLIC, lock.notification.visibility)
        assertEquals(ReminderLabels.LOCK_TITLE, lock.notification.title)
        assertTrue("no button on the lock notice", lock.notification.actions.isNullOrEmpty())

        val notices = notificationManager.getNotificationChannel(ReminderChannels.NOTICES)
        assertNull("the lock notice adds no second sound", notices.sound)
        assertFalse(notices.shouldVibrate())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, notices.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, notificationManager.getNotificationChannel(ReminderChannels.DOSES).importance)

        waitUntil("next slot 09:00") { reminderAlarms().singleOrNull()?.triggerAtMs == at(9, 0).ms }
    }

    /** W-2, W-4: the last dose is the folded line, a later skip shows when the reminder is unfolded. */
    @Test
    fun the_body_folds_to_the_last_dose_and_unfolds_to_a_later_skip() {
        app.clock.now = at(20, 0, day = 11)
        val (a, b) = seed("PillA", "PillB")
        runBlocking {
            app.clock.now = at(21, 0, day = 11)
            val core = app.container.core()
            core.record(a, DoseSource.APP, "2026-11-11")
            core.record(b, DoseSource.APP, "2026-11-11", DoseKind.SKIPPED)
        }
        ring(at(8, 0))

        val taken = dose(a)!!.notification
        assertEquals("마지막 복용 어제\u00a021:00", taken.text)
        assertNull("one line: nothing to unfold", taken.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        val skipped = dose(b)!!.notification
        assertEquals("folded: the last dose (none)", Labels.NO_RECORD, skipped.text)
        assertEquals("${Labels.NO_RECORD}\n어제 건너뜀", skipped.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
    }

    @Test
    fun taken_snooze_and_skip_from_the_reminders() {
        val (a, b) = seed("PillA", "PillB")
        ring(at(8, 0))
        val takeA = buttonIntent(a, ReminderLabels.TAKEN)

        press(a, ReminderLabels.TAKEN, at(8, 2))
        assertEquals(DoseKind.TAKEN, judgment(a))
        assertNull("PillA closes", dose(a))
        assertNotNull("PillB stays", dose(b))
        assertNotNull("the lock notice stays for PillB", lockNotice())

        // The same button again, from a copy still on another screen: nothing new, "이미 기록됨 (08:02)" for 10 s.
        runBlocking {
            app.clock.now = at(8, 3)
            runCatching { Reminders.onButton(app, takeA) }
        }
        val already = result(a)!!.notification
        assertEquals("이미 기록됨 (08:02)", already.text)
        assertEquals(10_000L, already.timeoutAfter)
        assertEquals(ReminderChannels.NOTICES, already.channelId)

        press(b, ReminderLabels.SNOOZE, at(8, 4))
        assertNull("snoozed, not judged", judgment(b))
        assertNull(dose(b))
        assertNull("no reminder left, so the lock notice goes too (N-13)", lockNotice())
        assertEquals("30 minutes after the press (N-7)", at(8, 34).ms, reminderAlarm().triggerAtMs)

        ring(at(8, 34))
        assertNotNull("PillB rings again (RR-51)", dose(b))
        assertNull("PillA is done", dose(a))
        assertNotNull(lockNotice())

        press(b, ReminderLabels.SKIP_TODAY, at(8, 40))
        assertEquals(DoseKind.SKIPPED, judgment(b))
        assertTrue(doses().isEmpty())
        assertNull(lockNotice())
        assertEquals("both judged: tomorrow's first slot", at(8, 0, day = 13).ms, reminderAlarm().triggerAtMs)
    }

    @Test
    fun a_button_from_a_window_that_ended_records_nothing_and_says_so() {
        val (a) = seed("PillA")
        ring(at(8, 0))
        val take = buttonIntent(a, ReminderLabels.TAKEN)
        runBlocking {
            app.clock.now = at(4, 1, day = 13)
            runCatching { Reminders.onButton(app, take) }
        }
        assertNull("nothing in the new window", judgment(a))
        assertEquals(ReminderLabels.STALE, result(a)!!.notification.text)
        assertNull(dose(a))
    }

    @Test
    fun swiping_reminders_away_keeps_their_slots_and_the_last_takes_the_lock_notice_off() {
        val (a, b) = seed("PillA", "PillB")
        ring(at(8, 0))
        swipe(a, at(8, 5))
        assertNotNull("PillB still shows, so does the lock notice", lockNotice())
        swipe(b, at(8, 6))
        assertNull("N-13: the last one went", lockNotice())
        assertNull(judgment(a))
        assertNull(judgment(b))
        assertEquals("REQ-042: rings again at the next slot", at(9, 0).ms, reminderAlarm().triggerAtMs)
    }

    @Test
    fun names_on_the_lock_screen_make_each_reminder_private_without_the_lock_notice_and_back() {
        seed("PillA", "PillB")
        ring(at(8, 0))
        names(true, at(8, 5))
        assertEquals(2, doses().size)
        for (dose in doses()) {
            assertEquals(Notification.VISIBILITY_PRIVATE, dose.notification.visibility)
            assertEquals(ReminderLabels.LOCK_TITLE, dose.notification.publicVersion.title)
        }
        assertNull("SPEC-034: the device setting decides, no separate notice", lockNotice())

        names(false, at(8, 6))
        assertTrue(doses().all { it.notification.visibility == Notification.VISIBILITY_SECRET })
        assertNotNull(lockNotice())
    }

    // ---------------------------------------------------------------------------------------------
    // TASK-040: after a restore

    @Test
    fun after_a_restore_only_the_restore_notice_until_it_is_acknowledged() {
        seed("PillA", "PillB")
        // The new phone: the same database from the backup, no install marker.
        app.restart()
        runBlocking { app.container.core() }
        waitUntil("restore notice") { restoreNotice() != null }
        assertEquals(ReminderLabels.RESTORED_TITLE, restoreNotice()!!.notification.title)
        assertTrue("no reminder alarm while the notice waits (R-3)", reminderAlarms().isEmpty())

        ring(at(8, 0))
        assertTrue("no dose reminder before [확인했어요]", doses().isEmpty())
        assertNull(lockNotice())
        assertNotNull(restoreNotice())

        runBlocking {
            app.clock.now = at(8, 10)
            app.container.core().acknowledgeRestore()
            afterWrite()
        }
        assertNull(restoreNotice())
        assertEquals("from the tap on: 08:00 is not shown late (RR-33, RR-73)", at(9, 0).ms, reminderAlarm().triggerAtMs)
        assertTrue(doses().isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // SCR-003#permission

    @Test
    fun with_notifications_off_nothing_shows_and_the_slot_passes() {
        val (a) = seed("PillA")
        shadowOf(notificationManager).setNotificationsEnabled(false)
        ring(at(8, 0))
        assertTrue(doses().isEmpty())
        assertNull(lockNotice())
        assertEquals(at(9, 0).ms, reminderAlarm().triggerAtMs)

        shadowOf(notificationManager).setNotificationsEnabled(true)
        runBlocking {
            app.clock.now = at(8, 30)
            afterWrite()
        }
        assertTrue("the 08:00 slot is not shown late", doses().isEmpty())
        ring(at(9, 0))
        assertNotNull(dose(a))
    }

    @Test
    fun with_the_dose_channel_switched_off_nothing_shows_not_even_the_lock_notice() {
        seed("PillA", "PillB")
        // The person switches "복용 알림" off in the system settings; an app can only lower a channel's importance.
        ReminderChannels.ensure(app)
        notificationManager.createNotificationChannel(
            NotificationChannel(ReminderChannels.DOSES, "복용 알림", NotificationManager.IMPORTANCE_NONE),
        )
        assertTrue(notificationManager.areNotificationsEnabled())
        assertEquals(NotificationManager.IMPORTANCE_NONE, notificationManager.getNotificationChannel(ReminderChannels.DOSES).importance)
        assertFalse(ReminderNotifications.canPostDoses(app))
        assertTrue("the restore notice and button results use the other channel", ReminderNotifications.canPost(app))

        ring(at(8, 0))
        assertTrue(doses().isEmpty())
        assertNull("SCR-003#permission: no lone \"복용 시간이에요\" on the notices channel", lockNotice())
        assertEquals("the slot passes", at(9, 0).ms, reminderAlarm().triggerAtMs)
        assertEquals(NotificationManager.IMPORTANCE_NONE, notificationManager.getNotificationChannel(ReminderChannels.DOSES).importance)
    }

    // ---------------------------------------------------------------------------------------------

    private fun at(hour: Int, minute: Int, day: Int = 12): Instant = LocalDateTime(2026, 11, day, hour, minute).toInstant(seoul)

    private val Instant.ms: Long get() = toEpochMilliseconds()

    /**
     * Registers a [ReminderReceiver] at run time. Robolectric does not deliver broadcasts to the receiver in the
     * manifest here ("Matching wrappers: []"), so this covers the receiver's own code (action, goAsync, container);
     * its manifest entry (not exported, the permission action) is checked on the merged manifest instead.
     */
    private fun listen() {
        val filter = IntentFilter().apply {
            for (action in listOf(
                ReminderAlarm.ACTION,
                ReminderReceiver.ACTION_TAKE,
                ReminderReceiver.ACTION_SKIP,
                ReminderReceiver.ACTION_SNOOZE,
                ReminderReceiver.ACTION_DISMISSED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            )) addAction(action)
        }
        app.registerReceiver(ReminderReceiver(), filter, Context.RECEIVER_NOT_EXPORTED)
    }

    /** Items with reminders from [start] through the app's use cases, then the app's after-write step. */
    private fun seed(vararg names: String, start: LocalTime = LocalTime(8, 0)): List<String> = runBlocking {
        val core = app.container.core()
        val person = core.people.ensureDefault().first()
        val ids = names.map { name ->
            val saved = core.saveIntake.create(IntakeDraft(personId = person.id, name = name, rule = ScheduleRule.Daily))
            val id = (saved as SaveIntakeResult.Saved).intakeId
            assertTrue("reminder of $name", core.reminders.saveRule(id, enabled = true, start = start) is SaveReminderResult.Saved)
            id
        }
        afterWrite()
        ids
    }

    /** `AppContainer.afterWrite`. Its widget refresh fails under Robolectric (no WorkManager); the reminder step runs anyway. */
    private suspend fun afterWrite() {
        runCatching { app.container.afterWrite() }
    }

    /** What [ReminderReceiver] does when the alarm rings, at [time]. */
    private fun ring(time: Instant) = runBlocking {
        app.clock.now = time
        Reminders.collect(app)
    }

    /** The intent of [title] on [id]'s reminder, as the system delivers it to [ReminderReceiver]. */
    private fun buttonIntent(id: String, title: String): Intent =
        shadowOf(dose(id)!!.notification.actions.single { it.title.toString() == title }.actionIntent).savedIntent

    /** What [ReminderReceiver] does when [title] is pressed on [id]'s reminder at [time]. */
    private fun press(id: String, title: String, time: Instant) {
        val intent = buttonIntent(id, title)
        runBlocking {
            app.clock.now = time
            // Its after-write refreshes the widgets, which fails under Robolectric.
            runCatching { Reminders.onButton(app, intent) }
        }
    }

    /** The person swipes [id]'s reminder away at [time]: the system takes it off and sends its delete intent. */
    private fun swipe(id: String, time: Instant) {
        val delete = shadowOf(dose(id)!!.notification.deleteIntent).savedIntent
        assertEquals(ReminderReceiver.ACTION_DISMISSED, delete.action)
        notificationManager.cancel(id, ReminderNotifications.ID_DOSE)
        runBlocking {
            app.clock.now = time
            Reminders.onDismissed(app)
        }
    }

    private fun names(show: Boolean, time: Instant) = runBlocking {
        app.clock.now = time
        app.container.core().settings.setShowNamesOnLock(show)
        afterWrite()
    }

    private fun judgment(id: String): DoseKind? = runBlocking {
        app.container.core().loadStatusBoard().items.single { it.intakeId == id }.judgment?.kind
    }

    private fun reminderAlarms(): List<ShadowAlarmManager.ScheduledAlarm> =
        alarmManager.scheduledAlarms.filter { alarm -> alarm.operation?.let { shadowOf(it).savedIntent.action } == ReminderAlarm.ACTION }

    private fun reminderAlarm(): ShadowAlarmManager.ScheduledAlarm = reminderAlarms().single()

    private fun active(): List<StatusBarNotification> = notificationManager.activeNotifications.toList()

    private fun doses(): List<StatusBarNotification> = active().filter { it.id == ReminderNotifications.ID_DOSE }

    private fun dose(id: String): StatusBarNotification? = doses().firstOrNull { it.tag == id }

    private fun lockNotice(): StatusBarNotification? = active().firstOrNull { it.id == ReminderNotifications.ID_LOCK }

    private fun restoreNotice(): StatusBarNotification? = active().firstOrNull { it.id == ReminderNotifications.ID_RESTORE }

    private fun result(id: String): StatusBarNotification? =
        active().firstOrNull { it.id == ReminderNotifications.ID_RESULT && it.tag == id }

    private val Notification.title: String? get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private val Notification.text: String? get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** Work started by a receiver runs on the container's scope: runs the main looper and waits for [done]. */
    private fun waitUntil(what: String, timeoutMillis: Long = 10_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < end) { "timed out: $what" }
            Thread.sleep(20)
        }
    }
}
