package com.judangbang.pillbadeum.usecase

import androidx.room3.TransactionScope
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.DoseRecordDao
import com.judangbang.pillbadeum.data.DoseRecordEntity
import com.judangbang.pillbadeum.data.ScheduleParams
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.data.loadWindowSettingsChangedAt
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.ScheduleSpan
import com.judangbang.pillbadeum.domain.UuidV7
import com.judangbang.pillbadeum.domain.Window
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.cellHolding
import com.judangbang.pillbadeum.domain.windowWithKey
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One cell of the judgment screen (SCR-007), app only (record-rules-supplement.md P-1). The widget and
 * notifications keep using [RecordCommand], which only reaches the current window.
 *
 * @property scheduleVersionId and [periodKey] name the cell. Both are needed: in the week a daily item
 *   becomes weekly, Monday's daily cell and that week's weekly cell share a period key (P-1, review B7).
 * @property takenAt TAKEN only: the time the user picked, or null for [지금 먹었어], which only the current
 *   window offers (P-2). Ignored for SKIPPED, whose time is set by P-3.
 * @property renderedAt when the screen read what it shows. A day-start or base-zone change after this
 *   makes the screen stale (P-5).
 * @property replacing the cell's active judgment the user is turning into [kind] (건너뜀 → 복용 and back),
 *   or null when the cell is empty.
 */
data class WindowCommand(
    val intakeId: String,
    val scheduleVersionId: String,
    val periodKey: String,
    val kind: DoseKind,
    val takenAt: Instant? = null,
    val deviceZoneId: String,
    val renderedAt: Instant,
    val replacing: String? = null,
)

/** Moves an active TAKEN judgment to another time (supplement P-6). Same fields as [WindowCommand]. */
data class TimeEditCommand(
    val doseId: String,
    val newTakenAt: Instant,
    val deviceZoneId: String,
    val renderedAt: Instant,
)

/**
 * Outcome of [RecordInWindow]. Kept apart from [RecordResult] because the widget and notification paths
 * match that type exhaustively and never see these cases.
 */
sealed interface WindowWriteResult {
    /** A new active judgment was stored; [replaced] is the row it voided in the same transaction. */
    data class Recorded(val record: DoseRecordEntity, val replaced: DoseRecordEntity? = null) : WindowWriteResult

    /** The target cell already has an active judgment ("이미 기록됨 (07:30)", REQ-031, RR-42). Nothing changed. */
    data class Already(val existing: DoseRecordEntity) : WindowWriteResult

    /** The edit asks for what is already stored. Nothing changed. */
    data object Unchanged : WindowWriteResult

    /** The cell ended before the last day-start or base-zone change (P-5, RR-44). Nothing changed. */
    data object Locked : WindowWriteResult

    /** The chosen time or cell is not allowed (P-1, P-2, P-4, REQ-032). Nothing changed. */
    data class InvalidTime(val reason: InvalidTimeReason) : WindowWriteResult

    /** What the screen showed no longer matches the database; it must reload. Nothing changed. */
    data object Stale : WindowWriteResult

    /** The intake or its person is missing or archived. Nothing changed. */
    data object Gone : WindowWriteResult

    /** Inconsistent state. Nothing changed. */
    data class Error(val message: String) : WindowWriteResult
}

enum class InvalidTimeReason {
    /** The cell's window has not started yet (P-1). */
    NOT_STARTED,

    /** The time is after now (P-2, REQ-032). */
    FUTURE,

    /** The time is outside the cell's window (P-2, I-1, RR-40). */
    OUTSIDE_WINDOW,

    /** No schedule version covers the time, e.g. before the item's first window (P-4). */
    BEFORE_SCHEDULE,
}

/**
 * App-only writes to any started window: record or skip a cell, turn a skip into a dose and back,
 * and move a dose to another time (record-rules-supplement.md I-1, I-2, P-1~P-6, TASK-005).
 *
 * Every call runs in ONE immediate write transaction, like [RecordDose]: checks, the void of the old row
 * and the insert of the new one either all land or none does (I-2). The clock is read after the writer
 * lock is held.
 *
 * Not here: re-planning the current window's reminder after a change (P-7, TASK-027), and resolving a
 * clock flag when its judgment is voided (C-3, TASK-006).
 */
