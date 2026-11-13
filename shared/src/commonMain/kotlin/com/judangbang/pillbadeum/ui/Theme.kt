package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.judangbang.pillbadeum.design.Tokens
import com.judangbang.pillbadeum.status.WidgetLayout
import com.judangbang.pillbadeum.usecase.PersonColors

/**
 * The app's colors, type and small parts, from the 09 mockup's tokens (2안 요일 약통, `design/base.tokens.json`,
 * `docs/09-mockup/boards/box.css`). Foundation only, no Material.
 */
internal object Ui {
    val Surface = Color(Tokens.SURFACE)
    val Sunken = Color(Tokens.SURFACE_SUNKEN)
    val Skip = Color(Tokens.SURFACE_SKIP)
    val Notice = Color(Tokens.SURFACE_NOTICE)
    val Ink = Color(Tokens.INK)
    val Muted = Color(Tokens.INK_MUTED)
    val OnSolid = Color(Tokens.INK_ON_SOLID)
    val NoticeInk = Color(Tokens.INK_NOTICE)
    val Danger = Color(Tokens.INK_DANGER)
    val LineSubtle = Color(Tokens.LINE_SUBTLE)
    val LineStrong = Color(Tokens.LINE_STRONG)

    /** `space.lg`: the screen's side padding. */
    val Side = 16.dp

    /** `radius.tile` */
    val Tile = RoundedCornerShape(18.dp)

    /** `radius.button` */
    val ButtonShape = RoundedCornerShape(12.dp)

    /** `font.size.*` with `font.line-height.normal` (1.4) unless said otherwise. */
    fun type(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Ink, lineHeight: Float = 1.4f, center: Boolean = false) =
        TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = weight,
            lineHeight = lineHeight.em,
            textAlign = if (center) TextAlign.Center else TextAlign.Start,
        )

    val SemiBold = FontWeight.W600
    val ExtraBold = FontWeight.W800
}

/** A person's three token colors (`color.person.n`): [solid] lid and taken tile, [tint] pending tile, [ink] its text. */
internal class PersonLook(val solid: Color, val tint: Color, val ink: Color)

internal fun personLook(hex: String): PersonLook =
    PersonColors.palette(hex).let { PersonLook(Color(it.solid), Color(it.tint), Color(it.ink)) }

/**
 * Text that breaks lines only at spaces, as the 09 mockup draws (`word-break: keep-all`): Korean words are joined with
 * [WidgetLayout.keepWords] (BUG-002, `지워지지 않 / 고`). TalkBack and tests read [text] without the joiners.
 */
@Composable
internal fun Words(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    heading: Boolean = false,
    /** What TalkBack reads instead of [text] (`11월 11일 수요일 04:00부터 …` for `11/11 (수) 04:00 ~ …`). */
    spoken: String? = null,
) {
    BasicText(
        text = WidgetLayout.keepWords(text),
        modifier = modifier.clearAndSetSemantics {
            if (spoken != null) contentDescription = spoken else this.text = AnnotatedString(text)
            if (heading) heading()
        },
        style = style,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** [Words] for text with styled parts; [plain] is what TalkBack reads. */
@Composable
internal fun Words(text: AnnotatedString, plain: String, style: TextStyle, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(plain) },
        style = style,
    )
}

/** The bar on top of every screen (box.css `.bar`): a back or close button, the title, then text buttons. */
@Composable
internal fun TopBar(
    title: String,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (navigation == null) 20.dp else 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        navigation?.invoke()
        Words(title, Ui.type(22, Ui.ExtraBold, lineHeight = 1.25f), Modifier.weight(1f), heading = true)
        actions()
    }
}

/** A text button of the bar (box.css `.tb`): `[지난 기록]`, `[설정]`. */
@Composable
internal fun BarButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(Ui.ButtonShape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Words(label, Ui.type(15, Ui.SemiBold, lineHeight = 1.25f))
    }
}

/** A button's look (box.css `.btn` and its kinds). [fill] null is transparent. */
internal data class ButtonLook(
    val fill: Color?,
    val ink: Color,
    val border: Color? = null,
    val weight: FontWeight = Ui.ExtraBold,
    val minHeight: Dp = 52.dp,
    val wide: Boolean = true,
) {
    companion object {
        /** `.btn.ink`: `[항목 추가]`, `[다시 시도]`. */
        val Ink = ButtonLook(Ui.Ink, Ui.OnSolid)

        /** `.btn.p`: `[먹었어]` on a pending tile. */
        fun person(solid: Color) = ButtonLook(solid, Ui.OnSolid)

        /** `[기록 취소]` on a taken tile: white outline on the person's color. */
        val OnSolid = ButtonLook(null, Ui.OnSolid, border = Ui.OnSolid, weight = Ui.SemiBold)

        /** `.btn.line` */
        val Line = ButtonLook(Ui.Surface, Ui.Ink, border = Ui.LineStrong, weight = Ui.SemiBold)

        /** `.btn.ink.sm`, `.btn.line.sm`: buttons in a notice band. */
        val InkSmall = Ink.copy(minHeight = 48.dp, wide = false)
        val LineSmall = Line.copy(minHeight = 48.dp, wide = false)

        /** `.btn.danger`: `[보관]` of the item form. */
        val Danger = ButtonLook(null, Ui.Danger, weight = Ui.SemiBold, minHeight = 48.dp)
    }
}

