package com.judangbang.pillbadeum.reminder

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.domain.ScheduleRule
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNotificationManager
import java.util.TimeZone as JavaTimeZone

/**
 * BUG-007: the reminders when the system lists a notification some time after the app posts it, as Android does
 * ([LaggingNotificationManager]). Robolectric's own NotificationManager lists it at once, so [ReminderFlowTest] cannot
 * see this.
 *
 * Found by the reminder emulator check on API 36 (PR #44, run 43798196358, RR-91): the next window's alarm came while
 * the last window's reminders were still up, and one of the four new reminders never showed. The system log had
 * "Cannot find enqueued record" for its key: the review after the new reminders still read the old one of that item and
 * cancelled it, and the cancel took the new one, not listed yet, as well.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReminderTestApp::class, shadows = [LaggingNotificationManager::class])
class ReminderListLagTest {
    private val app: ReminderTestApp get() = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val system: LaggingNotificationManager get() = Shadow.extract(notificationManager)
    private val seoul = TimeZone.of("Asia/Seoul")
    private lateinit var savedZone: JavaTimeZone

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        savedZone = JavaTimeZone.getDefault()
        JavaTimeZone.setDefault(JavaTimeZone.getTimeZone("Asia/Seoul"))
        LaggingNotificationManager.reset()
    }

    @After
    fun tearDown() {
        LaggingNotificationManager.reset()
        JavaTimeZone.setDefault(savedZone)
    }

    /**
     * RR-91: the 13th's 08:00 alarm comes at 08:40 while the 12th's reminders are still up (no day boundary in between).
     * The system lists nothing new before the collect ends, as when it is slower than the collect's wait: every item
     * still gets its new reminder, because the old ones are taken off before the new ones go up.
     */
    @Test
    fun the_next_window_s_alarm_replaces_every_reminder_left_from_the_last_window() {
        val ids = seed("PillA", "PillB", "PillC", "PillD")
        ring(at(8, 0))
        assertEquals("the 12th's reminders", ids.associateWith { "2026-11-12" }, listed())
        assertNotNull(lockNotice())

        LaggingNotificationManager.lag = LaggingNotificationManager.UNTIL_CAUGHT_UP
        ring(at(8, 40, day = 13))
        system.catchUp()
        assertEquals("each item's reminder of the 13th", ids.associateWith { "2026-11-13" }, listed())
        assertNotNull("\"복용 시간이에요\" (SPEC-013)", lockNotice())
    }

    /**
     * The inexact path's two alarms ([ReminderAlarm]) ring together, so a second collect comes right after the first. It
     * reads the list after the first collect's reminders are on it and leaves them and "복용 시간이에요" up.
     */
    @Test
    fun a_second_alarm_right_after_the_first_leaves_the_reminders_and_the_lock_screen_notice() {
        val ids = seed("PillA", "PillB")
        LaggingNotificationManager.lag = LAG_MILLIS
        ring(at(8, 0))
        ring(at(8, 0))
        Thread.sleep(2 * LAG_MILLIS)
        assertEquals(ids.associateWith { "2026-11-12" }, listed())
        assertNotNull("\"복용 시간이에요\" (SPEC-013)", lockNotice())
        assertTrue("nothing left waiting", LaggingNotificationManager.waitingCount() == 0)
    }

    // ---------------------------------------------------------------------------------------------

    private fun at(hour: Int, minute: Int, day: Int = 12): Instant = LocalDateTime(2026, 11, day, hour, minute).toInstant(seoul)

    /** Items with reminders from 08:00 through the app's use cases, then the app's after-write step. */
    private fun seed(vararg names: String): List<String> = runBlocking {
        val core = app.container.core()
        val person = core.people.ensureDefault().first()
        val ids = names.map { name ->
            val saved = core.saveIntake.create(IntakeDraft(personId = person.id, name = name, rule = ScheduleRule.Daily))
            val id = (saved as SaveIntakeResult.Saved).intakeId
            assertTrue("reminder of $name", core.reminders.saveRule(id, enabled = true, start = LocalTime(8, 0)) is SaveReminderResult.Saved)
            id
        }
        // Its widget refresh fails under Robolectric (no WorkManager); the reminder step runs anyway.
        runCatching { app.container.afterWrite() }
        ids
    }

    /** What [ReminderReceiver] does when the alarm rings, at [time]. */
    private fun ring(time: Instant) = runBlocking {
        app.clock.now = time
        Reminders.collect(app, alarmRang = true)
    }

    /** The item reminders the system lists: item to the period key it was made for. */
    private fun listed(): Map<String, String> =
        ReminderNotifications.shownDoses(app).associate { it.content.intakeId to it.content.periodKey }

    private fun lockNotice(): StatusBarNotification? =
        notificationManager.activeNotifications.firstOrNull { it.id == ReminderNotifications.ID_LOCK && it.tag == null }

    private companion object {
        /** About twice what Android takes while its notification assistant looks (up to about 200 ms). */
        const val LAG_MILLIS = 400L
    }
}

/**
 * NotificationManager as Android's notification service behaves, for BUG-007 ([ReminderListLagTest]): a notification is
 * listed ([NotificationManager.getActiveNotifications]) only [lag] milliseconds after `notify`, and a `cancel` of its tag
 * and id before then takes it away too, as NotificationManagerService takes a cancelled key out of its queue as well as
 * its list. Robolectric's own shadow, which this one extends, lists a notification at once; [lag] 0 keeps that.
 */
@Implements(NotificationManager::class)
class LaggingNotificationManager : ShadowNotificationManager() {
    private class Waiting(val tag: String?, val id: Int, val notification: Notification, val postedAtNanos: Long)

    @Implementation
    override fun notify(tag: String?, id: Int, notification: Notification) {
        if (lag == 0L) {
            super.notify(tag, id, notification)
            return
        }
        synchronized(waiting) {
            waiting.removeAll { it.tag == tag && it.id == id }
            waiting += Waiting(tag, id, notification, System.nanoTime())
        }
    }

    @Implementation
    override fun cancel(tag: String?, id: Int) {
        synchronized(waiting) { waiting.removeAll { it.tag == tag && it.id == id } }
        super.cancel(tag, id)
    }

    @Implementation
    override fun getActiveNotifications(): Array<StatusBarNotification> {
        list(all = false)
        return super.getActiveNotifications()
    }

    /** The system catches up: every notification still waiting is listed, in the order it was posted. */
    fun catchUp() = list(all = true)

    private fun list(all: Boolean) {
        val now = System.nanoTime()
        val due = synchronized(waiting) {
            val due = waiting.filter { all || (lag != UNTIL_CAUGHT_UP && now - it.postedAtNanos >= lag * 1_000_000) }
            waiting.removeAll(due.toSet())
            due
        }
        for (w in due) super.notify(w.tag, w.id, w.notification)
    }

    companion object {
        /** [lag] for a system that lists nothing until [catchUp]. */
        const val UNTIL_CAUGHT_UP: Long = Long.MAX_VALUE

        /** Milliseconds from `notify` until the notification is listed; 0 lists it at once. */
        @Volatile
        var lag: Long = 0L

        private val waiting = mutableListOf<Waiting>()

        fun waitingCount(): Int = synchronized(waiting) { waiting.size }

        fun reset() {
            lag = 0L
            synchronized(waiting) { waiting.clear() }
        }
    }
}
