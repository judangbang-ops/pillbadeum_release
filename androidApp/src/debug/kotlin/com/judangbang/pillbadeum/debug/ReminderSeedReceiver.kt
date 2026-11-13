package com.judangbang.pillbadeum.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.container
import com.judangbang.pillbadeum.domain.ReminderSettings
import com.judangbang.pillbadeum.domain.ScheduleRule
import com.judangbang.pillbadeum.usecase.IntakeDraft
import com.judangbang.pillbadeum.usecase.PersonChange
import com.judangbang.pillbadeum.usecase.PersonColors
import com.judangbang.pillbadeum.usecase.SaveIntakeResult
import com.judangbang.pillbadeum.usecase.SaveReminderResult
import com.judangbang.pillbadeum.usecase.SaveReminderSettingsResult
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/**
 * Debug builds only (`androidApp/src/debug`): sets up what the item and settings screens (TASK-023, TASK-020) set,
 * so that `scripts/emulator/reminder_check.py` can make the same state every run without driving those screens (the
 * item screen's reminder field is checked by the app test `ItemReminderTest`; kept by Claude's decision, 2026-11-13,
 * supplement N-13 '구현'). Everything goes through the same use cases as the app, then
 * [com.judangbang.pillbadeum.AppContainer.afterWrite].
 *
 * Not exported: only the shell as root (`adb root` on an emulator image) can reach it.
 *
 * - `SEED`: items with reminders on. Extras: `names` (comma-separated), `start` (HH:mm, default 08:00),
 *   `weekly` (a DayOfWeek name; daily when absent), `person` (added when no active person has that name),
 *   `max_per_day` (reminder settings, unchanged when absent). Result data: `seeded <id>,<id>,...`.
 * - `NAMES_ON_LOCK`: extra `show` (boolean), the "이름 표시" setting (SPEC-034). Result data: `names_on_lock <value>`.
 * - `START`: extras `name` and `start` (HH:mm): the item's reminders stay on from the new first time, as the item
 *   screen saves it (RR-56). Result data: `start <name> <HH:mm>`.
 * - `ARCHIVE_PERSON`: extra `name`: archives that active person, as the people screen does (RR-57, SPEC-027). Result
 *   data: `archived <name>`.
 * - `SETTINGS`: extras `max_per_day` and `interval_minutes` (int, each optional, at least one): the common reminder
 *   settings "하루 최대" and "간격" (N-1, N-4), the rest unchanged. Result data:
 *   `settings max_per_day <value> interval <minutes>`.
 *
 * The commands are in the script (an XML or KDoc comment cannot hold every character of them).
 */
class ReminderSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.container.appScope.launch {
            val result = try {
                handle(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "debug seed failed (${intent.action})", e)
                "failed ${e.javaClass.simpleName}: ${e.message}"
            }
            Log.i(TAG, result)
            pending.resultData = result
            pending.finish()
        }
    }

    private suspend fun handle(context: Context, intent: Intent): String {
        val core = context.container.core()
        val result = when (intent.action) {
            ACTION_SEED -> seed(core, intent)
            ACTION_NAMES_ON_LOCK -> {
                core.settings.setShowNamesOnLock(intent.getBooleanExtra("show", false))
                "names_on_lock ${core.settings.showNamesOnLock()}"
            }
            ACTION_START -> start(core, intent)
            ACTION_ARCHIVE_PERSON -> {
                val name = requireNotNull(intent.getStringExtra("name")) { "no name" }
                val person = core.people.list().firstOrNull { it.name == name } ?: error("no active person $name")
                val result = core.people.archive(person.id)
                check(result == PersonChange.Saved) { "person $name not archived: $result" }
                "archived $name"
            }
            ACTION_SETTINGS -> settings(core, intent)
            else -> return "unknown action ${intent.action}"
        }
        context.container.afterWrite()
        return result
    }

    private suspend fun seed(core: DidITakeCore, intent: Intent): String {
        val names = intent.getStringExtra("names").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(names.isNotEmpty()) { "no names" }
        val start = LocalTime.parse(intent.getStringExtra("start") ?: "08:00")
        val rule = intent.getStringExtra("weekly")?.let { ScheduleRule.Weekly(DayOfWeek.valueOf(it)) } ?: ScheduleRule.Daily
        if (intent.hasExtra("max_per_day")) {
            saveMaxPerDay(core, intent.getIntExtra("max_per_day", ReminderSettings.DEFAULT_MAX_PER_DAY))
        }
        val people = core.people.ensureDefault()
        val personName = intent.getStringExtra("person")
        val person = if (personName == null) {
            people.first()
        } else {
            people.firstOrNull { it.name == personName } ?: core.people.add(personName, PersonColors.DEFAULT)
        }
        val ids = names.map { name ->
            val saved = core.saveIntake.create(IntakeDraft(personId = person.id, name = name, rule = rule))
            val id = (saved as? SaveIntakeResult.Saved)?.intakeId ?: error("item $name not saved: $saved")
            val reminder = core.reminders.saveRule(id, enabled = true, start = start)
            check(reminder is SaveReminderResult.Saved) { "reminder of $name not saved: $reminder" }
            id
        }
        return "seeded ${ids.joinToString(",")}"
    }

    private suspend fun start(core: DidITakeCore, intent: Intent): String {
        val name = requireNotNull(intent.getStringExtra("name")) { "no name" }
        val start = LocalTime.parse(requireNotNull(intent.getStringExtra("start")) { "no start" })
        val intake = core.db.intakeDao().listActive().firstOrNull { it.name == name } ?: error("no active item $name")
        val saved = core.reminders.saveRule(intake.id, enabled = true, start = start)
        check(saved is SaveReminderResult.Saved) { "reminder of $name not saved: $saved" }
        return "start $name $start"
    }

    private suspend fun settings(core: DidITakeCore, intent: Intent): String {
        require(intent.hasExtra("max_per_day") || intent.hasExtra("interval_minutes")) { "no max_per_day or interval_minutes" }
        var settings = core.reminders.settings()
        if (intent.hasExtra("max_per_day")) {
            settings = settings.copy(maxPerDay = intent.getIntExtra("max_per_day", ReminderSettings.DEFAULT_MAX_PER_DAY))
        }
        if (intent.hasExtra("interval_minutes")) {
            settings = settings.copy(interval = intent.getIntExtra("interval_minutes", 0).minutes)
        }
        val saved = core.reminders.saveSettings(settings)
        check(saved is SaveReminderSettingsResult.Saved) { "reminder settings not saved: $saved" }
        val now = core.reminders.settings()
        return "settings max_per_day ${now.maxPerDay} interval ${now.interval.inWholeMinutes}"
    }

    private suspend fun saveMaxPerDay(core: DidITakeCore, maxPerDay: Int) {
        val saved = core.reminders.saveSettings(core.reminders.settings().copy(maxPerDay = maxPerDay))
        check(saved is SaveReminderSettingsResult.Saved) { "reminder settings not saved: $saved" }
    }

    private companion object {
        const val TAG = "ReminderSeed"
        const val ACTION_SEED = "com.judangbang.pillbadeum.debug.SEED"
        const val ACTION_NAMES_ON_LOCK = "com.judangbang.pillbadeum.debug.NAMES_ON_LOCK"
        const val ACTION_START = "com.judangbang.pillbadeum.debug.START"
        const val ACTION_ARCHIVE_PERSON = "com.judangbang.pillbadeum.debug.ARCHIVE_PERSON"
        const val ACTION_SETTINGS = "com.judangbang.pillbadeum.debug.SETTINGS"
    }
}
