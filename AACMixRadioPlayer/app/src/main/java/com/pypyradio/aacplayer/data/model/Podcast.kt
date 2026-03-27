package com.pypyradio.aacplayer.data.model

data class Podcast(
    val id: String,
    val title: String,
    val author: String?,
    val description: String?,
    val imageUrl: String?,
    val feedUrl: String?,
    val genre: String?,
    val episodeCount: Int?,
    val source: PodcastSource
)

data class PodcastEpisode(
    val id: String,
    val title: String,
    val podcastId: String,
    val podcastTitle: String?,
    val author: String?,
    val description: String?,
    val audioUrl: String,
    val imageUrl: String?,
    val durationMs: Long?,
    val publishedDate: String?,
    val source: PodcastSource
)

enum class PodcastSource {
    ITUNES, PODCAST_INDEX
}
