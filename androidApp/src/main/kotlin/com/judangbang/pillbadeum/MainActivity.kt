package com.judangbang.pillbadeum

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.judangbang.pillbadeum.boundary.DayBoundaryAlarm
import com.judangbang.pillbadeum.export.AndroidCardExporter
import com.judangbang.pillbadeum.ui.App
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so the screen reloads (the widget may have recorded meanwhile). */
    private var resumeCount by mutableIntStateOf(0)

    /** Bumped when [homeIntent] reaches this activity again: back to the first screen. */
    private var homeRequests by mutableIntStateOf(0)

    /**
     * Who asked last for the notification permission (SCR-006 `[알림 받기]`, REQ-049, TASK-023). An activity recreated while
     * the system window was open gets the answer with nobody waiting; its screen reads the phone again on resuming anyway.
     */
    private var notificationsAnswered: (() -> Unit)? = null

    private val notificationsRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val answered = notificationsAnswered
        notificationsAnswered = null
        answered?.invoke()
    }

    private var chooserClosed: CompletableDeferred<Unit>? = null
    private val cardShareRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        chooserClosed?.complete(Unit)
    }

    private suspend fun shareCard(intent: Intent, onLaunched: () -> Unit) {
        val closed = CompletableDeferred<Unit>()
        chooserClosed = closed
        try {
            cardShareRequest.launch(intent)
            onLaunched()
            closed.await()
        } finally {
            chooserClosed = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = container
        val cardExporter = AndroidCardExporter(container.cardExports, ::shareCard)
        container.appScope.launch {
            // A failed cleanup is retried before creating an export. Never include cache paths in logs.
            try {
                container.cardExports.clearOnFirstScreen()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The main screen still opens when the cache is unavailable.
            }
        }
        container.appScope.launch {
            // A database that cannot be opened shows SCR-004#error instead of closing the app.
            try {
                DayBoundaryAlarm.scheduleNext(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "day boundary alarm not set: ${e.javaClass.simpleName}")
            }
        }
        val phone = AndroidPhone(this) { answered ->
            notificationsAnswered = answered
            notificationsRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            App(
                open = { container.core() },
                // Widgets (and reminders, R3) follow every saved write even when this activity is recreated meanwhile
                // (rotation, font size, dark mode), which cancels the screen's scope after the write was saved.
                onDataChanged = { withContext(NonCancellable) { container.afterWrite() } },
                phone = phone,
                refreshKey = resumeCount,
                homeRequests = homeRequests,
                backHandler = { enabled, onBack -> BackHandler(enabled, onBack) },
                protectCardRecents = { visible -> ProtectCardRecents(visible) },
                keepCardShowAwake = { KeepCardShowAwake() },
                cardExporter = cardExporter,
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

/** SCR-011~015 (R 16): keep screenshots available while hiding the recent-apps preview. */
@Composable
internal fun ComponentActivity.ProtectCardRecents(visible: Boolean) {
    if (visible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        DisposableEffect(this) {
            setRecentsScreenshotEnabled(false)
            onDispose { setRecentsScreenshotEnabled(true) }
        }
    }
}

/** SCR-013: the card requests screen-on only while its activity is in the foreground. */
@Composable
internal fun ComponentActivity.KeepCardShowAwake() {
    DisposableEffect(this) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> window.addFlags(FLAG_KEEP_SCREEN_ON)
                Lifecycle.Event.ON_PAUSE -> window.clearFlags(FLAG_KEEP_SCREEN_ON)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            window.clearFlags(FLAG_KEEP_SCREEN_ON)
        }
    }
}
