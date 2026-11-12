package com.judangbang.pillbadeum.design

import com.judangbang.pillbadeum.usecase.PersonColors
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** [Tokens] stay equal to `design/base.tokens.json`, the 09 mockup's fixed tokens. */
class TokensTest {
    private val tokens: Map<*, *> by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "design/base.tokens.json").isFile) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "design/base.tokens.json not found above the working directory" }, "design/base.tokens.json")
        MiniJson(file.readText()).value() as Map<*, *>
    }

    private fun hex(vararg path: String): String {
        var node: Any? = tokens["color"]
        for (key in path) node = (node as Map<*, *>)[key]
        return ((node as Map<*, *>)["\$value"] as Map<*, *>)["hex"] as String
    }

    @Test
    fun base_colors_match_the_token_file() {
        assertEquals(hex("surface", "default"), PersonPalette.hex(Tokens.SURFACE))
        assertEquals(hex("surface", "skip"), PersonPalette.hex(Tokens.SURFACE_SKIP))
        assertEquals(hex("surface", "notice"), PersonPalette.hex(Tokens.SURFACE_NOTICE))
        assertEquals(hex("ink", "default"), PersonPalette.hex(Tokens.INK))
        assertEquals(hex("ink", "muted"), PersonPalette.hex(Tokens.INK_MUTED))
        assertEquals(hex("ink", "on-solid"), PersonPalette.hex(Tokens.INK_ON_SOLID))
        assertEquals(hex("ink", "notice"), PersonPalette.hex(Tokens.INK_NOTICE))
        assertEquals(hex("line", "subtle"), PersonPalette.hex(Tokens.LINE_SUBTLE))
        assertEquals(hex("line", "strong"), PersonPalette.hex(Tokens.LINE_STRONG))
    }

    @Test
    fun person_palettes_match_the_token_file() {
        assertEquals(6, Tokens.PEOPLE.size)
        Tokens.PEOPLE.forEachIndexed { index, palette ->
            val n = (index + 1).toString()
            assertEquals(hex("person", n, "solid"), PersonPalette.hex(palette.solid), "person.$n.solid")
            assertEquals(hex("person", n, "tint"), PersonPalette.hex(palette.tint), "person.$n.tint")
            assertEquals(hex("person", n, "ink"), PersonPalette.hex(palette.ink), "person.$n.ink")
        }
    }

    @Test
    fun stored_person_colors_map_to_token_palettes() {
        assertEquals("#2B59C3", PersonColors.DEFAULT, "나 starts blue, not the old green")
        assertEquals(Tokens.PEOPLE.map { it.solidHex }, PersonColors.PALETTE)
        assertEquals(Tokens.PEOPLE[1], PersonColors.palette("#c2501a"))
        // Colors offered before the tokens (test phones only) keep their place in the list.
        assertEquals(Tokens.PEOPLE[0], PersonColors.palette("#3F7D58"))
        assertEquals(Tokens.PEOPLE[2], PersonColors.palette("#1D4E89"))
        assertEquals(Tokens.PEOPLE[0], PersonColors.palette("#123456"))
    }
}

/** Just enough JSON for the token file (objects, arrays, strings, numbers, true, false, null). */
private class MiniJson(private val s: String) {
    private var i = 0

    fun value(): Any? {
        ws()
        return when (s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> true.also { i += 4 }
            'f' -> false.also { i += 5 }
            'n' -> null.also { i += 4 }
            else -> num()
        }
    }

    private fun obj(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (s[i] == '}') return map.also { i++ }
        while (true) {
            ws()
            val key = str()
            ws()
            i++ // ':'
            map[key] = value()
            ws()
            if (s[i++] == '}') return map
        }
    }

    private fun arr(): List<Any?> {
        val list = ArrayList<Any?>()
        i++
        ws()
        if (s[i] == ']') return list.also { i++ }
        while (true) {
            list += value()
            ws()
            if (s[i++] == ']') return list
        }
    }

    private fun str(): String {
        val out = StringBuilder()
        i++
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (s[i]) {
                    'u' -> out.append(s.substring(i + 1, i + 5).toInt(16).toChar()).also { i += 4 }
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    else -> out.append(s[i])
                }
            } else {
                out.append(s[i])
            }
            i++
        }
        i++
        return out.toString()
    }

    private fun num(): Double {
        val start = i
        while (i < s.length && s[i] in "+-.eE0123456789") i++
        return s.substring(start, i).toDouble()
    }

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }
}
