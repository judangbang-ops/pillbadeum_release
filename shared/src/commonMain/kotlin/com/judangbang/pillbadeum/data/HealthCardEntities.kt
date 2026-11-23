package com.judangbang.pillbadeum.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Stored as TEXT using the enum names. Keep the persisted names stable. */
enum class CardEntrySource { USER }

enum class CareContactKind { CARE_PROVIDER, GUARDIAN }

@Entity(
    tableName = "health_card",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
)
data class HealthCardEntity(
    @PrimaryKey @ColumnInfo(name = "person_id") val personId: String,
    @ColumnInfo(name = "edited_at") val editedAt: Long,
    @ColumnInfo(name = "emergency_copied_at") val emergencyCopiedAt: Long? = null,
    @ColumnInfo(name = "stale_notice_closed_at") val staleNoticeClosedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
)

@Entity(
    tableName = "condition",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["person_id"], name = "ix_condition_person")],
)
data class ConditionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "person_id") val personId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "diagnosed_on") val diagnosedOn: String? = null,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "code_system") val codeSystem: String? = null,
    @ColumnInfo(name = "code") val code: String? = null,
    @ColumnInfo(name = "source") val source: CardEntrySource = CardEntrySource.USER,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
)

@Entity(
    tableName = "procedure_history",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["person_id"], name = "ix_procedure_history_person")],
)
data class ProcedureHistoryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "person_id") val personId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "performed_on") val performedOn: String? = null,
    @ColumnInfo(name = "place") val place: String? = null,
    @ColumnInfo(name = "code_system") val codeSystem: String? = null,
    @ColumnInfo(name = "code") val code: String? = null,
    @ColumnInfo(name = "source") val source: CardEntrySource = CardEntrySource.USER,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
)

@Entity(
    tableName = "allergy",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["person_id"], name = "ix_allergy_person")],
)
data class AllergyEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "person_id") val personId: String,
    @ColumnInfo(name = "substance") val substance: String,
    @ColumnInfo(name = "reaction") val reaction: String? = null,
    @ColumnInfo(name = "code_system") val codeSystem: String? = null,
    @ColumnInfo(name = "code") val code: String? = null,
    @ColumnInfo(name = "source") val source: CardEntrySource = CardEntrySource.USER,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
)

@Entity(
    tableName = "implanted_device",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["person_id"], name = "ix_implanted_device_person")],
)
data class ImplantedDeviceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "person_id") val personId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "implanted_on") val implantedOn: String? = null,
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "code_system") val codeSystem: String? = null,
    @ColumnInfo(name = "code") val code: String? = null,
    @ColumnInfo(name = "source") val source: CardEntrySource = CardEntrySource.USER,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
)

@Entity(
    tableName = "care_contact",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["person_id", "kind"], name = "ix_care_contact_person_kind")],
)
data class CareContactEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "person_id") val personId: String,
    @ColumnInfo(name = "kind") val kind: CareContactKind,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "department") val department: String? = null,
    @ColumnInfo(name = "doctor") val doctor: String? = null,
    @ColumnInfo(name = "relation") val relation: String? = null,
    @ColumnInfo(name = "phone") val phone: String? = null,
    @ColumnInfo(name = "source") val source: CardEntrySource = CardEntrySource.USER,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
)
