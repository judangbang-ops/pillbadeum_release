package com.judangbang.pillbadeum.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.TestApp
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.domain.SlotLabel
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import com.judangbang.pillbadeum.usecase.WindowCommand
import com.judangbang.pillbadeum.usecase.WindowWriteResult
import java.io.File
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.shadows.ShadowDialog

/** Shared by the app's Robolectric screen tests ([AppScreenTest], [JudgeScreenTest]). */

/** A wall-clock time in Seoul: `at("2026-11-12T09:30")`. */
internal fun at(localDateTime: String): Instant = Instant.parse("${localDateTime}:00+09:00")

/** The items of [seedWeek]. */
internal class Seed(val morning: String, val vitamin: String, val pressure: String?, val bone: String?)

/**
 * The week of 09 BoxHome, registered on Monday 11/02 and seen on Thursday 11/12 at 09:30: 나's 아침약 (taken Mon–Thu) and
 * 비타민D (taken Mon–Wed, skipped Thu); with [family] 엄마's 혈압약 (taken Mon and Tue at 21:40) and the weekly 골다공증약
 * (taken Monday 11/02 at 08:10).
 */
internal fun TestApp.seedWeek(family: Boolean): Seed = runBlocking {
    val core = container.core()
    clock.now = at("2026-11-02T07:00")
    val me = core.people.list().single()
    val mom = if (family) core.people.add("엄마", PersonColors.PALETTE[1]) else null
    val morning = core.create(IntakeDraft(me.id, "아침약", slotLabel = SlotLabel.MORNING))
    val vitamin = core.create(IntakeDraft(me.id, "비타민D"))
    val pressure = mom?.let { core.create(IntakeDraft(it.id, "혈압약")) }
    val bone = mom?.let { core.create(IntakeDraft(it.id, "골다공증약", rule = ScheduleRule.Weekly(DayOfWeek.MONDAY))) }
    bone?.let { takeAt(it, "2026-11-02T08:10") }
    for (day in listOf("09", "10", "11")) {
        takeAt(morning, "2026-11-${day}T08:00")
        takeAt(vitamin, "2026-11-${day}T09:00")
    }
    pressure?.let {
        takeAt(it, "2026-11-09T21:40")
        takeAt(it, "2026-11-10T21:40")
    }
    takeAt(morning, "2026-11-12T08:02")
    skipAt(vitamin, "2026-11-12T09:10")
    clock.now = at("2026-11-12T09:30")
    Seed(morning, vitamin, pressure, bone)
}

private suspend fun DidITakeCore.create(draft: IntakeDraft): String = (saveIntake.create(draft) as SaveIntakeResult.Saved).intakeId

/** Records a dose of [intakeId] at [at] the way `[먹었어]` does. */
internal suspend fun TestApp.takeAt(intakeId: String, at: String) {
    clock.now = at(at)
    assertTrue(container.core().record(intakeId, DoseSource.APP, null) is RecordResult.Recorded)
}

/** Skips the window of [intakeId] that holds [at]. */
internal suspend fun TestApp.skipAt(intakeId: String, at: String) {
    clock.now = at(at)
    val core = container.core()
    val now = clock.now()
    val version = checkNotNull(core.db.scheduleVersionDao().findEffective(intakeId, now.toEpochMilliseconds()))
    val key = core.loadStatusBoard().items.single { it.intakeId == intakeId }.window.periodKey
    val result = core.recordInWindow.record(
        WindowCommand(intakeId, version.id, key, DoseKind.SKIPPED, deviceZoneId = "Asia/Seoul", renderedAt = now),
    )
    assertTrue("$result", result is WindowWriteResult.Recorded)
}

/** REQ-067 (TC-056): everything that can be pressed is at least 48 × 48 dp, measured unclipped. */
internal fun ComposeTestRule.assertTargetsAtLeast48dp() {
    val min = with(density) { 48.dp.roundToPx() }
    val small = onAllNodes(hasClickAction()).fetchSemanticsNodes()
        .filter { it.size.width < min || it.size.height < min }
        .map { node ->
            val name = node.config.getOrNull(SemanticsProperties.ContentDescription) ?: node.config.getOrNull(SemanticsProperties.Text)
            "$name ${node.size}"
        }
    assertEquals("targets under 48 dp", emptyList<String>(), small)
}

/** Every content description in the merged tree: what TalkBack reads for the parts that have one. */
internal fun ComposeTestRule.spokenTexts(): List<String> =
    onAllNodes(SemanticsMatcher("has a description") { it.config.contains(SemanticsProperties.ContentDescription) })
        .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.ContentDescription] }

/**
 * The window drawn into a bitmap by the view tree itself. Compose's `captureToImage` waits for a frame that Robolectric
 * never draws on its own, so the views draw here on a software canvas, as the widget check does. An open dialog is a
 * window of its own: it is drawn over the screen where its gravity puts it, behind its dim.
 */
internal fun ComposeTestRule.screenshot(activity: ComponentActivity): Bitmap {
    waitForIdle()
    val view = activity.window.decorView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    view.draw(canvas)
    for (dialog in ShadowDialog.getShownDialogs().filter { it.isShowing }) {
        val window = dialog.window ?: continue
        val decor = window.decorView
        if (decor.width == 0 || decor.height == 0) continue
        val attributes = window.attributes
        if (attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
            canvas.drawColor(Color.argb((attributes.dimAmount * 255).roundToInt(), 0, 0, 0))
        }
        val frame = Rect()
        val gravity = attributes.gravity.takeIf { it != Gravity.NO_GRAVITY } ?: Gravity.CENTER
        Gravity.apply(gravity, decor.width, decor.height, Rect(0, 0, view.width, view.height), frame)
        val saved = canvas.save()
        canvas.translate(frame.left.toFloat(), frame.top.toFloat())
        decor.draw(canvas)
        canvas.restoreToCount(saved)
    }
    return bitmap
}

/**
 * Saves the whole screen and the merged semantics tree of each window (the screen, then any open dialog) as
 * `build/app-render/<case>.png` and `<case>.txt`.
 */
internal fun ComposeTestRule.saveScreen(case: String, activity: ComponentActivity) {
    val bitmap = screenshot(activity)
    val dir = File("build/app-render").apply { mkdirs() }
    File(dir, "$case.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    File(dir, "$case.txt").writeText(onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) + "\n")
}
