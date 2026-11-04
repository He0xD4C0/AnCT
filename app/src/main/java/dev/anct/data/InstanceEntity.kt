package dev.anct.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.anct.core.ActualState
import dev.anct.core.DesiredState
import dev.anct.core.RuntimeKind
import dev.anct.core.RuntimeStatus

@Entity(tableName = "instances")
data class InstanceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: RuntimeKind,
    val desiredState: DesiredState,
    val statusSnapshot: ActualState,
    val configJson: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "operations")
data class OperationEntity(
    @PrimaryKey val id: String,
    val instanceId: String?,
    val kind: String,
    val state: String,
    val detail: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class StoredInstance(
    val entity: InstanceEntity,
    val status: RuntimeStatus,
)

