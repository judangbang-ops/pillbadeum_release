package com.judangbang.pillbadeum.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medication WHERE id = :id")
    suspend fun findById(id: String): MedicationEntity?

    @Upsert
    suspend fun upsert(medication: MedicationEntity)

    @Query("DELETE FROM medication WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface IntakeMedicationDao {
    @Query("SELECT * FROM intake_medication WHERE intake_id = :intakeId ORDER BY created_at, id")
    suspend fun listByIntake(intakeId: String): List<IntakeMedicationEntity>

    @Upsert
    suspend fun upsert(row: IntakeMedicationEntity)

    @Query("DELETE FROM intake_medication WHERE intake_id = :intakeId AND id = :id")
    suspend fun delete(intakeId: String, id: String): Int
}
