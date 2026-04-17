package com.pypyradio.aacplayer.data.repo

import com.pypyradio.aacplayer.data.api.PodcastClient
import com.pypyradio.aacplayer.data.db.FavoritePodcastDao
import com.pypyradio.aacplayer.data.db.FavoritePodcastEntity
import com.pypyradio.aacplayer.data.model.Podcast
import com.pypyradio.aacplayer.data.model.PodcastEpisode
import com.pypyradio.aacplayer.data.model.PodcastSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PodcastRepository(private val favoritePodcastDao: FavoritePodcastDao? = null) {
    
    private val iTunesApi = PodcastClient.iTunesApi
    private val podcastIndexApi = PodcastClient.podcastIndexApi
    
    // Podcast categories for browsing
    companion object {
        val CATEGORIES = listOf(
            "Trending" to null,
            "Comedy" to "comedy",
            "News" to "news",
            "True Crime" to "true crime",
            "Sports" to "sports",
            "Business" to "business",
            "Health" to "health",
            "Technology" to "technology",
            "Education" to "education",
            "Music" to "music",
            "Society" to "society",
            "Science" to "science"
        )
    }
    
    /**
     * Get trending podcasts - uses Podcast Index if configured, otherwise iTunes
     */
    suspend fun getTrendingPodcasts(limit: Int = 50): List<Podcast> {
        // Try Podcast Index first if configured
        if (PodcastClient.isPodcastIndexEnabled) {
            try {
                val response = podcastIndexApi.getTrending(max = limit)
                val podcasts = response.feeds?.mapNotNull { feed ->
                    if (feed.id == null || feed.title.isNullOrBlank()) return@mapNotNull null
                    Podcast(
                        id = "pi_${feed.id}",
                        title = feed.title,
                        author = feed.author,
                        description = feed.description,
                        imageUrl = feed.artwork ?: feed.image,
                        feedUrl = feed.url,
                        genre = feed.categories?.values?.firstOrNull(),
                        episodeCount = feed.episodeCount,
                        source = PodcastSource.PODCAST_INDEX
                    )
                } ?: emptyList()
                if (podcasts.isNotEmpty()) return podcasts
            } catch (_: Exception) { }
        }
        
        // Fallback to iTunes popular podcasts
        return searchITunesPodcasts("top podcast", limit)
    }
    
    /**
     * Search podcasts using iTunes API
     */
    suspend fun searchITunesPodcasts(query: String, limit: Int = 50): List<Podcast> {
        val response = iTunesApi.searchPodcasts(term = query, limit = limit)
        return response.results.mapNotNull { podcast ->
            if (podcast.collectionId == null || podcast.collectionName.isNullOrBlank()) return@mapNotNull null
            Podcast(
                id = "itunes_${podcast.collectionId}",
                title = podcast.collectionName,
                author = podcast.artistName,
                description = null,
                imageUrl = podcast.artworkUrl600 ?: podcast.artworkUrl100,
                feedUrl = podcast.feedUrl,
                genre = podcast.primaryGenreName,
                episodeCount = podcast.trackCount,
                source = PodcastSource.ITUNES
            )
        }
    }
    
    /**
     * Search podcasts using Podcast Index API (only if configured)
     */
    suspend fun searchPodcastIndex(query: String, limit: Int = 50): List<Podcast> {
        if (!PodcastClient.isPodcastIndexEnabled) return emptyList()
        
        return try {
            val response = podcastIndexApi.searchPodcasts(query = query, max = limit)
            response.feeds?.mapNotNull { feed ->
                if (feed.id == null || feed.title.isNullOrBlank()) return@mapNotNull null
                Podcast(
                    id = "pi_${feed.id}",
                    title = feed.title,
                    author = feed.author,
                    description = feed.description,
                    imageUrl = feed.artwork ?: feed.image,
                    feedUrl = feed.url,
                    genre = feed.categories?.values?.firstOrNull(),
                    episodeCount = feed.episodeCount,
                    source = PodcastSource.PODCAST_INDEX
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Search podcasts from both sources and combine results
     */
    suspend fun searchAllPodcasts(query: String, limit: Int = 50): List<Podcast> {
        val results = mutableListOf<Podcast>()
        
        // Try Podcast Index if configured
        if (PodcastClient.isPodcastIndexEnabled) {
            try {
                results.addAll(searchPodcastIndex(query, limit / 2))
            } catch (_: Exception) { }
        }
        
        // Add iTunes results (always available, no API key needed)
        try {
            val iTunesLimit = if (PodcastClient.isPodcastIndexEnabled) limit / 2 else limit
            results.addAll(searchITunesPodcasts(query, iTunesLimit))
        } catch (_: Exception) { }
        
        // Remove duplicates by title (case-insensitive)
        return results.distinctBy { it.title.lowercase() }.take(limit)
    }
    
    /**
     * Search podcasts by category/genre
     */
    suspend fun searchByCategory(category: String, limit: Int = 50): List<Podcast> {
        return searchAllPodcasts("$category podcast", limit)
    }
    
    /**
     * Get episodes for a podcast from iTunes
     */
    suspend fun getITunesEpisodes(podcastId: Long, limit: Int = 50): List<PodcastEpisode> {
        val response = iTunesApi.getPodcastEpisodes(podcastId = podcastId, limit = limit)
        return response.results.mapNotNull { episode ->
            if (episode.trackId == null || episode.episodeUrl.isNullOrBlank()) return@mapNotNull null
            PodcastEpisode(
                id = "itunes_ep_${episode.trackId}",
                title = episode.trackName ?: "Untitled Episode",
                podcastId = "itunes_${episode.collectionId}",
                podcastTitle = episode.collectionName,
                author = episode.artistName,
                description = episode.description ?: episode.shortDescription,
                audioUrl = episode.episodeUrl,
                imageUrl = episode.artworkUrl600 ?: episode.artworkUrl160,
                durationMs = episode.durationMs,
                publishedDate = episode.releaseDate,
                source = PodcastSource.ITUNES
            )
        }
    }
    
    /**
     * Get episodes for a podcast from Podcast Index
     */
    suspend fun getPodcastIndexEpisodes(feedId: Long, limit: Int = 50): List<PodcastEpisode> {
        return try {
            val response = podcastIndexApi.getEpisodes(feedId = feedId, max = limit)
            response.items?.mapNotNull { episode ->
                if (episode.id == null || episode.enclosureUrl.isNullOrBlank()) return@mapNotNull null
                PodcastEpisode(
                    id = "pi_ep_${episode.id}",
                    title = episode.title ?: "Untitled Episode",
                    podcastId = "pi_${episode.feedId}",
                    podcastTitle = episode.feedTitle,
                    author = null,
                    description = episode.description,
                    audioUrl = episode.enclosureUrl,
                    imageUrl = episode.image,
                    durationMs = episode.duration?.times(1000L),
                    publishedDate = episode.datePublished?.let { 
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .format(java.util.Date(it * 1000))
                    },
                    source = PodcastSource.PODCAST_INDEX
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get episodes for a podcast (auto-detect source)
     */
    suspend fun getEpisodes(podcast: Podcast, limit: Int = 50): List<PodcastEpisode> {
        return when (podcast.source) {
            PodcastSource.ITUNES -> {
                val id = podcast.id.removePrefix("itunes_").toLongOrNull() ?: return emptyList()
                getITunesEpisodes(id, limit)
            }
            PodcastSource.PODCAST_INDEX -> {
                val id = podcast.id.removePrefix("pi_").toLongOrNull() ?: return emptyList()
                getPodcastIndexEpisodes(id, limit)
            }
        }
    }
    
    /**
     * Search for podcast episodes directly
     */
    suspend fun searchEpisodes(query: String, limit: Int = 50): List<PodcastEpisode> {
        val response = iTunesApi.searchEpisodes(term = query, limit = limit)
        return response.results.mapNotNull { episode ->
            if (episode.trackId == null || episode.episodeUrl.isNullOrBlank()) return@mapNotNull null
            PodcastEpisode(
                id = "itunes_ep_${episode.trackId}",
                title = episode.trackName ?: "Untitled Episode",
                podcastId = "itunes_${episode.collectionId}",
                podcastTitle = episode.collectionName,
                author = episode.artistName,
                description = episode.description ?: episode.shortDescription,
                audioUrl = episode.episodeUrl,
                imageUrl = episode.artworkUrl600 ?: episode.artworkUrl160,
                durationMs = episode.durationMs,
                publishedDate = episode.releaseDate,
                source = PodcastSource.ITUNES
            )
        }
    }
    
    // ==================== FAVORITES ====================
    
    /**
     * Observe favorite podcasts as Flow
     */
    fun observeFavorites(): Flow<List<Podcast>> {
        return favoritePodcastDao?.observeAll()?.map { entities ->
            entities.map { it.toPodcast() }
        } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    
    /**
     * Check if podcast is favorite
     */
    suspend fun isFavorite(podcastId: String): Boolean {
        return favoritePodcastDao?.isFavorite(podcastId) ?: false
    }
    
    /**
     * Toggle favorite status
     */
    suspend fun toggleFavorite(podcast: Podcast) {
        val dao = favoritePodcastDao ?: return
        if (dao.isFavorite(podcast.id)) {
            dao.delete(podcast.id)
        } else {
            dao.upsert(podcast.toEntity())
        }
    }
    
    /**
     * Add podcast to favorites
     */
    suspend fun addFavorite(podcast: Podcast) {
        favoritePodcastDao?.upsert(podcast.toEntity())
    }
    
    /**
     * Remove podcast from favorites
     */
    suspend fun removeFavorite(podcastId: String) {
        favoritePodcastDao?.delete(podcastId)
    }
    
    /**
     * Update favorite order
     */
    suspend fun updateFavoriteOrder(podcastId: String, orderIndex: Int) {
        favoritePodcastDao?.updateOrder(podcastId, orderIndex)
    }
    
    // Extension functions for conversion
    private fun Podcast.toEntity() = FavoritePodcastEntity(
        id = id,
        title = title,
        author = author,
        description = description,
        imageUrl = imageUrl,
        feedUrl = feedUrl,
        genre = genre,
        episodeCount = episodeCount,
        source = source.name,
        orderIndex = System.currentTimeMillis().toInt() // Default to bottom
    )
    
    private fun FavoritePodcastEntity.toPodcast() = Podcast(
        id = id,
        title = title,
        author = author,
        description = description,
        imageUrl = imageUrl,
        feedUrl = feedUrl,
        genre = genre,
        episodeCount = episodeCount,
        source = PodcastSource.valueOf(source)
    )
}
