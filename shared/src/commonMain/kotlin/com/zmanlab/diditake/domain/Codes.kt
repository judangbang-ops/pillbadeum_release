package com.zmanlab.diditake.domain

// Fixed string codes (data-model.md section 0). Room stores these enums as TEXT using the constant
// name, so the constant name IS the persisted code: never rename or reorder-by-ordinal them.

/** dose_record.kind */
enum class DoseKind { TAKEN, SKIPPED }

/** dose_record.source */
enum class DoseSource { WIDGET, APP, NOTIFICATION }

/** schedule_version.type (MVP values; extensions are added as new constants). */
enum class ScheduleType { DAILY, WEEKLY }

/** schedule_version.late_policy: how a late weekly dose moves the anchor (extension behaviour). */
enum class LatePolicy { ASK, MOVE, KEEP }

/** intake.slot_label: display and ordering only. */
enum class SlotLabel { MORNING, NOON, EVENING, BEDTIME }
