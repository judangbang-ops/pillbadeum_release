package com.judangbang.pillbadeum

import android.app.Application
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.buildDidITakeDatabase
import com.judangbang.pillbadeum.restore.InstallMarker
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers

/**
 * The application of Robolectric tests that go through the real [AppContainer]: a database in memory (the bundled
 * SQLite cannot load under Robolectric) and a clock the test sets. Robolectric makes a new one for every test.
 */
class TestApp : Application(), ContainerHolder {
    class TestClock(@Volatile var now: Instant) : Clock {
        override fun now(): Instant = now
    }

    /** 2026-11-12 09:30 in Seoul unless a test moves it. */
    val clock = TestClock(Instant.parse("2026-11-12T09:30:00+09:00"))

    /** The install marker in memory: Robolectric cannot open a directory to flush it, as the real one does. */
    class MemoryMarker : InstallMarker {
        @Volatile
        private var id: String? = null

        override suspend fun read(): String? = id

        override suspend fun write(deviceId: String) {
            id = deviceId
        }
    }

    override val container: AppContainer by lazy {
        AppContainer(
            context = this,
            clock = clock,
            openDatabase = {
                buildDidITakeDatabase(Room.inMemoryDatabaseBuilder<DidITakeDatabase>(this), Dispatchers.IO, AndroidSQLiteDriver())
            },
            installMarker = { MemoryMarker() },
        )
    }
}
