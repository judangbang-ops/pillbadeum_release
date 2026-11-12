package com.judangbang.pillbadeum

import android.app.Application
import android.content.Context
import android.util.Log
import com.judangbang.pillbadeum.boundary.DayBoundaryAlarm
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.buildDidITakeDatabase
import com.judangbang.pillbadeum.data.didITakeDatabaseBuilder
import com.judangbang.pillbadeum.restore.FileInstallMarker
import com.judangbang.pillbadeum.restore.InstallState
import com.judangbang.pillbadeum.status.WidgetModel
import com.judangbang.pillbadeum.widget.WidgetUpdater
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DidITakeApplication : Application() {
    /** One container (and so one database) per process; Glance actions run in this process too. */
    val container: AppContainer by lazy { AppContainer(this, Clock.System) }
}

/** The app-level container of [context]'s process. */
val Context.container: AppContainer get() = (applicationContext as DidITakeApplication).container

/**
 * Manual wiring (no DI framework): the single database and [DidITakeCore] of this process.
 *
 * Window settings (day start, base zone) are read from the database with the defaults of
 * `WindowSettings`; the clock is injected here and nowhere else.
 */
class AppContainer(private val context: Context, private val clock: Clock) {
    /** Process-wide scope for short best-effort work started from receivers. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: DidITakeDatabase by lazy {
        buildDidITakeDatabase(didITakeDatabaseBuilder(context))
    }

    private val mutex = Mutex()

    @Volatile
    private var core: DidITakeCore? = null

    /**
     * Opens the core on first use: restore check with the install marker (device id), default
     * person "나". Every entry (app, widget, alarm, boot) comes through here first (supplement R-1).
     */
    suspend fun core(): DidITakeCore = core ?: mutex.withLock {
        core ?: DidITakeCore.open(database, FileInstallMarker.of(context), clock).also {
            core = it
            afterOpen(restored = it.installState is InstallState.Restored)
        }
    }

    /**
     * Once per process, after [core] is set (so the work below can call [core] again): re-arms the
     * day-boundary alarm, which is cheap and replaces the same alarm. After a restore (supplement
     * R-4, REQ-056) the widgets were drawn before the check, so they are redrawn too. Doing the
     * alarm on every process start also covers a restore whose rebuild was cut off.
     */
    private fun afterOpen(restored: Boolean) {
        appScope.launch {
            try {
                if (restored) {
                    Log.i(TAG, "restore detected: refreshing widgets")
                    WidgetUpdater.updateAll(context)
                }
                DayBoundaryAlarm.scheduleNext(context)
            } catch (e: Exception) {
                Log.e(TAG, "after-open work failed (restored=$restored)", e)
            }
        }
    }

    suspend fun widgetModel(): WidgetModel = WidgetModel.from(core().loadStatusBoard())

    /** Called after every write so every widget re-renders from the database. */
    suspend fun afterWrite() {
        WidgetUpdater.updateAll(context)
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}
