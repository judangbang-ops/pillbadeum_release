package com.judangbang.pillbadeum

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.judangbang.pillbadeum.boundary.DayBoundaryAlarm
import com.judangbang.pillbadeum.ui.App
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so the screen reloads (the widget may have recorded meanwhile). */
    private var resumeCount by mutableIntStateOf(0)

    /** Bumped when [homeIntent] reaches this activity again: back to the first screen. */
    private var homeRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = container
        container.appScope.launch {
            // A database that cannot be opened shows SCR-004#error instead of closing the app.
            try {
                DayBoundaryAlarm.scheduleNext(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "day boundary alarm not set", e)
            }
        }
        setContent {
            App(
                open = { container.core() },
                onDataChanged = { container.afterWrite() },
                refreshKey = resumeCount,
                homeRequests = homeRequests,
                backHandler = { enabled, onBack -> BackHandler(enabled, onBack) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_HOME, false)) homeRequests++
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }

    companion object {
        private const val TAG = "MainActivity"

        /** Set on [homeIntent]. */
        const val EXTRA_HOME: String = "com.judangbang.pillbadeum.extra.HOME"

        /**
         * Opens the app on its first screen, SCR-004, whatever screen it was left on (screens.md: the widget's header
         * and a reminder open SCR-004). A screen left open with unsaved input is closed without saving, as `[닫기]`.
         */
        fun homeIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
