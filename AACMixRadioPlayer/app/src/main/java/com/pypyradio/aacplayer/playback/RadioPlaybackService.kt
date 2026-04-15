package com.pypyradio.aacplayer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.os.Bundle
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pypyradio.aacplayer.MainActivity
import com.pypyradio.aacplayer.data.db.AppDatabase
import com.pypyradio.aacplayer.data.model.Podcast
import com.pypyradio.aacplayer.data.model.PodcastEpisode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.data.prefs.AppPreferences
import com.pypyradio.aacplayer.data.repo.PodcastRepository
import com.pypyradio.aacplayer.data.repo.StationRepository

/**
 * RadioPlaybackService - Media3 MediaLibraryService for Android Auto
 *
 * Proper Best Practices Implementation:
 * - Immediate response to Android Auto browsing
 * - Async station loading from repository
 * - Proper lifecycle management
 * - Wake locks for background playback
 * - Comprehensive logging for debugging
 */
class RadioPlaybackService : MediaLibraryService() {

    companion object {
        private const val TAG = "RadioPlaybackService"

        // Media IDs
        const val MEDIA_ID_ROOT = "root"
        const val MEDIA_ID_TOP_STATIONS = "top_stations"
        const val MEDIA_ID_FAVORITES = "favorites"
        const val MEDIA_ID_FAVORITE_STATIONS = "favorite_stations"
        const val MEDIA_ID_FAVORITE_PODCASTS = "favorite_podcasts"
        const val MEDIA_ID_BY_LANGUAGE = "by_language"
        const val MEDIA_ID_ENGLISH = "english"
        const val MEDIA_ID_HINDI = "hindi"
        const val MEDIA_ID_PODCASTS = "podcasts"

        // Instant response stations
        private val SAMPLE_STATIONS = listOf(
            Station(
                stationuuid = "bbc1",
                name = "BBC Radio 1",
                urlResolved = "http://stream.live.vc.bbcmedia.co.uk/bbc_radio_one",
                homepage = "https://www.bbc.co.uk/radio1",
                favicon = null,
                tags = "pop,rock,hits",
                countryCode = "GB",
                language = "english",
                codec = "mp3",
                bitrate = 128,
                lastCheckOk = 1
            ),
            Station(
                stationuuid = "mirchi",
                name = "Radio Mirchi",
                urlResolved = "https://stream.radiomirchi.com/mirchi_live",
                homepage = "https://www.radiomirchi.com",
                favicon = null,
                tags = "hindi,bollywood,music",
                countryCode = "IN",
                language = "hindi",
                codec = "aac",
                bitrate = 128,
                lastCheckOk = 1
            ),
            Station(
                stationuuid = "npr",
                name = "NPR News",
                urlResolved = "https://npr-live-mp3-128.akacast.akamaistream.net/7477",
                homepage = "https://www.npr.org",
                favicon = null,
                tags = "news,talk,information",
                countryCode = "US",
                language = "english",
                codec = "mp3",
                bitrate = 128,
                lastCheckOk = 1
            )
        )
    }

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var stationRepo: StationRepository
    private lateinit var podcastRepo: PodcastRepository
    private lateinit var prefs: AppPreferences
    private var cachedStations: List<Station> = SAMPLE_STATIONS
    private var cachedPodcasts: List<Podcast> = emptyList()
    private var cachedEpisodes: Map<String, List<PodcastEpisode>> = emptyMap()
    private var cachedFavorites: List<Station> = emptyList()
    private var failedStations = mutableSetOf<String>()
    private var currentStationIndex = 0
    private var isAutoSkipping = false

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        Log.i(TAG, "===== SERVICE CREATED =====")
        super.onCreate()

        try {
            // Initialize data layer
            val db = AppDatabase.get(this)
            stationRepo = StationRepository(db.favoritesDao())
            podcastRepo = PodcastRepository(db.favoritePodcastDao())
            prefs = AppPreferences(this)

            // Create player with error handling and slow network support
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    30_000, // minBufferMs
                    60_000, // maxBufferMs
                    2_500,  // bufferForPlaybackMs
                    5_000   // bufferForPlaybackAfterRebufferMs
                )
                .build()

            // Define browser-like User-Agent to prevent radio servers from blocking the player.
            // Matching the User-Agent used in StationsViewModel health checks.
            // Standard mobile identity for maximum stream compatibility
            val userAgent = "ExoPlayer/2.0 (AACMixRadioPlayer)"
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
            
