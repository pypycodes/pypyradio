package com.pypyradio.aacplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
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
                .add(Player.COMMAND_ADJUST_DEVICE_VOLUME)
                .add(Player.COMMAND_SET_DEVICE_VOLUME)
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
                        podcastEpisodesCache[podcastId]?.map { playableFromEpisode(it) } ?: run {
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

            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            // Resolve media items for Android Auto playback
            val resolvedItems = mediaItems.mapNotNull { requestedItem ->
                val mediaId = requestedItem.mediaId
                // Check if it's a podcast episode
                if (mediaId.startsWith("episode_")) {
                    val episodeId = mediaId.removePrefix("episode_")
                    podcastEpisodesCache.values.flatten().find { it.id == episodeId }?.let {
                        playableFromEpisode(it)
                    }
                } else {
                    // Find station in our lists
                    val station = topStations.find { it.stationuuid == mediaId }
                        ?: topHindiStations.find { it.stationuuid == mediaId }
                        ?: topEnglishStations.find { it.stationuuid == mediaId }
                        ?: favoriteStations.find { it.stationuuid == mediaId }
                    station?.let { playableFromStation(it) }
                }
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
            
            // If playlist has multiple items with URIs, use it directly (from app UI)
            // This handles India/Hindi tab, Favorites, search results, etc.
            if (mediaItems.size > 1 || (mediaItems.size == 1 && mediaItems[0].localConfiguration != null)) {
                // Playlist from app UI - use as-is
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
                )
            }
            
            // Single item without URI - Android Auto browsing request
            // Determine which playlist context this station belongs to and expand for next/prev
            if (mediaItems.size == 1 && mediaItems[0].localConfiguration == null) {
                // Find which list contains this station and build full playlist
                val (playlist, context) = when {
                    topHindiStations.any { it.stationuuid == id } -> 
                        topHindiStations.map { playableFromStation(it) } to MEDIA_ID_HINDI
                    topEnglishStations.any { it.stationuuid == id } -> 
                        topEnglishStations.map { playableFromStation(it) } to MEDIA_ID_ENGLISH
                    favoriteStations.any { it.stationuuid == id } -> 
                        favoriteStations.map { playableFromStation(it) } to MEDIA_ID_FAV
                    topStations.any { it.stationuuid == id } -> 
                        topStations.map { playableFromStation(it) } to MEDIA_ID_TOP
                    else -> {
                        // Check podcast episodes
                        val episode = podcastEpisodesCache.entries.find { (_, eps) -> 
                            eps.any { it.id == id?.removePrefix("episode_") }
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
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(playlist, selectedIndex, 0L)
                    )
                }
            }
            
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Create notification channel for Android 8+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "playback",
                "Playback",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Media playback controls"
                setShowBadge(false)
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
                        
                        val maxRetryForError = if (isNetworkError) 5 else maxRetries
                        
                        if (retryCount < maxRetryForError && currentMediaItem != null) {
                            val waitMs = when (retryCount) {
                                0 -> 1000L   // Quick first retry
                                1 -> 2000L
                                2 -> 3000L
                                3 -> 5000L
                                else -> 8000L
                            }
                            retryCount++
                            scope.launch {
                                delay(waitMs)
                                // Reset and prepare fresh for better recovery
                                stop()
                                prepare()
                                play()
                            }
                        } else if (prefs.isAutoSkipEnabled() && mediaItemCount > 1) {
                            // Auto-skip to next station if enabled and retries exhausted
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

        // Prefetch top stations for browsing (but don't auto-load into player - Android Auto requirement MA-1)
        scope.launch(Dispatchers.IO) {
            topStations = runCatching { repo.topVotedAac(120) }.getOrDefault(emptyList())
            // Don't pre-load into player - wait for user action (Android Auto compliance)
        }
        
        // Prefetch Top Hindi stations
        scope.launch(Dispatchers.IO) {
            topHindiStations = runCatching { repo.searchByLanguage("hindi", 100) }.getOrDefault(emptyList())
        }
        
        // Prefetch Top English stations
        scope.launch(Dispatchers.IO) {
            topEnglishStations = runCatching { repo.searchByLanguage("english", 100) }.getOrDefault(emptyList())
        }
        
        // Prefetch trending podcasts for Android Auto browsing
        scope.launch(Dispatchers.IO) {
            trendingPodcasts = runCatching { podcastRepo.getTrendingPodcasts(50) }.getOrDefault(emptyList())
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
        session?.run {
            player?.release()
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
        // Use folder type for browsable items
        val folderType = when (id) {
            MEDIA_ID_TOP -> MediaMetadata.FOLDER_TYPE_PLAYLISTS
            MEDIA_ID_HINDI -> MediaMetadata.FOLDER_TYPE_PLAYLISTS
            MEDIA_ID_ENGLISH -> MediaMetadata.FOLDER_TYPE_PLAYLISTS
            MEDIA_ID_FAV -> MediaMetadata.FOLDER_TYPE_PLAYLISTS
            MEDIA_ID_PODCASTS -> MediaMetadata.FOLDER_TYPE_PODCASTS
            else -> MediaMetadata.FOLDER_TYPE_MIXED
        }
        
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(folderType)
                    .build()
            )
            .build()
    }

    private fun playableFromStation(st: Station): MediaItem {
        // Build artwork URI from favicon if available
        val artworkUri = st.favicon?.takeIf { it.isNotBlank() }?.let { 
            android.net.Uri.parse(it) 
        }
        
        return MediaItem.Builder()
            .setMediaId(st.stationuuid)
            .setUri(st.urlResolved)
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
            .build()
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
                    .setFolderType(MediaMetadata.FOLDER_TYPE_ALBUMS)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
                    .build()
            )
            .build()
    }
    
    private fun playableFromEpisode(episode: PodcastEpisode): MediaItem {
        val artworkUri = episode.imageUrl?.takeIf { it.isNotBlank() }?.let {
            android.net.Uri.parse(it)
        }
        
        return MediaItem.Builder()
            .setMediaId("episode_${episode.id}")
            .setUri(episode.audioUrl)
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
            .build()
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
