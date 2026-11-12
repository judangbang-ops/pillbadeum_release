package com.judangbang.pillbadeum.restore

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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

    /**
     * Write to a temporary file, flush it to disk, rename, then flush the directory so the rename
     * itself survives a power cut (f2fs does not flush it with the file): never a half-written or
     * lost marker once this returns.
     */
    override suspend fun write(deviceId: String) = withContext(Dispatchers.IO) {
        val dir = checkNotNull(file.parentFile) { "no directory for $file" }
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(deviceId.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "could not replace $file" }
        syncDirectory(dir)
    }

    private fun syncDirectory(dir: File) {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } catch (e: ErrnoException) {
            // A file system that cannot flush a directory has nothing more to flush.
            if (e.errno != OsConstants.EINVAL) throw e
        } finally {
            Os.close(fd)
        }
    }

    companion object {
        const val FILE_NAME: String = "install_marker"

        fun of(context: Context): FileInstallMarker =
            FileInstallMarker(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
    }
}
