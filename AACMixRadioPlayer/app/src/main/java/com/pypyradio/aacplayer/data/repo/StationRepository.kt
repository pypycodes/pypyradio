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

    /**
     * Get filtered AAC stations with verified status, English/India preference, and quality sorting
     * This is the main method used by Android Auto for high-quality stations
     */
    suspend fun getEnglishStations(limit: Int = 100): List<Station> {
        val api = RadioBrowserClient.api()
        // Fetch more stations to account for filtering
        val allStations = mutableListOf<StationDto>()
        
        try {
            // Get top voted stations (prioritized by clickcount/popularity)
            val topStations = api.topVoted(limit * 3)
            allStations.addAll(topStations)
        } catch (e: Exception) {
            // Fallback to search if topVoted fails
            val searchResults = api.searchStations(
                name = null,
                codec = "aac",
                language = "english",
                hideBroken = true,
                limit = limit * 3,
                order = "clickcount",
                reverse = true
            )
            allStations.addAll(searchResults)
        }
        
        return allStations
            // Strict verification: only stations verified as working
            .filter { it.lastCheckOk == 1 }
            // Must have valid URL
            .filter { !it.urlResolved.isNullOrBlank() || !it.url.isNullOrBlank() }
            // AAC/AAC+ codec only (strict)
            .filter { it.codec?.lowercase() in listOf("aac", "aac+", "he-aac", "xheaac") }
            // English language only
            .filter { it.language?.lowercase() == "english" }
            // Convert to domain model
            .map { it.toDomain() }
            // Sort by quality (bitrate, then popularity)
            .sortedWith(compareByDescending<Station> { it.bitrate ?: 0 }
                .thenByDescending { it.tags?.contains("popular") == true }
                .thenBy { it.name })
            // Remove duplicates by name, keeping highest bitrate
            .deduplicateByHighestBitrate()
            // Take the requested limit
            .take(limit)
    }

    suspend fun getIndianStations(limit: Int = 100): List<Station> {
        val api = RadioBrowserClient.api()
        // Fetch more stations to account for filtering
        val allStations = mutableListOf<StationDto>()
        
        try {
            // Get stations from India
            val indianStations = api.searchStations(
                name = null,
                codec = "aac",
                countryCode = "in",
                hideBroken = true,
                limit = limit * 3,
                order = "clickcount",
                reverse = true
            )
            allStations.addAll(indianStations)
        } catch (e: Exception) {
            // Fallback to search by Indian languages
            val indianLanguages = listOf("hindi", "bengali", "tamil", "telugu", "marathi", "gujarati", "punjabi")
            indianLanguages.forEach { language ->
                try {
                    val langStations = api.searchStations(
                        name = null,
                        codec = "aac",
                        language = language,
                        hideBroken = true,
                        limit = 50,
                        order = "clickcount",
                        reverse = true
                    )
                    allStations.addAll(langStations)
                } catch (e: Exception) {
                    // Continue with next language
                }
            }
        }
        
        return allStations
            // Strict verification: only stations verified as working
            .filter { it.lastCheckOk == 1 }
            // Must have valid URL
            .filter { !it.urlResolved.isNullOrBlank() || !it.url.isNullOrBlank() }
            // AAC/AAC+ codec only (strict)
            .filter { it.codec?.lowercase() in listOf("aac", "aac+", "he-aac", "xheaac") }
            // Indian stations (country code or Indian languages)
            .filter { station ->
                val countryCode = station.countryCode?.lowercase()
                val language = station.language?.lowercase()
                val tags = station.tags?.lowercase()
                
                // Include stations from India
                countryCode == "in" ||
                // Include Indian languages
                language in listOf("hindi", "bengali", "tamil", "telugu", "marathi", "gujarati", "punjabi") ||
                // Include stations tagged with India
                tags?.contains("india") == true
            }
            // Convert to domain model
            .map { it.toDomain() }
            // Sort by quality (bitrate, then popularity)
            .sortedWith(compareByDescending<Station> { it.bitrate ?: 0 }
                .thenByDescending { it.tags?.contains("popular") == true }
                .thenBy { it.name })
            // Remove duplicates by name, keeping highest bitrate
            .deduplicateByHighestBitrate()
            // Take the requested limit
            .take(limit)
    }

    suspend fun getFilteredAacStations(limit: Int = 500): List<Station> {
        val api = RadioBrowserClient.api()
        
        // Fetch more stations to account for filtering
        val allStations = mutableListOf<StationDto>()
        
        try {
            // Get top voted stations (prioritized by clickcount/popularity)
            val topStations = api.topVoted(limit * 3)
            allStations.addAll(topStations)
        } catch (e: Exception) {
            // Fallback to search if topVoted fails
            val searchResults = api.searchStations(
                name = null,
                codec = "aac",
                hideBroken = true,
                limit = limit * 3,
                order = "clickcount",
                reverse = true
            )
            allStations.addAll(searchResults)
        }
        
        return allStations
            // Strict verification: only stations verified as working
            .filter { it.lastCheckOk == 1 }
            // Must have valid URL
            .filter { !it.urlResolved.isNullOrBlank() || !it.url.isNullOrBlank() }
            // AAC/AAC+ codec preference (prioritize AAC over MP3)
            .filter { it.codec?.lowercase() in listOf("aac", "aac+", "he-aac", "xheaac") }
            // Language and country filtering (English and India)
            .filter { station ->
                val language = station.language?.lowercase()
                val countryCode = station.countryCode?.lowercase()
                val tags = station.tags?.lowercase()
                
                // Include English stations from any country
                language == "english" ||
                // Include Indian stations (any language)
                countryCode == "in" ||
                // Include stations tagged with India
                tags?.contains("india") == true
            }
            // Convert to domain model
            .map { it.toDomain() }
            // Sort by quality (bitrate, then clickcount proxy)
            .sortedWith(compareByDescending<Station> { it.bitrate ?: 0 }
                .thenByDescending { it.tags?.contains("popular") == true }
                .thenBy { it.name })
            // Remove duplicates by name, keeping highest bitrate
            .deduplicateByHighestBitrate()
            // Take the requested limit
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
    
    suspend fun searchByCountryCode(countryCode: String, limit: Int = 300): List<Station> {
        val api = RadioBrowserClient.api()
        val result = api.searchStations(
            name = null,
            tag = null,
            country = null,
            countryCode = countryCode,
            language = null,
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

    suspend fun searchByLanguage(language: String, limit: Int = 500): List<Station> {
        val api = RadioBrowserClient.api()
        val result = api.searchStations(
            name = null,
            tag = null,
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

    suspend fun searchByLanguageAndTag(language: String, tag: String, limit: Int = 300): List<Station> {
        val api = RadioBrowserClient.api()
        val result = api.searchStations(
            name = null,
            tag = tag,
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

    /**
     * Get recommended high-quality stations that are always reachable
     */
    suspend fun getRecommendedStations(limit: Int = 20): List<Station> {
        return getFilteredAacStations(limit * 2)
            .filter { station ->
                // Additional quality filters for recommendations
                val bitrate = station.bitrate ?: 0
                val name = station.name.lowercase()
                val tags = station.tags?.lowercase() ?: ""
                
                // Prefer higher bitrate stations (128kbps+)
                bitrate >= 128 &&
                // Exclude test stations
                !name.contains("test") &&
                // Prefer established stations
                (tags.contains("bbc") || tags.contains("npr") || 
                 tags.contains("mirchi") || tags.contains("radio") ||
                 name.contains("bbc") || name.contains("npr") || 
                 name.contains("mirchi") || name.contains("times"))
            }
            .take(limit)
    }
}
