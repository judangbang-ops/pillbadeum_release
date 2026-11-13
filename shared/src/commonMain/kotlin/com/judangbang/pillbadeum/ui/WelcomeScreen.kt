package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.judangbang.pillbadeum.design.Tokens
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.WidgetLayout
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** What SCR-005 does (screens.md SCR-005 table of actions). */
internal class WelcomeActions(
    /** `[다음]` on the first page: the second page. */
    val next: () -> Unit,
    /** `[시작하기]` and `[건너뛰기]`: the first screen (SCR-004), and the intro does not show again. */
    val finish: () -> Unit,
    /** `[위젯 추가]`: asks the launcher to place the widget. */
    val pin: () -> Unit,
)

/** SCR-005's two pages. */
internal const val WELCOME_PAGES: Int = 2

/**
 * SCR-005, the first-run intro (09 Welcome1, Welcome2; FL-001, SPEC-002, TASK-022). [page] 0 says that the app is a
 * record tool and that one medicine is recorded on one phone (REQ-036, REQ-074); page 1 how to place the widget
 * (REQ-080). [canPin]: the launcher takes a request to place the widget, so `[위젯 추가]` shows; without it only the
 * steps show and `[시작하기]` becomes the dark button (SCR-005#error). Each page scrolls between its bar and its buttons,
 * so at the largest text the whole page can still be read (REQ-066).
 */
@Composable
internal fun WelcomeScreen(page: Int, canPin: Boolean, actions: WelcomeActions) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            BarButton(Labels.INTRO_SKIP, actions.finish)
        }
        // A page of its own scroll, so the second page starts at its top.
        key(page) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = Ui.Side, end = Ui.Side, top = 4.dp),
            ) {
                if (page == 0) WhatItIs() else HowToPlace()
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = Ui.Side, end = Ui.Side, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PageMarks(page)
            if (page == 0) {
                ActionButton(Labels.INTRO_NEXT, ButtonLook.Ink, actions.next)
            } else {
                if (canPin) ActionButton(Labels.INTRO_PIN, ButtonLook.Ink, actions.pin)
                ActionButton(Labels.INTRO_START, if (canPin) ButtonLook.Line else ButtonLook.Ink, actions.finish)
            }
        }
    }
}

/** Page 1 (09 Welcome1): the week picture, the headline, and the record-tool and one-phone notes. */
@Composable
private fun WhatItIs() {
    WeekPicture(Modifier.padding(top = 16.dp, bottom = 24.dp))
    Words(Labels.INTRO_HEADLINE, Ui.type(26, Ui.ExtraBold, lineHeight = 1.3f), Modifier.padding(horizontal = 4.dp), heading = true)
    Words(Labels.INTRO_LEAD, Ui.type(16, color = Ui.Muted), Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp))
    Column(
        Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Point(Labels.INTRO_TOOL_TITLE, Labels.INTRO_TOOL_BODY)
        Point("${Labels.ONE_PHONE}.", Labels.INTRO_ONE_PHONE_BODY)
    }
}

/** A note with an ink line on its left (09 Welcome1 `.pts p`): [title] in bold, then [body]; read as one sentence. */
@Composable
private fun Point(title: String, body: String) {
    val text = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(WidgetLayout.keepWords(title)) }
        append(' ')
        append(WidgetLayout.keepWords(body))
    }
    Words(
        text,
        plain = "$title $body",
        style = Ui.type(14),
        modifier = Modifier.fillMaxWidth()
            .drawBehind { drawRect(Ui.Ink, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 15.dp),
    )
}

/**
 * Page 1's picture (09 Welcome1 `.box`): a week of lids, Monday to Wednesday taken in the first person's color and
 * Thursday today. TalkBack reads it as one sentence ([Labels.INTRO_PICTURE]).
 */
