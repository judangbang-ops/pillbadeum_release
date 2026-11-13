package com.judangbang.pillbadeum.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.judangbang.pillbadeum.container
import com.judangbang.pillbadeum.restore.InstallState
import com.judangbang.pillbadeum.status.WidgetChoice
import com.judangbang.pillbadeum.status.showsPeople
import java.io.File

/**
 * Each widget's [WidgetChoice] (TERM-026, TASK-012), kept in the widget's own Glance state: outside the database and
 * the backup (05 ERD section 5.2). Glance deletes the state when the widget is removed.
 */
internal object WidgetChoices {
    /** Glance state key: [WidgetChoice.encode] of the widget's choice; absent while it has none. */
    val KEY = stringPreferencesKey("person_choice")

    /**
     * Written once [prepare] has run for this install. In `noBackupFilesDir`, so it is not backed up and is deleted
     * with the app's data.
     */
    private const val READY_FILE = "widget_choices_ready"

    private const val TAG = "WidgetChoices"

    suspend fun read(context: Context, id: GlanceId): WidgetChoice? =
        WidgetChoice.decode(getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[KEY])

    suspend fun write(context: Context, id: GlanceId, choice: WidgetChoice) {
        updateAppWidgetState(context, id) { it[KEY] = WidgetChoice.encode(choice) }
    }

    /**
     * Once per install, before any widget reads its choice (AppContainer opens the core with it): every placed widget
     * without a choice gets [WidgetChoice.Everyone]. Those are the widgets placed before the update that brought the
     * choice, so they keep showing everyone instead of all asking to be set up again (supplement R-3, RR-74). Widgets
     * placed later get a choice from SCR-002, or from [choosePinned] when the intro placed them, so afterwards only a
     * restore, or a widget placed from the intro while two people are active, is left without one.
     *
     * After a restore ([state] is [InstallState.Restored]) nothing is filled in: the restored widgets cannot know
     * whose they were, and from two people on they ask to be set up again (REQ-079). The file is still written, so
     * a later start does not fill them in either.
     */
    suspend fun prepare(context: Context, state: InstallState) {
        val ready = File(context.noBackupFilesDir, READY_FILE)
        if (ready.exists()) return
        var filled = 0
        if (state !is InstallState.Restored) {
            val glance = GlanceAppWidgetManager(context)
            for (appWidgetId in placedIds(context)) {
                val id = runCatching { glance.getGlanceIdBy(appWidgetId) }.getOrNull() ?: continue
                if (read(context, id) == null) {
                    write(context, id, WidgetChoice.Everyone)
                    filled++
                }
            }
        }
        ready.writeText("1\n")
        Log.i(TAG, "widget choices ready: $filled filled with everyone (install state ${state::class.simpleName})")
    }

    /**
     * A widget placed from SCR-005's `[위젯 추가]` (TASK-022). A launcher shows no configuration activity for a widget
     * placed on request (`AppWidgetManager.requestPinAppWidget`), so it gets its choice here as SCR-002 gives it with one
     * person: 전체 (REQ-018, SPEC-005). From two people on it is left without one and asks to be set up
     * (SCR-001#reconfigure, REQ-079); a widget that has a choice already keeps it. Returns the choice the widget has now,
     * null for that case or for an id that is not one of this app's widgets.
     */
    suspend fun choosePinned(context: Context, appWidgetId: Int): WidgetChoice? {
        val id = WidgetPickActivity.glanceIdOf(context, appWidgetId) ?: return null
        read(context, id)?.let { return it }
        if (showsPeople(context.container.core().people.list().size)) return null
        write(context, id, WidgetChoice.Everyone)
        return WidgetChoice.Everyone
    }

    /** The ids of this app's placed widgets, as the platform has them. */
    fun placedIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, DidITakeWidgetReceiver::class.java))
}
