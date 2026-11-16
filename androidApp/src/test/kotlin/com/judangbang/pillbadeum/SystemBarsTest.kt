package com.judangbang.pillbadeum

import android.content.ComponentName
import android.graphics.Color
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.widget.WidgetPickActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BUG-006: every screen is white, so the system bars over it need dark icons. From Android 15 (targetSdk 35 and later)
 * the app is drawn edge to edge under transparent bars, and the platform theme's light icons were white on white: the
 * reminder check's API 36 capture (`api36-10-app-taken.png`, run 43692177634) had its top 188 rows all #FFFFFF.
 *
 * Read on Android 15 (sdk 35), so `values-v35` and `values-v27` apply as on the phones of the bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class SystemBarsTest {
    private val app: TestApp get() = ApplicationProvider.getApplicationContext()

    @Test
    fun the_app_and_the_widget_picker_use_the_app_themes() {
        assertEquals(R.style.Theme_Pillbadeum, themeOf(MainActivity::class.java))
        assertEquals(R.style.Theme_Pillbadeum_Pick, themeOf(WidgetPickActivity::class.java))
    }

    @Test
    fun the_bars_have_dark_icons_over_the_white_screens() {
        for (style in listOf(R.style.Theme_Pillbadeum, R.style.Theme_Pillbadeum_Pick)) {
            val name = app.resources.getResourceEntryName(style)
            assertTrue("$name windowLightStatusBar", flag(style, android.R.attr.windowLightStatusBar))
            assertTrue("$name windowLightNavigationBar", flag(style, android.R.attr.windowLightNavigationBar))
        }
    }

    @Test
    fun up_to_android_14_the_app_bars_are_white() {
        // Android 15 and later draw no colour under an app drawn edge to edge; up to Android 14 the bars are these, with
        // the dark icons above.
        assertEquals(Color.WHITE, value(R.style.Theme_Pillbadeum, android.R.attr.statusBarColor).data)
        assertEquals(Color.WHITE, value(R.style.Theme_Pillbadeum, android.R.attr.navigationBarColor).data)
    }

    /** The theme the activity starts with: its own, or the application's when it has none. */
    private fun themeOf(activity: Class<*>): Int =
        app.packageManager.getActivityInfo(ComponentName(app, activity), 0).themeResource

    private fun value(style: Int, attr: Int): TypedValue {
        val value = TypedValue()
        check(ContextThemeWrapper(app, style).theme.resolveAttribute(attr, value, true)) {
            "${app.resources.getResourceEntryName(attr)} not in ${app.resources.getResourceEntryName(style)}"
        }
        return value
    }

    private fun flag(style: Int, attr: Int): Boolean =
        value(style, attr).let { it.type == TypedValue.TYPE_INT_BOOLEAN && it.data != 0 }
}
