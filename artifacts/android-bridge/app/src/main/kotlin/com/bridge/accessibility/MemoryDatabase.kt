package com.bridge.accessibility

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val embedding: String, // Stored as JSON or comma-separated string
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(memory: Memory)

    @Query("SELECT * FROM memories ORDER BY timestamp DESC")
    fun getAll(): Flow<List<Memory>>

    @Query("SELECT * FROM memories")
    suspend fun getAllSync(): List<Memory>
}

@Database(entities = [Memory::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
}
