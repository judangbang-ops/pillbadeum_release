package com.judangbang.pillbadeum

import android.app.Application
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.data.buildDidITakeDatabase
import com.judangbang.pillbadeum.restore.InstallMarker
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers

/**
 * The application of Robolectric tests that go through the real [AppContainer]: a database in memory (the bundled
 * SQLite cannot load under Robolectric), a clock the test sets, and the first-run intro already seen unless a test says
 * otherwise ([introSeen]). Robolectric makes a new one for every test.
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

    /**
     * Whether the database starts with the first-run intro seen (SCR-005, TASK-022), as on a phone past its first start:
     * true, so the app opens on its first screen. A test of the intro sets it to false before the database opens.
     */
    @Volatile
    var introSeen: Boolean = true

    /** Writes `intro_seen_at` into the new database, as [시작하기] would have. */
    private inner class IntroSeen : RoomDatabase.Callback() {
        override suspend fun onCreate(connection: SQLiteConnection) {
            val at = clock.now().toEpochMilliseconds()
            connection.execSQL("INSERT INTO setting (`key`, `value`) VALUES ('${SettingKeys.INTRO_SEEN_AT}', '$at')")
        }
    }

    override val container: AppContainer by lazy {
        AppContainer(
            context = this,
            clock = clock,
            openDatabase = {
                val builder = Room.inMemoryDatabaseBuilder<DidITakeDatabase>(this)
                if (introSeen) builder.addCallback(IntroSeen())
                buildDidITakeDatabase(builder, Dispatchers.IO, AndroidSQLiteDriver())
            },
            installMarker = { MemoryMarker() },
        )
    }
}
