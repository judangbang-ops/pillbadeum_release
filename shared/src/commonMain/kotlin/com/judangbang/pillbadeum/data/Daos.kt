package com.judangbang.pillbadeum.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import com.judangbang.pillbadeum.domain.SlotLabel

@Dao
interface PersonDao {
    @Insert
    suspend fun insert(person: PersonEntity)

    @Query("SELECT * FROM person WHERE archived_at IS NULL ORDER BY created_at, id")
    suspend fun listActive(): List<PersonEntity>

    /** Archived people, most recently archived first (SCR-010 "되살리기"). */
    @Query("SELECT * FROM person WHERE archived_at IS NOT NULL ORDER BY archived_at DESC, id")
    suspend fun listArchived(): List<PersonEntity>

    /** Everyone, archived people too (history keeps their past cells). */
    @Query("SELECT * FROM person ORDER BY created_at, id")
    suspend fun listAll(): List<PersonEntity>

    @Query("SELECT COUNT(*) FROM person WHERE archived_at IS NULL")
    suspend fun countActive(): Int

    @Query("SELECT * FROM person WHERE id = :id")
    suspend fun findById(id: String): PersonEntity?

    /** Edits an active person. Returns the number of rows changed (0 or 1). */
    @Query(
        """
        UPDATE person SET name = :name, color = :color, updated_at = :now, version = version + 1
        WHERE id = :id AND archived_at IS NULL
        """,
    )
    suspend fun updateDetails(id: String, name: String, color: String, now: Long): Int

    /** Soft delete (data-model.md section 3). Intakes and judgments are kept. */
    @Query(
        """
        UPDATE person SET archived_at = :now, updated_at = :now, version = version + 1
        WHERE id = :id AND archived_at IS NULL
        """,
    )
    suspend fun archive(id: String, now: Long): Int

    @Query(
        """
        UPDATE person SET archived_at = NULL, updated_at = :now, version = version + 1
        WHERE id = :id AND archived_at IS NOT NULL
        """,
    )
    suspend fun restore(id: String, now: Long): Int
}

@Dao
interface IntakeDao {
    @Insert
    suspend fun insert(intake: IntakeEntity)

    @Query("SELECT * FROM intake WHERE id = :id")
    suspend fun findById(id: String): IntakeEntity?

    /** Intakes shown on the widget and the app list: not archived, in display order. */
    @Query("SELECT * FROM intake WHERE archived_at IS NULL ORDER BY sort_order, created_at, id")
    suspend fun listActive(): List<IntakeEntity>

    /** Every intake, archived ones too, in display order (history keeps archived items, P-8). */
    @Query("SELECT * FROM intake ORDER BY sort_order, created_at, id")
    suspend fun listAll(): List<IntakeEntity>

    /** One person's intakes that are not archived themselves (person restore, supplement P-9). */
    @Query("SELECT * FROM intake WHERE person_id = :personId AND archived_at IS NULL ORDER BY sort_order, created_at, id")
    suspend fun listActiveOfPerson(personId: String): List<IntakeEntity>

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM intake")
    suspend fun maxSortOrder(): Int

    /** Edits display fields only; schedule changes go to a new schedule_version row. */
    @Query(
        """
        UPDATE intake
        SET person_id = :personId, name = :name, widget_alias = :widgetAlias, slot_label = :slotLabel,
            updated_at = :now, version = version + 1
        WHERE id = :id AND archived_at IS NULL
        """,
    )
    suspend fun updateDetails(
        id: String,
        personId: String,
        name: String,
        widgetAlias: String?,
        slotLabel: SlotLabel?,
        now: Long,
    ): Int

    /** Soft delete (data-model.md section 3). Returns the number of rows changed (0 or 1). */
    @Query(
        """
        UPDATE intake
        SET archived_at = :now, updated_at = :now, version = version + 1
        WHERE id = :id AND archived_at IS NULL
        """,
    )
    suspend fun archive(id: String, now: Long): Int
}

@Dao
interface ScheduleVersionDao {
    @Insert
    suspend fun insert(version: ScheduleVersionEntity)

    /** The version in effect at [at]: effective_from <= at < effective_to (NULL = open-ended). */
    @Query(
        """
        SELECT * FROM schedule_version
        WHERE intake_id = :intakeId
          AND archived_at IS NULL
          AND effective_from <= :at
          AND (effective_to IS NULL OR effective_to > :at)
        ORDER BY effective_from DESC
        LIMIT 1
        """,
    )
    suspend fun findEffective(intakeId: String, at: Long): ScheduleVersionEntity?

    /** Ends the current version. Versions are never overwritten (data-model.md section 2). */
    @Query(
        """
        UPDATE schedule_version
        SET effective_to = :effectiveTo, updated_at = :now, version = version + 1
        WHERE id = :id AND effective_to IS NULL
        """,
    )
    suspend fun close(id: String, effectiveTo: Long, now: Long): Int

