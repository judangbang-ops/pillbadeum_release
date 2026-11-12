package com.judangbang.pillbadeum.debug

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Debug-only experiment for the widget check (TASK-042). Glance runs its sessions as WorkManager work,
 * and WorkManager turns its RescheduleReceiver on while work is pending and off when none is left.
 * Each switch is a PACKAGE_CHANGED broadcast, and a launcher reloads the package's widgets on it,
 * showing the loading layout until the next render (seen in the release-variant check, 2026-11-12).
 *
 * One unique work far in the future keeps work pending, so the receiver stays on and the switching
 * stops. The scroll check runs the launcher case with and without it to see whether that is the cause.
 * It is not in the product.
 */
object KeepWork {
    private const val NAME = "probe-keep-reschedule-receiver"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<NoOpWorker>().setInitialDelay(3650, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        Log.i("WidgetHostActivity", "keep-work enqueued")
    }
}

class NoOpWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
