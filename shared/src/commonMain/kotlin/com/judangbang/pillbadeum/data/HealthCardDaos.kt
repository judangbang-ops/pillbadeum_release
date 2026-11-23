package com.judangbang.pillbadeum.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface HealthCardDao {
    @Query("SELECT * FROM health_card WHERE person_id = :personId")
    suspend fun findByPerson(personId: String): HealthCardEntity?

    @Upsert
    suspend fun upsert(card: HealthCardEntity)

    @Query(
        """
        UPDATE health_card
        SET stale_notice_closed_at = NULL, updated_at = :now, version = version + 1, device_id = :deviceId
        WHERE emergency_copied_at IS NOT NULL AND stale_notice_closed_at IS NOT NULL
        """,
    )
    suspend fun reopenCopiedNotices(now: Long, deviceId: String)
}

@Dao
interface ConditionDao {
    @Insert
    suspend fun insert(entry: ConditionEntity)

    @Query("SELECT * FROM condition WHERE person_id = :personId ORDER BY created_at, rowid")
    suspend fun listByPerson(personId: String): List<ConditionEntity>

    @Upsert
    suspend fun upsert(entry: ConditionEntity)

    @Query("DELETE FROM condition WHERE person_id = :personId AND id = :id")
    suspend fun delete(personId: String, id: String): Int

    @Query("DELETE FROM condition WHERE person_id = :personId")
    suspend fun deleteByPerson(personId: String): Int
}

@Dao
interface ProcedureHistoryDao {
    @Insert
    suspend fun insert(entry: ProcedureHistoryEntity)

    @Query("SELECT * FROM procedure_history WHERE person_id = :personId ORDER BY created_at, rowid")
    suspend fun listByPerson(personId: String): List<ProcedureHistoryEntity>

    @Upsert
    suspend fun upsert(entry: ProcedureHistoryEntity)

    @Query("DELETE FROM procedure_history WHERE person_id = :personId AND id = :id")
    suspend fun delete(personId: String, id: String): Int

    @Query("DELETE FROM procedure_history WHERE person_id = :personId")
    suspend fun deleteByPerson(personId: String): Int
}

@Dao
interface AllergyDao {
    @Insert
    suspend fun insert(entry: AllergyEntity)

    @Query("SELECT * FROM allergy WHERE person_id = :personId ORDER BY created_at, rowid")
    suspend fun listByPerson(personId: String): List<AllergyEntity>

    @Upsert
    suspend fun upsert(entry: AllergyEntity)

    @Query("DELETE FROM allergy WHERE person_id = :personId AND id = :id")
    suspend fun delete(personId: String, id: String): Int

    @Query("DELETE FROM allergy WHERE person_id = :personId")
    suspend fun deleteByPerson(personId: String): Int
}

@Dao
interface ImplantedDeviceDao {
    @Insert
    suspend fun insert(entry: ImplantedDeviceEntity)

    @Query("SELECT * FROM implanted_device WHERE person_id = :personId ORDER BY created_at, rowid")
    suspend fun listByPerson(personId: String): List<ImplantedDeviceEntity>

    @Upsert
    suspend fun upsert(entry: ImplantedDeviceEntity)

    @Query("DELETE FROM implanted_device WHERE person_id = :personId AND id = :id")
    suspend fun delete(personId: String, id: String): Int

    @Query("DELETE FROM implanted_device WHERE person_id = :personId")
    suspend fun deleteByPerson(personId: String): Int
}

@Dao
interface CareContactDao {
    @Insert
    suspend fun insert(entry: CareContactEntity)

    @Query("SELECT * FROM care_contact WHERE person_id = :personId ORDER BY created_at, rowid")
    suspend fun listByPerson(personId: String): List<CareContactEntity>

    @Upsert
    suspend fun upsert(entry: CareContactEntity)

    @Query("DELETE FROM care_contact WHERE person_id = :personId AND id = :id")
    suspend fun delete(personId: String, id: String): Int

    @Query("DELETE FROM care_contact WHERE person_id = :personId AND id = :id AND kind = :kind")
    suspend fun deleteOfKind(personId: String, id: String, kind: CareContactKind): Int

    @Query("DELETE FROM care_contact WHERE person_id = :personId")
    suspend fun deleteByPerson(personId: String): Int
}
