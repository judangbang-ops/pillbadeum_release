package com.judangbang.pillbadeum.debug

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
 * Debug-only (TASK-042, case FK of the widget scroll check; BUG-004). WorkManager turns its
 * RescheduleReceiver on when the first work is enqueued, and that switch is a PACKAGE_CHANGED for
 * this package: the AppWidgetService then drops the views of the package's widgets and asks for an
 * update. Glance 1.2.0 enqueues a 10-year work with its first session so the switch happens only once,
 * but that once is the first widget's first session.
 *
 * Enqueuing the same kind of work before any widget is placed (what the app could do at start) moves
 * the switch away from the first widget. Case FK places the first widget after this; case F without.
 * It is not in the product.
 */
object KeepWork {
    private const val NAME = "probe-keep-reschedule-receiver"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequest.Builder(NoOpWorker::class.java)
            .setInitialDelay(3650, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        Log.i("WidgetHostActivity", "keep-work enqueued")
    }
}

class NoOpWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
