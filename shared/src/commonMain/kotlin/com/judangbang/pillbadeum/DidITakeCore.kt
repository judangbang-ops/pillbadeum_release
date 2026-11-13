package com.judangbang.pillbadeum

import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.UuidV7
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.nextDayBoundary
import com.judangbang.pillbadeum.restore.AcknowledgeRestore
import com.judangbang.pillbadeum.restore.CheckInstall
import com.judangbang.pillbadeum.restore.InstallMarker
import com.judangbang.pillbadeum.restore.InstallState
import com.judangbang.pillbadeum.status.JudgeCell
import com.judangbang.pillbadeum.status.LoadHistory
import com.judangbang.pillbadeum.status.LoadJudgeCell
import com.judangbang.pillbadeum.status.LoadPeople
import com.judangbang.pillbadeum.status.LoadSettings
import com.judangbang.pillbadeum.status.LoadStatusBoard
import com.judangbang.pillbadeum.usecase.ArchiveIntake
import com.judangbang.pillbadeum.usecase.DeleteArchivedIntake
import com.judangbang.pillbadeum.usecase.FlagClockMismatch
import com.judangbang.pillbadeum.usecase.ManagePeople
import com.judangbang.pillbadeum.usecase.ManageReminders
import com.judangbang.pillbadeum.usecase.ManageSettings
import com.judangbang.pillbadeum.usecase.PlanReminders
import com.judangbang.pillbadeum.usecase.RecordCommand
import com.judangbang.pillbadeum.usecase.RecordDose
import com.judangbang.pillbadeum.usecase.RecordInWindow
import com.judangbang.pillbadeum.usecase.RecordResult
import com.judangbang.pillbadeum.usecase.ResolveClockFlag
import com.judangbang.pillbadeum.usecase.SaveIntake
import com.judangbang.pillbadeum.usecase.TimeEditCommand
import com.judangbang.pillbadeum.usecase.VoidDose
import com.judangbang.pillbadeum.usecase.WindowCommand
import com.judangbang.pillbadeum.usecase.WindowWriteResult
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Manual wiring of one database and every use case (no DI framework). Platform code creates one
 * instance per process with [open] and only calls these members; all writes go through them.
 *
 * @param deviceZone device time zone at input time (stored as `zone_id`); windows never use it.
 * @property installState what [open] found: a restore is detected here, before any write
 *   (record-rules-supplement.md R-1, R-2). Platform code re-creates widgets and alarms on
 *   [InstallState.Restored] (R-4).
 */
