package com.pypyradio.aacplayer.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorite_podcasts")
data class FavoritePodcastEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val description: String?,
    val imageUrl: String?,
    val feedUrl: String?,
    val genre: String?,
    val episodeCount: Int?,
    val source: String,
    val orderIndex: Int = 0
)

@Dao
interface FavoritePodcastDao {
    @Query("SELECT * FROM favorite_podcasts ORDER BY orderIndex ASC, title ASC")
    fun observeAll(): Flow<List<FavoritePodcastEntity>>
    
    @Query("UPDATE favorite_podcasts SET orderIndex = :newIndex WHERE id = :id")
    suspend fun updateOrder(id: String, newIndex: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_podcasts WHERE id = :id)")
    suspend fun isFavorite(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoritePodcastEntity)

    @Query("DELETE FROM favorite_podcasts WHERE id = :id")
    suspend fun delete(id: String)
}