            // Add player listener for error handling
            player?.addListener(PlayerErrorListener())

            // Create session with custom commands for next/prev
            session = MediaLibrarySession.Builder(this, player!!, RadioLibraryCallback())
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            
            // Note: Media3 handles standard Player commands (SKIP_TO_NEXT/PREVIOUS)
            // if the Player.Listener reports them as available. ExoPlayer handles this automatically
            // if it has multiple media items in the timeline.

            // Notification
            setMediaNotificationProvider(DefaultMediaNotificationProvider(this))

            // Acquire locks
            acquireWakeLocks()

            // Load stations and podcasts
            loadStations()
            loadPodcasts()
            refreshFavorites()

            Log.i(TAG, "Service initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error", e)
            throw e
        }
    }

    private fun acquireWakeLocks() {
        try {
            val wifiMgr = getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
wifiLock = wifiMgr?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pypyradio")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
            Log.d(TAG, "WiFi lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "WiFi lock failed", e)
        }

        try {
            val pwrMgr = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pwrMgr?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pypyradio")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock failed", e)
        }
    }

    private fun loadStations() {
        serviceScope.launch {
            try {
                // Increase initial load for better coverage in Android Auto
                val loaded = stationRepo.getFilteredAacStations(limit = 200)
                if (loaded.isNotEmpty()) {
                    cachedStations = loaded
                    Log.i(TAG, "Cached ${loaded.size} high-quality stations for instant access")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Station load error", e)
            }
        }
    }
    
    private suspend fun loadPodcastsInternal() {
        try {
            val loaded = podcastRepo.getTrendingPodcasts(limit = 20)
            cachedPodcasts = loaded
            Log.d(TAG, "Internal loaded ${loaded.size} podcasts from API")
        } catch (e: Exception) {
            Log.e(TAG, "Internal podcast load error", e)
        }
    }
    
    private suspend fun loadFavoritesInternal(): List<Station> {
        return try {
            val favorites = stationRepo.observeFavorites().first()
            Log.d(TAG, "Loaded ${favorites.size} favorites from database")
            // Update cached favorites for consistency
            cachedFavorites = favorites
            favorites
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load favorites from database", e)
            emptyList()
        }
    }
    
    private fun refreshFavorites() {
        serviceScope.launch {
            try {
                val favorites = stationRepo.observeFavorites().first()
                cachedFavorites = favorites
                Log.d(TAG, "Refreshed favorites: ${favorites.size} items")
                
                // Notify Android Auto that the favorites have changed
                session?.notifyChildrenChanged(MEDIA_ID_FAVORITES, 0, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh favorites", e)
            }
        }
    }
    
    private suspend fun loadStationsInternal() {
        try {
            val loaded = stationRepo.getFilteredAacStations(limit = 100)
            if (loaded.isNotEmpty()) {
                cachedStations = loaded
                Log.d(TAG, "Internal loaded ${loaded.size} stations from API")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Internal station load error", e)
        }
    }

    private fun loadPodcasts() {
        serviceScope.launch {
            try {
                val loaded = podcastRepo.getTrendingPodcasts(limit = 20)
                cachedPodcasts = loaded
                Log.d(TAG, "Loaded ${loaded.size} podcasts from API")
                
                // Pre-load episodes for first few podcasts
                loaded.take(5).forEach { podcast ->
                    try {
                        val episodes = podcastRepo.getEpisodes(podcast, limit = 20)
                        cachedEpisodes = cachedEpisodes + (podcast.id to episodes)
                        Log.d(TAG, "Loaded ${episodes.size} episodes for ${podcast.title}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load episodes for ${podcast.title}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Podcast load error", e)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        Log.i(TAG, "===== SERVICE DESTROYED =====")
        serviceScope.cancel()
        session?.release()
        player?.release()
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    /**
     * Callback handling Android Auto MediaBrowser protocol
     */
    private inner class RadioLibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(TAG, "onGetLibraryRoot from ${browser.packageName}")
            return try {
                val root = MediaItem.Builder()
                    .setMediaId(MEDIA_ID_ROOT)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("PyPy Radio")
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .build()
                    )
                    .build()
                Futures.immediateFuture(LibraryResult.ofItem(root, params))
            } catch (e: Exception) {
                Log.e(TAG, "onGetLibraryRoot error", e)
                Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
            Log.d(TAG, "onGetChildren: $parentId (page=$page)")
            try {
                val items = when (parentId) {
                    MEDIA_ID_ROOT -> listOf(
                        browsableItem(MEDIA_ID_TOP_STATIONS, "Top Stations"),
                        browsableItem(MEDIA_ID_FAVORITES, "Favorites"),
                        browsableItem(MEDIA_ID_ENGLISH, "English Stations"),
                        browsableItem(MEDIA_ID_HINDI, "Indian Stations"),
                        browsableItem(MEDIA_ID_PODCASTS, "Podcasts")
                    )
                    MEDIA_ID_TOP_STATIONS -> {
                        // Load trusted high-quality stations asynchronously
                        val topStations = try {
                            stationRepo.getRecommendedStations(50)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load top stations", e)
                            if (cachedStations.isEmpty()) loadStationsInternal()
                            cachedStations.take(50)
                        }
                        topStations.map(::playableItem)
                    }
                    MEDIA_ID_FAVORITES -> {
                        listOf(
                            browsableItem(MEDIA_ID_FAVORITE_STATIONS, "Favorite Stations"),
                            browsableItem(MEDIA_ID_FAVORITE_PODCASTS, "Favorite Podcasts")
                        )
                    }
                    MEDIA_ID_FAVORITE_STATIONS -> {
                        loadFavoritesInternal().map(::playableItem)
                    }
                    MEDIA_ID_FAVORITE_PODCASTS -> {
                        val favoritePodcasts = try {
                            podcastRepo.observeFavorites().first()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load favorite podcasts", e)
                            emptyList()
                        }
                        favoritePodcasts.map { podcast ->
                            browsableItem("podcast_${podcast.id}", podcast.title)
                        }
                    }
                    MEDIA_ID_ENGLISH -> {
                        val englishStations = try {
                            stationRepo.getEnglishStations(50)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load English stations", e)
                            emptyList()
                        }
                        englishStations.map(::playableItem)
                    }
                    MEDIA_ID_HINDI -> {
                        val indianStations = try {
                            stationRepo.getIndianStations(50)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load Indian stations", e)
                            emptyList()
                        }
                        indianStations.map(::playableItem)
                    }
                    MEDIA_ID_PODCASTS -> {
                        if (cachedPodcasts.isEmpty()) {
                            loadPodcastsInternal()
                        }
                        cachedPodcasts.map { podcast ->
                            browsableItem("podcast_${podcast.id}", podcast.title)
                        }
                    }
                    else -> if (parentId.startsWith("podcast_")) {
                        val podcastId = parentId.removePrefix("podcast_")
                        val podcast = cachedPodcasts.find { it.id == podcastId }
                        if (podcast != null) {
                            if (!cachedEpisodes.containsKey(podcastId)) {
                                try {
                                    val episodes = podcastRepo.getEpisodes(podcast, limit = 50)
                                    cachedEpisodes = cachedEpisodes + (podcastId to episodes)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to load episodes for ${podcast.title}", e)
                                }
                            }
                            cachedEpisodes[podcastId]?.map { episode ->
                                playablePodcastEpisodeItem(episode)
                            } ?: emptyList()
                        } else {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                }
                Log.d(TAG, "Returning ${items.size} items for $parentId")
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            } catch (e: Exception) {
                Log.e(TAG, "onGetChildren error", e)
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return try {
                val station = cachedStations.find { it.stationuuid == mediaId }
                if (station != null) {
                    Futures.immediateFuture(LibraryResult.ofItem(playableItem(station), null))
                } else {
                    Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                }
            } catch (e: Exception) {
                Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            Log.d(TAG, "onAddMediaItems: ${mediaItems.size} items")
            return try {
                val resolvedItems = mediaItems.map { item ->
                    val mediaId = item.mediaId
                    
                    // Check if it's a station
                    val station = cachedStations.find { it.stationuuid == mediaId }
                    if (station != null && item.localConfiguration == null) {
                        playableItem(station)
                    } else if (station != null) {
                        item
                    } else {
                        // Check if it's a podcast episode
                        val episode = cachedEpisodes.values.flatten().find { it.id == mediaId }
                        if (episode != null && item.localConfiguration == null) {
                            playablePodcastEpisodeItem(episode)
                        } else if (episode != null) {
                            item
                        } else {
                            // Fallback to item as-is
                            item
                        }
                    }
                }.toMutableList()
                
                Futures.immediateFuture(resolvedItems)
            } catch (e: Exception) {
                Log.e(TAG, "onAddMediaItems error", e)
                Futures.immediateFuture(mediaItems)
            }
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
            Log.d(TAG, "onSetMediaItems: ${mediaItems.size} items, startIndex=$startIndex")
            
            try {
                if (mediaItems.isEmpty()) {
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                } else {
                    // Ensure data is loaded before resolving items asynchronously
                    if (cachedStations.isEmpty()) {
                        loadStationsInternal()
                    }
                    if (cachedPodcasts.isEmpty()) {
                        loadPodcastsInternal()
                    }
                    if (cachedFavorites.isEmpty()) {
                        loadFavoritesInternal()
                    }
                    
                    // Resolve items from cache (instant)
                    val resolvedItems = mediaItems.mapNotNull { item ->
                        val mediaId = item.mediaId
                        
                        // Check if it's a station (check caches first)
                        var station = cachedStations.find { it.stationuuid == mediaId } 
                            ?: cachedFavorites.find { it.stationuuid == mediaId }
                        
                        // If not found in primary caches, try to find in recommendations (still fast)
                        if (station == null) {
                            station = try {
                                // Use a fast repository call - since we are in a suspend block (future)
                                stationRepo.getRecommendedStations(20).find { it.stationuuid == mediaId }
                            } catch (e: Exception) {
                                null
                            }
                        }
                        
                        if (station != null) {
                            if (isValidStation(station)) {
                                if (item.localConfiguration == null) {
                                    playableItem(station)
                                } else {
                                    item
                                }
                            } else {
                                Log.w(TAG, "Station validation failed: ${station.name} ($mediaId)")
                                failedStations.add(mediaId)
                                null
                            }
                        } else {
                            // UI provided URIs are PRIORITY - trust them and play instantly (1.0.94 style)
                            if (item.localConfiguration?.uri != null) {
                                item
                            } else {
                                // Check if it's a podcast episode (cache only)
                                val episode = cachedEpisodes.values.flatten().find { it.id == mediaId }
                                if (episode != null && isValidEpisode(episode)) {
                                    if (item.localConfiguration == null) playablePodcastEpisodeItem(episode) else item
                                } else {
                                    Log.w(TAG, "Unknown media item without URI: $mediaId")
                                    null
                                }
                            }
                        }
                    }
                    
                    if (resolvedItems.isEmpty()) {
                        Log.w(TAG, "No valid media items after validation")
                        showPlaybackError("No valid stations", "All selected stations failed validation")
                        MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
                    } else {
                        Log.d(TAG, "Successfully resolved ${resolvedItems.size} items for session")
                        MediaSession.MediaItemsWithStartPosition(resolvedItems, startIndex, startPositionMs)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onSetMediaItems error", e)
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            }
        }

        private fun browsableItem(id: String, title: String): MediaItem {
            return MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
        }

        private fun playableItem(station: Station): MediaItem {
            return MediaItem.Builder()
                .setMediaId(station.stationuuid)
                .setUri(station.urlResolved)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(station.name)
                        .setArtist(station.countryCode ?: "Unknown")
                        .setGenre(station.tags ?: "Radio")
                        .setAlbumTitle(station.language?.let { "$it Radio" } ?: "Radio")
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }

        
        private fun playablePodcastEpisodeItem(episode: PodcastEpisode): MediaItem {
            return MediaItem.Builder()
                .setMediaId(episode.id)
                .setUri(episode.audioUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setArtist(episode.podcastTitle ?: "")
                        .setGenre("Podcast")
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
    }

    /**
     * Player listener for handling playback errors and automatic station skipping
     */
    private inner class PlayerErrorListener : Player.Listener {
        
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error occurred", error)
            handlePlaybackError(error)
        }
        
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_IDLE -> {
                    Log.d(TAG, "Player state: IDLE")
                }
                Player.STATE_BUFFERING -> {
                    Log.d(TAG, "Player state: BUFFERING")
                    // Start timeout check for buffering
                    startBufferingTimeoutCheck()
                }
                Player.STATE_READY -> {
                    Log.d(TAG, "Player state: READY - Playback successful")
                    // Reset failed stations on successful playback
                    val currentMediaId = player?.currentMediaItem?.mediaId
                    if (currentMediaId != null) {
                        failedStations.remove(currentMediaId)
                        Log.d(TAG, "Removed $currentMediaId from failed stations")
                    }
                    cancelBufferingTimeoutCheck()
                }
                Player.STATE_ENDED -> {
                    Log.d(TAG, "Player state: ENDED")
                    // Auto-advance to next station
                    if (!isAutoSkipping) {
                        skipToNextStation("Playback ended")
                    }
                }
            }
        }
        
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentStationIndex = player?.currentMediaItemIndex ?: 0
            val mediaId = mediaItem?.mediaId
            Log.d(TAG, "Media item transition: $mediaId (index: $currentStationIndex, reason: $reason)")
        }
    }
    
    private var bufferingTimeoutJob: Job? = null
    
    private fun startBufferingTimeoutCheck() {
        cancelBufferingTimeoutCheck()
        bufferingTimeoutJob = serviceScope.launch {
            delay(12000) // 12 seconds timeout (reduced from 20s for snappier experience)
            val currentState = player?.playbackState
            if (currentState == Player.STATE_BUFFERING) {
                Log.w(TAG, "Buffering timeout (12s) - auto-skipping to next station")
                handlePlaybackError(RuntimeException("Buffering timeout - station may be dead"))
            }
        }
    }
    
    private fun cancelBufferingTimeoutCheck() {
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
    }
    
    private fun handlePlaybackError(error: Exception) {
        val currentMediaId = player?.currentMediaItem?.mediaId
        Log.w(TAG, "Handling playback error for: $currentMediaId (Error: ${error.message})")
        
        // Add to failed stations
        if (currentMediaId != null) {
            failedStations.add(currentMediaId)
        }
        
        // Don't re-enter if already skipping, but ensure skipping continues
        if (isAutoSkipping) return
        
        skipToNextStation("Playback error")
    }
    
    private fun skipToNextStation(reason: String) {
        val currentPlayer = player ?: return
        val totalItems = currentPlayer.mediaItemCount
        
        if (totalItems <= 1) {
            Log.w(TAG, "Cannot skip: Only one item in playlist")
            currentPlayer.prepare()
            currentPlayer.play()
            return
        }

        isAutoSkipping = true
        try {
            var nextIndex = (currentPlayer.currentMediaItemIndex + 1) % totalItems
            var attempts = 0
            
            Log.i(TAG, "Station Hunt: Searching for next playable station starting from index $nextIndex")
            
            // Loop through the timeline until we find something that doesn't have an empty URL
            while (attempts < totalItems) {
                val nextItem = currentPlayer.getMediaItemAt(nextIndex)
                val url = nextItem.localConfiguration?.uri?.toString() ?: ""
                
                if (url.isNotBlank()) {
                    Log.d(TAG, "Station Hunt: Found potential station at $nextIndex (${nextItem.mediaId})")
                    currentPlayer.seekToDefaultPosition(nextIndex)
                    currentPlayer.prepare()
                    currentPlayer.play()
                    return // Success - the next error (if any) will re-trigger this
                }
                
                Log.d(TAG, "Station Hunt: Index $nextIndex has no URL, skipping...")
                nextIndex = (nextIndex + 1) % totalItems
                attempts++
            }
            
            Log.e(TAG, "Station Hunt: Exhausted all $totalItems items. None are playable.")
            showPlaybackError(reason, "No playable stations found in list")
        } catch (e: Exception) {
            Log.e(TAG, "Critical error during Station Hunt", e)
        } finally {
            isAutoSkipping = false
        }
    }
    
    
    /**
     * Validate station before playback
     */
    private fun isValidStation(station: Station): Boolean {
        // More lenient validation: allow even if it failed before, giving it another chance
        // only reject if URL is missing.
        return station.urlResolved.isNotBlank() && station.name.isNotBlank()
    }
    
    /**
     * Validate podcast episode before playback
     */
    private fun isValidEpisode(episode: PodcastEpisode): Boolean {
        return episode.audioUrl.isNotBlank() &&
               episode.title.isNotBlank() &&
               !failedStations.contains(episode.id)
    }
    
    private fun showPlaybackError(reason: String, details: String) {
        Log.e(TAG, "Playback error - Reason: $reason, Details: $details")
        
        // We no longer replace the media item with an error item, 
        // as that destroys the playlist and disables Next/Prev controls.
        // Instead, we just log and let skipToNextStation handle the recovery.
    }
}
