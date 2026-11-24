package com.judangbang.pillbadeum.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.judangbang.pillbadeum.ui.CardExportFormat
import com.judangbang.pillbadeum.ui.CardExportPreview
import com.judangbang.pillbadeum.ui.CardExporter
import com.judangbang.pillbadeum.usecase.HealthCardSummary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

class CardExportProvider : FileProvider()

internal class CardExportStore(private val context: Context) {
    private val root get() = context.cacheDir.toPath().resolve("card-exports")
    private val mutex = Mutex()
    private var cleared = false

    /** MainActivity calls this once per process. A process started by the provider keeps granted files readable. */
    suspend fun clearOnFirstScreen() = withContext(Dispatchers.IO) {
        mutex.withLock { clearOldFiles() }
    }

    suspend fun share(document: CardDocument, openChooser: suspend (Intent, () -> Unit) -> Unit) {
        require(document.pages.isNotEmpty())
        var unsharedDirectory: Path? = null
        try {
            val chooser = withContext(Dispatchers.IO) {
                mutex.withLock {
                    // A fast tap can reach here before the MainActivity cleanup coroutine is scheduled.
                    clearOldFiles()
                    val exportRoot = Files.createDirectories(root)
                    check(Files.isDirectory(exportRoot, NOFOLLOW_LINKS))
                    val directory = Files.createDirectory(exportRoot.resolve(UUID.randomUUID().toString()))
                    unsharedDirectory = directory
                    val file = directory.resolve(document.filename)
                    Files.newOutputStream(file, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { document.writeTo(it) }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.card-export", file.toFile())
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = document.format.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(context.contentResolver, document.filename, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    Intent.createChooser(send, null)
                }
            }
            currentCoroutineContext().ensureActive()
            openChooser(chooser) { unsharedDirectory = null }
        } catch (e: OutOfMemoryError) {
            throw IOException("Insufficient memory for card export", e)
        } finally {
            // Launch may fail, or the screen may leave before the dispatcher hands back the intent.
            unsharedDirectory?.let { directory ->
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        removeTree(directory)
                    } catch (_: IOException) {
                        // First-screen cleanup in the next process can retry. Do not log the path.
                    } catch (_: java.io.UncheckedIOException) {
                        // Files.walk may wrap a read failure while the stream is consumed.
                    }
                }
            }
        }
    }

    private fun clearOldFiles() {
        if (cleared) return
        removeTree(root)
        cleared = true
    }

    private fun removeTree(directory: Path) {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

internal class AndroidCardExporter(
    private val store: CardExportStore,
    private val openChooser: suspend (Intent, () -> Unit) -> Unit,
) : CardExporter {
    override suspend fun prepare(card: HealthCardSummary, madeOn: LocalDate, format: CardExportFormat): CardExportPreview =
        withContext(Dispatchers.Default) { CardDocument.prepare(card, madeOn, format) }

    override suspend fun share(preview: CardExportPreview, onChooserOpening: () -> Unit) {
        store.share(preview as CardDocument) { chooser, onLaunched ->
            onChooserOpening()
            openChooser(chooser, onLaunched)
        }
    }
}
