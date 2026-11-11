package com.zmanlab.diditake.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase

/** App-private database file. Finish it with [buildDidITakeDatabase]. */
fun didITakeDatabaseBuilder(context: Context): RoomDatabase.Builder<DidITakeDatabase> {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath(DidITakeDatabase.FILE_NAME)
    return Room.databaseBuilder<DidITakeDatabase>(context = appContext, name = dbFile.absolutePath)
}
