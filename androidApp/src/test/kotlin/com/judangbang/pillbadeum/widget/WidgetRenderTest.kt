package com.judangbang.pillbadeum.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ListView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.judangbang.pillbadeum.R
import com.judangbang.pillbadeum.design.Tokens
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.TileState
import com.judangbang.pillbadeum.status.WidgetButton
import com.judangbang.pillbadeum.status.WidgetLayout
import com.judangbang.pillbadeum.status.WidgetModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TASK-014 render check: the widget drawn by Android's own views, text layout and fonts (Robolectric with native
 * graphics, Android 15, Korean locale, 3x density) at real widget sizes, with the TASK-044 drawing's items. It checks
 * what the estimate in [WidgetLayout] cannot check by itself:
 * - the RemoteViews survive the trip to the launcher, which refuses more than 10 nested levels;
 * - the down arrow shows whenever rows are cut at the bottom of the list (REQ-078);
 * - lines break only at spaces, so no Korean word is split, and every text gets room for itself.
 * Each case is also saved as `androidApp/build/widget-render/<case>.png` to look at.
 *
 * Not a launcher: no AppWidgetHost, launcher cell sizes, scrolling or taps, and the fonts are Robolectric's copies
 * of Android's (Roboto, Noto Sans CJK), not a Galaxy's. The emulator captures and the Galaxy check (TASK-032) cover
 * those. Android 16 (SDK 36) needs Robolectric on Java 21, so this runs on 15.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ko-rKR-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetRenderTest {
    private val nb = Labels.NBSP
    private val skippedMessage = "비타민D: 건너뜀으로 기록됨 (09:10). 바꾸려면 앱에서" // S-3

    private fun button(
        id: String,
        title: String,
        who: String?,
        lines: List<String>,
        state: TileState = TileState.PENDING,
        weekly: Boolean = false,
        person: Int = 0,
    ) = WidgetButton(
        intakeId = id,
        periodKey = "2026-11-12",
        title = title,
        who = who,
        palette = Tokens.PEOPLE[person],
        state = state,
        lines = lines,
        largeFirstLine = weekly && state == TileState.PENDING,
        description = title,
    )

    private fun model(
        buttons: List<WidgetButton>,
        restore: String? = null,
        reconfigure: String? = null,
        empty: String = Labels.EMPTY_WIDGET,
    ) = WidgetModel(
        title = Labels.APP_NAME,
        dateLabel = "11/12 (목)",
        countLabel = "",
        weekday = "목",
        headerDate = "${Labels.APP_NAME} · 11/12",
        countDone = if (reconfigure != null) "" else "${buttons.count { it.state == TileState.TAKEN }} / ${buttons.size}",
        countRest = when {
            reconfigure != null -> ""
            buttons.any { it.state == TileState.SKIPPED } -> "완료 · 건너뜀 1"
            else -> "완료"
        },
        buttons = buttons,
        restoreLine = restore,
        showPeople = buttons.any { it.who != null },
        reconfigure = reconfigure,
        emptyText = empty,
    )

    /** The TASK-044 drawing's four items with the longest copy (docs/05-ui/states/README.md section 2). */
    private val drawing = listOf(
        button("a", "아침약", "나", listOf("08:02 먹음"), TileState.TAKEN),
        button("b", "비타민D", "나", listOf("건너뜀"), TileState.SKIPPED),
        button("c", "혈압약", "엄마", listOf("마지막 복용 11/10(화)${nb}21:40", "어제 건너뜀"), person = 1),
        button("d", "골다공증약", "엄마", listOf("마지막 복용 11/02(월)${nb}08:10"), weekly = true, person = 1),
    )

    private fun short(count: Int) = (1..count).map { button("i$it", "약$it", null, listOf("마지막 복용 어제${nb}08:00")) }

    /** A 4x2 widget on a Pixel 5 in portrait (TASK-044 drawing). */
    private val portrait = DpSize(350.dp, 213.dp)

    @Test
    fun the_drawing_at_100_percent() = check("drawing-100", model(drawing), arrow = true)

    @Test
    fun the_drawing_at_130_percent() = check("drawing-130", model(drawing), fontScale = 1.3f, arrow = true)

    @Test
    fun the_drawing_at_200_percent() = check("drawing-200", model(drawing), fontScale = 2f, arrow = true)

    @Test
    fun the_message_in_place_of_the_header() = check("message-100", model(drawing), message = skippedMessage, arrow = true)

    @Test
    fun the_message_at_200_percent() =
        check("message-200", model(drawing), message = skippedMessage, fontScale = 2f, arrow = true)

    @Test
    fun a_one_line_message_is_still_a_48dp_button() =
        // REQ-067 (TASK-030): the shortest message kind, one line on a 4x2.
        check("message-short-100", model(drawing), message = "아침약: 이미 기록됨 (08:02). 변경 없음", arrow = true)

    @Test
    fun the_restore_band() =
        check("restore-100", model(short(4), restore = "복원됨 · 마지막 기록 11/09${nb}21:40 · 앱에서 확인"), arrow = true)

    @Test
    fun four_short_items_fit_and_six_do_not() {
        check("short4-100", model(short(4)), arrow = false)
        check("short6-100", model(short(6)), arrow = true)
    }

    @Test
    fun one_person_without_names() = check(
        "one-person-100",
        model(
            listOf(
                button("x", "아침약", null, listOf("08:02 먹음"), TileState.TAKEN),
                button("y", "저녁약", null, listOf("마지막 복용 어제${nb}20:10")),
                button("z", "비타민D", null, listOf("건너뜀"), TileState.SKIPPED),
            ),
        ),
        arrow = false,
    )

    @Test
    fun a_long_person_name_above_the_item() = check(
        "who-above-100",
        model(
            listOf(
                button("a", "골다공증약", "할머니", listOf("기록 없음"), person = 2),
                button("b", "혈압약", "엄마", listOf("기록 없음"), person = 1),
            ),
        ),
        arrow = false,
    )

    @Test
    fun landscape_and_narrow_sizes() {
        // Two landscape rows (about 117dp) and a launcher that gives four cells only 276dp.
        check("landscape-100", model(drawing), size = DpSize(350.dp, 117.dp), arrow = true)
        check("narrow-100", model(drawing), size = DpSize(276.dp, 213.dp), arrow = true)
    }

    @Test
    fun empty_and_loading() {
        check("empty-100", model(emptyList()), arrow = false)
        check("loading-100", null, arrow = false)
    }

    @Test
    fun the_reconfigure_line_instead_of_tiles_and_count() {
        // SCR-001#reconfigure (TASK-012, REQ-079): the line alone, no count; with a restore both lines show.
        val restoreLine = "복원됨 · 마지막 기록 11/09${nb}21:40 · 앱에서 확인"
        for ((name, root) in listOf(
            "reconfigure-100" to checkView("reconfigure-100", model(emptyList(), reconfigure = Labels.RECONFIGURE), arrow = false),
            "reconfigure-archived-200" to checkView(
                "reconfigure-archived-200",
                model(emptyList(), reconfigure = Labels.RECONFIGURE_ARCHIVED),
                fontScale = 2f,
                arrow = false,
            ),
            "reconfigure-restored-100" to checkView(
                "reconfigure-restored-100",
                model(emptyList(), restore = restoreLine, reconfigure = Labels.RECONFIGURE),
                arrow = false,
            ),
        )) {
            val texts = shownTexts(root)
            assertTrue("$name: the line is shown", texts.any { it.endsWith(Labels.RECONFIGURE) })
            assertTrue("$name: no count", texts.none { it.contains("완료") || it.contains(" / ") })
            assertTrue("$name: no empty line", texts.none { it == Labels.EMPTY_WIDGET })
        }
    }

    @Test
    fun a_widget_for_mom_and_her_empty_line() {
        // SPEC-005: only 엄마's tiles; with two people her name stays on them (REQ-018).
        val moms = drawing.filter { it.who == "엄마" }
        val root = checkView("mom-100", model(moms), arrow = false)
        assertTrue(shownTexts(root).none { it == "아침약" || it == "비타민D" })
        // SCR-001#empty for her (proposal).
        val empty = checkView("mom-empty-100", model(emptyList(), empty = Labels.emptyFor("엄마")), arrow = false)
        assertTrue(shownTexts(empty).contains("엄마 항목이 없어요"))
    }

    @Test
    fun the_pending_ring_stays_hollow_when_tinted() {
        // A stroked oval shape fills its inside once tinted (GradientDrawable paints the fill through the color
        // filter when it has no solid color): the ring came out as a disc before it became a vector.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val size = 72
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val ring = checkNotNull(context.getDrawable(R.drawable.widget_icon_ring)).mutate()
        ring.setTint(Tokens.PEOPLE[1].ink.toInt())
        ring.setBounds(0, 0, size, size)
        ring.draw(Canvas(bitmap))
        assertEquals("inside the ring", 0, Color.alpha(bitmap.getPixel(size / 2, size / 2)))
        assertTrue("the dash at the top", Color.alpha(bitmap.getPixel(size / 2, 3)) > 0)
    }

    // ---------------------------------------------------------------------------------------------

    private fun check(
        name: String,
        model: WidgetModel?,
        message: String? = null,
        size: DpSize = portrait,
        fontScale: Float = 1f,
        arrow: Boolean,
    ) {
        checkView(name, model, message, size, fontScale, arrow)
    }

    /** [check], returning the laid-out widget for more assertions. */
    private fun checkView(
        name: String,
        model: WidgetModel?,
        message: String? = null,
        size: DpSize = portrait,
        fontScale: Float = 1f,
        arrow: Boolean,
    ): View {
        val root = renderWidget(name, model, message, size, fontScale)
        val views = buildList {
            fun walk(v: View) {
                add(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
        }

        // REQ-067 (TASK-030): every button (the top, a tile, a band) is at least 48dp both ways; a pixel for rounding.
        val density = root.resources.displayMetrics.density
        for (button in views.filter { it.isClickable && shown(it) }) {
            val size = "${button.width / density}×${button.height / density}dp"
            assertTrue("$name: a ${button.javaClass.simpleName} button is $size", minOf(button.width, button.height) >= 48 * density - 1)
        }

        val arrowShown = views.any { it is ImageView && it.contentDescription == Labels.MORE && shown(it) }
        assertEquals("$name: down arrow", arrow, arrowShown)
        views.filterIsInstance<ListView>().filter(::shown).forEach { list ->
            if (rowsCut(list)) assertTrue("$name: rows are cut but the arrow is hidden", arrowShown)
        }

        for (text in views.filterIsInstance<TextView>().filter { shown(it) && it.text.isNotEmpty() }) {
            val plain = text.text.toString().replace(WidgetLayout.WORD_JOINER.toString(), "")
            assertTrue("$name: '$plain' got no width", text.width > 0)
            if (plain.none { it == ' ' }) assertEquals("$name: '$plain' has no space but wrapped", 1, text.lineCount)
            val layout = text.layout ?: continue
            for (line in 1 until layout.lineCount) {
                val before = text.text[layout.getLineStart(line) - 1]
                assertEquals("$name: line ${line + 1} of '$plain' starts inside a word", ' ', before)
            }
        }
        return root
    }

    /** Some rows are not laid out, or the last one goes past the bottom of the list. */
    private fun rowsCut(list: ListView): Boolean {
        val count = list.adapter?.count ?: return false
        if (count == 0) return false
        if (list.childCount < count) return true
        return list.getChildAt(list.childCount - 1).bottom > list.height - list.paddingBottom
    }
}
