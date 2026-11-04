package dev.anct.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InstanceDao {
    @Query("SELECT * FROM instances ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<InstanceEntity>>

    @Query("SELECT * FROM instances")
    suspend fun getAll(): List<InstanceEntity>

    @Query("SELECT * FROM instances WHERE id = :id")
    suspend fun get(id: String): InstanceEntity?

    @Query("SELECT * FROM instances WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): InstanceEntity?

    @Insert
    suspend fun insert(entity: InstanceEntity)

    @Update
    suspend fun update(entity: InstanceEntity)

    @Query("DELETE FROM instances WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface OperationDao {
    @Insert
    suspend fun insert(entity: OperationEntity)

    @Update
    suspend fun update(entity: OperationEntity)

    @Query("SELECT * FROM operations ORDER BY createdAtEpochMs DESC LIMIT 100")
    fun observeRecent(): Flow<List<OperationEntity>>
}

