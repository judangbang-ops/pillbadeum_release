package com.judangbang.pillbadeum.widget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.provideContent
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.MainActivity
import com.judangbang.pillbadeum.status.WidgetLayout
import com.judangbang.pillbadeum.status.WidgetModel
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Shared by the widget's render check ([WidgetRenderTest]) and the screens that change what a widget shows. */

/** The widget with fixed content instead of the database. */
private class FixedWidget(private val model: WidgetModel?, private val message: String?) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetContent(model, message, actionStartActivity<MainActivity>()) }
    }
}

/** A mid-grey wallpaper, so the widget's white corners show. */
private const val WALLPAPER: Int = 0xFF6B7A8F.toInt()

/**
 * Composes [model] as the widget draws it, sends it through a Parcel as to the launcher, applies, lays out at [size] and
 * saves `build/widget-render/<name>.png`.
 */
internal fun renderWidget(name: String, model: WidgetModel?, message: String?, size: DpSize, fontScale: Float): View {
    RuntimeEnvironment.setFontScale(fontScale)
    val context = ApplicationProvider.getApplicationContext<Context>()
    val views = runBlocking { withTimeout(60_000) { FixedWidget(model, message).compose(context, size = size) } }

    // The launcher gets a parceled copy, and unparceling throws past 10 nested RemoteViews.
    val parcel = Parcel.obtain()
    val copy = try {
        views.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        RemoteViews.CREATOR.createFromParcel(parcel)
    } finally {
        parcel.recycle()
    }

    // A list's rows are filled in only inside an app widget host view.
    val host = AppWidgetHostView(context)
    val density = context.resources.displayMetrics.density
    val width = (size.width.value * density).roundToInt()
    val height = (size.height.value * density).roundToInt()
    host.addView(copy.apply(context, host), ViewGroup.LayoutParams(width, height))
    repeat(2) {
        host.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    val margin = (12 * density).roundToInt()
    val bitmap = Bitmap.createBitmap(width + 2 * margin, height + 2 * margin, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(WALLPAPER)
    canvas.translate(margin.toFloat(), margin.toFloat())
    host.draw(canvas)
    val dir = File("build/widget-render").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    assertFalse("$name: nothing drawn", host.width == 0)
    return host
}

/** The plain text of every shown text view under [root]. */
internal fun shownTexts(root: View): List<String> = buildList {
    fun walk(v: View) {
        if (v is TextView && shown(v)) add(v.text.toString().replace(WidgetLayout.WORD_JOINER.toString(), ""))
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(root)
}

/** [view] and every view above it are visible. */
internal fun shown(view: View): Boolean {
    var v: View? = view
    while (v != null) {
        if (v.visibility != View.VISIBLE) return false
        v = v.parent as? View
    }
    return true
}