    /**
     * The open-ended (latest) version, whatever the clock says. Schedule edits start from this one so
     * a clock set back can never leave two open versions (TASK-005).
     */
    @Query(
        """
        SELECT * FROM schedule_version
        WHERE intake_id = :intakeId AND archived_at IS NULL AND effective_to IS NULL
        ORDER BY effective_from DESC, id DESC
        LIMIT 1
        """,
    )
    suspend fun findOpen(intakeId: String): ScheduleVersionEntity?

    @Query("SELECT * FROM schedule_version WHERE id = :id")
    suspend fun findById(id: String): ScheduleVersionEntity?

    /** Every version of one intake, oldest first (history and tests). */
    @Query("SELECT * FROM schedule_version WHERE intake_id = :intakeId ORDER BY effective_from, id")
    suspend fun listByIntake(intakeId: String): List<ScheduleVersionEntity>
}

@Dao
interface DoseRecordDao {
    /**
     * Returns the new rowId, or -1 when SQLite ignored the row.
     *
     * -1 does NOT by itself mean "duplicate judgment": OR IGNORE also skips primary key clashes
     * and NOT NULL violations. Callers must re-query (record-rules.md section 6).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(record: DoseRecordEntity): Long

    /**
     * Active judgment of one window (record-rules.md section 5). Matching on taken_at too keeps a
     * schedule or day-start change from producing a second judgment for the same real window.
     */
    @Query(
        """
        SELECT * FROM dose_record
        WHERE intake_id = :intakeId
          AND active = 1
          AND (period_key = :periodKey OR (taken_at >= :windowStart AND taken_at < :windowEnd))
        ORDER BY taken_at
        LIMIT 1
        """,
    )
    suspend fun findActiveJudgment(
        intakeId: String,
        periodKey: String,
        windowStart: Long,
        windowEnd: Long,
    ): DoseRecordEntity?

    /**
     * Every active judgment that [findActiveJudgment] would match for one window. Writers that replace
     * one of them (time edit, kind change) must see the others too (supplement P-6).
     */
    @Query(
        """
        SELECT * FROM dose_record
        WHERE intake_id = :intakeId
          AND active = 1
          AND (period_key = :periodKey OR (taken_at >= :windowStart AND taken_at < :windowEnd))
        ORDER BY taken_at, id
        """,
    )
    suspend fun findActiveJudgments(
        intakeId: String,
        periodKey: String,
        windowStart: Long,
        windowEnd: Long,
    ): List<DoseRecordEntity>

    /** Void (cancel) an active judgment (record-rules.md section 6.1). Returns rows changed. */
    @Query(
        """
        UPDATE dose_record
        SET active = NULL, voided_at = :now, void_reason = :reason,
            updated_at = :now, version = version + 1
        WHERE id = :id AND active = 1
        """,
    )
    suspend fun voidActive(id: String, now: Long, reason: String): Int

    /** Latest active judgment taken before [before] ("마지막 기록" on the widget and app). */
    @Query(
        """
        SELECT * FROM dose_record
        WHERE intake_id = :intakeId AND active = 1 AND taken_at < :before
        ORDER BY taken_at DESC
        LIMIT 1
        """,
    )
    suspend fun findLastActiveBefore(intakeId: String, before: Long): DoseRecordEntity?

    /**
     * `taken_at` of the latest active judgment of any intake taken before [before], or null.
     * Restore notice "마지막 기록" (supplement R-2): judgments a wrong clock put in the future are left out.
     */
    @Query("SELECT MAX(taken_at) FROM dose_record WHERE active = 1 AND taken_at < :before")
    suspend fun latestActiveTakenAtBefore(before: Long): Long?

    @Query("SELECT * FROM dose_record WHERE id = :id")
    suspend fun findById(id: String): DoseRecordEntity?

    /**
     * Active judgments of one intake that a cell between [fromKey] and [toKey] can show: by period key
     * (same schedule version) or by taken_at in [fromMillis, toMillis) (other versions, supplement P-8).
     */
    @Query(
        """
        SELECT * FROM dose_record
        WHERE intake_id = :intakeId AND active = 1
          AND ((period_key >= :fromKey AND period_key <= :toKey) OR (taken_at >= :fromMillis AND taken_at < :toMillis))
        ORDER BY taken_at, id
        """,
    )
    suspend fun listActiveForCells(
        intakeId: String,
        fromKey: String,
        toKey: String,
        fromMillis: Long,
        toMillis: Long,
    ): List<DoseRecordEntity>

    /** Full history of one intake, voided rows included. */
    @Query("SELECT * FROM dose_record WHERE intake_id = :intakeId ORDER BY recorded_at, id")
    suspend fun listByIntake(intakeId: String): List<DoseRecordEntity>
}

