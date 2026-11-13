package com.judangbang.pillbadeum.restore

import com.judangbang.pillbadeum.DidITakeCore
import com.judangbang.pillbadeum.data.SettingEntity
import com.judangbang.pillbadeum.data.SettingKeys
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.status.Labels
import com.judangbang.pillbadeum.status.WidgetModel
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.RecordResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Marker kept in memory; [beforeWrite] lets a test look at the database or fail the write. */
class FakeInstallMarker(var value: String? = null) : InstallMarker {
    var beforeWrite: suspend (String) -> Unit = {}
    var writes: Int = 0

    override suspend fun read(): String? = value

    override suspend fun write(deviceId: String) {
        beforeWrite(deviceId)
        value = deviceId
        writes++
    }
}

/**
 * TASK-029: restore detection and the restore notice (record-rules-supplement.md R-1 to R-3,
 * REQ-056, REQ-073, REQ-074). One [DidITakeCore.open] call = one process start. What the phone
 * itself does (backup, reinstall, widget redraw, alarms) is checked on an emulator (docs/07-qa).
 *
 * Calendar: 2026-11-10 Tue, 11-11 Wed (Asia/Seoul, day start 04:00).
 */
class InstallCheckTest {
    private val fx = DbFixture(start = seoul("2026-11-10T21:40"))
    private val marker = FakeInstallMarker()

    @AfterTest
    fun tearDown() = fx.close()

    private suspend fun open(m: InstallMarker = marker): DidITakeCore = DidITakeCore.open(fx.db, m, fx.clock) { SEOUL }

    private suspend fun setting(key: String): String? = fx.db.settingDao().get(key)

    /** The old phone's database as a backup brings it: its own device id and yesterday's record. */
    private suspend fun oldPhoneDatabase(): String {
        val intake = fx.newIntake()
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-10"))
        fx.db.settingDao().upsert(SettingEntity(SettingKeys.DEVICE_ID, OLD_PHONE))
        return intake
    }

    @Test
    fun newInstall_writesSameIdToMarkerAndDatabase_noNotice() = runTest {
        val core = open()
        assertEquals(InstallState.New, core.installState)
        assertEquals(core.deviceId, setting(SettingKeys.DEVICE_ID))
        assertEquals(core.deviceId, marker.value)
        assertNull(core.loadStatusBoard().restoreNotice)
        assertNull(setting(SettingKeys.RESTORE_NOTICE_PENDING))
    }

    /** RR-31: an app update keeps noBackupFilesDir, so marker and database agree. */
    @Test
    fun rr31_update_isNormal_sameDeviceId() = runTest {
        val first = open()
        fx.at("2026-11-11T08:00")
        val second = open()
        assertEquals(InstallState.Normal, second.installState)
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(1, marker.writes, "a normal start writes nothing")
        assertNull(second.loadStatusBoard().restoreNotice)
    }

    /** RR-34: clearing app data removes the database and the marker together. */
    @Test
    fun rr34_appDataCleared_isNewInstall() = runTest {
        val before = open()
        DbFixture(start = fx.clock.now()).use { cleared ->
            val after = DidITakeCore.open(cleared.db, FakeInstallMarker(), cleared.clock) { SEOUL }
            assertEquals(InstallState.New, after.installState)
            assertNotEquals(before.deviceId, after.deviceId)
            assertNull(after.loadStatusBoard().restoreNotice)
        }
    }

    /** R-1 table, last row: the database alone was lost. Same as a new install, marker rewritten. */
    @Test
    fun databaseLost_markerLeft_isNewInstall_markerRewritten() = runTest {
        marker.value = "left-over-id"
        val core = open()
        assertEquals(InstallState.New, core.installState)
        assertEquals(core.deviceId, marker.value)
        assertNotEquals("left-over-id", core.deviceId)
    }

    /** RR-30: new phone, restored database, today not recorded here yet. */
    @Test
    fun rr30_restoreOnNewPhone_newDeviceId_noticeWithLastRecord() = runTest {
        oldPhoneDatabase()
        fx.at("2026-11-11T07:30")

        val core = open()
        assertEquals(InstallState.Restored(OLD_PHONE), core.installState)
        assertNotEquals(OLD_PHONE, core.deviceId)
        assertEquals(core.deviceId, setting(SettingKeys.DEVICE_ID))
        assertEquals(core.deviceId, marker.value)
        assertEquals(OLD_PHONE, setting(SettingKeys.RESTORED_FROM_DEVICE_ID))
        assertEquals("1", setting(SettingKeys.RESTORE_NOTICE_PENDING))

        val board = core.loadStatusBoard()
        assertFalse(board.items.single().isDone, "today's window is pending on the new phone")
        val notice = assertNotNull(board.restoreNotice)
        assertEquals(seoul("2026-11-10T21:40"), notice.lastRecordAt)
        assertEquals("복원됨 · 마지막 기록 11/10 21:40 · 앱에서 확인", WidgetModel.from(board, SEOUL).restoreLine)
        assertEquals(
            "백업에서 복원했어요. 마지막 기록은 11/10 21:40이에요. 그 뒤의 기록은 없을 수 있어요. 한 약은 한 휴대폰에서 기록해 주세요",
            Labels.restoreAppNotice(notice, SEOUL),
        )
    }

