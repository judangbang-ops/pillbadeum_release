package com.judangbang.pillbadeum.debug

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.judangbang.pillbadeum.container
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.widget.DidITakeWidgetReceiver
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only widget host (TASK-042). Hosts one widget at an exact size so an emulator script can
 * check a layout at 4x2 with font 100% and the largest font, without driving a launcher.
 *
 * Extras (all optional):
 * - `provider`: `probe` (default, [ScrollProbeWidget]) or `main` (the product widget)
 * - `layout`: `grid` (default) or `list`, for the probe
 * - `w`, `h`: widget size in dp (default 250 x 110, the declared 4x2 minimum)
 * - `seed`: make sure at least this many active items exist (`Test1`, `Test2`, ...), created through
 *   the shared SaveIntake use case like the app's [저장]
 *
 * Binding needs `adb shell appwidget grantbind --package com.judangbang.pillbadeum` first; without it
 * the system bind dialog opens and the script taps its button.
 */
class WidgetHostActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private lateinit var frame: FrameLayout
    private lateinit var status: TextView
    private var pendingId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, HOST_ID)
        val density = resources.displayMetrics.density
        val pad = (16 * density).roundToInt()
        frame = FrameLayout(this).apply { setBackgroundColor(Color.rgb(0x5B, 0x6B, 0x7E)) }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            contentDescription = "probe-status"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0x5B, 0x6B, 0x7E))
            setPadding(pad, pad * 3, pad, pad)
            addView(status)
            addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        val layout = if (intent.getStringExtra("layout") == "list") ProbeLayout.LIST else ProbeLayout.GRID
        ProbeLayout.set(this, layout)
        val seed = intent.getIntExtra("seed", 0)
        val app = applicationContext
        app.container.appScope.launch {
            try {
                if (seed > 0) seedItems(seed)
                withContext(Dispatchers.Main) { bindWidget() }
            } catch (e: Exception) {
                Log.e(TAG, "host setup failed", e)
                withContext(Dispatchers.Main) { status.text = "probe-error ${e.message}" }
            }
        }
    }

    private suspend fun seedItems(count: Int) {
        val core = applicationContext.container.core()
        val person = core.people.list().first()
        val existing = core.loadStatusBoard().items.size
        for (i in existing + 1..count) {
            core.saveIntake.create(IntakeDraft(personId = person.id, name = "Test$i", rule = ScheduleRule.Daily))
        }
        Log.i(TAG, "seeded ${maxOf(0, count - existing)} items")
    }

    private fun widthDp() = intent.getIntExtra("w", 250)

    private fun heightDp() = intent.getIntExtra("h", 110)

    private fun providerName(): ComponentName = ComponentName(
        this,
        if (intent.getStringExtra("provider") == "main") DidITakeWidgetReceiver::class.java else ScrollProbeWidgetReceiver::class.java,
    )

    private fun bindWidget() {
        // One widget at a time: drop what an earlier run left in this host.
        host.appWidgetIds.forEach { host.deleteAppWidgetId(it) }
        val manager = AppWidgetManager.getInstance(this)
        val id = host.allocateAppWidgetId()
        val provider = providerName()
        if (manager.bindAppWidgetIdIfAllowed(id, provider, sizeOptions())) {
            show(id)
        } else {
            // Not granted: ask the way a launcher does (the script taps the dialog's button).
            pendingId = id
            status.text = "probe-bind-dialog"
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, sizeOptions()),
                REQUEST_BIND,
            )
        }
    }

    @Deprecated("Activity result API is not needed for this debug screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_BIND) return
        if (resultCode == RESULT_OK) {
            AppWidgetManager.getInstance(this).updateAppWidgetOptions(pendingId, sizeOptions())
            show(pendingId)
        } else {
            status.text = "probe-error bind refused"
        }
    }

    private fun sizeOptions(): Bundle {
        val w = widthDp()
        val h = heightDp()
        return Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(w.toFloat(), h.toFloat())))
            }
        }
    }

    private fun show(id: Int) {
        val manager = AppWidgetManager.getInstance(this)
        val info = manager.getAppWidgetInfo(id)
        if (info == null) {
            status.text = "probe-error no widget info"
            return
        }
        val view: AppWidgetHostView = host.createView(applicationContext, id, info)
        val density = resources.displayMetrics.density
        val w = widthDp()
        val h = heightDp()
        frame.removeAllViews()
        frame.addView(view, FrameLayout.LayoutParams((w * density).roundToInt(), (h * density).roundToInt()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.updateAppWidgetSize(Bundle(), listOf(SizeF(w.toFloat(), h.toFloat())))
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(null, w, h, w, h)
        }
        manager.updateAppWidgetOptions(id, sizeOptions())
        val fontScale = resources.configuration.fontScale
        status.text = "probe-ready ${providerName().shortClassName} ${w}x${h}dp font $fontScale id $id"
        Log.i(TAG, status.text.toString())
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
    }

    override fun onStop() {
        super.onStop()
        host.stopListening()
    }

    private companion object {
        const val TAG = "WidgetHostActivity"
        const val HOST_ID = 4242
        const val REQUEST_BIND = 7
    }
}