@Composable
private fun WeekPicture(modifier: Modifier) {
    val solid = Color(Tokens.PEOPLE[0].solid)
    Column(
        modifier.fillMaxWidth().clip(Ui.Tile).background(Ui.Sunken).padding(horizontal = 12.dp, vertical = 16.dp)
            .clearAndSetSemantics { contentDescription = Labels.INTRO_PICTURE },
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { day ->
                Words(Labels.weekday(day), Ui.type(14, Ui.SemiBold, Ui.Muted, lineHeight = 1.25f, center = true), Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(DayOfWeek.entries.size) { day -> Box(Modifier.weight(1f).height(56.dp).drawBehind { drawLid(day, solid) }) }
        }
    }
}

/** One lid of [WeekPicture]: taken (`.lidc.t`), today (`.lidc.now`) or later (`.lidc`). */
private fun DrawScope.drawLid(day: Int, solid: Color) {
    val radius = 12.dp.toPx()
    when {
        day < 3 -> {
            drawRoundRect(solid, cornerRadius = CornerRadius(radius))
            drawCheck(Ui.OnSolid)
        }
        day == 3 -> {
            // A 2 dp sunken gap and a 2 dp ink ring outside the lid, then a dashed border in the person's color.
            val outer = 4.dp.toPx()
            val gap = 2.dp.toPx()
            drawRoundRect(Ui.Ink, Offset(-outer, -outer), Size(size.width + 2 * outer, size.height + 2 * outer), CornerRadius(radius + outer))
            drawRoundRect(Ui.Sunken, Offset(-gap, -gap), Size(size.width + 2 * gap, size.height + 2 * gap), CornerRadius(radius + gap))
            drawRoundRect(Ui.Surface, cornerRadius = CornerRadius(radius))
            val w = 2.dp.toPx()
            drawRoundRect(
                solid,
                Offset(w / 2, w / 2),
                Size(size.width - w, size.height - w),
                CornerRadius(radius - w / 2),
                style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
        else -> {
            drawRoundRect(Ui.Surface, cornerRadius = CornerRadius(radius))
            val w = 1.5.dp.toPx()
            drawRoundRect(Ui.LineStrong, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(radius - w / 2), style = Stroke(w))
        }
    }
}

/** The 18 dp check of a taken lid (09 `M5 12.5l4.5 4.5L19 7.5` in a 24 box, stroke 2.6), in the middle. */
private fun DrawScope.drawCheck(color: Color) {
    val box = 18.dp.toPx()
    val u = box / 24f
    val left = (size.width - box) / 2
    val top = (size.height - box) / 2
    val path = Path().apply {
        moveTo(left + 5f * u, top + 12.5f * u)
        lineTo(left + 9.5f * u, top + 17f * u)
        lineTo(left + 19f * u, top + 7.5f * u)
    }
    drawPath(path, color, style = Stroke(2.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Page 2 (09 Welcome2): the headline, a picture of the widget, and the steps to place it by hand. */
@Composable
private fun HowToPlace() {
    Words(Labels.INTRO_PIN_TITLE, Ui.type(26, Ui.ExtraBold, lineHeight = 1.3f), Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp), heading = true)
    Words(Labels.INTRO_PIN_LEAD, Ui.type(16, color = Ui.Muted), Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp))
    WidgetPicture(Modifier.padding(vertical = 20.dp))
    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Labels.INTRO_STEPS.forEachIndexed { i, step -> Step(i + 1, step) }
    }
    Words(Labels.INTRO_STEPS_NOTE, Ui.type(14, color = Ui.Muted), Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp))
}

/** A numbered step (09 Welcome2 `.steps li`), read with its number: `1. 홈 화면의 빈 곳을 길게 누르기`. */
@Composable
private fun Step(number: Int, step: String) {
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { text = AnnotatedString("$number. $step") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).clip(RoundedCornerShape(8.dp)).background(Ui.Ink),
            contentAlignment = Alignment.Center,
        ) {
            BasicText("$number", style = Ui.type(14, Ui.ExtraBold, Ui.OnSolid, lineHeight = 1f, center = true))
        }
        Words(step, Ui.type(16, lineHeight = 1.25f), Modifier.weight(1f))
    }
}

/**
 * Page 2's picture of the widget (09 Welcome2 `.mini`): the header and four tiles of the 09 week, for the eye only
 * (`aria-hidden`); the steps say what to do.
 */
@Composable
private fun WidgetPicture(modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    val me = Tokens.PEOPLE[0]
    val mom = Tokens.PEOPLE[1]
    Column(
        modifier.fillMaxWidth().hiddenFromTalkBack().shadow(6.dp, shape).border(1.dp, Ui.LineSubtle, shape).clip(shape)
            .background(Ui.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.sizeIn(minWidth = 22.dp, minHeight = 22.dp).clip(RoundedCornerShape(7.dp)).background(Ui.Ink),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(Labels.weekday(DayOfWeek.THURSDAY), style = Ui.type(12, Ui.ExtraBold, Ui.OnSolid, lineHeight = 1f, center = true))
            }
            BasicText(
                Labels.widgetDate(LocalDate(2026, 11, 12)),
                Modifier.weight(1f),
                style = Ui.type(12, Ui.SemiBold, lineHeight = 1.25f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Ui.Ink, fontWeight = FontWeight.Bold)) { append("2 / 4") }
                    append(" 완료")
                },
                style = Ui.type(12, color = Ui.Muted, lineHeight = 1.25f),
                maxLines = 1,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MiniTile("아침약", "08:02 먹음", Color(me.solid), Ui.OnSolid, Modifier.weight(1f))
            MiniTile("비타민D", "건너뜀", Ui.Skip, Ui.Muted, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MiniTile("혈압약", "어제 21:40", Color(mom.tint), Color(mom.ink), Modifier.weight(1f))
            MiniTile("골다공증약", "11/02(월)", Color(mom.tint), Color(mom.ink), Modifier.weight(1f))
        }
    }
}

/** A tile of [WidgetPicture] (09 `.mt`): the name in bold over a short line. */
@Composable
private fun MiniTile(name: String, detail: String, fill: Color, ink: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(fill).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        BasicText(name, style = Ui.type(13, Ui.ExtraBold, ink, lineHeight = 1.25f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(detail, style = Ui.type(10, color = ink, lineHeight = 1.25f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The page marks over the buttons (09 `.pg`): the current page long and dark; read as `2쪽 중 1쪽`. */
@Composable
private fun PageMarks(page: Int) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp).clearAndSetSemantics { contentDescription = Labels.introPage(page + 1, WELCOME_PAGES) },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(WELCOME_PAGES) { i ->
            val on = i == page
            Box(Modifier.width(if (on) 32.dp else 20.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (on) Ui.Ink else Ui.LineStrong))
        }
    }
}