    /** RR-32: a widget tap before [확인했어요] records with the new device id; the notice stays. */
    @Test
    fun rr32_tapBeforeConfirm_recordsWithNewDeviceId_noticeStays() = runTest {
        val intake = oldPhoneDatabase()
        fx.at("2026-11-11T07:30")
        val core = open()

        fx.at("2026-11-11T08:00")
        val tap = assertIs<RecordResult.Recorded>(core.record(intake, DoseSource.WIDGET, "2026-11-11"))
        assertEquals(core.deviceId, tap.record.deviceId)
        assertNotEquals(OLD_PHONE, tap.record.deviceId)
        assertNotNull(core.loadStatusBoard().restoreNotice, "recording does not clear the notice")
        assertEquals(seoul("2026-11-10T21:40"), core.loadStatusBoard().restoreNotice?.lastRecordAt)
    }

    /** RR-33, app part: [확인했어요] clears the notice for good. Reminders are TASK-040. */
    @Test
    fun rr33_confirm_clearsNotice_nextStartIsNormal() = runTest {
        oldPhoneDatabase()
        fx.at("2026-11-11T07:30")
        val core = open()
        core.acknowledgeRestore()

        val board = core.loadStatusBoard()
        assertNull(board.restoreNotice)
        assertNull(WidgetModel.from(board, SEOUL).restoreLine)
        assertEquals("0", setting(SettingKeys.RESTORE_NOTICE_PENDING))

        fx.at("2026-11-11T09:00")
        val again = open()
        assertEquals(InstallState.Normal, again.installState)
        assertEquals(core.deviceId, again.deviceId)
        assertNull(again.loadStatusBoard().restoreNotice)
    }

    /** RR-71: backup restored over an installed app: the marker stays but holds another id. */
    @Test
    fun rr71_restoreOverInstalledApp_markerDiffers_isRestored() = runTest {
        oldPhoneDatabase()
        marker.value = "this-phone-before-restore"
        val core = open()
        assertEquals(InstallState.Restored(OLD_PHONE), core.installState)
        assertNotNull(core.loadStatusBoard().restoreNotice)
    }

    /** R-2 order: the marker is written while the database still has the old id. */
    @Test
    fun restore_writesMarkerBeforeDatabase() = runTest {
        oldPhoneDatabase()
        marker.beforeWrite = {
            assertEquals(OLD_PHONE, setting(SettingKeys.DEVICE_ID))
            assertNull(setting(SettingKeys.RESTORE_NOTICE_PENDING))
        }
        open()
        assertEquals(1, marker.writes)
    }

    /** RR-72: the process dies after the marker write, before the transaction; the next start redoes R-2. */
    @Test
    fun rr72_diedAfterMarkerWrite_nextStartRestoresAgain_keepsOldPhoneId() = runTest {
        oldPhoneDatabase()
        var interruptedId: String? = null
        val dying = object : InstallMarker {
            override suspend fun read(): String? = marker.read()
            override suspend fun write(deviceId: String) {
                marker.write(deviceId)
                interruptedId = deviceId
                throw IllegalStateException("process killed after the marker write")
            }
        }
        assertFailsWith<IllegalStateException> { open(dying) }
        assertEquals(OLD_PHONE, setting(SettingKeys.DEVICE_ID), "transaction did not run")
        assertNull(setting(SettingKeys.RESTORE_NOTICE_PENDING))
        assertEquals(interruptedId, marker.value)

        val core = open()
        assertEquals(InstallState.Restored(OLD_PHONE), core.installState)
        assertEquals(OLD_PHONE, setting(SettingKeys.RESTORED_FROM_DEVICE_ID))
        assertNotEquals(interruptedId, core.deviceId)
        assertEquals(core.deviceId, marker.value)
        assertEquals("1", setting(SettingKeys.RESTORE_NOTICE_PENDING))
    }

    /** RR-75: a judgment a wrong clock put in the future is not the "last record". */
    @Test
    fun rr75_futureRecordFromWrongClock_notShownAsLastRecord() = runTest {
        val intake = oldPhoneDatabase()
        fx.at("2026-11-13T09:00") // the old phone's clock was two days ahead
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-13"))

        fx.at("2026-11-11T07:30")
        val notice = assertNotNull(open().loadStatusBoard().restoreNotice)
        assertEquals(seoul("2026-11-10T21:40"), notice.lastRecordAt)
    }

