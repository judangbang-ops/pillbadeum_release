package com.judangbang.pillbadeum

import android.app.Application
import android.content.Context
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.buildDidITakeDatabase
import com.judangbang.pillbadeum.data.didITakeDatabaseBuilder
import com.judangbang.pillbadeum.status.WidgetModel
import com.judangbang.pillbadeum.widget.WidgetUpdater
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** Opens the core on first use (device id + default person "나"). */
    suspend fun core(): DidITakeCore = core ?: mutex.withLock {
        core ?: DidITakeCore.open(database, clock).also { core = it }
    }

    suspend fun widgetModel(): WidgetModel = WidgetModel.from(core().loadStatusBoard())

    /** Called after every write so every widget re-renders from the database. */
    suspend fun afterWrite() {
        WidgetUpdater.updateAll(context)
    }
}
