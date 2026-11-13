package com.judangbang.pillbadeum.reminder

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.R
import com.judangbang.pillbadeum.status.ReminderContent
import com.judangbang.pillbadeum.status.ReminderLabels
import com.judangbang.pillbadeum.usecase.PlanReminders
import kotlin.time.Duration
import kotlin.time.Instant

/** A dose reminder on screen, read back from its notification. */
internal data class PostedDose(val content: ReminderContent, val visibility: Int)

/**
 * The reminder notifications (TASK-025, TASK-040, screens.md SCR-003). Platform `Notification` API only, as
 * in the lock-screen experiment (PR #15).
 *
 * - One notification per item: tag = intake id, id [ID_DOSE]. A new slot of the same item replaces it and
 *   rings again (N-13). Its buttons carry the item and the period key it was made for (REQ-045).
 * - Lock screen (SPEC-013, PR #15 method 5): with names hidden (the default) every item notification is
 *   `VISIBILITY_SECRET` and one separate `VISIBILITY_PUBLIC` "복용 시간이에요" ([ID_LOCK]) stands in for all
 *   of them, without sound, name or button. With names shown an item notification is `VISIBILITY_PRIVATE`
 *   with the public version "복용 시간이에요", so the device's lock-screen setting decides.
 * - The restore notice ([ID_RESTORE], R-3) and a button's result ([ID_RESULT], tag = intake id) go to the
 *   silent channel.
 */
internal object ReminderNotifications {
    const val ID_DOSE: Int = 1
    const val ID_LOCK: Int = 2
    const val ID_RESTORE: Int = 3
    const val ID_RESULT: Int = 4

    /** design/base.tokens.json `color.ink.default` (09 mockup: the accent of the notifications). */
    private const val ACCENT: Int = 0xFF15171C.toInt()

    private const val KEY = "com.judangbang.pillbadeum.reminder."
    private const val KEY_INTAKE = KEY + "intake_id"
    private const val KEY_PERIOD = KEY + "period_key"
    private const val KEY_NAME = KEY + "name"
    private const val KEY_TITLE = KEY + "title"
    private const val KEY_TEXT = KEY + "text"
    private const val KEY_PERSON = KEY + "person"
    private const val KEY_SKIP = KEY + "skip"
    private const val KEY_SNOOZE = KEY + "can_snooze"
    private const val KEY_END = KEY + "window_end"

    private fun manager(context: Context): NotificationManager? = context.getSystemService(NotificationManager::class.java)

    /** False while the app may not post (Android 13+ permission refused, or notifications off for the app). */
    fun canPost(context: Context): Boolean = manager(context)?.areNotificationsEnabled() == true

