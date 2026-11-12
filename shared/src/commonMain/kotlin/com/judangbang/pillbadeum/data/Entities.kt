package com.judangbang.pillbadeum.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.judangbang.pillbadeum.domain.DoseKind
import com.judangbang.pillbadeum.domain.DoseSource
import com.judangbang.pillbadeum.domain.LatePolicy
import com.judangbang.pillbadeum.domain.ScheduleType
import com.judangbang.pillbadeum.domain.SlotLabel

// MVP tables of data-model.md section 2.
//
// Common metadata (data-model.md section 0) on every table except `setting`:
//   id TEXT PK (UUIDv7) | created_at | updated_at | version | device_id | archived_at (nullable)
// Times are UTC epoch milliseconds. Enums are stored as TEXT codes (see domain/Codes.kt).
// Foreign keys use NO_ACTION on purpose: no CASCADE, rows are archived instead of deleted
// (data-model.md section 3).

@Entity(tableName = "person")
data class PersonEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "color") val color: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
)

@Entity(
    tableName = "intake",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["person_id"], name = "ix_intake_person")],
)
data class IntakeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "person_id") val personId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "widget_alias") val widgetAlias: String? = null,
    @ColumnInfo(name = "slot_label") val slotLabel: SlotLabel? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
)

@Entity(
    tableName = "schedule_version",
    foreignKeys = [
        ForeignKey(
            entity = IntakeEntity::class,
            parentColumns = ["id"],
            childColumns = ["intake_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["intake_id", "effective_from"], name = "ix_schedule_intake_from")],
)
data class ScheduleVersionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "intake_id") val intakeId: String,
    @ColumnInfo(name = "type") val type: ScheduleType,
    /** e.g. `{}` for DAILY, `{"anchorWeekday":"MON"}` for WEEKLY. See [ScheduleParams]. */
    @ColumnInfo(name = "params_json") val paramsJson: String,
    @ColumnInfo(name = "late_policy") val latePolicy: LatePolicy,
    @ColumnInfo(name = "effective_from") val effectiveFrom: Long,
    /** NULL while this is the current version. */
    @ColumnInfo(name = "effective_to") val effectiveTo: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
)

@Entity(
    tableName = "dose_record",
    foreignKeys = [
        ForeignKey(
            entity = IntakeEntity::class,
            parentColumns = ["id"],
            childColumns = ["intake_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ScheduleVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_version_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        // At most one active judgment per window: SQLite treats NULLs as distinct in UNIQUE,
        // so voided rows (active = NULL) can repeat while active rows (active = 1) cannot.
        Index(value = ["intake_id", "period_key", "active"], name = "ux_dose_active", unique = true),
        Index(value = ["intake_id", "taken_at"], name = "ix_dose_taken"),
        // Covers the schedule_version foreign key (avoids full scans on parent updates).
        Index(value = ["schedule_version_id"], name = "ix_dose_schedule_version"),
    ],
)
data class DoseRecordEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "intake_id") val intakeId: String,
    @ColumnInfo(name = "schedule_version_id") val scheduleVersionId: String,
    @ColumnInfo(name = "period_key") val periodKey: String,
    @ColumnInfo(name = "kind") val kind: DoseKind,
    @ColumnInfo(name = "taken_at") val takenAt: Long,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "source") val source: DoseSource,
    /** [ACTIVE] or NULL (voided). Never 0: Room creates no CHECK constraint, writers guarantee it. */
    @ColumnInfo(name = "active") val active: Int? = ACTIVE,
    @ColumnInfo(name = "voided_at") val voidedAt: Long? = null,
    @ColumnInfo(name = "void_reason") val voidReason: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
) {
    val isActive: Boolean get() = active == ACTIVE

    companion object {
        const val ACTIVE: Int = 1
    }
}

@Entity(tableName = "medication")
data class MedicationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    /** Link to public drug data (extension stage). */
    @ColumnInfo(name = "product_code") val productCode: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
)

@Entity(
    tableName = "intake_medication",
    foreignKeys = [
        ForeignKey(
            entity = IntakeEntity::class,
            parentColumns = ["id"],
            childColumns = ["intake_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medication_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["intake_id"], name = "ix_intake_medication_intake"),
        Index(value = ["medication_id"], name = "ix_intake_medication_medication"),
    ],
)
data class IntakeMedicationEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "intake_id") val intakeId: String,
    @ColumnInfo(name = "medication_id") val medicationId: String,
    @ColumnInfo(name = "qty_per_take") val qtyPerTake: String? = null,
    @ColumnInfo(name = "effective_from") val effectiveFrom: Long,
    @ColumnInfo(name = "effective_to") val effectiveTo: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
)

@Entity(
    tableName = "dose_version",
    foreignKeys = [
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["id"],
            childColumns = ["medication_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["medication_id", "effective_from"], name = "ix_dose_version_medication_from")],
)
data class DoseVersionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "medication_id") val medicationId: String,
    @ColumnInfo(name = "dose_text") val doseText: String,
    @ColumnInfo(name = "effective_from") val effectiveFrom: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "version") val version: Int = 1,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
)

/** Key-value settings. No common metadata (data-model.md section 0). Keys: [SettingKeys]. */
@Entity(tableName = "setting")
data class SettingEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)
