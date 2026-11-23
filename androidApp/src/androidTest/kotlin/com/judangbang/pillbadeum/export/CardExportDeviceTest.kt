package com.judangbang.pillbadeum.export

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.data.PersonEntity
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.ui.CardExportFormat.PDF
import com.judangbang.pillbadeum.ui.CardExportFormat.PNG
import com.judangbang.pillbadeum.usecase.CardEntry
import com.judangbang.pillbadeum.usecase.CardEntryFields
import com.judangbang.pillbadeum.usecase.CardMedication
import com.judangbang.pillbadeum.usecase.HealthCardSummary
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CardExportDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val root get() = File(context.cacheDir, "card-exports")
    private val editedOn = LocalDate(2026, 11, 22)
    private val today = LocalDate(2026, 11, 23)
    private val threeEntries = listOf(
        CardEntryFields.Allergy("가상 원인", "가상 반응"),
        CardEntryFields.Condition("가상 질환", "2015"),
        CardEntryFields.Guardian("가상 딸", "010-0000-0000", "딸"),
    )

    @Test
    fun tc097_one_a4_pdf_embeds_hangul_and_preserves_header_and_summary() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val chooser = CardExportStore(context).createShareIntent(CardDocument.prepare(fixture(), today, PDF))
        val uri = sharedFile(chooser)
        val text = pdfText(uri).single()
        assertEquals("pillbadeum-card-20261123.pdf", uri.lastPathSegment)
        val header = listOf(
            "가상 엄마", Labels.CARD_DISCLAIMER, "적지 않은 종류는 보이지 않아요",
            "마지막으로 고친 날 2026-11-22", "만든 날 2026-11-23",
            "지금 먹는 약", "가상정 10mg", "보기정 500mg", "알레르기", "가상 원인",
        )
        assertEquals(header.map(text::indexOf).sorted(), header.map(text::indexOf))
        assertTrue(header.all(text::contains))
        val pdf = read(uri).toString(Charsets.ISO_8859_1)
        assertTrue(pdf.contains(Regex("/FontFile[23]\\b")))
        assertTrue(pdf.contains(Regex("/FontName /[^\\s]*(NotoSansCJK|NotoSansKR)", RegexOption.IGNORE_CASE)))
    }

    @Test
    fun tc097_fixed_boundary_adds_one_page_and_repeats_the_header() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val store = CardExportStore(context)
        fun boundary(lines: Int) = CardDocument.prepare(
            fixture(entries = listOf(CardEntryFields.Condition("가상 질환", note = note(lines))), withMedications = false),
            today, PDF,
        )
        val first = pdfText(sharedFile(store.createShareIntent(boundary(23))))
        val second = pdfText(sharedFile(store.createShareIntent(boundary(24))))
        assertEquals(1, first.size)
        assertEquals(2, second.size)
        second.forEachIndexed { index, text ->
            assertTrue(text.contains("가상 엄마 · ${index + 1}/2쪽"))
            assertTrue(text.contains(Labels.CARD_DISCLAIMER))
            assertTrue(text.contains("마지막으로 고친 날 2026-11-22"))
            assertTrue(text.contains("만든 날 2026-11-23"))
        }
        assertTrue(second.last().contains("24행"))
    }

    @Test
    fun tc097_long_single_entry_continues_without_losing_lines() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val card = fixture(entries = listOf(CardEntryFields.Condition("가상 질환", note = note(60))), withMedications = false)
        val chooser = CardExportStore(context).createShareIntent(CardDocument.prepare(card, today, PDF))
        val pages = pdfText(sharedFile(chooser))
        assertTrue(pages.size > 1)
        assertEquals((1..60).toList(), Regex("(\\d+)행").findAll(pages.joinToString(" ")).map { it.groupValues[1].toInt() }.toList())
    }

    @Test
    fun tc097_png_has_a4_width_and_stops_before_bitmap_allocation_when_over_limit() = runBlocking {
        val short = pngBounds(CardDocument.prepare(fixture(), today, PNG))
        assertEquals(1190, short.outWidth)
        assertEquals(1684, short.outHeight)
        val long = fixture(entries = listOf(CardEntryFields.Condition("가상 질환", note = note(45))), withMedications = false)
        val longDocument = CardDocument.prepare(long, today, PNG)
        assertEquals("세로로 긴 사진이에요", longDocument.notice)
        val longImage = pngBounds(longDocument)
        assertEquals(1190, longImage.outWidth)
        assertTrue(longImage.outHeight in 1685..3368)

        val sixty = fixture(
            entries = (1..20).flatMap { index ->
                listOf(CardEntryFields.Allergy("가상 원인 $index"), CardEntryFields.Condition("가상 질환 $index"),
                    CardEntryFields.Procedure("가상 수술 $index"))
            },
            withMedications = false,
        )
        val blocked = CardDocument.prepare(sixty, today, PNG)
        assertTrue(blocked.pages.isEmpty())
        assertEquals("사진이 너무 길어요. PDF로 보내 주세요", blocked.notice)
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { blocked.writeTo(output) }
        assertEquals(0, output.size())
    }

    @Test
    fun tc097_same_day_exports_keep_separate_uris_and_only_grant_reading(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val store = CardExportStore(context)
        val first = store.createShareIntent(CardDocument.prepare(fixture(), today, PDF))
        val firstUri = sharedFile(first)
        val firstBytes = read(firstUri)
        val secondUri = sharedFile(store.createShareIntent(CardDocument.prepare(fixture("나"), today, PDF)))
        assertEquals(firstUri.lastPathSegment, secondUri.lastPathSegment)
        assertNotEquals(firstUri.path, secondUri.path)
        assertArrayEquals(firstBytes, read(firstUri))
        assertTrue(pdfText(firstUri).single().startsWith("가상 엄마"))
        assertTrue(pdfText(secondUri).single().startsWith("나"))

        val target = target(first)
        assertEquals(Intent.ACTION_SEND, target.action)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, target.flags)
        assertEquals(firstUri, target.clipData?.getItemAt(0)?.uri)
        assertEquals("application/pdf", resolver.getType(firstUri))
        val authority = "${context.packageName}.card-export"
        val provider = checkNotNull(context.packageManager.resolveContentProvider(authority, 0))
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val unrelated = File.createTempFile("fixture", ".pdf", context.cacheDir)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, authority, unrelated)
            }
        } finally {
            unrelated.delete()
        }
    }

    @Test
    fun tc097_provider_read_preserves_cache_until_app_screen_opens(): Unit = runBlocking {
        val store = CardExportStore(context)
        val chooser = store.createShareIntent(CardDocument.prepare(fixture(), today, PDF))
        val uri = sharedFile(chooser)
        assertTrue(read(uri).isNotEmpty())
        assertTrue(root.isDirectory)
        ActivityScenario.launch(MainActivity::class.java).use {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (root.exists() && System.nanoTime() < deadline) Thread.sleep(25)
            assertTrue(root.list().isNullOrEmpty())
        }
    }

    private fun target(chooser: Intent): Intent =
        @Suppress("DEPRECATION") checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT))

    private fun sharedFile(chooser: Intent): Uri =
        @Suppress("DEPRECATION") checkNotNull(target(chooser).getParcelableExtra(Intent.EXTRA_STREAM))

    private fun read(uri: Uri): ByteArray = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

    private fun pdfText(uri: Uri): List<String> =
        PdfRenderer(checkNotNull(resolver.openFileDescriptor(uri, "r"))).use { pdf ->
            (0 until pdf.pageCount).map { index ->
                pdf.openPage(index).use { page ->
                    assertEquals(595, page.width)
                    assertEquals(842, page.height)
                    page.textContents.joinToString(" ") { it.text }.trim()
                }
            }
        }

    private fun pngBounds(document: CardDocument): BitmapFactory.Options {
        val bytes = ByteArrayOutputStream().also(document::writeTo).toByteArray()
        return BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        }
    }

    private fun note(lines: Int) = (1..lines).joinToString("\n") { "${it}행" }

    private fun fixture(
        name: String = "가상 엄마",
        entries: List<CardEntryFields> = threeEntries,
        withMedications: Boolean = true,
    ) = HealthCardSummary(
        person = PersonEntity("fixture", name, "#000000", 0L, 0L, deviceId = "fixture"),
        entries = entries.mapIndexed { id, fields -> CardEntry("fixture-$id", fields) },
        medications = if (withMedications) listOf(
            CardMedication("fixture", "가상정 10mg", "1정", null, null, true),
            CardMedication("fixture", "보기정 500mg", "반 알", null, null, true),
        ) else emptyList(),
        lastEditedDate = editedOn,
        staleNotice = null,
    )
}
