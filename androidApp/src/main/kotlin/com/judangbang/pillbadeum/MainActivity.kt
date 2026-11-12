package com.judangbang.pillbadeum

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.judangbang.pillbadeum.boundary.DayBoundaryAlarm
import com.judangbang.pillbadeum.ui.App
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so the screen reloads (the widget may have recorded meanwhile). */
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = container
        container.appScope.launch { DayBoundaryAlarm.scheduleNext(applicationContext) }
        setContent {
            var core by remember { mutableStateOf<DidITakeCore?>(null) }
            LaunchedEffect(Unit) { core = container.core() }
            core?.let { App(core = it, onDataChanged = { container.afterWrite() }, refreshKey = resumeCount) }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}
