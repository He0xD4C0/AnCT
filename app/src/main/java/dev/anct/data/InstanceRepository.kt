package dev.anct.data

import dev.anct.core.ActualState
import dev.anct.core.DesiredState
import dev.anct.core.RuntimeSpec
import dev.anct.core.RuntimeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class InstanceRepository(
    private val database: AppDatabase,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val instances: Flow<List<StoredInstance>> = database.instanceDao().observeAll().map { rows ->
        rows.map { row ->
            StoredInstance(
                entity = row,
                status = RuntimeStatus(row.statusSnapshot),
            )
        }
    }

    suspend fun getAll(): List<StoredInstance> =
        database.instanceDao().getAll().map { StoredInstance(it, RuntimeStatus(it.statusSnapshot)) }

    suspend fun get(id: String): StoredInstance? = database.instanceDao().get(id)?.let {
        StoredInstance(it, RuntimeStatus(it.statusSnapshot))
    }

    suspend fun getByName(name: String): StoredInstance? = database.instanceDao().getByName(name)?.let {
        StoredInstance(it, RuntimeStatus(it.statusSnapshot))
    }

    suspend fun upsert(spec: RuntimeSpec, desiredState: DesiredState = DesiredState.STOPPED) {
        val now = System.currentTimeMillis()
        val existing = database.instanceDao().get(spec.id)
        val entity = InstanceEntity(
            id = spec.id,
            name = spec.name,
            kind = spec.kind,
            desiredState = desiredState,
            statusSnapshot = existing?.statusSnapshot ?: ActualState.Unknown,
            configJson = json.encodeToString(spec),
            createdAtEpochMs = existing?.createdAtEpochMs ?: spec.createdAtEpochMs,
            updatedAtEpochMs = now,
        )
        if (existing == null) database.instanceDao().insert(entity) else database.instanceDao().update(entity)
    }

    suspend fun setDesired(id: String, desiredState: DesiredState) {
        val existing = database.instanceDao().get(id) ?: return
        database.instanceDao().update(
            existing.copy(desiredState = desiredState, updatedAtEpochMs = System.currentTimeMillis()),
        )
    }

    suspend fun setStatus(id: String, status: RuntimeStatus) {
        val existing = database.instanceDao().get(id) ?: return
        database.instanceDao().update(
            existing.copy(
                statusSnapshot = status.actualState,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun delete(id: String) = database.instanceDao().delete(id)

    fun decode(entity: InstanceEntity): RuntimeSpec = json.decodeFromString(entity.configJson)
}

