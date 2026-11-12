package com.judangbang.pillbadeum

import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.loadOrCreateDeviceId
import com.judangbang.pillbadeum.data.loadWindowSettings
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.UuidV7
import com.judangbang.pillbadeum.domain.WindowSettings
import com.judangbang.pillbadeum.domain.nextDayBoundary
import com.judangbang.pillbadeum.status.LoadHistory
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
import com.judangbang.pillbadeum.usecase.VoidDose
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * Manual wiring of one database and every use case (no DI framework). Platform code creates one
 * instance per process with [open] and only calls these members; all writes go through them.
 *
 * @param deviceZone device time zone at input time (stored as `zone_id`); windows never use it.
 */
class DidITakeCore private constructor(
    val db: DidITakeDatabase,
    val clock: Clock,
    val deviceId: String,
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

    /** One month of history (SCR-008, supplement P-8). */
    val loadHistory: LoadHistory = LoadHistory(db, clock)

    /** Reminder plan: next alarm after a change, overdue reminders when the alarm rings (TASK-011, N-8). */
    val planReminders: PlanReminders = PlanReminders(db, clock)

    /** Reminder settings of items, the common settings and [30분 뒤] (TASK-011, N-1~N-7). */
    val reminders: ManageReminders = ManageReminders(db, clock, deviceId)

    /** Records TAKEN for [intakeId] from [source], checked against [renderedPeriodKey]. */
    suspend fun record(intakeId: String, source: DoseSource, renderedPeriodKey: String?): RecordResult =
        recordDose(
            RecordCommand(
                intakeId = intakeId,
                kind = DoseKind.TAKEN,
                source = source,
                renderedPeriodKey = renderedPeriodKey,
                deviceZoneId = deviceZone().id,
            ),
        )

    /** Day start and base zone from the setting table (defaults: 04:00, Asia/Seoul). */
    suspend fun windowSettings(): WindowSettings = db.settingDao().loadWindowSettings()

    /** Next day boundary after now in the base zone (day-boundary widget refresh). */
    suspend fun nextDayBoundary(): Instant = nextDayBoundary(clock.now(), windowSettings())

    companion object {
        /** Loads or creates the device id and the default person "나". */
        suspend fun open(
            db: DidITakeDatabase,
            clock: Clock = Clock.System,
            deviceZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
        ): DidITakeCore {
            val ids = UuidV7(clock)
            val deviceId = db.settingDao().loadOrCreateDeviceId(ids)
            return DidITakeCore(db, clock, deviceId, ids, deviceZone).also { it.people.ensureDefault() }
        }
    }
}
