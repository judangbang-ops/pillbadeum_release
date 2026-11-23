package com.judangbang.pillbadeum

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.room3.Room
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.buildDidITakeDatabase
import com.judangbang.pillbadeum.restore.InstallMarker
import com.judangbang.pillbadeum.ui.App
import com.judangbang.pillbadeum.usecase.CardEntryFields
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.SaveCardEntryResult
import java.io.File
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking

/** TC-096: real Android clipboard and SystemUI on a dedicated API 32 or 34 emulator. */
class EmergencyClipboardDeviceTest : Instrumentation() {
    private lateinit var core: DidITakeCore
    private lateinit var personId: String

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        try {
            check(Build.VERSION.SDK_INT in setOf(32, 34)) { "Run on the API 32 or API 34 test emulator" }
            val expected = seedCard()
            // The production entry point is TASK-061; open at SCR-011 like the Robolectric tests.
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val automation = uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val clipboard = checkNotNull(activity.getSystemService(ClipboardManager::class.java))
            try {
                click("응급 대비 문장")
                waitForNode("subject") { it.text?.toString()?.startsWith("가상 엄마의 정보예요.") == true }
                click("모두 복사")
                verifyCopy(clipboard, expected, "all")
                awaitPreviewDismissal()

                val scroll = waitForNode("scroll") { it.isScrollable && it.packageName == targetContext.packageName }
                check(scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { "Scroll to copy by section" }
                click("알레르기 복사")
                verifyCopy(clipboard, ALLERGY, "line")
            } finally {
                runOnMainSync { clipboard.clearPrimaryClip(); activity.finish() }
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nTC-096 API ${Build.VERSION.SDK_INT}: all and line passed\n") })
        } catch (e: Exception) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nTC-096 failed: ${e.message}\n") })
        }
    }

    override fun newActivity(loader: ClassLoader, className: String, intent: Intent): Activity =
        if (className == MainActivity::class.java.name) TestCardActivity(core, personId)
        else super.newActivity(loader, className, intent)

    private fun seedCard(): String = runBlocking {
        val database = buildDidITakeDatabase(Room.inMemoryDatabaseBuilder<DidITakeDatabase>(targetContext))
        val marker = object : InstallMarker {
            override suspend fun read(): String? = null
            override suspend fun write(deviceId: String) = Unit
        }
        core = DidITakeCore.open(database, marker, Clock.System)
        personId = core.people.add("가상 엄마", PersonColors.PALETTE[1]).id
        val fields = CardEntryFields.Allergy(ALLERGY)
        check(core.saveCardEntry.add(personId, core.saveCardEntry.newEntryId(), fields) is SaveCardEntryResult.Saved)
        val card = checkNotNull(core.loadHealthCard(personId))
        checkNotNull(core.buildEmergencyText(card)).text
    }

    private fun verifyCopy(clipboard: ClipboardManager, expected: String, button: String) {
        val hasPreview = Build.VERSION.SDK_INT >= 33
        val hint = if (hasPreview) "이제 긴급 정보에 붙여 넣어 주세요." else "복사했어요. 긴급 정보에 붙여 넣어 주세요."
        waitForNode("app copy hint after $button") { it.text?.toString()?.startsWith(hint) == true }
        if (hasPreview) waitForNode("SystemUI masked clipboard preview after $button", ::isMaskedPreview)
        saveScreenshot(button)
        runOnMainSync {
            val clip = checkNotNull(clipboard.primaryClip) { "Clipboard is empty after $button" }
            check(clip.itemCount == 1 && clip.getItemAt(0).text.toString() == expected) { "Wrong clipboard contents after $button" }
            val sensitive = clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
            check(sensitive == hasPreview) { "Wrong clipboard flag after $button" }
        }
    }

    private fun awaitPreviewDismissal() {
        val until = SystemClock.uptimeMillis() + 10_000
        while (uiAutomation.windows.any { it.root?.findVisible(::isMaskedPreview) != null }) {
            check(SystemClock.uptimeMillis() < until) { "The first clipboard preview did not close" }
            SystemClock.sleep(100)
        }
    }

    private fun isMaskedPreview(node: AccessibilityNodeInfo): Boolean =
        node.packageName == "com.android.systemui" && node.text?.toString()?.let(MASK::matches) == true

    private fun saveScreenshot(button: String) {
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        val file = File(targetContext.cacheDir, "tc096-api${Build.VERSION.SDK_INT}-$button.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun click(label: String) {
        val button = waitForNode(label) { it.isClickable && (it.text == label || it.contentDescription == label) }
        check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not tap $label" }
    }

    private fun waitForNode(label: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 6_000
        do {
            val found = uiAutomation.windows.firstNotNullOfOrNull { it.root?.findVisible(matches) }
            if (found != null) return found
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < until)
        error("Missing $label")
    }

    private fun AccessibilityNodeInfo.findVisible(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (isVisibleToUser && matches(this)) return this
        return (0 until childCount).firstNotNullOfOrNull { getChild(it)?.findVisible(matches) }
    }

    private class TestCardActivity(private val core: DidITakeCore, private val personId: String) : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContent {
                App(
                    open = { core },
                    onDataChanged = {},
                    phone = AndroidPhone(this),
                    initialHealthCardPersonId = personId,
                    backHandler = { enabled, back -> BackHandler(enabled, back) },
                    protectCardRecents = { ProtectCardRecents(it) },
                )
            }
        }
    }

    private companion object {
        const val ALLERGY = "가상 페니실린 계열"
        val MASK = Regex("[•●·*∙]{3,}")
    }
}
