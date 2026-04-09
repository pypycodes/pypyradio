package com.pypyradio.aacplayer.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pypyradio.aacplayer.data.db.AppDatabase
import com.pypyradio.aacplayer.data.db.StationStatusEntity
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.data.repo.StationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// Simplified - no more filter enum needed
enum class StationFilter { ALL, WORKING_ONLY, HIDE_FAILED }

data class UiState(
    val loading: Boolean = false,
    val error: String? = null,
    val stations: List<Station> = emptyList(),
    val query: String = "",
    val failedStationIds: Set<String> = emptySet(),
    val workingStationIds: Set<String> = emptySet(),
    val playbackError: String? = null,
    val filter: StationFilter = StationFilter.HIDE_FAILED  // Default to hide failed
)

class StationsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val repo = StationRepository(db.favoritesDao())
    private val statusDao = db.stationStatusDao()

    private val _browse = MutableStateFlow(UiState(loading = true))
    val browse: StateFlow<UiState> = _browse

    val favorites = repo.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { 
        searchByLanguage("english") // Default to English stations
        loadStationStatuses()
        startPeriodicHealthCheck()
    }
    
    /**
     * Starts periodic health check that runs every 5 minutes
     * Re-checks failed stations to see if they're back online
     * Also checks a batch of unchecked stations
     */
    private fun startPeriodicHealthCheck() = viewModelScope.launch(Dispatchers.IO) {
        while (true) {
            delay(5 * 60 * 1000L) // Wait 5 minutes
            
            // Re-check some failed stations (they might be back online)
            val failedToRecheck = _browse.value.failedStationIds.take(10)
            failedToRecheck.forEach { stationId ->
                val station = _browse.value.stations.find { it.stationuuid == stationId }
                if (station != null) {
                    try {
                        val isReachable = checkUrlReachable(station.urlResolved)
                        if (isReachable) {
                            markStationWorkingSilent(stationId)
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                    delay(300)
                }
            }
            
            // Also check some unchecked stations
            val unchecked = _browse.value.stations.filter { station ->
                !_browse.value.workingStationIds.contains(station.stationuuid) &&
                !_browse.value.failedStationIds.contains(station.stationuuid)
            }.take(20)
            
            unchecked.forEach { station ->
                try {
                    val isReachable = checkUrlReachable(station.urlResolved)
                    if (isReachable) {
                        markStationWorkingSilent(station.stationuuid)
                    } else {
                        markStationFailedSilent(station.stationuuid)
                    }
                } catch (e: Exception) {
                    // Ignore
                }
                delay(300)
            }
        }
    }
    
    private fun loadStationStatuses() = viewModelScope.launch {
        val working = statusDao.getWorkingStationIds().toSet()
        val failed = statusDao.getFailedStationIds().toSet()
        _browse.value = _browse.value.copy(
            workingStationIds = working,
            failedStationIds = failed
        )
    }

    fun setQuery(q: String) { _browse.value = _browse.value.copy(query = q) }

    // Helper to update stations and trigger background health check
    private fun updateStations(stations: List<Station>) {
        _browse.value = _browse.value.copy(loading = false, stations = stations, error = null)
        // Trigger silent background health check
        runBackgroundHealthCheck(stations)
    }

    fun loadTop() = viewModelScope.launch {
        _browse.value = _browse.value.copy(loading = true, error = null)
        runCatching { repo.topVotedAac(500) }
            .onSuccess { updateStations(it) }
            .onFailure { _browse.value = _browse.value.copy(loading = false, stations = emptyList(), error = it.message ?: "Failed") }
    }

    fun search() = viewModelScope.launch {
        val q = _browse.value.query.trim()
        if (q.isEmpty()) {
            loadTop()
            return@launch
        }
        _browse.value = _browse.value.copy(loading = true, error = null)
        runCatching { repo.searchAac(q, 200) }
            .onSuccess { updateStations(it) }
            .onFailure { _browse.value = _browse.value.copy(loading = false, stations = emptyList(), error = it.message ?: "Failed") }
    }

    fun searchByTag(tag: String) = viewModelScope.launch {
        _browse.value = _browse.value.copy(loading = true, error = null)
        runCatching { repo.searchByTag(tag, 200) }
            .onSuccess { updateStations(it) }
            .onFailure { _browse.value = _browse.value.copy(loading = false, stations = emptyList(), error = it.message ?: "Failed") }
    }

    fun searchByCountryAndLanguage(country: String, language: String?) = viewModelScope.launch {
        _browse.value = _browse.value.copy(loading = true, error = null)
        runCatching { repo.searchByCountryAndLanguage(country, language, 1500) }
            .onSuccess { updateStations(it) }
            .onFailure { _browse.value = _browse.value.copy(loading = false, stations = emptyList(), error = it.message ?: "Failed") }
    }

    fun searchNewsByLanguage(language: String?) = viewModelScope.launch {
        _browse.value = _browse.value.copy(loading = true, error = null)
        runCatching { repo.searchNewsByLanguage(language, 200) }
            .onSuccess { updateStations(it) }
            .onFailure { _browse.value = _browse.value.copy(loading = false, stations = emptyList(), error = it.message ?: "Failed") }
    }

    fun searchByLanguage(language: String) = viewModelScope.launch {
        _browse.value = _browse.value.copy(loading = true, error = null)
        runCatching { repo.searchByLanguage(language, 500) }
            .onSuccess { updateStations(it) }
            .onFailure { _browse.value = _browse.value.copy(loading = false, stations = emptyList(), error = it.message ?: "Failed") }
    }

    fun searchByLanguageAndTag(language: String, tag: String) = viewModelScope.launch {
        _browse.value = _browse.value.copy(loading = true, error = null)
        runCatching { repo.searchByLanguageAndTag(language, tag, 300) }
            .onSuccess { updateStations(it) }
            .onFailure { _browse.value = _browse.value.copy(loading = false, stations = emptyList(), error = it.message ?: "Failed") }
    }

    fun toggleFavorite(station: Station) = viewModelScope.launch {
        repo.toggleFavorite(station)
    }

    fun markStationFailed(stationId: String, errorMessage: String) = viewModelScope.launch {
        _browse.value = _browse.value.copy(
            failedStationIds = _browse.value.failedStationIds + stationId,
            workingStationIds = _browse.value.workingStationIds - stationId,
            playbackError = errorMessage
        )
        // Persist to database
        if (!statusDao.exists(stationId)) {
            statusDao.upsert(StationStatusEntity(stationuuid = stationId, lastStatus = "failed", failCount = 1, lastFailedTimestamp = System.currentTimeMillis()))
        } else {
            statusDao.markFailed(stationId)
        }
    }
    
    fun markStationWorking(stationId: String) = viewModelScope.launch {
        _browse.value = _browse.value.copy(
            workingStationIds = _browse.value.workingStationIds + stationId,
            failedStationIds = _browse.value.failedStationIds - stationId
        )
        // Persist to database
        if (!statusDao.exists(stationId)) {
            statusDao.upsert(StationStatusEntity(stationuuid = stationId, lastStatus = "working", playCount = 1, lastPlayedTimestamp = System.currentTimeMillis()))
        } else {
            statusDao.markWorking(stationId)
        }
    }

    fun clearPlaybackError() {
        _browse.value = _browse.value.copy(playbackError = null)
    }
    
    fun setFilter(filter: StationFilter) {
        _browse.value = _browse.value.copy(filter = filter)
    }
    
    fun getFilteredStations(): List<Station> {
        val state = _browse.value
        return when (state.filter) {
            StationFilter.ALL -> state.stations
            StationFilter.WORKING_ONLY -> state.stations.filter { state.workingStationIds.contains(it.stationuuid) }
            StationFilter.HIDE_FAILED -> state.stations.filter { !state.failedStationIds.contains(it.stationuuid) }
        }
    }
    
    // Flag to prevent multiple concurrent health checks
    private var isCheckingHealth = false
    
    /**
     * Background health checker - runs silently without UI notifications
     * Checks station URLs to see if they're reachable
     * Called automatically when stations are loaded
     */
    private fun runBackgroundHealthCheck(stations: List<Station>) = viewModelScope.launch(Dispatchers.IO) {
        if (isCheckingHealth) return@launch
        isCheckingHealth = true
        
        try {
            // Only check stations we haven't checked yet
            val uncheckedStations = stations.filter { station ->
                val id = station.stationuuid
                !_browse.value.workingStationIds.contains(id) && !_browse.value.failedStationIds.contains(id)
            }.take(30) // Check max 30 at a time to be gentle on network
            
            uncheckedStations.forEach { station ->
                try {
                    val isReachable = checkUrlReachable(station.urlResolved)
                    if (isReachable) {
                        markStationWorkingSilent(station.stationuuid)
                    } else {
                        markStationFailedSilent(station.stationuuid)
                    }
                } catch (e: Exception) {
                    // Silently ignore individual check failures
                }
                delay(200) // Gentle delay between checks
            }
        } finally {
            isCheckingHealth = false
        }
    }
    
    // Silent versions that don't trigger UI updates for playback error
    private fun markStationWorkingSilent(stationId: String) = viewModelScope.launch {
        _browse.value = _browse.value.copy(
            workingStationIds = _browse.value.workingStationIds + stationId,
            failedStationIds = _browse.value.failedStationIds - stationId
        )
        if (!statusDao.exists(stationId)) {
            statusDao.upsert(StationStatusEntity(stationuuid = stationId, lastStatus = "working", playCount = 1, lastPlayedTimestamp = System.currentTimeMillis()))
        } else {
            statusDao.markWorking(stationId)
        }
    }
    
    private fun markStationFailedSilent(stationId: String) = viewModelScope.launch {
        _browse.value = _browse.value.copy(
            failedStationIds = _browse.value.failedStationIds + stationId,
            workingStationIds = _browse.value.workingStationIds - stationId
        )
        if (!statusDao.exists(stationId)) {
            statusDao.upsert(StationStatusEntity(stationuuid = stationId, lastStatus = "failed", failCount = 1, lastFailedTimestamp = System.currentTimeMillis()))
        } else {
            statusDao.markFailed(stationId)
        }
    }
    
    /**
     * Quick URL reachability check - just checks if we can connect
     */
    private suspend fun checkUrlReachable(url: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext false
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "HEAD"
            connection.instanceFollowRedirects = true
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..399
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Clear failed status for a station (for retry)
     */
    fun clearFailedStatus(stationId: String) = viewModelScope.launch {
        _browse.value = _browse.value.copy(
            failedStationIds = _browse.value.failedStationIds - stationId
        )
        statusDao.clearStatus(stationId)
    }
    
    /**
     * Clear all failed statuses (reset)
     */
    fun clearAllFailedStatuses() = viewModelScope.launch {
        _browse.value = _browse.value.copy(failedStationIds = emptySet())
        statusDao.clearAllFailed()
    }
}
