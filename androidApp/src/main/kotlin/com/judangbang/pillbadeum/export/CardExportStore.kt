package com.judangbang.pillbadeum.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.judangbang.pillbadeum.ui.CardExportFormat
import com.judangbang.pillbadeum.ui.CardExportPreview
import com.judangbang.pillbadeum.ui.CardExporter
import com.judangbang.pillbadeum.usecase.HealthCardSummary
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
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

    suspend fun createShareIntent(document: CardDocument): Intent = withContext(Dispatchers.IO) {
        require(document.pages.isNotEmpty())
        mutex.withLock {
            // A fast tap can reach here before the MainActivity cleanup coroutine is scheduled.
            clearOldFiles()
            val directory = Files.createDirectory(Files.createDirectories(root).resolve(UUID.randomUUID().toString()))
            var ready = false
            try {
                val file = directory.resolve(document.filename)
                Files.newOutputStream(file).use(document::writeTo)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.card-export", file.toFile())
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = document.format.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(context.contentResolver, document.filename, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                Intent.createChooser(send, null).also { ready = true }
            } finally {
                if (!ready) removeTree(directory)
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
    private val openChooser: suspend (Intent) -> Unit,
) : CardExporter {
    override suspend fun prepare(card: HealthCardSummary, madeOn: LocalDate, format: CardExportFormat): CardExportPreview =
        withContext(Dispatchers.Default) { CardDocument.prepare(card, madeOn, format) }

    override suspend fun share(preview: CardExportPreview, onChooserOpening: () -> Unit) {
        val chooser = store.createShareIntent(preview as CardDocument)
        onChooserOpening()
        openChooser(chooser)
    }
}
