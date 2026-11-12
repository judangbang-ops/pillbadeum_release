package com.judangbang.pillbadeum.ui

import kotlinx.datetime.LocalTime

/**
 * What the app asks of the phone itself, outside the database (SCR-009, TASK-020). Android gives its own; a test can give
 * one that records the calls.
 */
interface Phone {
    /** The installed version (`versionName`, `0.1.0`), shown under `앱 정보`. */
    val appVersion: String

    /** Android 8.x (API 26, 27): the app's backup rules skip the backup there on purpose (REQ-054, supplement B-3). */
    val backupUnsupported: Boolean

    /** Whether a screen lock (PIN, pattern or password) is set, or null when it cannot be read (SPEC-035). */
    fun screenLock(): Boolean?

    /** Opens [url] in a browser; false when nothing can open it, and the app shows the address (SPEC-036). */
    fun openUrl(url: String): Boolean

    /**
     * Shows the system time picker at [initial], 24-hour and to the minute (SCR-009 rule: no rounding to hours or half
     * hours). [onPicked] gets the time chosen; nothing is called when the picker is cancelled.
     */
    fun pickTime(initial: LocalTime, onPicked: (LocalTime) -> Unit)

    /** After the day start or base zone changed: the day-boundary refresh is set for the new boundary (SPEC-033). */
    suspend fun windowSettingsChanged()
}
