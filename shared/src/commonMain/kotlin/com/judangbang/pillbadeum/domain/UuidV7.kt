package com.judangbang.pillbadeum.domain

import kotlin.random.Random
import kotlin.time.Clock

/**
 * RFC 9562 UUID version 7 generator (data-model.md section 0: every row id is a device-made UUIDv7).
 *
 * Layout: 48-bit unix epoch milliseconds | version `0111` | 12 random bits |
 * variant `10` | 62 random bits. Output is the lowercase canonical 8-4-4-4-12 string.
 *
 * Ids from different milliseconds sort by time (string order == time order). Ids created within
 * the same millisecond are random relative to each other; no counter is kept.
 */
class UuidV7(
    private val clock: Clock = Clock.System,
    private val random: Random = Random.Default,
) {
    fun next(): String {
        val unixMillis = clock.now().toEpochMilliseconds()
        require(unixMillis in 0..MAX_UNIX_MILLIS) { "timestamp out of UUIDv7 range: $unixMillis" }

        val randA = random.nextInt() and 0xFFF // 12 bits
        val randB = random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL // 62 bits

        val mostSignificant = (unixMillis shl 16) or (VERSION shl 12) or randA.toLong()
        val leastSignificant = VARIANT_BITS or randB
        return format(mostSignificant, leastSignificant)
    }

    private fun format(msb: Long, lsb: Long): String {
        val hex = msb.toULong().toString(16).padStart(16, '0') +
            lsb.toULong().toString(16).padStart(16, '0')
        return buildString(36) {
            append(hex, 0, 8)
            append('-')
            append(hex, 8, 12)
            append('-')
            append(hex, 12, 16)
            append('-')
            append(hex, 16, 20)
            append('-')
            append(hex, 20, 32)
        }
    }

    private companion object {
        const val MAX_UNIX_MILLIS: Long = 0xFFFF_FFFF_FFFFL // 48 bits
        const val VERSION: Long = 0x7L
        const val VARIANT_BITS: Long = Long.MIN_VALUE // top two bits `10`
    }
}
