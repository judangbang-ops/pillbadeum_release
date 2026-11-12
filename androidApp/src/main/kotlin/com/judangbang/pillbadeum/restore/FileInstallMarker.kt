package com.judangbang.pillbadeum.restore

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `noBackupFilesDir/install_marker` (record-rules-supplement.md R-1). Auto Backup and device
 * transfer always leave this directory out, and an app update keeps it, so a missing or different
 * marker next to an existing database means the database was restored. Clearing the app's data
 * removes it together with the database (RR-34).
 */
class FileInstallMarker(private val file: File) : InstallMarker {
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        file.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    /** Write to a temporary file, flush it to disk, then rename: never a half-written marker. */
    override suspend fun write(deviceId: String) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(deviceId.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "could not replace $file" }
    }

    companion object {
        const val FILE_NAME: String = "install_marker"

        fun of(context: Context): FileInstallMarker =
            FileInstallMarker(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
    }
}
