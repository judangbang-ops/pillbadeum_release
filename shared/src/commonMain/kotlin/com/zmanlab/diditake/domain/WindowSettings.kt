package com.zmanlab.diditake.domain

import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/**
 * Inputs for window calculation (record-rules.md section 2).
 *
 * Every window is computed in [baseZone], never in the device zone, so travelling or a device
 * zone change does not move a window (record-rules.md section 8).
 *
 * @property dayStart time of day at which a new day begins (`T0`, setting key `day_start`).
 * @property baseZone base time zone (`Z`, setting key `base_zone`).
 */
data class WindowSettings(
    val dayStart: LocalTime = DEFAULT_DAY_START,
    val baseZone: TimeZone = TimeZone.of(DEFAULT_BASE_ZONE_ID),
) {
    companion object {
        /** Default of setting `day_start` (data-model.md section 2, setting). */
        val DEFAULT_DAY_START: LocalTime = LocalTime(hour = 4, minute = 0)

        /** Default of setting `base_zone` (data-model.md section 2, setting). */
        const val DEFAULT_BASE_ZONE_ID: String = "Asia/Seoul"
    }
}
