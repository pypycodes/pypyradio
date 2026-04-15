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
    val filter: StationFilter = StationFilter.ALL
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
        // Initial load of favorite/failed statuses
        loadStationStatuses()
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
