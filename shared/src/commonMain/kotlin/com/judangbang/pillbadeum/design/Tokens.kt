package com.judangbang.pillbadeum.design

/**
 * The 09 mockup's color tokens that code draws with (`design/base.tokens.json`, 2안 요일 약통, fixed 2026-11-12
 * 19:11 KST). Values are opaque ARGB. `TokensTest` keeps them equal to the JSON file, so change the file first.
 */
object Tokens {
    /** `color.surface.default`: app and widget background. */
    const val SURFACE: Long = 0xFFFFFFFF

    /** `color.surface.skip`: a skipped tile. */
    const val SURFACE_SKIP: Long = 0xFFECEDEF

    /** `color.surface.notice`: restore and permission notice band, widget message. */
    const val SURFACE_NOTICE: Long = 0xFFFFF1CC

    /** `color.ink.default`: body text, the weekday chip. */
    const val INK: Long = 0xFF15171C

    /** `color.ink.muted`: secondary text (6.1:1 on white, 5.2:1 on a skipped tile). */
    const val INK_MUTED: Long = 0xFF5C6270

    /** `color.ink.on-solid`: text on a filled tile or button. */
    const val INK_ON_SOLID: Long = 0xFFFFFFFF

    /** `color.ink.notice`: text on the notice band (8.2:1). */
    const val INK_NOTICE: Long = 0xFF5C4400

    /** `color.line.subtle`: decorative divider, never the only carrier of meaning. */
    const val LINE_SUBTLE: Long = 0xFFE3E5E9

    /** `color.line.strong`: input borders, the skipped disc (3.2:1 on white). */
    const val LINE_STRONG: Long = 0xFF8B909A

    /** `color.person.1`~`6`, in token order. A person stores only the lid color ([PersonPalette.solid]). */
    val PEOPLE: List<PersonPalette> = listOf(
        PersonPalette(solid = 0xFF2B59C3, tint = 0xFFE4ECFB, ink = 0xFF1E3F8F),
        PersonPalette(solid = 0xFFC2501A, tint = 0xFFFBE7DC, ink = 0xFF8A3510),
        PersonPalette(solid = 0xFF1F7A4C, tint = 0xFFDFF1E6, ink = 0xFF155636),
        PersonPalette(solid = 0xFF6B43B8, tint = 0xFFECE6F8, ink = 0xFF4B2D86),
        PersonPalette(solid = 0xFFB8325F, tint = 0xFFF9E2EA, ink = 0xFF842143),
        PersonPalette(solid = 0xFF0F6E7A, tint = 0xFFDCEFF1, ink = 0xFF0A4F58),
    )
}

/**
 * One person's colors (`color.person.n`): [solid] fills a taken tile, [tint] is a pending tile's background and
 * [ink] its text and dashed ring.
 */
data class PersonPalette(val solid: Long, val tint: Long, val ink: Long) {
    /** `#RRGGBB` of [solid], the value stored on the person. */
    val solidHex: String get() = hex(solid)

    companion object {
        fun hex(argb: Long): String = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
    }
}
