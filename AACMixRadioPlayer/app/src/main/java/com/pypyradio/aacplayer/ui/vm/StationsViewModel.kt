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
    val filter: StationFilter = StationFilter.ALL,
    val isFilteringStations: Boolean = false  // true while background health check is running
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
        // Don't load stations here — BrowseScreen handles initial loading via its
        // LaunchedEffect to avoid race conditions between competing station lists.
        loadStationStatuses()
        startPeriodicHealthCheck()
    }
    
    /**
     * Continuous background health checker
     * - Checks ALL unchecked stations using a HEAD request
     * - Stations that fail are persisted to DB and never shown again
     * - Previously failed stations are NOT re-checked (HEAD != stream playable)
     */
    private fun startPeriodicHealthCheck() = viewModelScope.launch(Dispatchers.IO) {
        // Initial delay before starting
        delay(10_000L) // Wait 10 seconds after app start
        
        while (true) {
            // Get all unchecked stations
            val uncheckedStations = _browse.value.stations.filter { station ->
                val id = station.stationuuid
                !_browse.value.workingStationIds.contains(id) && 
                !_browse.value.failedStationIds.contains(id)
            }
            
            // Check all unchecked stations
            for (station in uncheckedStations) {
                try {
                    val isReachable = checkUrlReachable(station.urlResolved)
                    if (isReachable) {
                        markStationWorkingSilent(station.stationuuid)
                    } else {
                        markStationFailedSilent(station.stationuuid)
                    }
                } catch (e: Exception) {
                    // Ignore individual failures
                }
                delay(500) // 500ms between checks to be gentle on network
            }
            
            // After checking all unchecked stations, wait 2 minutes before next cycle.
            // Previously failed stations are intentionally NOT re-checked:
            // HEAD responses can be 200 even when the actual audio stream is dead.
            // Stations are only un-marked when the user successfully plays them (STATE_READY).
            delay(2 * 60 * 1000L)
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
            .onSuccess { stations ->
                // Sort with India (IN) stations first, then keep original order
                val sorted = stations.sortedWith(
                    compareByDescending<Station> { it.countryCode == "IN" }
                )
                updateStations(sorted)
            }
            .onFailure { _browse.value = _browse.value.copy(loading = false, stations = emptyList(), error = it.message ?: "Failed") }
    }

    fun refresh() {
        // Reload current data
        if (_browse.value.query.isNotEmpty()) {
            search()
        } else {
            loadTop()
        }
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
    
    fun searchByCountry(countryCode: String) = viewModelScope.launch {
        _browse.value = _browse.value.copy(loading = true, error = null)
        runCatching { repo.searchByCountryCode(countryCode, 300) }
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
     * Checks ALL unchecked station URLs to see if they're reachable
     * Called automatically when stations are loaded
     */
    private fun runBackgroundHealthCheck(stations: List<Station>) = viewModelScope.launch(Dispatchers.IO) {
        if (isCheckingHealth) return@launch
        isCheckingHealth = true
        withContext(Dispatchers.Main) {
            _browse.value = _browse.value.copy(isFilteringStations = true)
        }
        
        try {
            val uncheckedStations = stations.filter { station ->
                val id = station.stationuuid
                !_browse.value.workingStationIds.contains(id) && !_browse.value.failedStationIds.contains(id)
            }
            
            for (station in uncheckedStations) {
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
                delay(300) // 300ms between checks
            }
        } finally {
            isCheckingHealth = false
            withContext(Dispatchers.Main) {
                _browse.value = _browse.value.copy(isFilteringStations = false)
            }
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
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            // Use GET instead of HEAD because many radio servers (Icecast/Shoutcast) 
            // return 404 or 405 to HEAD requests even if they are perfectly functional.
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            
            // We only care about the response code, we don't need to read the stream
            val responseCode = connection.responseCode
            responseCode in 200..399
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
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
