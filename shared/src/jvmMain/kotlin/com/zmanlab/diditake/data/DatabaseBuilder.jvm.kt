package com.zmanlab.diditake.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
import java.io.File

/** File database for JVM hosts. Finish it with [buildDidITakeDatabase]. */
fun didITakeDatabaseBuilder(file: File): RoomDatabase.Builder<DidITakeDatabase> =
    Room.databaseBuilder<DidITakeDatabase>(name = file.absolutePath)

/** In-memory database (tests). Finish it with [buildDidITakeDatabase]. */
fun inMemoryDidITakeDatabaseBuilder(): RoomDatabase.Builder<DidITakeDatabase> =
    Room.inMemoryDatabaseBuilder<DidITakeDatabase>()
