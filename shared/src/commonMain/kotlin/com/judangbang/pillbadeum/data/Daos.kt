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

    @Query("SELECT * FROM person WHERE id = :id")
    suspend fun findById(id: String): PersonEntity?
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
}
