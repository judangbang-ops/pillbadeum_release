package com.judangbang.pillbadeum.usecase

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.judangbang.pillbadeum.data.CareContactKind
import com.judangbang.pillbadeum.data.DidITakeDatabase
import kotlin.time.Clock

/** Zero means the deletion was cancelled or the entry was already gone. */
class DeleteCardEntries(
    private val db: DidITakeDatabase,
    private val clock: Clock,
    private val deviceId: String,
) {
    suspend fun one(personId: String, kind: CardEntryKind, id: String, confirmed: Boolean): Int = delete(personId, confirmed) {
        when (kind) {
            CardEntryKind.CONDITION -> db.conditionDao().delete(personId, id)
            CardEntryKind.PROCEDURE -> db.procedureHistoryDao().delete(personId, id)
            CardEntryKind.ALLERGY -> db.allergyDao().delete(personId, id)
            CardEntryKind.IMPLANTED_DEVICE -> db.implantedDeviceDao().delete(personId, id)
            CardEntryKind.CARE_PROVIDER -> db.careContactDao().deleteOfKind(personId, id, CareContactKind.CARE_PROVIDER)
            CardEntryKind.GUARDIAN -> db.careContactDao().deleteOfKind(personId, id, CareContactKind.GUARDIAN)
        }
    }

    suspend fun all(personId: String, confirmed: Boolean): Int = delete(personId, confirmed) {
        db.conditionDao().deleteByPerson(personId) +
            db.procedureHistoryDao().deleteByPerson(personId) +
            db.allergyDao().deleteByPerson(personId) +
            db.implantedDeviceDao().deleteByPerson(personId) +
            db.careContactDao().deleteByPerson(personId)
    }

    private suspend fun delete(personId: String, confirmed: Boolean, remove: suspend () -> Int): Int {
        if (!confirmed) return 0
        return db.useWriterConnection { writer ->
            writer.immediateTransaction {
                val count = remove()
                if (count > 0) db.markHealthCardEdited(personId, clock.now().toEpochMilliseconds(), deviceId)
                count
            }
        }
    }
}