class DidITakeCore private constructor(
    val db: DidITakeDatabase,
    val clock: Clock,
    val deviceId: String,
    val installState: InstallState,
    ids: UuidV7,
    private val deviceZone: () -> TimeZone,
) {
    val recordDose: RecordDose = RecordDose(db, clock, ids, deviceId)
    val voidDose: VoidDose = VoidDose(db, clock)
    val saveIntake: SaveIntake = SaveIntake(db, clock, ids, deviceId)
    val archiveIntake: ArchiveIntake = ArchiveIntake(db, clock)
    val people: ManagePeople = ManagePeople(db, clock, ids, deviceId)
    val settings: ManageSettings = ManageSettings(db, clock)
    val deleteArchivedIntake: DeleteArchivedIntake = DeleteArchivedIntake(db)

    /** Raises clock warnings (supplement C-1): call on clock, time zone and boot broadcasts. */
    val flagClockMismatch: FlagClockMismatch = FlagClockMismatch(db, clock)

    /** [그대로 두기] / [기록 취소] on a clock warning (supplement C-3). */
    val resolveClockFlag: ResolveClockFlag = ResolveClockFlag(db, clock)

    /** App-only writes to any started window and time edits (SCR-007, supplement P-1~P-6). */
    val recordInWindow: RecordInWindow = RecordInWindow(db, clock, ids, deviceId)
    val loadStatusBoard: LoadStatusBoard = LoadStatusBoard(db, clock)
    /** [확인했어요] on the restore notice; dose reminders start again from the tap (R-3, TASK-040). */
    val acknowledgeRestore: AcknowledgeRestore = AcknowledgeRestore(db, clock)

    /** One month of history (SCR-008, supplement P-8). */
    val loadHistory: LoadHistory = LoadHistory(db, clock)

    /** One cell of the judgment screen (SCR-007, TASK-018). */
    val loadJudgeCell: LoadJudgeCell = LoadJudgeCell(db, clock)

    /** What the settings screen shows (SCR-009, TASK-020). */
    val loadSettings: LoadSettings = LoadSettings(db)

    /** What the people screen shows (SCR-010, TASK-021). */
    val loadPeople: LoadPeople = LoadPeople(db)

    /** Reminder plan: next alarm after a change, overdue reminders when the alarm rings (TASK-011, N-8). */
    val planReminders: PlanReminders = PlanReminders(db, clock)

    /** Reminder settings of items, the common settings and [30분 뒤] (TASK-011, N-1~N-7). */
    val reminders: ManageReminders = ManageReminders(db, clock, deviceId)

    /**
     * Records [kind] (TAKEN unless a notification's [오늘 건너뜀] says SKIPPED) for [intakeId] from [source],
     * checked against [renderedPeriodKey].
     */
    suspend fun record(
        intakeId: String,
        source: DoseSource,
        renderedPeriodKey: String?,
        kind: DoseKind = DoseKind.TAKEN,
    ): RecordResult =
        recordDose(
            RecordCommand(
                intakeId = intakeId,
                kind = kind,
                source = source,
                renderedPeriodKey = renderedPeriodKey,
                deviceZoneId = deviceZone().id,
            ),
        )

    /**
     * Writes [kind] into [cell] from SCR-007 ([RecordInWindow.record]): [takenAt] is the picked time, null for now (current
     * window only, P-2); [replacing] is the cell's judgment being turned into [kind] (a skip into a dose). The cell's read
     * time is the rendered time, so a settings change after it makes the write stale (P-5).
     */
    suspend fun recordIn(cell: JudgeCell, kind: DoseKind, takenAt: Instant? = null, replacing: String? = null): WindowWriteResult =
        recordInWindow.record(
            WindowCommand(
                intakeId = cell.intakeId,
                scheduleVersionId = cell.scheduleVersionId,
                periodKey = cell.window.periodKey,
                kind = kind,
                takenAt = takenAt,
                deviceZoneId = deviceZone().id,
                renderedAt = cell.now,
                replacing = replacing,
            ),
        )

    /** Moves the dose [doseId] of [cell] to [newTakenAt] (SCR-007 `[먹은 시각 고치기]`, supplement P-6). */
    suspend fun editTime(cell: JudgeCell, doseId: String, newTakenAt: Instant): WindowWriteResult =
        recordInWindow.editTime(TimeEditCommand(doseId, newTakenAt, deviceZone().id, renderedAt = cell.now))

    /** Day start and base zone from the setting table (defaults: 04:00, Asia/Seoul). */
    suspend fun windowSettings(): WindowSettings = db.settingDao().loadWindowSettings()

    /** Next day boundary after now in the base zone (day-boundary widget refresh). */
    suspend fun nextDayBoundary(): Instant = nextDayBoundary(clock.now(), windowSettings())

    companion object {
        /**
         * Checks for a restore with [installMarker] (and so loads or creates the device id), then
         * makes sure the default person "나" exists.
         */
        suspend fun open(
            db: DidITakeDatabase,
            installMarker: InstallMarker,
            clock: Clock = Clock.System,
            deviceZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
        ): DidITakeCore {
            val ids = UuidV7(clock)
            val install = CheckInstall(db, clock, ids, installMarker)()
            return DidITakeCore(db, clock, install.deviceId, install.state, ids, deviceZone)
                .also { it.people.ensureDefault() }
        }
    }
}
