package com.judangbang.pillbadeum.domain

import com.judangbang.pillbadeum.testing.TestClock
import com.judangbang.pillbadeum.testing.ZeroRandom
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class UuidV7Test {
    private val clock = TestClock(Instant.parse("2026-11-11T00:00:00Z"))
    private val canonical = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    @Test
    fun format_is_lowercase_canonical_with_version_7_and_variant_10() {
        val generator = UuidV7(clock, Random(seed = 1))
        repeat(1_000) {
            val text = generator.next()
            assertTrue(canonical.matches(text), "not canonical lowercase: $text")
            val uuid = UUID.fromString(text)
            assertEquals(7, uuid.version(), "version nibble of $text")
            assertEquals(2, uuid.variant(), "variant bits of $text must be 10")
            assertEquals('7', text[14])
            assertTrue(text[19] in "89ab", "variant hex digit of $text")
        }
    }

    @Test
    fun first_48_bits_are_unix_epoch_millis() {
        val text = UuidV7(clock, Random(seed = 2)).next()
        val millis = text.replace("-", "").substring(0, 12).toLong(16)
        assertEquals(clock.now().toEpochMilliseconds(), millis)
    }

    @Test
    fun ids_from_later_milliseconds_sort_later() {
        val generator = UuidV7(clock, Random(seed = 3))
        val ids = List(500) {
            clock.now += 1.milliseconds
            generator.next()
        }
        assertEquals(ids, ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun random_bits_vary_within_the_same_millisecond() {
        val generator = UuidV7(clock, Random(seed = 4))
        val ids = List(1_000) { generator.next() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith(ids.first().substring(0, 13)) }, "same timestamp prefix expected")
    }

    @Test
    fun injected_random_and_clock_make_ids_deterministic() {
        val text = UuidV7(clock, ZeroRandom).next()
        val millisHex = clock.now().toEpochMilliseconds().toString(16).padStart(12, '0')
        assertEquals("${millisHex.substring(0, 8)}-${millisHex.substring(8)}-7000-8000-000000000000", text)
    }

    @Test
    fun rejects_timestamps_before_unix_epoch() {
        val generator = UuidV7(TestClock(Instant.parse("1969-12-31T23:59:59Z")), Random(seed = 5))
        assertFailsWith<IllegalArgumentException> { generator.next() }
    }
}
