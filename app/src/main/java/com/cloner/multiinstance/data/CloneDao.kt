package com.cloner.multiinstance.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.cloner.multiinstance.model.CloneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CloneDao {
    @Query("SELECT * FROM clones ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CloneEntity>>

    @Query("SELECT * FROM clones WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CloneEntity?

    @Query("SELECT COUNT(*) FROM clones WHERE sourcePackage = :pkg")
    suspend fun countForPackage(pkg: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(clone: CloneEntity)

    @Update
    suspend fun update(clone: CloneEntity)

    @Delete
    suspend fun delete(clone: CloneEntity)
}
