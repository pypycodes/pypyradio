package com.pypyradio.aacplayer.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * iTunes Search API for podcasts (no API key needed)
 */
interface ITunesPodcastApi {
    
    @GET("search")
    suspend fun searchPodcasts(
        @Query("term") term: String,
        @Query("media") media: String = "podcast",
        @Query("entity") entity: String = "podcast",
        @Query("limit") limit: Int = 50
    ): ITunesSearchResponse
    
    @GET("search")
    suspend fun searchEpisodes(
        @Query("term") term: String,
        @Query("media") media: String = "podcast",
        @Query("entity") entity: String = "podcastEpisode",
        @Query("limit") limit: Int = 50
    ): ITunesEpisodeResponse
    
    @GET("lookup")
    suspend fun getPodcastEpisodes(
        @Query("id") podcastId: Long,
        @Query("media") media: String = "podcast",
        @Query("entity") entity: String = "podcastEpisode",
        @Query("limit") limit: Int = 100
    ): ITunesEpisodeResponse
}

data class ITunesSearchResponse(
    val resultCount: Int,
    val results: List<ITunesPodcast>
)

data class ITunesPodcast(
    val collectionId: Long?,
    val collectionName: String?,
    val artistName: String?,
    val artworkUrl100: String?,
    val artworkUrl600: String?,
    val feedUrl: String?,
    val primaryGenreName: String?,
    val genres: List<String>?,
    val trackCount: Int?,
    val releaseDate: String?,
    val collectionViewUrl: String?
)

data class ITunesEpisodeResponse(
    val resultCount: Int,
    val results: List<ITunesEpisode>
)

data class ITunesEpisode(
    val trackId: Long?,
    val trackName: String?,
    val collectionId: Long?,
    val collectionName: String?,
    val artistName: String?,
    val artworkUrl160: String?,
    val artworkUrl600: String?,
    val episodeUrl: String?,
    val episodeFileExtension: String?,
    val description: String?,
    val shortDescription: String?,
    val releaseDate: String?,
    @SerializedName("trackTimeMillis") val durationMs: Long?,
    val episodeGuid: String?,
    val episodeContentType: String?
)

/**
 * Podcast Index API (free, requires API key)
 * Register at https://podcastindex.org/ for free API credentials
 */
interface PodcastIndexApi {
    
    @GET("search/byterm")
    suspend fun searchPodcasts(
        @Query("q") query: String,
        @Query("max") max: Int = 50
    ): PodcastIndexSearchResponse
    
    @GET("podcasts/trending")
    suspend fun getTrending(
        @Query("max") max: Int = 50,
        @Query("lang") language: String? = null,
        @Query("cat") category: String? = null
    ): PodcastIndexTrendingResponse
    
    @GET("episodes/byfeedid")
    suspend fun getEpisodes(
        @Query("id") feedId: Long,
        @Query("max") max: Int = 50
    ): PodcastIndexEpisodesResponse
    
    @GET("categories/list")
    suspend fun getCategories(): PodcastIndexCategoriesResponse
}

data class PodcastIndexSearchResponse(
    val status: String?,
    val feeds: List<PodcastIndexFeed>?
)

data class PodcastIndexTrendingResponse(
    val status: String?,
    val feeds: List<PodcastIndexFeed>?
)

data class PodcastIndexFeed(
    val id: Long?,
    val title: String?,
    val url: String?,
    val author: String?,
    val image: String?,
    val artwork: String?,
    val description: String?,
    val language: String?,
    val categories: Map<String, String>?,
    val episodeCount: Int?
)

data class PodcastIndexEpisodesResponse(
    val status: String?,
    val items: List<PodcastIndexEpisode>?
)

data class PodcastIndexEpisode(
    val id: Long?,
    val title: String?,
    val description: String?,
    val enclosureUrl: String?,
    val enclosureType: String?,
    val enclosureLength: Long?,
    val duration: Int?,
    val image: String?,
    val feedId: Long?,
    val feedTitle: String?,
    val datePublished: Long?
)

data class PodcastIndexCategoriesResponse(
    val status: String?,
    val feeds: Map<String, String>?
)
