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
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pypyradio.aacplayer.MainActivity
import com.pypyradio.aacplayer.data.db.AppDatabase
import com.pypyradio.aacplayer.data.model.Podcast
import com.pypyradio.aacplayer.data.model.PodcastEpisode
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.data.prefs.AppPreferences
import com.pypyradio.aacplayer.data.repo.PodcastRepository
import com.pypyradio.aacplayer.data.repo.StationRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

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

            // Create player with error handling
            player = ExoPlayer.Builder(this)
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
                val loaded = stationRepo.getFilteredAacStations(limit = 100)
                if (loaded.isNotEmpty()) {
                    cachedStations = loaded
                    Log.d(TAG, "Loaded ${loaded.size} stations from API")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Station load error", e)
            }
        }
    }
    
    private fun loadPodcastsSync() {
        try {
            val loaded = runBlocking {
                podcastRepo.getTrendingPodcasts(limit = 20)
            }
            cachedPodcasts = loaded
            Log.d(TAG, "Sync loaded ${loaded.size} podcasts from API")
        } catch (e: Exception) {
            Log.e(TAG, "Sync podcast load error", e)
        }
    }
    
    private fun loadFavoritesSync(): List<Station> {
        return try {
            val favorites = runBlocking {
                stationRepo.observeFavorites().first()
            }
            Log.d(TAG, "Loaded ${favorites.size} favorites from database")
            // Debug: Log favorite station details
            favorites.forEach { station ->
                Log.d(TAG, "Favorite: ${station.name}, url: ${station.urlResolved}, country: ${station.countryCode}, tags: ${station.tags}")
            }
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
    
    private fun loadStationsSync() {
        try {
            val loaded = runBlocking {
                stationRepo.getFilteredAacStations(limit = 100)
            }
            if (loaded.isNotEmpty()) {
                cachedStations = loaded
                Log.d(TAG, "Sync loaded ${loaded.size} stations from API")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync station load error", e)
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
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.d(TAG, "onGetChildren: $parentId (page=$page)")
            return try {
                val items = when (parentId) {
                    MEDIA_ID_ROOT -> listOf(
                        browsableItem(MEDIA_ID_TOP_STATIONS, "Top Stations"),
                        browsableItem(MEDIA_ID_FAVORITES, "Favorites"),
                        browsableItem(MEDIA_ID_ENGLISH, "English Stations"),
                        browsableItem(MEDIA_ID_HINDI, "Indian Stations"),
                        browsableItem(MEDIA_ID_PODCASTS, "Podcasts")
                    )
                    MEDIA_ID_TOP_STATIONS -> {
                        // Load high-quality stations using enhanced filtering
                        val topStations = runBlocking {
                            try {
                                // Combine English and Indian stations for FOR YOU
                                val englishStations = stationRepo.getEnglishStations(25)
                                val indianStations = stationRepo.getIndianStations(25)
                                (englishStations + indianStations)
                                    .sortedWith(compareByDescending<Station> { it.bitrate ?: 0 }
                                        .thenByDescending { it.tags?.contains("popular") == true }
                                        .thenBy { it.name })
                                    .take(50)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to load top stations", e)
                                // Fallback to cached stations
                                if (cachedStations.isEmpty()) {
                                    loadStationsSync()
                                }
                                cachedStations.take(50)
                            }
                        }
                        topStations.map(::playableItem)
                    }
                    MEDIA_ID_FAVORITES -> {
                        // Load actual favorites from database
                        val favorites = loadFavoritesSync()
                        favorites.map(::playableItem)
                    }
                                        MEDIA_ID_ENGLISH -> {
                        val englishStations = runBlocking {
                            try {
                                stationRepo.getEnglishStations(50)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to load English stations", e)
                                emptyList()
                            }
                        }
                        englishStations.map(::playableItem)
                    }
                    MEDIA_ID_HINDI -> {
                        val indianStations = runBlocking {
                            try {
                                stationRepo.getIndianStations(50)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to load Indian stations", e)
                                emptyList()
                            }
                        }
                        indianStations.map(::playableItem)
                    }
                    MEDIA_ID_PODCASTS -> {
                        if (cachedPodcasts.isEmpty()) {
                            loadPodcastsSync()
                        }
                        cachedPodcasts.map { podcast ->
                            browsableItem("podcast_${podcast.id}", podcast.title)
                        }
                    }
                    else -> if (parentId.startsWith("podcast_")) {
                        val podcastId = parentId.removePrefix("podcast_")
                        val podcast = cachedPodcasts.find { it.id == podcastId }
                        if (podcast != null) {
                            // Load episodes if not cached
                            if (!cachedEpisodes.containsKey(podcastId)) {
                                serviceScope.launch {
                                    try {
                                        val episodes = podcastRepo.getEpisodes(podcast, limit = 50)
                                        cachedEpisodes = cachedEpisodes + (podcastId to episodes)
                                        Log.d(TAG, "Loaded ${episodes.size} episodes for ${podcast.title}")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to load episodes for ${podcast.title}", e)
                                    }
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
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            } catch (e: Exception) {
                Log.e(TAG, "onGetChildren error", e)
                Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
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
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Log.d(TAG, "onSetMediaItems: ${mediaItems.size} items, startIndex=$startIndex")
            
            return try {
                if (mediaItems.isEmpty()) {
                    Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                    )
                } else {
                    // Ensure data is loaded before resolving items
                    if (cachedStations.isEmpty()) {
                        loadStationsSync()
                    }
                    if (cachedPodcasts.isEmpty()) {
                        loadPodcastsSync()
                    }
                    if (cachedFavorites.isEmpty()) {
                        loadFavoritesSync()
                    }
                    
                    // Resolve items from cache and validate before playback
                    val resolvedItems = mediaItems.mapNotNull { item ->
                        val mediaId = item.mediaId
                        
                        // Skip already failed stations
                        if (failedStations.contains(mediaId)) {
                            Log.d(TAG, "Skipping failed station: $mediaId")
                            return@mapNotNull null
                        }
                        
                        // Check if it's a station (check all possible sources)
                        var station = cachedStations.find { it.stationuuid == mediaId } 
                            ?: cachedFavorites.find { it.stationuuid == mediaId }
                        
                        // If not found in caches, try to load it dynamically
                        if (station == null) {
                            station = runBlocking {
                                try {
                                    // Try to find in English stations
                                    val englishStations = stationRepo.getEnglishStations(200)
                                    englishStations.find { it.stationuuid == mediaId }
                                        ?: // Try to find in Indian stations
                                        stationRepo.getIndianStations(200).find { it.stationuuid == mediaId }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to resolve station $mediaId", e)
                                    null
                                }
                            }
                        }
                        if (station != null) {
                            // Validate station before creating media item
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
                            // Check if it's a podcast episode
                            val episode = cachedEpisodes.values.flatten().find { it.id == mediaId }
                            if (episode != null) {
                                // Validate episode before creating media item
                                if (isValidEpisode(episode)) {
                                    if (item.localConfiguration == null) {
                                        playablePodcastEpisodeItem(episode)
                                    } else {
                                        item
                                    }
                                } else {
                                    Log.w(TAG, "Episode validation failed: ${episode.title} ($mediaId)")
                                    failedStations.add(mediaId)
                                    null
                                }
                            } else {
                                Log.w(TAG, "Unknown media item: $mediaId")
                                null
                            }
                        }
                    }
                    
                    if (resolvedItems.isEmpty()) {
                        Log.w(TAG, "No valid media items after validation")
                        showPlaybackError("No valid stations", "All selected stations failed validation")
                        Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
                        )
                    } else {
                        Log.d(TAG, "Setting ${resolvedItems.size} validated items to player, starting at index $startIndex")
                        player?.setMediaItems(resolvedItems, startIndex, startPositionMs)
                        player?.prepare()
                        player?.play()
                        
                        Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(resolvedItems, startIndex, startPositionMs)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onSetMediaItems error", e)
                Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                )
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
            delay(15000) // 15 seconds timeout
            val currentState = player?.playbackState
            if (currentState == Player.STATE_BUFFERING) {
                Log.w(TAG, "Buffering timeout - treating as error")
                handlePlaybackError(RuntimeException("Buffering timeout - station may be dead"))
            }
        }
    }
    
    private fun cancelBufferingTimeoutCheck() {
        bufferingTimeoutJob?.cancel()
        bufferingTimeoutJob = null
    }
    
    private fun handlePlaybackError(error: Exception) {
        if (isAutoSkipping) {
            Log.d(TAG, "Already auto-skipping, ignoring error")
            return
        }
        
        val currentMediaId = player?.currentMediaItem?.mediaId
        val currentStationName = player?.currentMediaItem?.mediaMetadata?.title
        
        Log.w(TAG, "Handling playback error for: $currentStationName ($currentMediaId)")
        
        // Add to failed stations
        if (currentMediaId != null) {
            failedStations.add(currentMediaId)
            Log.d(TAG, "Added $currentMediaId to failed stations. Total failed: ${failedStations.size}")
        }
        
        // Try to skip to next station
        skipToNextStation("Playback error: ${error.message}")
    }
    
    private fun skipToNextStation(reason: String) {
        isAutoSkipping = true
        
        try {
            val currentPlayer = player ?: return
            val totalItems = currentPlayer.mediaItemCount
            
            if (totalItems <= 1) {
                Log.w(TAG, "No other stations to skip to")
                showPlaybackError(reason, "No other stations available")
                isAutoSkipping = false
                return
            }
            
            // Try to find next playable station
            var attempts = 0
            val maxAttempts = minOf(totalItems, 10) // Prevent infinite loops
            
            while (attempts < maxAttempts) {
                val nextIndex = (currentPlayer.currentMediaItemIndex + 1) % totalItems
                val nextMediaItem = currentPlayer.getMediaItemAt(nextIndex)
                val nextMediaId = nextMediaItem.mediaId
                
                Log.d(TAG, "Attempting to skip to station at index $nextIndex: $nextMediaId")
                
                if (!failedStations.contains(nextMediaId)) {
                    Log.i(TAG, "Skipping to next station: $nextMediaId (Reason: $reason)")
                    currentPlayer.seekToDefaultPosition(nextIndex)
                    currentPlayer.prepare()
                    currentPlayer.play()
                    break
                } else {
                    Log.d(TAG, "Skipping failed station: $nextMediaId")
                    attempts++
                }
            }
            
            if (attempts >= maxAttempts) {
                Log.e(TAG, "All stations failed to play")
                showPlaybackError(reason, "All stations failed to play. Please check your internet connection.")
                // Reset failed stations after complete failure
                failedStations.clear()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during station skip", e)
            showPlaybackError(reason, "Failed to skip to next station")
        } finally {
            isAutoSkipping = false
        }
    }
    
    
    /**
     * Validate station before playback
     */
    private fun isValidStation(station: Station): Boolean {
        return station.urlResolved.isNotBlank() &&
               station.name.isNotBlank() &&
               (station.lastCheckOk == 1) &&
               !failedStations.contains(station.stationuuid)
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
        Log.w(TAG, "Playback error - Reason: $reason, Details: $details")
        
        // You could show a notification or toast here
        // For now, we'll just log it
        serviceScope.launch {
            try {
                // Update media metadata to show error state
                player?.let { player ->
                    val currentMediaItem = player.currentMediaItem
                    if (currentMediaItem != null) {
                        val errorMetadata = MediaMetadata.Builder()
                            .setTitle("Playback Error")
                            .setArtist(details)
                            .setGenre("Error")
                            .build()
                        
                        val errorItem = MediaItem.Builder()
                            .setMediaId("error")
                            .setUri(currentMediaItem.localConfiguration?.uri)
                            .setMediaMetadata(errorMetadata)
                            .build()
                        
                        player.setMediaItem(errorItem)
                        player.prepare()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show error metadata", e)
            }
        }
    }
}
