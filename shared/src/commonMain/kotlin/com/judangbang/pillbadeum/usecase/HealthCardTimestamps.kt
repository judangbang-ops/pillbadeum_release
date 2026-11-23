package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.DidITakeDatabase
import com.judangbang.pillbadeum.data.HealthCardEntity
import com.judangbang.pillbadeum.data.SettingKeys
import kotlin.time.Clock

enum class StaleCardNotice { CHANGED, RESTORED, EMPTY }

internal suspend fun DidITakeDatabase.healthCardRestoredAt(): Long? =
    settingDao().get(SettingKeys.RESTORED_AT)?.toLongOrNull()

private fun HealthCardEntity.lastAcknowledgedAt(): Long? =
    emergencyCopiedAt?.let { maxOf(it, staleNoticeClosedAt ?: 0L) }

internal fun HealthCardEntity.hasStaleNotice(restoredAt: Long?): Boolean =
    lastAcknowledgedAt()?.let { maxOf(editedAt, restoredAt ?: 0L) > it } ?: false

internal fun HealthCardEntity.staleNotice(restoredAt: Long?, empty: Boolean): StaleCardNotice? {
    if (!hasStaleNotice(restoredAt)) return null
    if (empty) return StaleCardNotice.EMPTY
    return if ((restoredAt ?: 0L) > checkNotNull(lastAcknowledgedAt())) {
        StaleCardNotice.RESTORED
    } else {
        StaleCardNotice.CHANGED
    }
}

internal fun newHealthCard(personId: String, editedAt: Long, now: Long, deviceId: String) =
    HealthCardEntity(personId = personId, editedAt = editedAt, createdAt = now, updatedAt = now, deviceId = deviceId)

/** Call inside the transaction that made the change (SPEC-049). */
internal suspend fun DidITakeDatabase.markHealthCardEdited(personId: String, now: Long, deviceId: String) {
    val cards = healthCardDao()
    val card = cards.findByPerson(personId)
    cards.upsert(
        card?.copy(editedAt = now, updatedAt = now, version = card.version + 1, deviceId = deviceId)
            ?: newHealthCard(personId, editedAt = now, now = now, deviceId = deviceId),
    )
}

internal suspend fun DidITakeDatabase.markHealthCardEdited(personIds: Set<String>, now: Long, deviceId: String) {
    for (personId in personIds) markHealthCardEdited(personId, now, deviceId)
}

enum class EmergencyCopy { ALL, LINE }

enum class EmergencyCopyRecord { RECORDED, NOTICE_REMAINS, GONE }

class HealthCardNoticeActions(
    private val db: DidITakeDatabase,
    private val clock: Clock,
    private val deviceId: String,
) {
    /** Call after putting the text on the clipboard successfully. The S-2 read and write share one transaction. */
    suspend fun recordCopy(personId: String, copy: EmergencyCopy): EmergencyCopyRecord =
        db.useWriterConnection { writer ->
            writer.immediateTransaction {
                val cards = db.healthCardDao()
                val now = clock.now().toEpochMilliseconds()
                val existing = cards.findByPerson(personId)
                val card = existing ?: run {
                    val person = db.personDao().findById(personId) ?: return@immediateTransaction EmergencyCopyRecord.GONE
                    val intakes = db.intakeDao().listActiveOfPerson(personId)
                    if (intakes.isEmpty()) return@immediateTransaction EmergencyCopyRecord.GONE
                    newHealthCard(personId, summaryEditedAt(person, intakes), now, deviceId)
                }
                if (copy == EmergencyCopy.LINE && card.hasStaleNotice(db.healthCardRestoredAt())) {
                    return@immediateTransaction EmergencyCopyRecord.NOTICE_REMAINS
                }
                cards.upsert(
                    card.copy(
                        emergencyCopiedAt = now,
                        updatedAt = now,
                        version = existing?.let { it.version + 1 } ?: card.version,
                        deviceId = deviceId,
                    ),
                )
                EmergencyCopyRecord.RECORDED
            }
        }

    /** Both [다 넣었어요] and the empty notice's [닫기] acknowledge the notice. */
    suspend fun closeNotice(personId: String): Boolean =
        db.useWriterConnection { writer ->
            writer.immediateTransaction {
                val cards = db.healthCardDao()
                val card = cards.findByPerson(personId) ?: return@immediateTransaction false
                if (!card.hasStaleNotice(db.healthCardRestoredAt())) return@immediateTransaction false
                val now = clock.now().toEpochMilliseconds()
                cards.upsert(
                    card.copy(staleNoticeClosedAt = now, updatedAt = now, version = card.version + 1, deviceId = deviceId),
                )
                true
            }
        }
}
