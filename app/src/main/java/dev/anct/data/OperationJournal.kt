package dev.anct.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow

class OperationJournal(
    private val dao: OperationDao,
) {
    fun recent(): Flow<List<OperationEntity>> = dao.observeRecent()

    suspend fun begin(instanceId: String?, kind: String): OperationEntity {
        val now = System.currentTimeMillis()
        val operation = OperationEntity(
            id = UUID.randomUUID().toString(),
            instanceId = instanceId,
            kind = kind,
            state = "RUNNING",
            detail = "",
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        dao.insert(operation)
        return operation
    }

    suspend fun finish(operation: OperationEntity, success: Boolean, detail: String) {
        dao.update(
            operation.copy(
                state = if (success) "SUCCEEDED" else "FAILED",
                detail = detail.take(8_000),
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }
}
