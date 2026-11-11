package com.zmanlab.diditake.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text

/** Home screen widget, static stub for now. */
class DidITakeWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // TODO: tap-to-record per docs/03-sequence/sequences.md section 1: render each intake with
            //  its period key, ActionCallback -> RecordDose(intakeId, renderedPeriodKey) -> updateAll.
            Text(text = "DidITake")
        }
    }
}

class DidITakeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DidITakeWidget()
}
