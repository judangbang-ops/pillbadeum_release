package com.judangbang.pillbadeum.widget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * BUG-004: the first widget placed after an install could stay on the loading view.
 *
 * WorkManager turns its `RescheduleReceiver` on when the first work is enqueued, and that switch is a
 * PACKAGE_CHANGED for this package. The AppWidgetService then drops the views of the package's widgets
 * and the launcher shows the initial (loading) layout until the app sends a new one. Glance 1.2.0
 * enqueues a 10-year work with its first session so the switch happens only once, but that once was the
 * first widget's first session (PR #16 report, sections 1.5 and 3.7).
 *
 * Enqueuing the same kind of work when the app starts moves the switch away from the first widget: in
 * the PR #16 check (case FK, API 34 and 36, three times each) the first widget then saw no
 * PACKAGE_CHANGED. A person usually opens the app first to add an item, so the switch happens there. If
 * a widget is placed before the app ever started, the switch happens as before and the widget recovers
 * the way case F did.
 *
 * Not used: turning the receiver on with `setComponentEnabledSetting` ourselves, which is the same
 * PACKAGE_CHANGED (the widget showed the loading view for about 10 seconds, PR #16 case P), and a newer
 * WorkManager, which still switches the receiver.
 */
object RescheduleReceiverKeeper {
    /** Unique work name; KEEP makes every later start a no-op, so the receiver is not switched again. */
    const val WORK_NAME: String = "pillbadeum-keep-reschedule-receiver"

    private const val TAG = "RescheduleReceiverKeeper"
    private const val DELAY_DAYS = 3650L

    /** Called from `Application.onCreate`. Never throws: a failure only brings back the old behaviour. */
    fun ensure(context: Context) {
        try {
            val request = OneTimeWorkRequest.Builder(NoOpWorker::class.java)
                .setInitialDelay(DELAY_DAYS, TimeUnit.DAYS)
                // Same kind of work as Glance's own keep-alive: it is not meant to run.
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Log.e(TAG, "could not enqueue the keep-alive work", e)
        }
    }
}

/** Does nothing; [RescheduleReceiverKeeper] only needs one unfinished work to exist. */
class NoOpWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
