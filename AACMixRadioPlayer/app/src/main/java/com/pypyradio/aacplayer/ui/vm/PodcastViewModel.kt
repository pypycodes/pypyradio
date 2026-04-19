package com.pypyradio.aacplayer.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pypyradio.aacplayer.data.db.AppDatabase
import com.pypyradio.aacplayer.data.model.Podcast
import com.pypyradio.aacplayer.data.model.PodcastEpisode
import com.pypyradio.aacplayer.data.repo.PodcastRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PodcastUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val podcasts: List<Podcast> = emptyList(),
    val episodes: List<PodcastEpisode> = emptyList(),
    val selectedPodcast: Podcast? = null,
    val query: String = "",
    val showingEpisodes: Boolean = false,
    val showingFavorites: Boolean = false
)

class PodcastViewModel(app: Application) : AndroidViewModel(app) {
    
    private val db = AppDatabase.get(app)
    private val repo = PodcastRepository(db.favoritePodcastDao())
    
    private val _state = MutableStateFlow(PodcastUiState(loading = true))
    val state: StateFlow<PodcastUiState> = _state
    
    val favorites: StateFlow<List<Podcast>> = repo.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    init {
        loadTrending()
    }
    
    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }
    
    fun loadTrending() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null, showingEpisodes = false, selectedPodcast = null)
        runCatching { repo.getTrendingPodcasts(50) }
            .onSuccess { _state.value = _state.value.copy(loading = false, podcasts = it, error = null) }
            .onFailure { error ->
                val msg = error.message ?: "Failed to load"
                val friendlyMsg = if (msg.contains("Chain validation failed", ignoreCase = true)) {
                    "Security error (Chain Validation). Please check if your TV's Date & Time are correct."
                } else msg
                _state.value = _state.value.copy(loading = false, podcasts = emptyList(), error = friendlyMsg)
            }
    }
    
    fun refresh() {
        // Reload current data
        if (_state.value.query.isNotEmpty()) {
            search()
        } else {
            loadTrending()
        }
    }
    
    fun search() = viewModelScope.launch {
        val q = _state.value.query.trim()
        if (q.isEmpty()) {
            loadTrending()
            return@launch
        }
        _state.value = _state.value.copy(loading = true, error = null, showingEpisodes = false, selectedPodcast = null)
        runCatching { repo.searchAllPodcasts(q, 50) }
            .onSuccess { _state.value = _state.value.copy(loading = false, podcasts = it, error = null) }
            .onFailure { error ->
                val msg = error.message ?: "Failed to search"
                val friendlyMsg = if (msg.contains("Chain validation failed", ignoreCase = true)) {
                    "Security error. Please check if your TV's Date & Time are correct."
                } else msg
                _state.value = _state.value.copy(loading = false, podcasts = emptyList(), error = friendlyMsg)
            }
    }
    
    fun searchByCategory(category: String) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null, showingEpisodes = false, selectedPodcast = null)
        runCatching { repo.searchByCategory(category, 50) }
            .onSuccess { _state.value = _state.value.copy(loading = false, podcasts = it, error = null) }
            .onFailure { _state.value = _state.value.copy(loading = false, podcasts = emptyList(), error = it.message ?: "Failed to load") }
    }
    
    fun searchEpisodes() = viewModelScope.launch {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return@launch
        _state.value = _state.value.copy(loading = true, error = null, showingEpisodes = true, selectedPodcast = null)
        runCatching { repo.searchEpisodes(q, 50) }
            .onSuccess { _state.value = _state.value.copy(loading = false, episodes = it, error = null) }
            .onFailure { _state.value = _state.value.copy(loading = false, episodes = emptyList(), error = it.message ?: "Failed to search") }
    }
    
    fun loadEpisodes(podcast: Podcast) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null, showingEpisodes = true, selectedPodcast = podcast)
        runCatching { repo.getEpisodes(podcast, 50) }
            .onSuccess { _state.value = _state.value.copy(loading = false, episodes = it, error = null) }
            .onFailure { error ->
                val msg = error.message ?: "Failed to load episodes"
                val friendlyMsg = if (msg.contains("Chain validation failed", ignoreCase = true)) {
                    "Security error. Please check if your TV's Date & Time are correct."
                } else msg
                _state.value = _state.value.copy(loading = false, episodes = emptyList(), error = friendlyMsg)
            }
    }
    
    fun backToPodcasts() {
        _state.value = _state.value.copy(showingEpisodes = false, selectedPodcast = null, episodes = emptyList(), showingFavorites = false)
    }
    
    fun toggleFavorite(podcast: Podcast) = viewModelScope.launch {
        repo.toggleFavorite(podcast)
    }
    
    fun moveFavorite(podcastId: String, direction: Int) = viewModelScope.launch {
        val currentFavs = favorites.value.toMutableList()
        val index = currentFavs.indexOfFirst { it.id == podcastId }
        val targetIndex = index + direction
        
        if (index >= 0 && targetIndex >= 0 && targetIndex < currentFavs.size) {
            val temp = currentFavs[index]
            currentFavs[index] = currentFavs[targetIndex]
            currentFavs[targetIndex] = temp
            
            currentFavs.forEachIndexed { i, podcast ->
                repo.updateFavoriteOrder(podcast.id, i)
            }
        }
    }

    fun moveFavoriteByIndices(fromIndex: Int, toIndex: Int) = viewModelScope.launch {
        val currentFavs = favorites.value.toMutableList()
        if (fromIndex in currentFavs.indices && toIndex in currentFavs.indices) {
            val item = currentFavs.removeAt(fromIndex)
            currentFavs.add(toIndex, item)
            currentFavs.forEachIndexed { i, podcast ->
                repo.updateFavoriteOrder(podcast.id, i)
            }
        }
    }
    
    fun showFavorites() {
        _state.value = _state.value.copy(showingFavorites = true, showingEpisodes = false, selectedPodcast = null)
    }
    
    fun loadFavoriteEpisodes(podcast: Podcast) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null, showingEpisodes = true, selectedPodcast = podcast, showingFavorites = false)
        runCatching { repo.getEpisodes(podcast, 50) }
            .onSuccess { _state.value = _state.value.copy(loading = false, episodes = it, error = null) }
            .onFailure { _state.value = _state.value.copy(loading = false, episodes = emptyList(), error = it.message ?: "Failed to load episodes") }
    }
}