    /** RR-75, other side: every restored judgment is after this phone's clock. No time, no "no record". */
    @Test
    fun onlyLaterRecords_noticeShowsNoTime_doesNotSayNoRecord() = runTest {
        val intake = fx.newIntake()
        fx.at("2026-11-13T09:00")
        assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-13"))
        fx.db.settingDao().upsert(SettingEntity(SettingKeys.DEVICE_ID, OLD_PHONE))

        fx.at("2026-11-11T07:30") // this phone's clock is behind the old phone's records
        val board = open().loadStatusBoard()
        val notice = assertNotNull(board.restoreNotice)
        assertNull(notice.lastRecordAt)
        assertTrue(notice.hasRecords)
        assertEquals(CheckInstall.ONLY_LATER_RECORDS, setting(SettingKeys.RESTORE_NOTICE_LAST_RECORD_AT))
        assertEquals("복원됨 · 앱에서 확인", WidgetModel.from(board, SEOUL).restoreLine)
        assertEquals(
            "백업에서 복원했어요. 다른 휴대폰에서 한 기록은 없을 수 있어요. 한 약은 한 휴대폰에서 기록해 주세요",
            Labels.restoreAppNotice(notice, SEOUL),
        )
    }

    /** A cancelled (void) judgment is not the "last record": only active ones count. */
    @Test
    fun voidedLatestJudgment_isSkipped() = runTest {
        val intake = oldPhoneDatabase() // active 11-10 21:40
        fx.at("2026-11-11T07:00")
        val later = assertIs<RecordResult.Recorded>(fx.record(intake, renderedKey = "2026-11-11"))
        assertEquals(1, fx.db.doseRecordDao().voidActive(later.record.id, fx.clock.now().toEpochMilliseconds(), "USER_CANCEL"))

        fx.at("2026-11-11T07:30")
        val notice = assertNotNull(open().loadStatusBoard().restoreNotice)
        assertEquals(seoul("2026-11-10T21:40"), notice.lastRecordAt)
    }

    /** A backup taken after an earlier restore: the old restore settings are overwritten, not kept. */
    @Test
    fun secondRestore_overwritesEarlierRestoreSettings() = runTest {
        oldPhoneDatabase()
        val settings = fx.db.settingDao()
        settings.upsert(SettingEntity(SettingKeys.RESTORED_FROM_DEVICE_ID, "phone-before-old-phone"))
        settings.upsert(SettingEntity(SettingKeys.RESTORE_NOTICE_PENDING, CheckInstall.DONE))
        settings.upsert(SettingEntity(SettingKeys.RESTORE_NOTICE_LAST_RECORD_AT, "0"))

        fx.at("2026-11-11T07:30")
        val core = open()
        assertEquals(InstallState.Restored(OLD_PHONE), core.installState)
        assertEquals(OLD_PHONE, setting(SettingKeys.RESTORED_FROM_DEVICE_ID))
        assertEquals(CheckInstall.PENDING, setting(SettingKeys.RESTORE_NOTICE_PENDING))
        assertEquals(seoul("2026-11-10T21:40"), core.loadStatusBoard().restoreNotice?.lastRecordAt)
    }

    /** New install, process killed after the marker write: the next start is a new install again. */
    @Test
    fun newInstall_diedAfterMarkerWrite_nextStartIsNewInstall() = runTest {
        val dying = object : InstallMarker {
            override suspend fun read(): String? = marker.read()
            override suspend fun write(deviceId: String) {
                marker.write(deviceId)
                throw IllegalStateException("process killed after the marker write")
            }
        }
        assertFailsWith<IllegalStateException> { open(dying) }
        assertNull(setting(SettingKeys.DEVICE_ID))

        val core = open()
        assertEquals(InstallState.New, core.installState)
        assertEquals(core.deviceId, marker.value)
        assertEquals(core.deviceId, setting(SettingKeys.DEVICE_ID))
        assertNull(core.loadStatusBoard().restoreNotice)
    }

    /** Restored database without any judgment: the notice says so instead of a time (Claude default). */
    @Test
    fun restoreWithoutRecords_noticeSaysNoRecord() = runTest {
        fx.newIntake()
        fx.db.settingDao().upsert(SettingEntity(SettingKeys.DEVICE_ID, OLD_PHONE))
        val board = open().loadStatusBoard()
        val notice = assertNotNull(board.restoreNotice)
        assertNull(notice.lastRecordAt)
        assertFalse(notice.hasRecords)
        assertEquals(CheckInstall.NO_RECORDS, setting(SettingKeys.RESTORE_NOTICE_LAST_RECORD_AT))
        assertEquals("복원됨 · 기록 없음 · 앱에서 확인", WidgetModel.from(board, SEOUL).restoreLine)
        assertTrue(Labels.restoreAppNotice(notice, SEOUL).endsWith(Labels.ONE_PHONE))
    }

    private companion object {
        const val OLD_PHONE = "old-phone-device-id"
    }
}