@Dao
interface SettingDao {
    @Query("SELECT `value` FROM setting WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun upsert(setting: SettingEntity)

    @Query("DELETE FROM setting WHERE `key` = :key")
    suspend fun delete(key: String): Int
}

/**
 * Hard delete of one archived intake and everything that points at it (REQ-015, TASK-010). Foreign
 * keys are NO ACTION, so children go first; callers run these in one transaction.
 */
@Dao
interface IntakePurgeDao {
    @Query("DELETE FROM clock_flag WHERE dose_id IN (SELECT id FROM dose_record WHERE intake_id = :intakeId)")
    suspend fun deleteClockFlags(intakeId: String): Int

    @Query("DELETE FROM dose_record WHERE intake_id = :intakeId")
    suspend fun deleteJudgments(intakeId: String): Int

    @Query("DELETE FROM reminder_snooze WHERE intake_id = :intakeId")
    suspend fun deleteSnooze(intakeId: String): Int

    @Query("DELETE FROM reminder_rule WHERE intake_id = :intakeId")
    suspend fun deleteReminderRule(intakeId: String): Int

    @Query("DELETE FROM intake_medication WHERE intake_id = :intakeId")
    suspend fun deleteIntakeMedications(intakeId: String): Int

    @Query("DELETE FROM schedule_version WHERE intake_id = :intakeId")
    suspend fun deleteScheduleVersions(intakeId: String): Int

    /** Deletes the intake only when it is archived. Returns the number of rows deleted (0 or 1). */
    @Query("DELETE FROM intake WHERE id = :intakeId AND archived_at IS NOT NULL")
    suspend fun deleteArchivedIntake(intakeId: String): Int
}

/**
 * Warnings for judgments written while the phone clock was ahead (record-rules-supplement.md C-1~C-3,
 * TASK-006). A flag sits next to its judgment so the judgment row itself is never rewritten (I-2).
 */
@Dao
interface ClockFlagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(flag: ClockFlagEntity): Long

    /** Active judgments taken after [threshold] that have no flag yet (a resolved flag counts). */
    @Query(
        """
        SELECT * FROM dose_record
        WHERE active = 1 AND taken_at > :threshold
          AND id NOT IN (SELECT dose_id FROM clock_flag)
        ORDER BY taken_at, id
        """,
    )
    suspend fun findUnflaggedTakenAfter(threshold: Long): List<DoseRecordEntity>

    /** Active judgments with an unresolved flag, oldest first. */
    @Query(
        """
        SELECT d.* FROM dose_record d JOIN clock_flag f ON f.dose_id = d.id
        WHERE f.resolved_at IS NULL AND d.active = 1
        ORDER BY d.taken_at, d.id
        """,
    )
    suspend fun listFlaggedJudgments(): List<DoseRecordEntity>

    @Query("SELECT * FROM clock_flag WHERE dose_id = :doseId")
    suspend fun find(doseId: String): ClockFlagEntity?

    /** Resolves the flag of [doseId]. Returns rows changed (0 when there is no open flag). */
    @Query("UPDATE clock_flag SET resolved_at = :now WHERE dose_id = :doseId AND resolved_at IS NULL")
    suspend fun resolve(doseId: String, now: Long): Int
}

/** Reminder rules and snoozes (record-rules-supplement.md N-1, N-2, N-7; TASK-011). */
@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder_rule WHERE intake_id = :intakeId")
    suspend fun findRule(intakeId: String): ReminderRuleEntity?

    @Upsert
    suspend fun upsertRule(rule: ReminderRuleEntity)

    /** Rules switched on, of items and people that are not archived. */
    @Query(
        """
        SELECT r.* FROM reminder_rule r
        JOIN intake i ON i.id = r.intake_id
        JOIN person p ON p.id = i.person_id
        WHERE r.enabled = 1 AND i.archived_at IS NULL AND p.archived_at IS NULL
        ORDER BY i.sort_order, i.created_at, i.id
        """,
    )
    suspend fun listEnabledRules(): List<ReminderRuleEntity>

    @Query("SELECT * FROM reminder_snooze")
    suspend fun listSnoozes(): List<ReminderSnoozeEntity>

    @Query("SELECT * FROM reminder_snooze WHERE intake_id = :intakeId")
    suspend fun findSnooze(intakeId: String): ReminderSnoozeEntity?

    /** Only the last snooze of an item is kept (supplement N-7). */
    @Upsert
    suspend fun upsertSnooze(snooze: ReminderSnoozeEntity)

    @Query("DELETE FROM reminder_snooze WHERE intake_id = :intakeId")
    suspend fun deleteSnooze(intakeId: String): Int
}
