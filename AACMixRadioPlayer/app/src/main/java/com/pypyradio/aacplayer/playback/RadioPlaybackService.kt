package com.pypyradio.aacplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.DefaultMediaNotificationProvider
import com.pypyradio.aacplayer.MainActivity
import com.pypyradio.aacplayer.data.db.AppDatabase
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.data.prefs.AppPreferences
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
    private lateinit var prefs: AppPreferences

    @Volatile private var topStations: List<Station> = emptyList()
    @Volatile private var favoriteStations: List<Station> = emptyList()

    private var retryCount = 0
    private val maxRetries = 3

    private val callback = object : MediaLibrarySession.Callback {

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
                    browsable(MEDIA_ID_FAV, "Favorites")
                )
                MEDIA_ID_TOP -> topStations.map { playableFromStation(it) }
                MEDIA_ID_FAV -> favoriteStations.map { playableFromStation(it) }
                else -> emptyList()
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
                // Find station in our lists
                val station = topStations.find { it.stationuuid == mediaId }
                    ?: favoriteStations.find { it.stationuuid == mediaId }
                station?.let { playableFromStation(it) }
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
            // Expand to topStations playlist
            if (mediaItems.size == 1 && mediaItems[0].localConfiguration == null) {
                val allStations = topStations.map { playableFromStation(it) }
                val selectedIndex = allStations.indexOfFirst { it.mediaId == id }
                if (selectedIndex >= 0 && allStations.isNotEmpty()) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(allStations, selectedIndex, 0L)
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
        prefs = AppPreferences.get(this)

        // Configure load control optimized for slow/unstable connections
        // Larger buffers = more resilient to network hiccups
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5000,    // Min buffer before playback starts (5 sec - slightly longer for stability)
                60000,   // Max buffer size (60 sec - large buffer for slow connections)
                2500,    // Buffer for playback (2.5 sec)
                5000     // Buffer for rebuffering (5 sec - more buffer after rebuffer)
            )
            .setPrioritizeTimeOverSizeThresholds(true) // Prioritize playback continuity
            .build()
        
        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setWakeMode(android.media.AudioManager.MODE_NORMAL)
            .build().apply {
                playWhenReady = true
                // Enable shuffle and repeat modes for Android Auto controls
                shuffleModeEnabled = false
                repeatMode = Player.REPEAT_MODE_ALL

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
        scope.launch {
            topStations = runCatching { repo.topVotedAac(120) }.getOrDefault(emptyList())
            // Don't pre-load into player - wait for user action (Android Auto compliance)
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

    private fun browsable(id: String, title: String): MediaItem {
        // Use folder type for browsable items
        val folderType = when (id) {
            MEDIA_ID_TOP -> MediaMetadata.FOLDER_TYPE_PLAYLISTS
            MEDIA_ID_FAV -> MediaMetadata.FOLDER_TYPE_PLAYLISTS
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

    companion object {
        const val MEDIA_ID_ROOT = "root"
        const val MEDIA_ID_TOP = "top"
        const val MEDIA_ID_FAV = "fav"
    }
}
