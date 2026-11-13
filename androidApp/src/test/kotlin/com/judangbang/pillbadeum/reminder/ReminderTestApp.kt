package com.judangbang.pillbadeum.reminder

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.judangbang.pillbadeum.AppContainer
import com.judangbang.pillbadeum.ContainerHolder
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.buildDidITakeDatabase
import com.judangbang.pillbadeum.restore.InstallMarker
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers

/**
 * The application of the reminder tests: like [TestApp] (database in memory, a clock the test sets), with [restart],
 * a new process on the same database and another install marker, as on a new phone after a restore (R-1).
 */
class ReminderTestApp : Application(), ContainerHolder {
    /** 2026-11-12 (Thu) 07:50 in Seoul unless a test moves it. */
    val clock = TestApp.TestClock(Instant.parse("2026-11-12T07:50:00+09:00"))

    private val database: DidITakeDatabase by lazy {
        buildDidITakeDatabase(Room.inMemoryDatabaseBuilder<DidITakeDatabase>(this), Dispatchers.IO, AndroidSQLiteDriver())
    }

    @Volatile
    private var current: AppContainer? = null

    override val container: AppContainer
        get() = current ?: synchronized(this) { current ?: open(TestApp.MemoryMarker()).also { current = it } }

    /** A new process on the same database with [marker]; an empty marker means the database came from a backup. */
    fun restart(marker: InstallMarker = TestApp.MemoryMarker()) {
        synchronized(this) { current = open(marker) }
    }

    private fun open(marker: InstallMarker): AppContainer =
        AppContainer(this, clock, openDatabase = { database }, installMarker = { marker })
}
