package com.zmanlab.diditake.usecase

import com.zmanlab.diditake.data.DidITakeDatabase
import com.zmanlab.diditake.data.DoseRecordDao
import com.zmanlab.diditake.data.DoseRecordEntity
import com.zmanlab.diditake.data.IntakeEntity
import com.zmanlab.diditake.data.ScheduleParams
import com.zmanlab.diditake.data.ScheduleVersionEntity
import com.zmanlab.diditake.data.loadWindowSettings
import com.zmanlab.diditake.domain.UuidV7
import com.zmanlab.diditake.domain.Window
import com.zmanlab.diditake.domain.windowOf
import kotlin.time.Instant

/** Result of the first checks of record-rules.md section 6, shared by every judgment writer. */
internal sealed interface WindowLookup {
    data class Open(
        val intake: IntakeEntity,
        val schedule: ScheduleVersionEntity,
        val window: Window,
    ) : WindowLookup

    data class Rejected(val result: RecordResult) : WindowLookup
}

/**
 * Loads the intake, its schedule version in effect at [now] and the current window, then checks
 * the rendered period key. Must run inside the caller's write transaction.
 */
internal suspend fun DidITakeDatabase.lookUpWindow(
    intakeId: String,
    renderedPeriodKey: String?,
    now: Instant,
): WindowLookup {
    val intake = intakeDao().findById(intakeId)
    if (intake == null || intake.archivedAt != null) return WindowLookup.Rejected(RecordResult.Gone)

    val schedule = scheduleVersionDao().findEffective(intake.id, now.toEpochMilliseconds())
        ?: return WindowLookup.Rejected(RecordResult.Error("no schedule version in effect for intake ${intake.id}"))

    val window = windowOf(ScheduleParams.toRule(schedule), now, settingDao().loadWindowSettings())
    if (renderedPeriodKey != null && renderedPeriodKey != window.periodKey) {
        return WindowLookup.Rejected(RecordResult.Stale(window.periodKey))
    }
    return WindowLookup.Open(intake, schedule, window)
}

/** Active judgment of [window] for [intakeId] (record-rules.md section 5). */
internal suspend fun DoseRecordDao.findActiveIn(intakeId: String, window: Window): DoseRecordEntity? =
    findActiveJudgment(
        intakeId = intakeId,
        periodKey = window.periodKey,
        windowStart = window.start.toEpochMilliseconds(),
        windowEnd = window.end.toEpochMilliseconds(),
    )

/** A new active judgment row filling [WindowLookup.Open.window]. */
internal fun WindowLookup.Open.newJudgment(
    command: RecordCommand,
    now: Instant,
    ids: UuidV7,
    deviceId: String,
): DoseRecordEntity {
    val nowMillis = now.toEpochMilliseconds()
    return DoseRecordEntity(
        id = ids.next(),
        intakeId = intake.id,
        scheduleVersionId = schedule.id,
        periodKey = window.periodKey,
        kind = command.kind,
        takenAt = nowMillis,
        zoneId = command.deviceZoneId,
        recordedAt = nowMillis,
        source = command.source,
        active = DoseRecordEntity.ACTIVE,
        createdAt = nowMillis,
        updatedAt = nowMillis,
        deviceId = deviceId,
    )
}