/** A button; when not [enabled] it is drawn sunken and muted and does nothing (SCR-007's `[이 시각으로 기록]` before a time). */
@Composable
internal fun ActionButton(
    label: String,
    look: ButtonLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    val shown = if (enabled) look else look.copy(fill = Ui.Sunken, ink = Ui.Muted, border = null)
    var m = modifier.heightIn(min = shown.minHeight)
    if (shown.wide) m = m.fillMaxWidth()
    m = m.clip(Ui.ButtonShape)
    shown.fill?.let { m = m.background(it) }
    shown.border?.let { m = m.border(1.5.dp, it, Ui.ButtonShape) }
    Row(
        m.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        Words(label, Ui.type(16, shown.weight, shown.ink, lineHeight = 1.25f, center = true))
    }
}

/** The `+` of `[항목 추가]`. */
@Composable
internal fun PlusIcon(color: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = 2.dp.toPx()
        val inset = this.size.width / 8
        val mid = this.size.width / 2
        drawLine(color, Offset(mid, inset), Offset(mid, this.size.height - inset), w, StrokeCap.Round)
        drawLine(color, Offset(inset, mid), Offset(this.size.width - inset, mid), w, StrokeCap.Round)
    }
}

/** A 48 dp icon button of the bar: close (`×`) or back (`←`), read as [label]. */
@Composable
internal fun BarIconButton(label: String, close: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(Ui.ButtonShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val w = 2.dp.toPx()
            val s = size.width
            if (close) {
                drawLine(Ui.Ink, Offset(s * 0.25f, s * 0.25f), Offset(s * 0.75f, s * 0.75f), w, StrokeCap.Round)
                drawLine(Ui.Ink, Offset(s * 0.75f, s * 0.25f), Offset(s * 0.25f, s * 0.75f), w, StrokeCap.Round)
            } else {
                drawLine(Ui.Ink, Offset(s * 0.2f, s / 2), Offset(s * 0.8f, s / 2), w, StrokeCap.Round)
                drawLine(Ui.Ink, Offset(s * 0.2f, s / 2), Offset(s * 0.45f, s * 0.25f), w, StrokeCap.Round)
                drawLine(Ui.Ink, Offset(s * 0.2f, s / 2), Offset(s * 0.45f, s * 0.75f), w, StrokeCap.Round)
            }
        }
    }
}

/** A section title (box.css `h2`): `오늘`, `이번 주 약통`. */
@Composable
internal fun SectionTitle(text: String) {
    Words(
        text,
        Ui.type(15, Ui.ExtraBold, Ui.Muted, lineHeight = 1.25f).copy(letterSpacing = 0.02.em),
        Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp),
        heading = true,
    )
}

/**
 * A notice band (box.css `.band`): restore, permission, clock warnings and messages. [title] goes first in bold;
 * [live] has TalkBack read the band when it appears (a result message).
 */
@Composable
internal fun Band(
    text: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    live: Boolean = false,
    buttons: (@Composable RowScope.() -> Unit)? = null,
) {
    var m = modifier.fillMaxWidth().clip(Ui.Tile).background(Ui.Notice)
    if (live) m = m.semantics { liveRegion = LiveRegionMode.Polite }
    Column(m.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        title?.let { Words(it, Ui.type(14, Ui.ExtraBold, Ui.NoticeInk)) }
        Words(text, Ui.type(14, Ui.SemiBold, Ui.NoticeInk))
        if (buttons != null) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = buttons)
        }
    }
}

/** Draws a dashed rounded border inside the bounds (box.css `dashed`). */
internal fun Modifier.dashedBorder(width: Dp, color: Color, radius: Dp, dash: Dp, gap: Dp): Modifier = drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx() - w / 2),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx()))),
    )
}

/** A question before a change ("기록을 취소할까요?"), with `[아니요]` and [confirm]. */
@Composable
internal fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            // Scrolls when large text makes it taller than the window, so the buttons stay reachable.
            Modifier.clip(Ui.Tile).background(Ui.Surface).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Words(title, Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f), heading = true)
            Words(body, Ui.type(16, color = Ui.Muted))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                ActionButton("아니요", ButtonLook.LineSmall, onDismiss)
                ActionButton(confirm, ButtonLook.InkSmall, onConfirm)
            }
        }
    }
}

/** A notice after a change that was made (SCR-006#quiet), with `[확인]` alone; closing it any way is the same. */
@Composable
internal fun NoticeDialog(title: String, body: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.clip(Ui.Tile).background(Ui.Surface).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Words(title, Ui.type(18, Ui.ExtraBold, lineHeight = 1.3f), heading = true)
            Words(body, Ui.type(16, color = Ui.Muted))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                ActionButton("확인", ButtonLook.InkSmall, onClose)
            }
        }
    }
}

/** For a part that only repeats what TalkBack already reads elsewhere (`aria-hidden`). */
internal fun Modifier.hiddenFromTalkBack(): Modifier = clearAndSetSemantics { }
