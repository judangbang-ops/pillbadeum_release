package com.zmanlab.diditake.probe

import androidx.room3.useWriterConnection
import com.zmanlab.diditake.data.DoseRecordEntity
import com.zmanlab.diditake.domain.DoseKind
import com.zmanlab.diditake.domain.DoseSource
import com.zmanlab.diditake.domain.ScheduleRule
import com.zmanlab.diditake.status.LoadStatusBoard
import com.zmanlab.diditake.status.WidgetModel
import com.zmanlab.diditake.testing.DbFixture
import com.zmanlab.diditake.testing.SEOUL
import com.zmanlab.diditake.testing.TEST_DEVICE_ID
import com.zmanlab.diditake.testing.seoul
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek

/**
 * Scratch probes (review only, not part of the product). Each block prints what the code at
 * 7b8d9df does today; nothing is asserted, the output is read by the reviewer.
 */
class BehaviourProbeTest {
    private val out = StringBuilder()

    private suspend fun show(label: String, fx: DbFixture) {
        val model = WidgetModel.from(LoadStatusBoard(fx.db, fx.clock)())
        out.appendLine("  [$label] header=${model.dateLabel} ${model.countLabel}")
        model.buttons.forEach { b ->
            out.appendLine("      button done=${b.done} key=${b.periodKey} subtitle='${b.subtitle}' talkback='${b.description}'")
        }
        if (model.buttons.isEmpty()) out.appendLine("      (no buttons)")
    }

    @Test
    fun probes() = runBlocking {
        // A. A skip is shown the same way as a taken dose.
        DbFixture(start = seoul("2026-11-10T09:10")).use { fx ->
            out.appendLine("A. skip on 11/10 09:10 (daily item), then the next morning")
            val intake = fx.newIntake()
            out.appendLine("  skip result=${fx.record(intake, null, DoseSource.APP, DoseKind.SKIPPED)::class.simpleName}")
            show("11/10 09:11, same window", fx.also { it.at("2026-11-10T09:11") })
            show("11/11 08:00, next window", fx.also { it.at("2026-11-11T08:00") })
        }

        // B. Hypothetical naive implementation of "skip a past window": the row keeps the past
        //    period key but taken_at = now. The judgment query then also matches today's window.
        DbFixture(start = seoul("2026-11-12T09:00")).use { fx ->
            out.appendLine("B. hypothetical row: period_key=2026-11-11 (yesterday), taken_at=now (11/12 09:00), SKIPPED")
            val intake = fx.newIntake()
            val now = fx.clock.now().toEpochMilliseconds()
            val schedule = checkNotNull(fx.db.scheduleVersionDao().findEffective(intake, now)).id
            fx.db.doseRecordDao().insertOrIgnore(
                DoseRecordEntity(
                    id = fx.ids.next(), intakeId = intake, scheduleVersionId = schedule, periodKey = "2026-11-11",
                    kind = DoseKind.SKIPPED, takenAt = now, zoneId = SEOUL.id, recordedAt = now, source = DoseSource.APP,
                    createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID,
                ),
            )
            show("11/12 09:00, today's window", fx)
            out.appendLine("  widget tap today -> ${fx.record(intake, "2026-11-12")::class.simpleName}")
        }

        // C. Archived person: the board hides the item, RecordDose still records.
        DbFixture(start = seoul("2026-11-11T08:00")).use { fx ->
            out.appendLine("C. person archived after the widget was drawn")
            val intake = fx.newIntake()
            show("before archive", fx)
            fx.db.useWriterConnection { c ->
                c.usePrepared("UPDATE person SET archived_at = ?") { st -> st.bindLong(1, fx.clock.now().toEpochMilliseconds()); st.step() }
            }
            show("after archive", fx)
            out.appendLine("  tap on the stale button -> ${fx.record(intake, "2026-11-11")::class.simpleName} (screens.md SCR-001#gone expects Gone)")
            out.appendLine("  active rows now=${fx.activeRows(intake).size}")
        }

        // D. Clock set ahead, record, clock set back, then the real day arrives.
        DbFixture(start = seoul("2026-11-11T08:00")).use { fx ->
            out.appendLine("D. clock wrongly at Fri 11/13 08:00, widget tap, clock back to Wed 11/11 08:05, then real Fri 11/13 09:00")
            val intake = fx.newIntake()
            fx.at("2026-11-13T08:00")
            out.appendLine("  tap with wrong clock -> ${fx.record(intake, "2026-11-13")::class.simpleName}")
            show("clock back: Wed 11/11 08:05", fx.also { it.at("2026-11-11T08:05") })
            show("real Fri 11/13 09:00", fx.also { it.at("2026-11-13T09:00") })
        }

        // E. Weekly item (anchor Monday) taken six days late, anchor day kept (what KEEP-only means).
        DbFixture(start = seoul("2026-11-15T22:00")).use { fx ->
            out.appendLine("E. weekly item (anchor Mon), dose on Sun 11/15 22:00, then Mon 11/16 08:00")
            val intake = fx.newIntake(rule = ScheduleRule.Weekly(DayOfWeek.MONDAY))
            out.appendLine("  tap on Sun 11/15 22:00 -> ${fx.record(intake, null)::class.simpleName}")
            show("Sun 11/15 22:01", fx.also { it.at("2026-11-15T22:01") })
            show("Mon 11/16 08:00, 10 hours later", fx.also { it.at("2026-11-16T08:00") })
        }

        val dir = File(checkNotNull(System.getenv("PROBE_DIR")) { "PROBE_DIR not set" }).apply { mkdirs() }
        File(dir, "behaviour-probe-result.txt").writeText(out.toString())
        println(out)
    }
}
