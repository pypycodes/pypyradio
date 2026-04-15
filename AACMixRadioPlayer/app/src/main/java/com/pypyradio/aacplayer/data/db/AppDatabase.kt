package com.pypyradio.aacplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [FavoriteStationEntity::class, FavoritePodcastEntity::class, StationStatusEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoritesDao(): FavoriteStationDao
    abstract fun favoritePodcastDao(): FavoritePodcastDao
    abstract fun stationStatusDao(): StationStatusDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS favorite_podcasts (
                        id TEXT PRIMARY KEY NOT NULL,
                        title TEXT NOT NULL,
                        author TEXT,
                        description TEXT,
                        imageUrl TEXT,
                        feedUrl TEXT,
                        genre TEXT,
                        episodeCount INTEGER,
                        source TEXT NOT NULL
                    )
                """)
            }
        }
        
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS station_status (
                        stationuuid TEXT PRIMARY KEY NOT NULL,
                        lastPlayedTimestamp INTEGER NOT NULL DEFAULT 0,
                        lastFailedTimestamp INTEGER NOT NULL DEFAULT 0,
                        playCount INTEGER NOT NULL DEFAULT 0,
                        failCount INTEGER NOT NULL DEFAULT 0,
                        lastStatus TEXT NOT NULL DEFAULT 'unknown'
                    )
                """)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add indices to favorite_stations
                db.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_stations_stationuuid ON favorite_stations(stationuuid)")
                
                // Add indices to station_status
                db.execSQL("CREATE INDEX IF NOT EXISTS index_station_status_stationuuid ON station_status(stationuuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_station_status_lastStatus ON station_status(lastStatus)")
            }
        }
        
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aac_radio.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
    }
}
