package com.judangbang.pillbadeum.data

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        PersonEntity::class,
        IntakeEntity::class,
        ScheduleVersionEntity::class,
        DoseRecordEntity::class,
        MedicationEntity::class,
        IntakeMedicationEntity::class,
        DoseVersionEntity::class,
        SettingEntity::class,
        ReminderRuleEntity::class,
        ReminderSnoozeEntity::class,
        ClockFlagEntity::class,
    ],
    // Every version bump adds a Migration in Migrations.kt and a test in MigrationTest (REQ-072).
    version = 2,
    exportSchema = true,
)
@ConstructedBy(DidITakeDatabaseConstructor::class)
abstract class DidITakeDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun intakeDao(): IntakeDao
    abstract fun scheduleVersionDao(): ScheduleVersionDao
    abstract fun doseRecordDao(): DoseRecordDao
    abstract fun settingDao(): SettingDao
    abstract fun intakePurgeDao(): IntakePurgeDao
    abstract fun clockFlagDao(): ClockFlagDao

    companion object {
        const val FILE_NAME: String = "diditake.db"
    }
}

/** Room generates the `actual` implementation for every target. */
@Suppress("KotlinNoActualForExpect")
expect object DidITakeDatabaseConstructor : RoomDatabaseConstructor<DidITakeDatabase> {
    override fun initialize(): DidITakeDatabase
}

/**
 * Finishes a platform builder (file or in-memory) with the shared configuration:
 * bundled SQLite (same SQLite version on every platform), the schema migrations and a background
 * query context. There is no destructive fallback: a missing migration fails instead of wiping
 * records (REQ-072).
 */
fun buildDidITakeDatabase(
    builder: RoomDatabase.Builder<DidITakeDatabase>,
    queryContext: CoroutineContext = Dispatchers.IO,
): DidITakeDatabase = builder
    .setDriver(BundledSQLiteDriver())
    .addMigrations(*Migrations.ALL)
    .setQueryCoroutineContext(queryContext)
    .build()
