package com.zmanlab.diditake

import com.zmanlab.diditake.data.DidITakeDatabase
import com.zmanlab.diditake.data.loadOrCreateDeviceId
import com.zmanlab.diditake.data.loadWindowSettings
import com.zmanlab.diditake.domain.DoseKind
import com.zmanlab.diditake.domain.DoseSource
import com.zmanlab.diditake.domain.UuidV7
import com.zmanlab.diditake.domain.WindowSettings
import com.zmanlab.diditake.domain.nextDayBoundary
import com.zmanlab.diditake.status.LoadStatusBoard
import com.zmanlab.diditake.usecase.ArchiveIntake
import com.zmanlab.diditake.usecase.ManagePeople
import com.zmanlab.diditake.usecase.RecordCommand
import com.zmanlab.diditake.usecase.RecordDose
import com.zmanlab.diditake.usecase.RecordResult
import com.zmanlab.diditake.usecase.SaveIntake
import com.zmanlab.diditake.usecase.VoidDose
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
    val loadStatusBoard: LoadStatusBoard = LoadStatusBoard(db, clock)

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
