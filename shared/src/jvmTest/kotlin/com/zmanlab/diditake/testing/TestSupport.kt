package com.zmanlab.diditake.testing

import com.zmanlab.diditake.data.DidITakeDatabase
import com.zmanlab.diditake.data.DoseRecordEntity
import com.zmanlab.diditake.data.IntakeEntity
import com.zmanlab.diditake.data.PersonEntity
import com.zmanlab.diditake.data.ScheduleParams
import com.zmanlab.diditake.data.ScheduleVersionEntity
import com.zmanlab.diditake.data.buildDidITakeDatabase
import com.zmanlab.diditake.data.inMemoryDidITakeDatabaseBuilder
import com.zmanlab.diditake.domain.DoseKind
import com.zmanlab.diditake.domain.DoseSource
import com.zmanlab.diditake.domain.LatePolicy
import com.zmanlab.diditake.domain.ScheduleRule
import com.zmanlab.diditake.domain.UuidV7
import com.zmanlab.diditake.usecase.ChangeJudgment
import com.zmanlab.diditake.usecase.RecordCommand
import com.zmanlab.diditake.usecase.RecordDose
import com.zmanlab.diditake.usecase.RecordResult
import com.zmanlab.diditake.usecase.VoidDose
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

val SEOUL: TimeZone = TimeZone.of("Asia/Seoul")

/** Wall-clock time in Asia/Seoul, e.g. `seoul("2026-11-11T08:02")`. */
fun seoul(localDateTime: String): Instant = LocalDateTime.parse(localDateTime).toInstant(SEOUL)

/** Clock the test moves by hand. */
class TestClock(@Volatile var now: Instant) : Clock {
    override fun now(): Instant = now
}

/** Random source that always returns zero bits: with a fixed clock every UUID is identical. */
object ZeroRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}

const val TEST_DEVICE_ID: String = "test-device"

/** In-memory database plus the use cases wired the way the app wires them (no DI framework). */
class DbFixture(start: Instant = seoul("2026-11-11T08:00"), ids: UuidV7? = null) : AutoCloseable {
    val clock = TestClock(start)
    val ids: UuidV7 = ids ?: UuidV7(clock, Random(seed = 7))
    val db: DidITakeDatabase = buildDidITakeDatabase(inMemoryDidITakeDatabaseBuilder(), Dispatchers.IO)

    val recordDose = RecordDose(db, clock, this.ids, TEST_DEVICE_ID)
    val voidDose = VoidDose(db, clock)
    val changeJudgment = ChangeJudgment(db, clock, this.ids, TEST_DEVICE_ID)

    private val setupIds = UuidV7(clock, Random(seed = 99))

    fun at(localDateTime: String) {
        clock.now = seoul(localDateTime)
    }

    /** Person + intake + first schedule version effective from [effectiveFrom]. Returns the intake id. */
    suspend fun newIntake(
        rule: ScheduleRule = ScheduleRule.Daily,
        effectiveFrom: Instant = seoul("2026-01-01T00:00"),
    ): String {
        val nowMillis = clock.now().toEpochMilliseconds()
        val personId = setupIds.next()
        db.personDao().insert(
            PersonEntity(
                id = personId, name = "Test person", color = "#4E79A7",
                createdAt = nowMillis, updatedAt = nowMillis, deviceId = TEST_DEVICE_ID,
            ),
        )
        val intakeId = setupIds.next()
        db.intakeDao().insert(
            IntakeEntity(
                id = intakeId, personId = personId, name = "Morning dose", sortOrder = 0,
                createdAt = nowMillis, updatedAt = nowMillis, deviceId = TEST_DEVICE_ID,
            ),
        )
        insertScheduleVersion(intakeId, rule, effectiveFrom)
        return intakeId
    }

    /** Ends the current version at [at] and starts a new one with [rule] (data-model.md section 2). */
    suspend fun changeSchedule(intakeId: String, rule: ScheduleRule, at: Instant) {
        val dao = db.scheduleVersionDao()
        val current = checkNotNull(dao.findEffective(intakeId, at.toEpochMilliseconds() - 1))
        check(dao.close(current.id, at.toEpochMilliseconds(), clock.now().toEpochMilliseconds()) == 1)
        insertScheduleVersion(intakeId, rule, at)
    }

    private suspend fun insertScheduleVersion(intakeId: String, rule: ScheduleRule, effectiveFrom: Instant) {
        val nowMillis = clock.now().toEpochMilliseconds()
        db.scheduleVersionDao().insert(
            ScheduleVersionEntity(
                id = setupIds.next(),
                intakeId = intakeId,
                type = ScheduleParams.typeOf(rule),
                paramsJson = ScheduleParams.encode(rule),
                latePolicy = LatePolicy.ASK,
                effectiveFrom = effectiveFrom.toEpochMilliseconds(),
                createdAt = nowMillis,
                updatedAt = nowMillis,
                deviceId = TEST_DEVICE_ID,
            ),
        )
    }

    suspend fun record(
        intakeId: String,
        renderedKey: String?,
        source: DoseSource = DoseSource.WIDGET,
        kind: DoseKind = DoseKind.TAKEN,
        deviceZoneId: String = SEOUL.id,
    ): RecordResult = recordDose(RecordCommand(intakeId, kind, source, renderedKey, deviceZoneId))

    suspend fun history(intakeId: String): List<DoseRecordEntity> = db.doseRecordDao().listByIntake(intakeId)

    suspend fun activeRows(intakeId: String): List<DoseRecordEntity> = history(intakeId).filter { it.isActive }

    override fun close() = db.close()
}
