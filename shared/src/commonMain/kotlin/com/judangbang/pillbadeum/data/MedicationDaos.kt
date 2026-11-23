package com.judangbang.pillbadeum.data

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

data class IntakeMedicationFields(val id: String, val name: String, val quantity: String?)

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medication WHERE id = :id")
    suspend fun findById(id: String): MedicationEntity?

    @Upsert
    suspend fun upsert(medication: MedicationEntity)

    @Query("DELETE FROM medication WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("""
        DELETE FROM medication WHERE id = :id
          AND NOT EXISTS (SELECT 1 FROM intake_medication WHERE medication_id = :id)
          AND NOT EXISTS (SELECT 1 FROM dose_version WHERE medication_id = :id)
    """)
    suspend fun deleteUnlinked(id: String): Int
}

@Dao
interface IntakeMedicationDao {
    @Query("SELECT * FROM intake_medication WHERE intake_id = :intakeId ORDER BY created_at, id")
    suspend fun listByIntake(intakeId: String): List<IntakeMedicationEntity>

    @Query("""
        SELECT im.id, m.name, im.qty_per_take AS quantity
        FROM intake_medication im JOIN medication m ON im.medication_id = m.id
        WHERE im.intake_id = :intakeId ORDER BY im.created_at, im.id
    """)
    suspend fun listFields(intakeId: String): List<IntakeMedicationFields>

    @Upsert
    suspend fun upsert(row: IntakeMedicationEntity)

    @Query("DELETE FROM intake_medication WHERE intake_id = :intakeId AND id = :id")
    suspend fun delete(intakeId: String, id: String): Int
}
