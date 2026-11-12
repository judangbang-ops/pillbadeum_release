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
import com.judangbang.pillbadeum.restore.InstallState
import com.judangbang.pillbadeum.status.WidgetChoice
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
     * placed later always get a choice from SCR-002, so afterwards only a restore leaves a widget without one.
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

    /** The ids of this app's placed widgets, as the platform has them. */
    fun placedIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, DidITakeWidgetReceiver::class.java))
}
