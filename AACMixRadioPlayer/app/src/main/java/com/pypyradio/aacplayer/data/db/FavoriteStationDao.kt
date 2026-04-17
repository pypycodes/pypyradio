package com.pypyradio.aacplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteStationDao {
    @Query("SELECT * FROM favorite_stations ORDER BY orderIndex ASC, name ASC")
    fun observeAll(): Flow<List<FavoriteStationEntity>>
    
    @Query("UPDATE favorite_stations SET orderIndex = :newIndex WHERE stationuuid = :id")
    suspend fun updateOrder(id: String, newIndex: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_stations WHERE stationuuid = :id)")
    suspend fun isFavorite(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteStationEntity)

    @Query("DELETE FROM favorite_stations WHERE stationuuid = :id")
    suspend fun delete(id: String)
}
