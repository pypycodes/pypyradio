package com.pypyradio.aacplayer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "station_status")
data class StationStatusEntity(
    @PrimaryKey val stationuuid: String,
    val lastPlayedTimestamp: Long = 0,
    val lastFailedTimestamp: Long = 0,
    val playCount: Int = 0,
    val failCount: Int = 0,
    val lastStatus: String = "unknown" // "working", "failed", "unknown"
)

@Dao
interface StationStatusDao {
    @Query("SELECT * FROM station_status")
    fun observeAll(): Flow<List<StationStatusEntity>>
    
    @Query("SELECT * FROM station_status WHERE stationuuid = :id")
    suspend fun getStatus(id: String): StationStatusEntity?
    
    @Query("SELECT stationuuid FROM station_status WHERE lastStatus = 'working'")
    suspend fun getWorkingStationIds(): List<String>
    
    @Query("SELECT stationuuid FROM station_status WHERE lastStatus = 'failed'")
    suspend fun getFailedStationIds(): List<String>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StationStatusEntity)
    
    @Query("UPDATE station_status SET lastStatus = 'working', lastPlayedTimestamp = :timestamp, playCount = playCount + 1 WHERE stationuuid = :id")
    suspend fun markWorking(id: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("UPDATE station_status SET lastStatus = 'failed', lastFailedTimestamp = :timestamp, failCount = failCount + 1 WHERE stationuuid = :id")
    suspend fun markFailed(id: String, timestamp: Long = System.currentTimeMillis())
    
    @Query("SELECT EXISTS(SELECT 1 FROM station_status WHERE stationuuid = :id)")
    suspend fun exists(id: String): Boolean
}
