package com.pypyradio.aacplayer.data.repo

import com.pypyradio.aacplayer.data.api.RadioBrowserClient
import com.pypyradio.aacplayer.data.api.StationDto
import com.pypyradio.aacplayer.data.db.FavoriteStationDao
import com.pypyradio.aacplayer.data.db.FavoriteStationEntity
import com.pypyradio.aacplayer.data.model.Station
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StationRepository(private val favoritesDao: FavoriteStationDao) {

    fun observeFavorites(): Flow<List<Station>> =
        favoritesDao.observeAll().map { list ->
            list.map { e ->
                Station(
                    stationuuid = e.stationuuid,
                    name = e.name,
                    urlResolved = e.urlResolved,
                    homepage = null,
                    favicon = e.favicon,
                    tags = null,
                    countryCode = e.countryCode,
                    language = null,
                    codec = e.codec,
                    bitrate = e.bitrate,
                    lastCheckOk = 1
                )
            }
        }

    suspend fun topVotedAac(limit: Int = 500): List<Station> {
        val api = RadioBrowserClient.api()
        val all = api.topVoted(limit * 2) // Fetch more to account for filtering and deduplication
        return all
            .filter { it.lastCheckOk == null || it.lastCheckOk == 1 }
            .filter { !it.urlResolved.isNullOrBlank() || !it.url.isNullOrBlank() }
            .map { it.toDomain() }
            .deduplicateByHighestBitrate()
            .take(limit)
    }

    suspend fun searchAac(name: String, limit: Int = 200): List<Station> {
        val api = RadioBrowserClient.api()
        val result = api.searchStations(
            name = name,
            codec = null,
            hideBroken = true,
            limit = limit * 2,
            order = "votes",
            reverse = true
        )
        return result
            .filter { !it.urlResolved.isNullOrBlank() || !it.url.isNullOrBlank() }
            .map { it.toDomain() }
            .deduplicateByHighestBitrate()
            .take(limit)
    }

    suspend fun searchByTag(tag: String, limit: Int = 200): List<Station> {
        val api = RadioBrowserClient.api()
        val result = api.searchStations(
            name = null,
            tag = tag,
            codec = null,
            hideBroken = true,
            limit = limit * 2,
            order = "votes",
            reverse = true
        )
        return result
            .filter { !it.urlResolved.isNullOrBlank() || !it.url.isNullOrBlank() }
            .map { it.toDomain() }
            .deduplicateByHighestBitrate()
            .take(limit)
    }

    suspend fun searchByCountryAndLanguage(country: String, language: String?, limit: Int = 1500): List<Station> {
        val api = RadioBrowserClient.api()
        val result = api.searchStations(
            name = null,
            tag = null,
            country = country.ifBlank { null },
            language = language,
            codec = null,
            hideBroken = true,
            limit = limit,
            order = "votes",
            reverse = true
        )
        return result
            .filter { !it.urlResolved.isNullOrBlank() || !it.url.isNullOrBlank() }
            .map { it.toDomain() }
            .deduplicateByHighestBitrate()
    }

    suspend fun searchNewsByLanguage(language: String?, limit: Int = 200): List<Station> {
        val api = RadioBrowserClient.api()
        val result = api.searchStations(
            name = null,
            tag = "news",
            country = null,
            language = language,
            codec = null,
            hideBroken = true,
            limit = limit * 2,
            order = "votes",
            reverse = true
        )
        return result
            .filter { !it.urlResolved.isNullOrBlank() || !it.url.isNullOrBlank() }
            .map { it.toDomain() }
            .deduplicateByHighestBitrate()
            .take(limit)
    }

    suspend fun toggleFavorite(station: Station) {
        val isFav = favoritesDao.isFavorite(station.stationuuid)
        if (isFav) {
            favoritesDao.delete(station.stationuuid)
        } else {
            favoritesDao.upsert(
                FavoriteStationEntity(
                    stationuuid = station.stationuuid,
                    name = station.name,
                    urlResolved = station.urlResolved,
                    favicon = station.favicon,
                    countryCode = station.countryCode,
                    codec = station.codec,
                    bitrate = station.bitrate
                )
            )
        }
    }

    suspend fun pingClick(stationuuid: String) {
        runCatching { RadioBrowserClient.api().pingClick(stationuuid) }
    }

    private fun StationDto.toDomain(): Station =
        Station(
            stationuuid = stationuuid,
            name = name,
            urlResolved = urlResolved ?: url ?: "",
            homepage = homepage,
            favicon = favicon,
            tags = tags,
            countryCode = countryCode,
            language = language,
            codec = codec,
            bitrate = bitrate,
            lastCheckOk = lastCheckOk
        )
    
    /**
     * Deduplicate stations by name, keeping only the one with highest bitrate
     */
    private fun List<Station>.deduplicateByHighestBitrate(): List<Station> {
        return groupBy { it.name.lowercase().trim() }
            .map { (_, stations) -> 
                stations.maxByOrNull { it.bitrate ?: 0 } ?: stations.first()
            }
    }
}
