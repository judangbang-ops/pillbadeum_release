package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.judangbang.pillbadeum.design.Tokens
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.PickOption
import com.judangbang.pillbadeum.status.WidgetChoice
import com.judangbang.pillbadeum.status.WidgetLayout
import com.judangbang.pillbadeum.status.WidgetPick

/** What the widget's person picker shows (SCR-002, TASK-012). */
sealed interface PickState {
    /**
     * Reading the people. Nothing is drawn yet, so with one person, who never sees this screen (REQ-018), the
     * launcher stays in view while the widget is set to 전체.
     */
    data object Waiting : PickState

    /** Still reading after a moment: `불러오는 중…` (SCR-002#loading). */
    data object Loading : PickState

    /** The choices (SCR-002#normal), with `저장하지 못했어요. 다시 골라 주세요` above them after a failed save (SCR-002#error). */
    data class Ready(val options: List<PickOption>, val error: String? = null) : PickState
}

/**
 * SCR-002 as the 09 mockup's `WidgetPick` board draws it (2안 요일 약통, 09 tokens): the question, 전체 and each
 * person on a lid of their color with how many items the widget would show, and [취소] at the bottom. Picking draws
 * the widget for that choice; [취소] and back do not place the widget (SPEC-005). The list scrolls when it is longer
 * than the screen (large fonts, many people).
 */
@Composable
fun WidgetPickScreen(state: PickState, onPick: (WidgetChoice) -> Unit, onCancel: () -> Unit) {
    if (state == PickState.Waiting) {
        Box(Modifier.fillMaxSize())
        return
    }
    Column(Modifier.fillMaxSize().background(color(Tokens.SURFACE)).safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
        ) {
            when (state) {
                PickState.Waiting, PickState.Loading -> Words(Labels.LOADING, size = 16, ink = Tokens.INK_MUTED, inset = true)
                is PickState.Ready -> {
                    Words(
                        Labels.PICK_TITLE,
                        size = 22,
                        weight = FontWeight.ExtraBold,
                        lineHeight = 1.25f,
                        inset = true,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Words(Labels.PICK_SUBTITLE, size = 16, ink = Tokens.INK_MUTED, inset = true)
                    state.error?.let { error ->
                        Spacer(Modifier.height(16.dp))
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color(Tokens.SURFACE_NOTICE))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Words(error, size = 14, ink = Tokens.INK_NOTICE, weight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.options.forEach { option -> Option(option) { onPick(option.choice) } }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Words(Labels.PICK_CANCEL, size = 16, weight = FontWeight.SemiBold)
            }
        }
    }
}

/** One choice (09 `.opt`): at least 72dp tall, a 1.5dp strong line, read as `엄마, 약 2개`. */
@Composable
private fun Option(option: PickOption, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(shape).background(color(Tokens.SURFACE))
            .border(1.5.dp, color(Tokens.LINE_STRONG), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clearAndSetSemantics { contentDescription = WidgetPick.spoken(option) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Lid(option)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Words(option.title, size = 18, weight = FontWeight.ExtraBold, lineHeight = 1.25f)
                if (option.detail.isNotEmpty()) Words(option.detail, size = 14, ink = Tokens.INK_MUTED, lineHeight = 1.25f)
            }
        }
    }
}

/** 09 `.lid`: 40dp, the person's color with their first letter, or ink with four squares for 전체. */
@Composable
private fun Lid(option: PickOption) {
    val fill = option.palette?.solid ?: Tokens.INK
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color(fill)),
        contentAlignment = Alignment.Center,
    ) {
        val letter = option.letter
        if (letter == null) {
            Canvas(Modifier.size(20.dp)) {
                // The mockup's 20x20 icon: four 7x7 squares with 2 rounded corners, 2 from the edges.
                val unit = size.width / 20f
                for (x in listOf(2f, 11f)) for (y in listOf(2f, 11f)) {
                    drawRoundRect(
                        color = color(Tokens.INK_ON_SOLID),
                        topLeft = Offset(x * unit, y * unit),
                        size = Size(7 * unit, 7 * unit),
                        cornerRadius = CornerRadius(2 * unit),
                    )
                }
            }
        } else {
            Words(letter, size = 16, ink = Tokens.INK_ON_SOLID, weight = FontWeight.ExtraBold)
        }
    }
}

/** Text in the 09 sizes whose Korean words stay whole: lines break only at spaces, as on the widget (BUG-002). */
@Composable
private fun Words(
    text: String,
    size: Int,
    modifier: Modifier = Modifier,
    ink: Long = Tokens.INK,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: Float = 1.4f,
    inset: Boolean = false,
) {
    BasicText(
        text = WidgetLayout.keepWords(text),
        modifier = if (inset) modifier.padding(horizontal = 4.dp) else modifier,
        style = TextStyle(color = color(ink), fontSize = size.sp, fontWeight = weight, lineHeight = (size * lineHeight).sp),
    )
}

private fun color(argb: Long): Color = Color(argb)
