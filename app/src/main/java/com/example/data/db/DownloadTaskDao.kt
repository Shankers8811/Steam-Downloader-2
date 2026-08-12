package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY timestamp DESC")
    fun getAllTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE status IN ('DOWNLOADING', 'PAUSED') ORDER BY timestamp DESC LIMIT 1")
    fun getActiveTask(): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getTaskById(id: Int): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity): Long

    @Update
    suspend fun updateTask(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTask(id: Int)

    @Query("DELETE FROM download_tasks")
    suspend fun clearAll()
}
