package com.judangbang.pillbadeum.usecase

import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.DoseRecordDao
import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.data.IntakeEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.ScheduleVersionEntity
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.data.loadWindowSettingsChangedAt
import com.judangbang.pillbadeum.domain.UuidV7
import com.judangbang.pillbadeum.domain.Window
import com.judangbang.pillbadeum.domain.windowOf
import com.judangbang.pillbadeum.domain.windowWithKey
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
    // An archived person's intakes are gone from the widget too (REQ-005, review B5).
    val person = personDao().findById(intake.personId)
    if (person == null || person.archivedAt != null) return WindowLookup.Rejected(RecordResult.Gone)

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

/**
 * P-5: [record]'s own cell (its version's window with its period key, under today's settings) ended before
 * the last day-start or base-zone change, so it cannot be changed. A change time after [now] counts as now
 * (the clock was ahead when it was saved). Inside the caller's transaction.
 */
internal suspend fun DidITakeDatabase.isLockedJudgment(record: DoseRecordEntity, now: Instant): Boolean {
    val changedAt = settingDao().loadWindowSettingsChangedAt() ?: return false
    val version = scheduleVersionDao().findById(record.scheduleVersionId) ?: return false
    val window = windowWithKey(ScheduleParams.toRule(version), record.periodKey, settingDao().loadWindowSettings())
        ?: return false
    return window.end <= minOf(changedAt, now)
}
