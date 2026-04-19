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
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
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
import com.pypyradio.aacplayer.data.prefs.SoundMode

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
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null

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
            val userAgent = "Mozilla/5.0 (Android 14; Mobile; rv:115.0) Gecko/115.0 Firefox/115.0"
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true)

            player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
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

            // Observe sound mode changes
            serviceScope.launch {
                prefs.soundMode.collect { mode ->
                    Log.i(TAG, "Sound Mode Changed: $mode")
                    applySoundMode(mode)
                }
            }

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
    
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed (App swiped away). Stopping playback.")
        
        // Stop the player so it doesn't continue playing as a zombie process
        player?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
        
        // Tell Android to destroy the service
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "===== SERVICE DESTROYED =====")
        serviceScope.cancel()
        session?.release()
        player?.release()
        wifiLock?.release()
        wakeLock?.release()
        // Release volume enhancer
        loudnessEnhancer?.let {
            it.enabled = false
            it.release()
        }
        loudnessEnhancer = null
        dynamicsProcessing?.let {
            it.enabled = false
            it.release()
        }
        dynamicsProcessing = null

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
                        topStations.mapNotNull(::playableItem)
                    }
                    MEDIA_ID_FAVORITES -> {
                        listOf(
                            browsableItem(MEDIA_ID_FAVORITE_STATIONS, "Favorite Stations"),
                            browsableItem(MEDIA_ID_FAVORITE_PODCASTS, "Favorite Podcasts")
                        )
                    }
                    MEDIA_ID_FAVORITE_STATIONS -> {
                        loadFavoritesInternal().mapNotNull(::playableItem)
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
                        englishStations.mapNotNull(::playableItem)
                    }
                    MEDIA_ID_HINDI -> {
                        val indianStations = try {
                            stationRepo.getIndianStations(50)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load Indian stations", e)
                            emptyList()
                        }
                        indianStations.mapNotNull(::playableItem)
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
        ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.future {
            Log.d(TAG, "onGetItem: $mediaId")
            try {
                val item = resolveFromCache(mediaId)
                if (item != null) {
                    LibraryResult.ofItem(item, null)
                } else {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "onGetItem error", e)
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future {
            Log.d(TAG, "onAddMediaItems: ${mediaItems.size} items")
            try {
                mediaItems.map { item ->
                    val mediaId = item.mediaId
                    
                    // Already has URI? Keep it.
                    if (item.localConfiguration?.uri != null) return@map item
                    
                    // Resolve from cache/repository
                    resolveFromCache(mediaId) ?: item
                }.toMutableList()
            } catch (e: Exception) {
                Log.e(TAG, "onAddMediaItems error", e)
                mediaItems
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
            
            // CRITICAL: Immediately cancel any background skip/timeout logic 
            // the moment a manual UI request arrives.
            isAutoSkipping = false
            cancelBufferingTimeoutCheck()
            
            // (Removed async player.stop() because UI and Service now share the same 
            // player instance, and this async call was overwriting the UI's play command)
            try {
                val firstItem = mediaItems.firstOrNull()
                if (firstItem == null) {
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, startPositionMs)
                } else if (mediaItems.size > 1) {
                    // === MULTI-ITEM PLAYBACK (Manual/Legacy) ===
                    // Just passthrough as is, but ensure URIs are set
                    val resolved = mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                        if (uri != null && item.localConfiguration == null) {
                            item.buildUpon().setUri(uri).build()
                        } else item
                    }
                    val safePos = if (startPositionMs <= 0L) androidx.media3.common.C.TIME_UNSET else startPositionMs
                    MediaSession.MediaItemsWithStartPosition(resolved, startIndex, safePos)
                } else {
                    // === ATOMIC SINGLE-ITEM PLAYBACK (Android Auto logic) ===
                    // The UI now only sends ONE item. We expand it here internally
                    // to provide full Next/Prev support without Binder overhead.
                    val tappedMediaId = firstItem.mediaId
                    val tappedUri = firstItem.requestMetadata.mediaUri ?: firstItem.localConfiguration?.uri
                    
                    Log.d(TAG, "Atomic play request for: $tappedMediaId")

                    // 1. Fallback to Auto expansion (Favorites, Podcasts)
                    val (autoList, autoIndex) = buildAutoPlaylist(tappedMediaId)
                    var targetPlaylist = autoList
                    var targetIndex = autoIndex

                    val safePos = if (startPositionMs <= 0L) androidx.media3.common.C.TIME_UNSET else startPositionMs
                    if (targetPlaylist.isNotEmpty()) {
                        Log.i(TAG, "Playing expanded playlist: ${targetPlaylist.size} items, index=$targetIndex")
                        MediaSession.MediaItemsWithStartPosition(targetPlaylist, targetIndex, safePos)
                    } else {
                        // 3. Absolute fallback: Just play the single item
                        Log.i(TAG, "Could not expand playlist — playing as standalone item")
                        val single = if (tappedUri != null) {
                            if (firstItem.localConfiguration == null) firstItem.buildUpon().setUri(tappedUri).build()
                            else firstItem
                        } else {
                            resolveFromCache(tappedMediaId)
                        }
                        
                        if (single != null) {
                            MediaSession.MediaItemsWithStartPosition(listOf(single), 0, safePos)
                        } else {
                            showPlaybackError("Station not found", "Could not resolve: $tappedMediaId")
                            MediaSession.MediaItemsWithStartPosition(emptyList(), 0, androidx.media3.common.C.TIME_UNSET)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onSetMediaItems error", e)
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            }
        }
        
        /**
         * Resolve a single mediaId from internal caches (stations, favorites, podcasts)
         * or fallback to database/API lookup for search/browse results.
         */
        private suspend fun resolveFromCache(mediaId: String): MediaItem? {
            // 1. Check top stations cache
            var station = cachedStations.find { it.stationuuid == mediaId }
            
            // 2. Check favorites
            if (station == null) {
                station = cachedFavorites.find { it.stationuuid == mediaId }
            }
            
            // 3. New: Check ActivePlaylistCache (this contains what the user is seeing in the phone UI)
            if (station == null) {
                station = ActivePlaylistCache.currentBrowseItems.find { it.stationuuid == mediaId }
            }
            
            // 4. Fallback: Query the database/API directly (most robust)
            if (station == null) {
                Log.d(TAG, "Station $mediaId not in cache, attempting repository lookup")
                station = try {
                    stationRepo.getStationByUuid(mediaId)
                } catch (e: Exception) {
                    Log.w(TAG, "Repository lookup failed for $mediaId", e)
                    null
                }
                
                // If found, add to cachedStations so future lookups are faster
                station?.let { 
                    if (cachedStations.size < 500) {
                        cachedStations = cachedStations + it
                    }
                }
            }

            if (station != null && isValidStation(station)) {
                return playableItem(station)
            }
            
            // 5. Podcast episodes
            val episode = cachedEpisodes.values.flatten().find { it.id == mediaId }
            if (episode != null && isValidEpisode(episode)) {
                return playablePodcastEpisodeItem(episode)
            }
            
            Log.w(TAG, "Media item could not be resolved: $mediaId")
            return null
        }
        
        /**
         * Safely map a list of stations to MediaItems using a sliding window around the tapped item.
         * This prevents Binder IPC crashes (TransactionTooLargeException) when playlists exceed ~100 items.
         */
        private fun windowedPlaylist(stations: List<Station>, tappedMediaId: String): Pair<List<MediaItem>, Int>? {
            val foundIndex = stations.indexOfFirst { it.stationuuid == tappedMediaId }
            if (foundIndex < 0) return null
            
            val window = 1 // Original stable N-1, N, N+1 logic to avoid Binder IPC limits
            val from = maxOf(0, foundIndex - window)
            val to = minOf(stations.size, foundIndex + window + 1)
            val slice = stations.subList(from, to)
            
            val items = slice.filter { isValidStation(it) }.mapNotNull(::playableItem)
            val index = items.indexOfFirst { it.mediaId == tappedMediaId }.coerceAtLeast(0)
            return items to index
        }
        
        private suspend fun buildAutoPlaylist(tappedMediaId: String): Pair<List<MediaItem>, Int> {
            // 1. Check favorites first (most common use case where Next/Prev is heavily used)
            windowedPlaylist(cachedFavorites, tappedMediaId)?.let {
                Log.d(TAG, "Auto playlist from Favorites: ${it.first.size} items")
                // CRITICAL: Synchronize browse cache so dynamic shifting works on Android Auto
                ActivePlaylistCache.currentBrowseItems = cachedFavorites
                return it
            }
            
            // 2. Podcast episodes
            val allEpisodes = cachedEpisodes.values.flatten()
            val episode = allEpisodes.find { it.id == tappedMediaId }
            if (episode != null) {
                // Find the podcast this episode belongs to and return all episodes as playlist
                val podcastId = cachedEpisodes.entries.find { (_, eps) -> eps.any { it.id == tappedMediaId } }?.key
                val podcastEpisodes = podcastId?.let { cachedEpisodes[it] } ?: listOf(episode)
                val items = podcastEpisodes.filter { isValidEpisode(it) }.map(::playablePodcastEpisodeItem)
                val index = items.indexOfFirst { it.mediaId == tappedMediaId }.coerceAtLeast(0)
                Log.d(TAG, "Auto playlist from Podcast: ${items.size} episodes")
                return items to index
            }
            
            // 5. Fallback: return empty (caller will try single-item resolution)
            return emptyList<MediaItem>() to 0
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
    } // End of RadioLibraryCallback

    private fun playableItem(station: Station): MediaItem? {
        return try {
            // Ensure we have a valid playable URL string to avoid MediaItem.Builder crashes
            val uriStr = station.urlResolved.takeIf { it.isNotBlank() } ?: ""
            
            MediaItem.Builder()
                .setMediaId(station.stationuuid)
                .setUri(uriStr)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(station.name.take(100))
                        .setArtist(station.countryCode?.take(10) ?: "Radio")
                        .setGenre(station.tags?.take(50) ?: "Radio")
                        .setAlbumTitle(station.language?.take(50)?.let { "$it Radio" } ?: "Radio")
                        .setArtworkUri(station.favicon?.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) } 
                            ?: android.net.Uri.parse("android.resource://${packageName}/drawable/pypyradio_fallback_cover_art"))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Skipping station ${station.stationuuid} due to unparseable URL", e)
            null
        }
    }

    
    private fun playablePodcastEpisodeItem(episode: PodcastEpisode): MediaItem {
        return MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(episode.audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.podcastTitle ?: "Podcast")
                    .setGenre("Podcast")
                    .setArtworkUri(episode.imageUrl?.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) }
                        ?: android.net.Uri.parse("android.resource://${packageName}/drawable/pypyradio_fallback_cover_art"))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }
    /**
     * Player listener for handling playback errors and automatic station skipping
     */
    private inner class PlayerErrorListener : Player.Listener {
        
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error occurred", error)
            handlePlaybackError(error)
        }
        
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val p = player ?: return
            // RACE CONDITION FIX: If the UI commanded 'play()' (playWhenReady=true) but the 
            // playlist items arrived asynchronously *afterwards*, the player will be stuck in STATE_IDLE.
            // When the timeline finally arrives, we must manually wake the player up via prepare().
            if (p.playbackState == Player.STATE_IDLE && p.playWhenReady && !timeline.isEmpty) {
                Log.i(TAG, "Timeline populated asynchronously. Waking up player and forcing prepare().")
                p.prepare()
            }
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
                    
                    // SAFETY: Re-force volume for emulators on every readiness change
                    if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator")) {
                        player?.volume = 1.0f
                    }
                    
                    // CRITICAL: Ensure Sound Modes are active for the current session
                    applySoundMode(prefs.getSoundMode())
                    
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
                    // For live radio streams, STATE_ENDED is a normal event
                    // (server disconnects briefly, stream rotates, etc.).
                    // Do NOT auto-skip — just let the player sit in ENDED state.
                    // The user can tap Next or Retry. The BrowseScreen UI will
                    // NOT mark this as a failure (we removed that earlier).
                    cancelBufferingTimeoutCheck()
                }
            }
        }
        
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentStationIndex = player?.currentMediaItemIndex ?: 0
            val mediaId = mediaItem?.mediaId
            Log.d(TAG, "Media item transition: $mediaId (index: $currentStationIndex, reason: $reason)")
            
            // If the user manually skips (SEEK), force the player to prepare and play 
            // the new item immediately. This recovers from error/stopped states.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                player?.let { p ->
                    if (!p.isPlaying || p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) {
                        Log.i(TAG, "Manual skip detected. Forcing playback recovery.")
                        p.prepare()
                        p.play()
                    }
                }
            }

            // Apply current sound mode constraints on every track change
            applySoundMode(prefs.getSoundMode())
            
            // DYNAMIC TIMELINE SHIFTING
            // Allows the user to continuously hit Next or Prev sequentially, automatically
            // fetching the "new N+1" gracefully into ExoPlayer without violating Binder size limits.
            val currentId = mediaItem?.mediaId ?: return
            
            // Determine which list we are moving through (Priority: Phone Browse -> Favorites -> Top Stations)
            var sourceList = ActivePlaylistCache.currentBrowseItems
            var foundIndex = sourceList.indexOfFirst { it.stationuuid == currentId }
            
            if (foundIndex < 0) {
                sourceList = cachedFavorites
                foundIndex = sourceList.indexOfFirst { it.stationuuid == currentId }
            }
            
            if (foundIndex < 0) {
                sourceList = cachedStations
                foundIndex = sourceList.indexOfFirst { it.stationuuid == currentId }
            }
            
            if (foundIndex >= 0) {
                player?.let { p ->
                    // Dynamically append N+1 if we reached the right edge of our micro-window
                    if (p.currentMediaItemIndex >= p.mediaItemCount - 1 && foundIndex + 1 < sourceList.size) {
                        val nextStation = sourceList[foundIndex + 1]
                        playableItem(nextStation)?.let {
                            Log.i(TAG, "Dynamic Expand: Appending Next station ${it.mediaId} to timeline edge")
                            p.addMediaItem(it)
                        }
                    }
                    // Dynamically prepend N-1 if we reached the left edge of our micro-window
                    if (p.currentMediaItemIndex == 0 && foundIndex - 1 >= 0) {
                        val prevStation = sourceList[foundIndex - 1]
                        playableItem(prevStation)?.let {
                            Log.i(TAG, "Dynamic Expand: Prepending Prev station ${it.mediaId} to timeline edge")
                            p.addMediaItem(0, it)
                        }
                    }
                }
            }
        }


        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            super.onAudioSessionIdChanged(audioSessionId)
            reInitAudioEffects(audioSessionId)
        }
    }

    private fun reInitAudioEffects(audioSessionId: Int) {
        try {
            // 1. Setup Loudness Enhancer
            currentAudioSessionId = audioSessionId
            loudnessEnhancer?.release()
            val enhancer = LoudnessEnhancer(audioSessionId)
            val mode = prefs.getSoundMode()
            val targetGain = when(mode) {
                com.pypyradio.aacplayer.data.prefs.SoundMode.LOUD -> 800
                com.pypyradio.aacplayer.data.prefs.SoundMode.STUDY -> 300
                com.pypyradio.aacplayer.data.prefs.SoundMode.NIGHT -> 0
                else -> 150
            }
            enhancer.setTargetGain(targetGain)
            enhancer.enabled = targetGain > 0
            loudnessEnhancer = enhancer

            // 2. Setup Dynamics Processing (DRC)
            dynamicsProcessing?.release()
            
            // SAFETY: Advanced audio effects (DynamicsProcessing/LoudnessEnhancer) 
            // often cause silences or crashes in virtualized emulator environments.
            val isEmulator = Build.FINGERPRINT.startsWith("generic") || 
                           Build.FINGERPRINT.startsWith("unknown") ||
                           Build.MODEL.contains("google_sdk") || 
                           Build.MODEL.contains("Emulator") || 
                           Build.MODEL.contains("Android SDK built for x86") ||
                           Build.PRODUCT.contains("sdk_gphone") ||
                           Build.PRODUCT.contains("vbox86p")
            
            if (isEmulator) {
                Log.i(TAG, "Emulator detected ($isEmulator). Bypassing Audio FX for sound compatibility.")
                dynamicsProcessing = null
                loudnessEnhancer = null
                enhancer.release()
                player?.volume = 1.0f 
            } else {
                try {
                    // Initialize hardware DRC
                    val baseConfig = createDRCConfig()
                    val dp = DynamicsProcessing(0, audioSessionId, baseConfig)
                    dynamicsProcessing = dp
                    applySoundMode(mode) 
                    Log.i(TAG, "Hardware Dynamic Range Compression initialized for session $audioSessionId")
                } catch (e: Exception) {
                    Log.w(TAG, "DynamicsProcessing not supported: falling back to basic boost", e)
                    dynamicsProcessing = null
                    enhancer.enabled = targetGain > 0
                }
            }
            
            Log.i(TAG, "Audio FX initialized for session $audioSessionId in $mode mode")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize Audio FX", e)
        }
    }
    
    private var bufferingTimeoutJob: Job? = null
    
    private fun startBufferingTimeoutCheck() {
        cancelBufferingTimeoutCheck()
        bufferingTimeoutJob = serviceScope.launch {
            val timeoutValue = 35000L // 35 seconds timeout
            delay(timeoutValue)
            val currentState = player?.playbackState
            if (currentState == Player.STATE_BUFFERING) {
                Log.w(TAG, "Buffering timeout (35s) - station may be dead")
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
        
        // Check user preferences for Smart Auto-Skip
        if (prefs.isAutoSkipEnabled()) {
            if (isAutoSkipping) return
            Log.i(TAG, "Smart Auto-Skip enabled: Attempting to move to next station.")
            skipToNextStation("Playback error")
        } else {
            // Let the UI handle the retry/error state. Auto-skipping inherently destroys the user's intended playback context.
            Log.i(TAG, "Smart Auto-Skip disabled: Halting player in error state.")
        }
    }
    
    private var currentAudioSessionId: Int = -1

    private fun applySoundMode(mode: com.pypyradio.aacplayer.data.prefs.SoundMode) {
        val p = player ?: return
        p.volume = 1.0f // Maintain healthy signal for DRC
        
        // SESSION WATCHDOG: If the player's current audio session ID doesn't match 
        // our initialized effects, we must re-bind everything immediately or the mode "does nothing".
        val activeSid = p.audioSessionId
        if (activeSid != 0 && activeSid != currentAudioSessionId) {
            Log.i(TAG, "Audio Session mismatch ($activeSid vs $currentAudioSessionId). Re-initializing effects for mode: $mode")
            reInitAudioEffects(activeSid)
            return // reInitAudioEffects handles the application
        }

        // Apply DynamicsProcessing settings in real-time
        try {
            dynamicsProcessing?.let { dp ->
                applyDRCSettings(dp, mode)
                dp.enabled = true
                Log.i(TAG, "DRC Settings applied for mode: $mode")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply DRC settings", e)
        }

        // Maintain LoudnessEnhancer fallback
        when (mode) {
            com.pypyradio.aacplayer.data.prefs.SoundMode.DEFAULT -> updateLoudnessBoost(150) 
            com.pypyradio.aacplayer.data.prefs.SoundMode.STUDY -> updateLoudnessBoost(300)
            com.pypyradio.aacplayer.data.prefs.SoundMode.NIGHT -> updateLoudnessBoost(0)
            com.pypyradio.aacplayer.data.prefs.SoundMode.LOUD -> updateLoudnessBoost(800)
        }
    }

    private fun applyDRCSettings(dp: DynamicsProcessing, mode: com.pypyradio.aacplayer.data.prefs.SoundMode) {
        val channelIndices = intArrayOf(0, 1) // L, R

        for (ch in channelIndices) {
            when (mode) {
                com.pypyradio.aacplayer.data.prefs.SoundMode.NIGHT -> {
                    // MbcBand(enabled, cutoff, attack, release, ratio, threshold, knee, noiseGate, expanderRatio, preGain, postGain)
                    val mbcBand = DynamicsProcessing.MbcBand(true, 20000f, 5f, 50f, 6f, -24f, 6f, -60f, 1f, 0f, 0f)
                    dp.setMbcBandByChannelIndex(ch, 0, mbcBand)
                    
                    // Limiter(inUse, enabled, linkGroup, attack, release, ratio, threshold, postGain)
                    val limiter = DynamicsProcessing.Limiter(true, true, 0, 1f, 20f, 20f, -2f, 0f)
                    dp.setLimiterByChannelIndex(ch, limiter)
                }
                com.pypyradio.aacplayer.data.prefs.SoundMode.STUDY -> {
                    val mbcBand = DynamicsProcessing.MbcBand(true, 20000f, 10f, 100f, 2.5f, -18f, 6f, -60f, 1f, 0f, 0f)
                    dp.setMbcBandByChannelIndex(ch, 0, mbcBand)
                    
                    val limiter = DynamicsProcessing.Limiter(true, true, 0, 2f, 50f, 20f, -3f, 0f)
                    dp.setLimiterByChannelIndex(ch, limiter)
                }
                com.pypyradio.aacplayer.data.prefs.SoundMode.LOUD -> {
                    val mbcBand = DynamicsProcessing.MbcBand(true, 20000f, 2f, 50f, 4f, -12f, 6f, -60f, 1f, 0f, 0f)
                    dp.setMbcBandByChannelIndex(ch, 0, mbcBand)
                    
                    val limiter = DynamicsProcessing.Limiter(true, true, 0, 1f, 10f, 20f, -1f, 0f)
                    dp.setLimiterByChannelIndex(ch, limiter)
                }
                else -> {
                    val mbcBand = DynamicsProcessing.MbcBand(false, 20000f, 10f, 100f, 1f, 0f, 6f, -60f, 1f, 0f, 0f)
                    dp.setMbcBandByChannelIndex(ch, 0, mbcBand)
                    
                    val limiter = DynamicsProcessing.Limiter(true, true, 0, 2f, 50f, 20f, -1f, 0f)
                    dp.setLimiterByChannelIndex(ch, limiter)
                }
            }
        }
    }

    private fun createDRCConfig(): DynamicsProcessing.Config {
        // Use a standard base config (Stereo, 1-band MBC, Limiter on)
        return DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, // channelCount
            false, // usePreEq
            0,
            true, // useMbc
            1, // mbcBandCount
            false, // usePostEq
            0,
            true // useLimiter
        ).build()
    }

    private fun updateLoudnessBoost(mB: Int) {
        try {
            loudnessEnhancer?.let {
                it.setTargetGain(mB)
                it.enabled = mB > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update loudness boost", e)
        }
    }
    
    private fun skipToNextStation(reason: String) {
        val currentPlayer = player ?: return
        val totalItems = currentPlayer.mediaItemCount
        
        if (totalItems <= 1) {
            Log.w(TAG, "Cannot skip: Only one item in playlist. Stopping playback.")
            currentPlayer.stop()
            currentPlayer.prepare() // Prepare to allow the user to manually retry if they want
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
                // Check both localConfiguration (set by service) and requestMetadata (survives IPC)
                val url = nextItem.localConfiguration?.uri?.toString()
                    ?: nextItem.requestMetadata.mediaUri?.toString()
                    ?: ""
                
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
