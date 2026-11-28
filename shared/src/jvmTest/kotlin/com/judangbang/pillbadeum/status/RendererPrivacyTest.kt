package com.judangbang.pillbadeum.status

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.judangbang.pillbadeum.data.IntakeMedicationEntity
import com.judangbang.pillbadeum.data.MedicationEntity
import com.judangbang.pillbadeum.testing.DbFixture
import com.judangbang.pillbadeum.testing.SEOUL
import com.judangbang.pillbadeum.testing.TEST_DEVICE_ID
import com.judangbang.pillbadeum.testing.seoul
import com.judangbang.pillbadeum.usecase.LoadHealthCard
import com.judangbang.pillbadeum.usecase.ManageReminders
import com.judangbang.pillbadeum.usecase.PlanReminders
import com.judangbang.pillbadeum.usecase.SaveReminderResult
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime

class RendererPrivacyTest {
    @Test
    fun tc108_widgetAndReminderDataPathsDoNotQueryCardTables() = runTest {
        val driver = QueryRecordingDriver()
        DbFixture(start = seoul("2026-11-11T07:57"), driver = driver).use { fixture ->
            val db = fixture.db
            val intakeId = fixture.newIntake()
            val person = db.personDao().listActive().single()
            val now = fixture.clock.now().toEpochMilliseconds()
            db.medicationDao().upsert(MedicationEntity(
                "test-med", "Test medicine", createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID,
            ))
            db.intakeMedicationDao().upsert(IntakeMedicationEntity(
                "test-row", intakeId, "test-med", "27 tablets", effectiveFrom = now,
                createdAt = now, updatedAt = now, deviceId = TEST_DEVICE_ID,
            ))
            val reminders = ManageReminders(db, fixture.clock, TEST_DEVICE_ID)
            assertIs<SaveReminderResult.Saved>(reminders.saveRule(intakeId, enabled = true, start = LocalTime(8, 0)))
            val loadBoard = LoadStatusBoard(db, fixture.clock)
            val plan = PlanReminders(db, fixture.clock)
            assertEquals(seoul("2026-11-11T08:00"), plan.nextAlarm())

            driver.selects.clear()
            val widget = WidgetModel.from(loadBoard(), SEOUL)
            assertEquals(intakeId, widget.buttons.single().intakeId)
            driver.assertNoCardQueries()

            driver.selects.clear()
            fixture.at("2026-11-11T08:01")
            val notice = plan.fire(alarmRang = true).notices.single()
            val notification = assertNotNull(ReminderLabels.content(loadBoard(), notice, SEOUL))
            assertEquals(intakeId, notification.intakeId)
            driver.assertNoCardQueries()

            // Positive control using the real card use case and the same database and recorder.
            driver.selects.clear()
            assertNotNull(LoadHealthCard(db)(person.id))
            val cardQueries = driver.selects.flatMap { query -> CARD_TABLES.findAll(query).map { it.groupValues[1].lowercase() } }
            assertContains(cardQueries, "health_card")
            assertContains(cardQueries, "intake_medication")
            assertContains(cardQueries, "medication")
        }
    }

    private class QueryRecordingDriver : SQLiteDriver {
        private val delegate = BundledSQLiteDriver()
        val selects = ConcurrentLinkedQueue<String>()

        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    if (!SELECT.containsMatchIn(sql)) return statement
                    // Room may reuse a cached prepared statement between both render paths.
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean {
                            selects.add(sql)
                            return statement.step()
                        }
                    }
                }
            }
        }

        fun assertNoCardQueries() {
            assertTrue(selects.isNotEmpty(), "The renderer did not execute any SQL reads")
            val cardReads = selects.filter { CARD_TABLES.containsMatchIn(it) }
            assertEquals(emptyList(), cardReads)
        }
    }

    private companion object {
        val SELECT = Regex("\\bSELECT\\b", RegexOption.IGNORE_CASE)
        val CARD_TABLES = Regex(
            "\\b(?:FROM|JOIN)\\s+(?:main\\s*\\.\\s*)?[`\"\\[]?" +
                "(health_card|condition|procedure_history|allergy|implanted_device|care_contact|intake_medication|medication)\\b",
            RegexOption.IGNORE_CASE,
        )
    }
}
