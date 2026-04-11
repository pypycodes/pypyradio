package com.pypyradio.aacplayer.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.DefaultMediaNotificationProvider
import com.pypyradio.aacplayer.MainActivity
import com.pypyradio.aacplayer.data.db.AppDatabase
import com.pypyradio.aacplayer.data.model.Podcast
import com.pypyradio.aacplayer.data.model.PodcastEpisode
import com.pypyradio.aacplayer.data.model.PodcastSource
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.data.prefs.AppPreferences
import com.pypyradio.aacplayer.data.repo.PodcastRepository
import com.pypyradio.aacplayer.data.repo.StationRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class RadioPlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var repo: StationRepository
    private lateinit var podcastRepo: PodcastRepository
    private lateinit var prefs: AppPreferences

    // Initialize with static fallback stations for immediate Android Auto response
    @Volatile private var topStations: List<Station> = createStaticTopStations()
    @Volatile private var topHindiStations: List<Station> = createStaticHindiStations()
    @Volatile private var topEnglishStations: List<Station> = createStaticEnglishStations()
    @Volatile private var favoriteStations: List<Station> = emptyList()
    @Volatile private var trendingPodcasts: List<Podcast> = createStaticPodcasts()
    @Volatile private var podcastEpisodesCache: Map<String, List<PodcastEpisode>> = emptyMap()
    
    // Track current playlist context for next/prev navigation
    @Volatile private var currentPlaylistContext: String = MEDIA_ID_TOP

    private var retryCount = 0
    
    // Locks to keep network alive when screen is off
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val maxRetries = 3

    // Static fallback stations for immediate Android Auto response
    private fun createStaticTopStations(): List<Station> {
        return listOf(
            Station(
                stationuuid = "static_1",
                name = "BBC Radio 1",
                urlResolved = "http://stream.live.vc.bbcmedia.co.uk/bbc_radio_one",
                homepage = "https://www.bbc.co.uk/radio1",
                favicon = null,
                tags = null,
                countryCode = "GB",
                language = "english",
                codec = "mp3",
                bitrate = 128,
                lastCheckOk = 1
            ),
            Station(
                stationuuid = "static_2", 
                name = "Radio Mirchi",
                urlResolved = "http://radio.indianwebradio.com:8000/radiomirchi",
                homepage = "https://www.radiomirchi.com",
                favicon = null,
                tags = null,
                countryCode = "IN",
                language = "hindi",
                codec = "mp3",
                bitrate = 96,
                lastCheckOk = 1
            ),
            Station(
                stationuuid = "static_3",
                name = "NPR News",
                urlResolved = "https://npr-live-mp3-128.akacast.akamaistream.net/7477_1394813",
                homepage = "https://www.npr.org",
                favicon = null,
                tags = null,
                countryCode = "US",
                language = "english",
                codec = "mp3",
                bitrate = 128,
                lastCheckOk = 1
            )
        )
    }
    
    private fun createStaticHindiStations(): List<Station> {
        return listOf(
            Station(
                stationuuid = "hindi_1",
                name = "Radio City",
                urlResolved = "http://radio.indianwebradio.com:8000/radiocity",
                homepage = "https://www.radiocity.in",
                favicon = null,
                tags = null,
                countryCode = "IN",
                language = "hindi",
                codec = "mp3",
                bitrate = 96,
                lastCheckOk = 1
            ),
            Station(
                stationuuid = "hindi_2",
                name = "Red FM",
                urlResolved = "http://radio.indianwebradio.com:8000/redfm",
                homepage = "https://www.redfm.in",
                favicon = null,
                tags = null,
                countryCode = "IN", 
                language = "hindi",
                codec = "mp3",
                bitrate = 96,
                lastCheckOk = 1
            )
        )
    }
    
    private fun createStaticEnglishStations(): List<Station> {
        return listOf(
            Station(
                stationuuid = "english_1",
                name = "Classic FM",
                urlResolved = "http://media-ice.musicradio.com:80/ClassicFMMP3",
                homepage = "https://www.classicfm.com",
                favicon = null,
                tags = null,
                countryCode = "GB",
                language = "english",
                codec = "mp3",
                bitrate = 128,
                lastCheckOk = 1
            ),
            Station(
                stationuuid = "english_2",
                name = "Jazz 24",
                urlResolved = "http://jazz24-128k.streamguys1.com/jazz24.mp3",
                homepage = "https://www.jazz24.org",
                favicon = null,
                tags = null,
                countryCode = "US",
                language = "english",
                codec = "mp3",
                bitrate = 128,
                lastCheckOk = 1
            )
        )
    }
    
    private fun createStaticPodcasts(): List<Podcast> {
        return listOf(
            Podcast(
                id = "podcast_1",
                title = "The Daily",
                author = "The New York Times",
                description = "This is what a news podcast should sound like",
                imageUrl = null,
                feedUrl = null,
                genre = "News",
                episodeCount = null,
                source = PodcastSource.PODCAST_INDEX
            ),
            Podcast(
                id = "podcast_2", 
                title = "Tech Talk",
                author = "Tech Podcast Network",
                description = "Latest technology news and discussions",
                imageUrl = null,
                feedUrl = null,
                genre = "Technology",
                episodeCount = null,
                source = PodcastSource.PODCAST_INDEX
            )
        )
    }

    private val callback = object : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // Enable all playback commands including next/prev for Android Auto
            val availableSessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .build()
            
            val availablePlayerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SET_SHUFFLE_MODE)
                .add(Player.COMMAND_SET_REPEAT_MODE)
                .add(Player.COMMAND_GET_DEVICE_VOLUME)
                .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
                .build()
            
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(availableSessionCommands)
                .setAvailablePlayerCommands(availablePlayerCommands)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId(MEDIA_ID_ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("pypyradio")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            android.util.Log.d("RadioService", "onGetChildren called for parentId: $parentId")
            
            val items = when (parentId) {
                MEDIA_ID_ROOT -> listOf(
                    browsable(MEDIA_ID_TOP, "Top Stations"),
                    browsable(MEDIA_ID_HINDI, "Top Hindi"),
                    browsable(MEDIA_ID_ENGLISH, "Top English"),
                    browsable(MEDIA_ID_FAV, "Favorites"),
                    browsable(MEDIA_ID_PODCASTS, "Podcasts")
                )
                MEDIA_ID_TOP -> topStations.map { playableFromStation(it) }
                MEDIA_ID_HINDI -> topHindiStations.map { playableFromStation(it) }
                MEDIA_ID_ENGLISH -> topEnglishStations.map { playableFromStation(it) }
                MEDIA_ID_FAV -> {
                    if (favoriteStations.isEmpty()) {
                        // Return a "no favorites" placeholder
                        val noFavStation = Station(
                            stationuuid = "no_favorites",
                            name = "No favorites added",
                            urlResolved = "",
                            homepage = null,
                            favicon = null,
                            tags = null,
                            countryCode = null,
                            language = null,
                            codec = null,
                            bitrate = null,
                            lastCheckOk = null
                        )
                        listOf(playableFromStation(noFavStation))
                    } else {
                        favoriteStations.map { playableFromStation(it) }
                    }
                }
                MEDIA_ID_PODCASTS -> trendingPodcasts.map { browsableFromPodcast(it) }
                else -> {
                    // Check if it's a podcast ID - load episodes
                    if (parentId.startsWith("podcast_")) {
                        val podcastId = parentId.removePrefix("podcast_")
                        podcastEpisodesCache[podcastId]?.map { playableFromEpisode(it, includeUri = false) } ?: run {
                            // Return a placeholder episode
                            val placeholderEpisode = PodcastEpisode(
                                id = "placeholder_episode",
                                title = "Loading episodes...",
                                podcastId = podcastId,
                                podcastTitle = null,
                                author = null,
                                description = null,
                                audioUrl = "",
                                imageUrl = null,
                                durationMs = null,
                                publishedDate = null,
                                source = PodcastSource.PODCAST_INDEX
                            )
                            listOf(playableFromEpisode(placeholderEpisode, includeUri = false))
                        }
                    } else {
                        emptyList()
                    }
                }
            }
            
            android.util.Log.d("RadioService", "Returning ${items.size} items for $parentId")
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // IMPORTANT: use map (not mapNotNull) so list size is NEVER changed.
            // Dropping items shifts indices and breaks startIndex, causing wrong station to play.
            val resolvedItems = mediaItems.map { requestedItem ->
                val mediaId = requestedItem.mediaId
                // Podcast episode
                if (mediaId.startsWith("episode_")) {
                    val episodeId = mediaId.removePrefix("episode_")
                    val ep = podcastEpisodesCache.values.flatten().find { it.id == episodeId }
                    if (ep != null) return@map playableFromEpisode(ep)
                } else {
                    val station = topStations.find { it.stationuuid == mediaId }
                        ?: topHindiStations.find { it.stationuuid == mediaId }
                        ?: topEnglishStations.find { it.stationuuid == mediaId }
                        ?: favoriteStations.find { it.stationuuid == mediaId }
                        ?: ActivePlaylistCache.currentBrowseItems.find { it.stationuuid == mediaId }
                    if (station != null) return@map playableFromStation(station, includeUri = true)
                }
                // Fallback: URI already in requestMetadata (set by BrowseScreen/FavoritesScreen)
                val uri = requestedItem.requestMetadata.mediaUri
                if (uri != null) {
                    return@map requestedItem.buildUpon().setUri(uri).build()
                }
                // Last resort: return item as-is (may fail to play but list size preserved)
                requestedItem
            }.toMutableList()
            return Futures.immediateFuture(resolvedItems)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            retryCount = 0
            val id = mediaItems.getOrNull(startIndex)?.mediaId
            if (!id.isNullOrBlank()) {
                scope.launch(Dispatchers.IO) { repo.pingClick(id) }
            }
            
            // Single item play request — from both Android Auto browsing and app tap.
            // Expand into a full playlist from our cached lists for next/prev support.
            // This avoids IPC serialization issues that occur when the app sends a large
            // playlist through MediaController (items can be dropped in onAddMediaItems,
            // causing startIndex to point to the wrong station, typically index 0).
            if (mediaItems.size == 1 && id != null) {
                // Find which cached list contains this station and build full playlist
                val (playlist, context) = when {
                    ActivePlaylistCache.currentBrowseItems.any { it.stationuuid == id } -> {
                        android.util.Log.d("RadioService", "Cache HIT browse_active for $id (${ActivePlaylistCache.currentBrowseItems.size} items)")
                        ActivePlaylistCache.currentBrowseItems.map { playableFromStation(it, includeUri = true) } to "browse_active"
                    }
                    topHindiStations.any { it.stationuuid == id } -> 
                        topHindiStations.map { playableFromStation(it, includeUri = true) } to MEDIA_ID_HINDI
                    topEnglishStations.any { it.stationuuid == id } -> 
                        topEnglishStations.map { playableFromStation(it, includeUri = true) } to MEDIA_ID_ENGLISH
                    favoriteStations.any { it.stationuuid == id } -> 
                        favoriteStations.map { playableFromStation(it, includeUri = true) } to MEDIA_ID_FAV
                    topStations.any { it.stationuuid == id } -> 
                        topStations.map { playableFromStation(it, includeUri = true) } to MEDIA_ID_TOP
                    else -> {
                        // Check podcast episodes
                        val episode = podcastEpisodesCache.entries.find { (_, eps) -> 
                            eps.any { it.id == id.removePrefix("episode_") }
                        }
                        if (episode != null) {
                            episode.value.map { playableFromEpisode(it) } to "podcast_${episode.key}"
                        } else {
                            android.util.Log.w("RadioService", "Cache MISS for $id — browse cache size=${ActivePlaylistCache.currentBrowseItems.size}")
                            emptyList<MediaItem>() to MEDIA_ID_TOP
                        }
                    }
                }
                
                currentPlaylistContext = context
                val selectedIndex = playlist.indexOfFirst { it.mediaId == id }
                if (selectedIndex >= 0 && playlist.isNotEmpty()) {
                    val startIndexInSublist = kotlin.math.max(0, selectedIndex - 50)
                    val endIndexInSublist = kotlin.math.min(playlist.size, selectedIndex + 50)
                    val windowedPlaylist = playlist.subList(startIndexInSublist, endIndexInSublist)
                    android.util.Log.d("RadioService", "Returning ${windowedPlaylist.size} items, startIndex=${selectedIndex - startIndexInSublist}")
                    
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            windowedPlaylist, 
                            selectedIndex - startIndexInSublist, 
                            0L
                        )
                    )
                }
                // Station not found in any cache — play single item via requestMetadata
            }

            // Multi-item request (e.g. windowed playlist from BrowseScreen).
            // MediaController does NOT transmit LocalConfiguration (setUri) over IPC.
            // We MUST resolve each item's URI here from caches or requestMetadata.
            // This is what makes next/prev work reliably for browse results.
            currentPlaylistContext = "browse_active"
            val resolvedItems = mediaItems.map { item ->
                val stId = item.mediaId
                // Try service caches first (exact rebuild with codec info etc.)
                val cachedStation = ActivePlaylistCache.currentBrowseItems.find { it.stationuuid == stId }
                    ?: topStations.find { it.stationuuid == stId }
                    ?: topHindiStations.find { it.stationuuid == stId }
                    ?: topEnglishStations.find { it.stationuuid == stId }
                    ?: favoriteStations.find { it.stationuuid == stId }

                if (cachedStation != null) {
                    playableFromStation(cachedStation, includeUri = true)
                } else {
                    // Not in any cache — use requestMetadata URI sent by the UI
                    val uri = item.requestMetadata.mediaUri
                    if (uri != null) {
                        item.buildUpon().setUri(uri).build()
                    } else {
                        item // Last resort: return as-is, may fail to play
                    }
                }
            }

            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolvedItems, startIndex, startPositionMs)
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // Acquire WiFi lock to keep WiFi active when screen is off
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                @Suppress("DEPRECATION")
                val wifiMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wifiManager.createWifiLock(wifiMode, "pypyradio:wifilock")
                wifiLock?.setReferenceCounted(false)
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            // WiFi lock not critical - continue without it
        }
        
        // Acquire partial wake lock to keep CPU running for network operations
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pypyradio:wakelock")
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire()
            }
        } catch (e: Exception) {
            // Wake lock not critical - continue without it
        }

        // Create notification channel for Android 8+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "playback",
                "Media Playback",
                android.app.NotificationManager.IMPORTANCE_DEFAULT  // Required for lock screen visibility
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
                setSound(null, null)  // No sound for media notifications
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC  // Show on lock screen
            }
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // Notification provider with media controls (foreground)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("playback")
                .setNotificationId(1001)
                .build()
        )

        repo = StationRepository(AppDatabase.get(this).favoritesDao())
        podcastRepo = PodcastRepository(AppDatabase.get(this).favoritePodcastDao())
        prefs = AppPreferences.get(this)

        // Configure load control optimized for slow/unstable connections (especially Android Auto over car network)
        // Larger buffers = more resilient to network hiccups
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000,   // Min buffer before playback starts (15 sec - longer for car network stability)
                120000,  // Max buffer size (120 sec - very large buffer for Android Auto)
                5000,    // Buffer for playback (5 sec)
                15000    // Buffer for rebuffering (15 sec - more buffer after rebuffer for car)
            )
            .setPrioritizeTimeOverSizeThresholds(true) // Prioritize playback continuity
            .build()
        
        // Audio attributes for music content - CRITICAL for Android Auto volume control
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true) // true = handle audio focus automatically
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK) // Keep WiFi/network alive during playback
            .setDeviceVolumeControlEnabled(true) // Enable steering wheel volume control
            .build().apply {
                playWhenReady = true
                // Enable shuffle and repeat modes for Android Auto controls
                shuffleModeEnabled = false
                repeatMode = Player.REPEAT_MODE_ALL
                // Set device volume to use STREAM_MUSIC for car audio
                setDeviceVolume(getDeviceVolume(), C.VOLUME_FLAG_SHOW_UI)

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // More aggressive retry for network errors
                        val isNetworkError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                        
                        // Buffer for 1 retry before auto-skipping.
                        val maxRetryForError = if (isNetworkError) 1 else maxRetries
                        
                        if (retryCount < maxRetryForError && currentMediaItem != null) {
                            retryCount++
                            scope.launch {
                                delay(1500L) // 1.5 sec — fair chance for network, not frustrating
                                stop()
                                prepare()
                                play()
                            }
                        } else {
                            // Retries exhausted — skip to next station in the queue.
                            scope.launch {
                                delay(300L)
                                retryCount = 0
                                if (hasNextMediaItem()) {
                                    seekToNextMediaItem()
                                    prepare()
                                    play()
                                } else if (mediaItemCount > 1) {
                                    // Loop back to first item
                                    seekTo(0, 0L)
                                    prepare()
                                    play()
                                }
                                // Don't stop - allow continuous cycling through failed stations
                            }
                        }
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        retryCount = 0
                        mediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { id ->
                            scope.launch(Dispatchers.IO) { repo.pingClick(id) }
                        }
                    }
                    
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> retryCount = 0
                            Player.STATE_ENDED -> {
                                // Live radio never legitimately ends.
                                // STATE_ENDED means server closed stream cleanly (dead/invalid stream).
                                // Skip to next immediately — no retry needed.
                                scope.launch {
                                    delay(300L)
                                    retryCount = 0
                                    if (hasNextMediaItem()) {
                                        seekToNextMediaItem()
                                        prepare()
                                        play()
                                    } else if (mediaItemCount > 1) {
                                        // Loop back to first item
                                        seekTo(0, 0L)
                                        prepare()
                                        play()
                                    }
                                    // Don't stop - allow continuous cycling through failed stations
                                }
                            }
                        }
                    }
                })
            }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        try {
            android.util.Log.d("RadioService", "Creating MediaLibrarySession")
            session = MediaLibrarySession.Builder(this, player!!, callback)
                .setSessionActivity(pendingIntent)
                .build()
            android.util.Log.d("RadioService", "MediaLibrarySession created successfully")
        } catch (e: Exception) {
            android.util.Log.e("RadioService", "Failed to create MediaLibrarySession", e)
            // Try to create a minimal session as fallback
            try {
                session = MediaLibrarySession.Builder(this, player!!, callback).build()
                android.util.Log.d("RadioService", "Fallback MediaLibrarySession created")
            } catch (e2: Exception) {
                android.util.Log.e("RadioService", "Failed to create fallback session", e2)
                // Continue without session - at least the service won't crash
            }
        }

        // Load initial data synchronously for Android Auto to prevent blank screen
        scope.launch(Dispatchers.IO) {
            try {
                // Load essential data immediately
                topStations = repo.topVotedAac(50) // Smaller initial load
                withContext(Dispatchers.Main) {
                    session?.notifyChildrenChanged(MEDIA_ID_TOP, topStations.size, null)
                }
                
                // Load rest of data
                launch(Dispatchers.IO) {
                    val moreStations = repo.topVotedAac(120)
                    topStations = moreStations
                    withContext(Dispatchers.Main) {
                        session?.notifyChildrenChanged(MEDIA_ID_TOP, topStations.size, null)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RadioService", "Failed to load top stations", e)
            }
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                topHindiStations = repo.searchByLanguage("hindi", 50)
                withContext(Dispatchers.Main) {
                    session?.notifyChildrenChanged(MEDIA_ID_HINDI, topHindiStations.size, null)
                }
            } catch (e: Exception) {
                android.util.Log.e("RadioService", "Failed to load Hindi stations", e)
            }
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                topEnglishStations = repo.searchByLanguage("english", 50)
                withContext(Dispatchers.Main) {
                    session?.notifyChildrenChanged(MEDIA_ID_ENGLISH, topEnglishStations.size, null)
                }
            } catch (e: Exception) {
                android.util.Log.e("RadioService", "Failed to load English stations", e)
            }
        }
        
        scope.launch(Dispatchers.IO) {
            try {
                trendingPodcasts = podcastRepo.getTrendingPodcasts(30)
                withContext(Dispatchers.Main) {
                    session?.notifyChildrenChanged(MEDIA_ID_PODCASTS, trendingPodcasts.size, null)
                }
            } catch (e: Exception) {
                android.util.Log.e("RadioService", "Failed to load podcasts", e)
            }
        }

        // Keep favorites updated
        scope.launch {
            repo.observeFavorites().collectLatest {
                favoriteStations = it
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // Stop playback when swiped away
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.run {
            stop()
            clearMediaItems()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Release locks
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null
        
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        
        session?.run {
            player.release()
            release()
        }
        player = null
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loadPodcastEpisodes(podcastId: String) {
        val podcast = trendingPodcasts.find { it.id == podcastId } ?: return
        val episodes = runCatching { podcastRepo.getEpisodes(podcast, 30) }.getOrDefault(emptyList())
        podcastEpisodesCache = podcastEpisodesCache + (podcastId to episodes)
        // Notify Android Auto that children changed so it refreshes the episode list
        withContext(Dispatchers.Main) {
            session?.notifyChildrenChanged("podcast_$podcastId", episodes.size, null)
        }
    }
    
    private fun browsable(id: String, title: String): MediaItem {
        // Use media type for browsable items (folderType is deprecated)
        val mediaType = when (id) {
            MEDIA_ID_TOP -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            MEDIA_ID_HINDI -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            MEDIA_ID_ENGLISH -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            MEDIA_ID_FAV -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            MEDIA_ID_PODCASTS -> MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS
            else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        }
        
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()
    }

    private fun playableFromStation(st: Station, includeUri: Boolean = false): MediaItem {
        // Build artwork URI from favicon if available
        val artworkUri = st.favicon?.takeIf { it.isNotBlank() }?.let { 
            android.net.Uri.parse(it) 
        }
        
        val builder = MediaItem.Builder()
            .setMediaId(st.stationuuid)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(st.name)
                    .setArtist(st.countryCode ?: "Radio")
                    .setAlbumTitle(st.tags?.split(",")?.firstOrNull()?.trim() ?: "Internet Radio")
                    .setArtworkUri(artworkUri)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
        
        // Always store the URI in RequestMetadata so onAddMediaItems can resolve it
        // even when the station isn't in the in-memory cache (avoids dropped items on next/prev)
        if (st.urlResolved.isNotBlank()) {
            builder.setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(android.net.Uri.parse(st.urlResolved))
                    .build()
            )
        }
        
        // Also set the URI directly when playing (not just browsing)
        if (includeUri && st.urlResolved.isNotBlank()) {
            builder.setUri(st.urlResolved)
        }
        
        return builder.build()
    }

    private fun browsableFromPodcast(podcast: Podcast): MediaItem {
        val artworkUri = podcast.imageUrl?.takeIf { it.isNotBlank() }?.let {
            android.net.Uri.parse(it)
        }
        
        return MediaItem.Builder()
            .setMediaId("podcast_${podcast.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(podcast.title)
                    .setArtist(podcast.author ?: "Podcast")
                    .setArtworkUri(artworkUri)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
                    .build()
            )
            .build()
    }
    
    private fun playableFromEpisode(episode: PodcastEpisode, includeUri: Boolean = true): MediaItem {
        val artworkUri = episode.imageUrl?.takeIf { it.isNotBlank() }?.let {
            android.net.Uri.parse(it)
        }
        
        val builder = MediaItem.Builder()
            .setMediaId("episode_${episode.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(episode.podcastTitle ?: episode.author ?: "Podcast")
                    .setAlbumTitle(episode.podcastTitle)
                    .setArtworkUri(artworkUri)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                    .build()
            )
        
        // Only include URI when actually playing
        if (includeUri && !episode.audioUrl.isNullOrBlank()) {
            builder.setUri(episode.audioUrl)
        }
        
        return builder.build()
    }

    companion object {
        const val MEDIA_ID_ROOT = "root"
        const val MEDIA_ID_TOP = "top"
        const val MEDIA_ID_HINDI = "hindi"
        const val MEDIA_ID_ENGLISH = "english"
        const val MEDIA_ID_FAV = "fav"
        const val MEDIA_ID_PODCASTS = "podcasts"
    }
}