    /**
     * [canPost] and the dose reminders channel not switched off in the system settings: SCR-003#permission covers both
     * ("알림 권한 거부, 알림 채널 끔"), and the app's guidance (TASK-026) tests the same. A channel not made yet counts as on.
     */
    fun canPostDoses(context: Context): Boolean {
        if (!canPost(context)) return false
        val channel = manager(context)?.getNotificationChannel(ReminderChannels.DOSES) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun visibilityFor(showNames: Boolean): Int =
        if (showNames) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_SECRET

    /** Shows each item's reminder, replacing one already on screen (it rings again), and the lock-screen notice. */
    fun postDoses(context: Context, contents: List<ReminderContent>, showNames: Boolean, now: Instant) {
        if (contents.isEmpty()) return
        val manager = manager(context) ?: return
        ReminderChannels.ensure(context)
        for (content in contents) {
            manager.cancel(content.intakeId, ID_RESULT)
            manager.notify(content.intakeId, ID_DOSE, dose(context, content, showNames, now, alertAgain = true))
        }
        val others = shownDoses(context).map { it.content }.filter { shown -> contents.none { it.intakeId == shown.intakeId } }
        postLockNotice(context, others + contents, showNames, now, replace = true)
    }

    /** Shows [content] again without a new sound: names on the lock screen changed (SPEC-034), or [30분 뒤] went. */
    fun repost(context: Context, content: ReminderContent, showNames: Boolean, now: Instant) {
        val manager = manager(context) ?: return
        manager.notify(content.intakeId, ID_DOSE, dose(context, content, showNames, now, alertAgain = false))
    }

    /** The item reminders on screen. */
    fun shownDoses(context: Context): List<PostedDose> =
        manager(context)?.activeNotifications.orEmpty()
            .filter { it.id == ID_DOSE && it.tag != null && it.packageName == context.packageName }
            .mapNotNull { sbn -> sbn.notification.extras.toContent()?.let { PostedDose(it, sbn.notification.visibility) } }

    fun cancelDose(context: Context, intakeId: String) {
        manager(context)?.cancel(intakeId, ID_DOSE)
    }

    /** Takes every dose reminder and the lock-screen notice off (restore detected, R-3). Results stay. */
    fun cancelAllDoses(context: Context) {
        val manager = manager(context) ?: return
        for (dose in shownDoses(context)) manager.cancel(dose.content.intakeId, ID_DOSE)
        manager.cancel(ID_LOCK)
    }

    /**
     * Keeps "복용 시간이에요" while names are hidden and at least one item reminder is left in [remaining]; takes
     * it off otherwise (N-13: also after the last reminder was swiped away). It is posted again only when missing.
     */
    fun syncLockNotice(context: Context, remaining: List<ReminderContent>, showNames: Boolean, now: Instant) {
        postLockNotice(context, remaining, showNames, now, replace = false)
    }

    private fun postLockNotice(
        context: Context,
        remaining: List<ReminderContent>,
        showNames: Boolean,
        now: Instant,
        replace: Boolean,
    ) {
        val manager = manager(context) ?: return
        if (showNames || remaining.isEmpty()) {
            manager.cancel(ID_LOCK)
            return
        }
        val shown = manager.activeNotifications.orEmpty().any { it.id == ID_LOCK && it.tag == null }
        if (shown && !replace) return
        ReminderChannels.ensure(context)
        manager.notify(ID_LOCK, lockNotice(context, until = remaining.maxOf { it.windowEnd }, now = now))
    }

    /**
     * Replaces the item's reminder with the result of its button (SCR-003#already, #stale, #error). The result
     * names the item, so it follows the lock-screen setting like the reminder. [timeout] closes it.
     */
    fun showResult(
        context: Context,
        content: ReminderContent,
        text: String,
        showNames: Boolean,
        timeout: Duration?,
    ) {
        val manager = manager(context) ?: return
        manager.cancel(content.intakeId, ID_DOSE)
        if (!canPost(context)) return
        ReminderChannels.ensure(context)
        val builder = Notification.Builder(context, ReminderChannels.NOTICES)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setColor(ACCENT)
            .setContentTitle(content.name)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setVisibility(visibilityFor(showNames))
        if (showNames) builder.setPublicVersion(lockVersion(context, ReminderChannels.NOTICES))
        if (timeout != null) builder.setTimeoutAfter(timeout.inWholeMilliseconds)
        manager.notify(content.intakeId, ID_RESULT, builder.build())
    }

    /** "백업에서 복원했어요. 앱에서 확인해 주세요" instead of dose reminders until [확인했어요] (SCR-003#restored). */
    fun showRestoreNotice(context: Context) {
        val manager = manager(context) ?: return
        if (!canPost(context)) return
        ReminderChannels.ensure(context)
        val notification = Notification.Builder(context, ReminderChannels.NOTICES)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setColor(ACCENT)
            .setContentTitle(ReminderLabels.RESTORED_TITLE)
            .setContentText(ReminderLabels.RESTORED_TEXT)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        manager.notify(ID_RESTORE, notification)
    }

    fun cancelRestoreNotice(context: Context) {
        manager(context)?.cancel(ID_RESTORE)
    }

    private fun dose(
        context: Context,
        content: ReminderContent,
        showNames: Boolean,
        now: Instant,
        alertAgain: Boolean,
    ): Notification {
        val lines = content.text.lines()
        val builder = Notification.Builder(context, ReminderChannels.DOSES)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setColor(ACCENT)
            .setContentTitle(content.title)
            // The last dose folded (W-2, W-4); a later skip only unfolded.
            .setContentText(lines.first())
            .setCategory(Notification.CATEGORY_REMINDER)
            .setShowWhen(true)
            .setWhen(now.toEpochMilliseconds())
            .setOnlyAlertOnce(!alertAgain)
            // Body tap opens the app (SCR-004); the reminder stays until a judgment, the window end or a swipe.
            .setContentIntent(openApp(context))
            .setDeleteIntent(button(context, ReminderReceiver.ACTION_DISMISSED, content))
            .addExtras(content.toBundle())
            .setVisibility(visibilityFor(showNames))
        if (showNames) builder.setPublicVersion(lockVersion(context, ReminderChannels.DOSES))
        if (lines.size > 1) builder.setStyle(Notification.BigTextStyle().bigText(content.text))
        if (content.person != null) builder.setSubText(content.person)
        // Taken off when the window ends even if the day-boundary refresh is late (N-13).
        val untilEnd = content.windowEnd - now
        if (untilEnd.isPositive()) builder.setTimeoutAfter(untilEnd.inWholeMilliseconds)
        builder.addAction(action(context, ReminderLabels.TAKEN, ReminderReceiver.ACTION_TAKE, content))
        if (content.canSnooze && now + PlanReminders.SNOOZE < content.windowEnd) {
            builder.addAction(action(context, ReminderLabels.SNOOZE, ReminderReceiver.ACTION_SNOOZE, content))
        }
        builder.addAction(action(context, content.skipLabel, ReminderReceiver.ACTION_SKIP, content))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setAllowSystemGeneratedContextualActions(false)
        return builder.build()
    }

    /** "복용 시간이에요" alone on the lock screen while names are hidden: no name, no button, no sound. */
    private fun lockNotice(context: Context, until: Instant, now: Instant): Notification {
        val builder = Notification.Builder(context, ReminderChannels.NOTICES)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setColor(ACCENT)
            .setContentTitle(ReminderLabels.LOCK_TITLE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(now.toEpochMilliseconds())
            .setContentIntent(openApp(context))
        val untilEnd = until - now
        if (untilEnd.isPositive()) builder.setTimeoutAfter(untilEnd.inWholeMilliseconds)
        return builder.build()
    }

    /** Public version of an item notification while names are shown (SPEC-013: the device setting decides). */
    private fun lockVersion(context: Context, channel: String): Notification =
        Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setColor(ACCENT)
            .setContentTitle(ReminderLabels.LOCK_TITLE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()

    private fun action(context: Context, title: String, intentAction: String, content: ReminderContent): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stat_reminder),
            title,
            button(context, intentAction, content),
        ).build()

    /**
     * Explicit broadcast to the app's own receiver, which is not exported (REQ-081). The data URI makes each
     * item, period key and button its own PendingIntent; the extras carry the reminder for the receiver.
     */
    private fun button(context: Context, intentAction: String, content: ReminderContent): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(intentAction)
            .setData(
                Uri.Builder().scheme("pillbadeum").authority("reminder")
                    .appendPath(content.intakeId).appendPath(content.periodKey).build(),
            )
            .putExtras(content.toBundle())
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * The app on its first screen, SCR-004, whatever screen it was left on (screens.md: a reminder opens SCR-004), as the
     * widget's header does ([MainActivity.homeIntent]).
     */
    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        MainActivity.homeIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The reminder a button intent carries, or null when it carries none (not one of ours). */
    fun contentOf(intent: Intent): ReminderContent? = intent.extras?.toContent()

    private fun ReminderContent.toBundle(): Bundle = Bundle().apply {
        putString(KEY_INTAKE, intakeId)
        putString(KEY_PERIOD, periodKey)
        putString(KEY_NAME, name)
        putString(KEY_TITLE, title)
        putString(KEY_TEXT, text)
        putString(KEY_PERSON, person)
        putString(KEY_SKIP, skipLabel)
        putBoolean(KEY_SNOOZE, canSnooze)
        putLong(KEY_END, windowEnd.toEpochMilliseconds())
    }

    private fun Bundle.toContent(): ReminderContent? {
        val intakeId = getString(KEY_INTAKE) ?: return null
        val periodKey = getString(KEY_PERIOD) ?: return null
        if (!containsKey(KEY_END)) return null
        return ReminderContent(
            intakeId = intakeId,
            periodKey = periodKey,
            name = getString(KEY_NAME).orEmpty(),
            title = getString(KEY_TITLE).orEmpty(),
            text = getString(KEY_TEXT).orEmpty(),
            person = getString(KEY_PERSON),
            skipLabel = getString(KEY_SKIP) ?: ReminderLabels.SKIP_TODAY,
            canSnooze = getBoolean(KEY_SNOOZE, false),
            windowEnd = Instant.fromEpochMilliseconds(getLong(KEY_END)),
        )
    }
}
