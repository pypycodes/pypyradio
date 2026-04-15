package com.pypyradio.aacplayer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorite_stations",
    indices = [Index(value = ["stationuuid"])]
)
data class FavoriteStationEntity(
    @PrimaryKey val stationuuid: String,
    val name: String,
    val urlResolved: String,
    val favicon: String?,
    val countryCode: String?,
    val codec: String?,
    val bitrate: Int?
)
