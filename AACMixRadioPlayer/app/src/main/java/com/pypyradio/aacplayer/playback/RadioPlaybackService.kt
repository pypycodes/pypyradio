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

    @Volatile private var topStations: List<Station> = emptyList()
    @Volatile private var topHindiStations: List<Station> = emptyList()
    @Volatile private var topEnglishStations: List<Station> = emptyList()
    @Volatile private var favoriteStations: List<Station> = emptyList()
    @Volatile private var trendingPodcasts: List<Podcast> = emptyList()
    @Volatile private var podcastEpisodesCache: Map<String, List<PodcastEpisode>> = emptyMap()
    
    // Track current playlist context for next/prev navigation
    @Volatile private var currentPlaylistContext: String = MEDIA_ID_TOP

    private var retryCount = 0
    
    // Locks to keep network alive when screen is off
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val maxRetries = 3

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
            return try {
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
                    MEDIA_ID_FAV -> favoriteStations.map { playableFromStation(it) }
                    MEDIA_ID_PODCASTS -> trendingPodcasts.map { browsableFromPodcast(it) }
                    else -> {
                        // Check if it's a podcast ID - load episodes
                        if (parentId.startsWith("podcast_")) {
                            val podcastId = parentId.removePrefix("podcast_")
                            // Don't include URI for browsing - only for playback
                            podcastEpisodesCache[podcastId]?.map { playableFromEpisode(it, includeUri = false) } ?: run {
                                // Load episodes async and return empty for now
                                scope.launch(Dispatchers.IO) {
                                    loadPodcastEpisodes(podcastId)
                                }
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }
                    }
                }
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            } catch (e: Exception) {
                Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // Resolve media items for Android Auto playback - include URIs for actual playback
            val resolvedItems = mediaItems.mapNotNull { requestedItem ->
                val mediaId = requestedItem.mediaId
                // Check if it's a podcast episode
                if (mediaId.startsWith("episode_")) {
                    val episodeId = mediaId.removePrefix("episode_")
                    val ep = podcastEpisodesCache.values.flatten().find { it.id == episodeId }
                    if (ep != null) return@mapNotNull playableFromEpisode(ep)
                } else {
                    // Find station in our lists and include URI for playback
                    val station = topStations.find { it.stationuuid == mediaId }
                        ?: topHindiStations.find { it.stationuuid == mediaId }
                        ?: topEnglishStations.find { it.stationuuid == mediaId }
                        ?: favoriteStations.find { it.stationuuid == mediaId }
                    if (station != null) return@mapNotNull playableFromStation(station, includeUri = true)
                }
                
                // If not found in caches but controller provided a requestMetadata with mediaUri
                if (requestedItem.requestMetadata.mediaUri != null) {
                    return@mapNotNull requestedItem.buildUpon()
                        .setUri(requestedItem.requestMetadata.mediaUri)
                        .build()
                }
                
                null
            }.toMutableList()
            
            return Futures.immediateFuture(
                if (resolvedItems.isNotEmpty()) resolvedItems else mediaItems
            )
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
                    ActivePlaylistCache.currentBrowseItems.any { it.stationuuid == id } -> 
                        ActivePlaylistCache.currentBrowseItems.map { playableFromStation(it, includeUri = true) } to "browse_active"
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
                            emptyList<MediaItem>() to MEDIA_ID_TOP
                        }
                    }
                }
                
                currentPlaylistContext = context
                val selectedIndex = playlist.indexOfFirst { it.mediaId == id }
                if (selectedIndex >= 0 && playlist.isNotEmpty()) {
                    // Prevent TransactionTooLargeException over IPC by returning a windowed subset
                    // 100 items is plenty for Next/Prev functionality without blowing up the Binder limit
                    val startIndexInSublist = kotlin.math.max(0, selectedIndex - 50)
                    val endIndexInSublist = kotlin.math.min(playlist.size, selectedIndex + 50)
                    val windowedPlaylist = playlist.subList(startIndexInSublist, endIndexInSublist)
                    
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            windowedPlaylist, 
                            selectedIndex - startIndexInSublist, 
                            0L
                        )
                    )
                }
                // Station not found in caches — fall through to play just the single item
            }
            
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
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
                        
                        // Buffer for max 3 retries (progressive backoff) before auto-skipping.
                        // This allows up to ~12 seconds of recovery time for cellular dead zones
                        // without making the player feel permanently frozen.
                        val maxRetryForError = if (isNetworkError) 3 else maxRetries
                        
                        if (retryCount < maxRetryForError && currentMediaItem != null) {
                            val waitMs = when (retryCount) {
                                0 -> 2000L // 2 sec initial buffer
                                1 -> 4000L // 4 sec secondary buffer
                                else -> 6000L // 6 sec final effort
                            }
                            retryCount++
                            scope.launch {
                                delay(waitMs)
                                // Reset and prepare fresh for better recovery
                                stop()
                                prepare()
                                play()
                            }
                        } else if (mediaItemCount > 1) {
                            // Auto-skip to next station if retries exhausted
                            scope.launch {
                                delay(500L)
                                retryCount = 0
                                if (hasNextMediaItem()) {
                                    seekToNextMediaItem()
                                } else {
                                    // Loop back to first station
                                    seekTo(0, 0L)
                                }
                                prepare()
                                play()
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
                        // Reset retry count when playback is ready/playing
                        if (playbackState == Player.STATE_READY) {
                            retryCount = 0
                        }
                    }
                })
            }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaLibrarySession.Builder(this, player!!, callback)
            .setSessionActivity(pendingIntent)
            .build()

        // Async prefetch top stations and podcasts for Android Auto browsing
        scope.launch(Dispatchers.IO) {
            topStations = repo.topVotedAac(120)
            launch(Dispatchers.Main) { session?.notifyChildrenChanged(MEDIA_ID_TOP, topStations.size, null) }
        }
        
        scope.launch(Dispatchers.IO) {
            topHindiStations = repo.searchByLanguage("hindi", 100)
            launch(Dispatchers.Main) { session?.notifyChildrenChanged(MEDIA_ID_HINDI, topHindiStations.size, null) }
        }
        
        scope.launch(Dispatchers.IO) {
            topEnglishStations = repo.searchByLanguage("english", 100)
            launch(Dispatchers.Main) { session?.notifyChildrenChanged(MEDIA_ID_ENGLISH, topEnglishStations.size, null) }
        }
        
        scope.launch(Dispatchers.IO) {
            trendingPodcasts = podcastRepo.getTrendingPodcasts(50)
            launch(Dispatchers.Main) { session?.notifyChildrenChanged(MEDIA_ID_PODCASTS, trendingPodcasts.size, null) }
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
        
        // Only include URI when actually playing, not for browsing
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