class RecordInWindow(
    private val db: DidITakeDatabase,
    private val clock: Clock,
    private val ids: UuidV7,
    private val deviceId: String,
) {
    suspend fun record(command: WindowCommand): WindowWriteResult =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction { record(command, clock.now()) }
        }

    suspend fun editTime(command: TimeEditCommand): WindowWriteResult =
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction { editTime(command, clock.now()) }
        }

    private suspend fun TransactionScope<WindowWriteResult>.record(command: WindowCommand, now: Instant): WindowWriteResult {
        if (!isActiveIntake(command.intakeId)) return WindowWriteResult.Gone
        val context = loadContext(command.intakeId, command.renderedAt) ?: return WindowWriteResult.Stale
        val (spans, settings, changedAt) = context

        val span = spans.firstOrNull { it.versionId == command.scheduleVersionId } ?: return WindowWriteResult.Stale
        val window = windowWithKey(span.rule, command.periodKey, settings) ?: return WindowWriteResult.Stale
        if (!span.owns(window)) return WindowWriteResult.Stale
        if (window.start > now) return WindowWriteResult.InvalidTime(InvalidTimeReason.NOT_STARTED)
        if (isLocked(window, changedAt)) return WindowWriteResult.Locked

        val takenAt = when (command.kind) {
            // P-3: the current window keeps "now"; a past window stores its start, the screen hides it.
            DoseKind.SKIPPED -> if (now in window) now else window.start
            DoseKind.TAKEN -> when (val picked = command.takenAt) {
                // [지금 먹었어] exists only in the current window (P-2). The screen showed a window that
                // has ended since, so it is out of date.
                null -> if (now in window) now else return WindowWriteResult.Stale
                else -> pickedTimeProblem(picked, window, now)?.let { return it } ?: picked
            }
        }

        val dao = db.doseRecordDao()
        val actives = dao.activeIn(command.intakeId, window)
        val replaced = command.replacing?.let { id ->
            actives.firstOrNull { it.id == id } ?: return WindowWriteResult.Stale
        }
        if (replaced != null && replaced.kind == command.kind) return WindowWriteResult.Already(replaced)
        actives.firstOrNull { it.id != replaced?.id }?.let { return WindowWriteResult.Already(it) }

        if (replaced != null) voidOrRollBack(replaced, now, VoidReasons.CHANGED)
        val record = newRow(command.intakeId, span, window, command.kind, takenAt, command.deviceZoneId, now)
        insertOrRollBack(record)
        return WindowWriteResult.Recorded(record, replaced)
    }

    private suspend fun TransactionScope<WindowWriteResult>.editTime(command: TimeEditCommand, now: Instant): WindowWriteResult {
        val old = db.doseRecordDao().findById(command.doseId)
        if (old == null || !old.isActive) return WindowWriteResult.Stale
        if (!isActiveIntake(old.intakeId)) return WindowWriteResult.Gone
        // A skip has no time on screen (P-3); it is changed with [record] and `replacing` instead.
        if (old.kind != DoseKind.TAKEN) return WindowWriteResult.Error("only a dose has a time to edit (${old.id})")
        val context = loadContext(old.intakeId, command.renderedAt) ?: return WindowWriteResult.Stale
        val (spans, settings, changedAt) = context

        val source = spans.firstOrNull { it.versionId == old.scheduleVersionId }
            ?: return WindowWriteResult.Error("judgment ${old.id} points at a missing schedule version")
        val sourceWindow = windowWithKey(source.rule, old.periodKey, settings)
            ?: return WindowWriteResult.Error("judgment ${old.id} has a period key its version cannot produce")
        if (isLocked(sourceWindow, changedAt)) return WindowWriteResult.Locked

        val t = command.newTakenAt
        if (t > now) return WindowWriteResult.InvalidTime(InvalidTimeReason.FUTURE)
        val (target, window) = cellHolding(t, spans, preferred = source, settings)
            ?: return WindowWriteResult.InvalidTime(InvalidTimeReason.BEFORE_SCHEDULE)
        if (isLocked(window, changedAt)) return WindowWriteResult.Locked

        val sameCell = target.versionId == old.scheduleVersionId && window.periodKey == old.periodKey
        if (sameCell && t.toEpochMilliseconds() == old.takenAt) return WindowWriteResult.Unchanged

        // P-6, REQ-031: another active judgment in the new time's window blocks the edit.
        db.doseRecordDao().activeIn(old.intakeId, window)
            .firstOrNull { it.id != old.id }
            ?.let { return WindowWriteResult.Already(it) }

        voidOrRollBack(old, now, VoidReasons.TIME_EDIT)
        val record = newRow(old.intakeId, target, window, DoseKind.TAKEN, t, command.deviceZoneId, now)
        insertOrRollBack(record)
        return WindowWriteResult.Recorded(record, replaced = old)
    }

    private data class Context(val spans: List<ScheduleSpan>, val settings: WindowSettings, val changedAt: Instant?)

    /** Spans and settings, or null when the settings changed after the screen was drawn (P-5). */
    private suspend fun loadContext(intakeId: String, renderedAt: Instant): Context? {
        val settingDao = db.settingDao()
        val changedAt = settingDao.loadWindowSettingsChangedAt()
        if (changedAt != null && changedAt > renderedAt) return null
        val spans = db.scheduleVersionDao().listByIntake(intakeId)
            .filter { it.archivedAt == null }
            .map {
                ScheduleSpan(
                    versionId = it.id,
                    rule = ScheduleParams.toRule(it),
                    from = Instant.fromEpochMilliseconds(it.effectiveFrom),
                    to = it.effectiveTo?.let(Instant::fromEpochMilliseconds),
                )
            }
        return Context(spans, settingDao.loadWindowSettings(), changedAt)
    }

    private suspend fun isActiveIntake(intakeId: String): Boolean {
        val intake = db.intakeDao().findById(intakeId) ?: return false
        if (intake.archivedAt != null) return false
        val person = db.personDao().findById(intake.personId) ?: return false
        return person.archivedAt == null
    }

    /** P-5: a window that ended before the last settings change cannot be written. */
    private fun isLocked(window: Window, changedAt: Instant?): Boolean = changedAt != null && window.end <= changedAt

    private fun pickedTimeProblem(picked: Instant, window: Window, now: Instant): WindowWriteResult? = when {
        picked > now -> WindowWriteResult.InvalidTime(InvalidTimeReason.FUTURE)
        picked !in window -> WindowWriteResult.InvalidTime(InvalidTimeReason.OUTSIDE_WINDOW)
        else -> null
    }

    private suspend fun TransactionScope<WindowWriteResult>.voidOrRollBack(row: DoseRecordEntity, now: Instant, reason: String) {
        if (db.doseRecordDao().voidActive(row.id, now.toEpochMilliseconds(), reason) != 1) {
            rollback(WindowWriteResult.Error("judgment ${row.id} could not be voided"))
        }
    }

    private suspend fun TransactionScope<WindowWriteResult>.insertOrRollBack(row: DoseRecordEntity) {
        if (db.doseRecordDao().insertOrIgnore(row) == -1L) {
            rollback(WindowWriteResult.Error("judgment for ${row.intakeId} ${row.periodKey} was ignored"))
        }
    }

    private fun newRow(
        intakeId: String,
        span: ScheduleSpan,
        window: Window,
        kind: DoseKind,
        takenAt: Instant,
        zoneId: String,
        now: Instant,
    ): DoseRecordEntity {
        val nowMillis = now.toEpochMilliseconds()
        return DoseRecordEntity(
            id = ids.next(),
            intakeId = intakeId,
            scheduleVersionId = span.versionId,
            periodKey = window.periodKey,
            kind = kind,
            takenAt = takenAt.toEpochMilliseconds(),
            zoneId = zoneId,
            recordedAt = nowMillis,
            source = DoseSource.APP,
            active = DoseRecordEntity.ACTIVE,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            deviceId = deviceId,
        )
    }
}

/** Every active judgment the current-window lookup would match for [window] (record-rules.md 5). */
internal suspend fun DoseRecordDao.activeIn(intakeId: String, window: Window): List<DoseRecordEntity> =
    findActiveJudgments(
        intakeId = intakeId,
        periodKey = window.periodKey,
        windowStart = window.start.toEpochMilliseconds(),
        windowEnd = window.end.toEpochMilliseconds(),
    )
