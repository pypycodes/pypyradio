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

        // Configure load control for faster playback start (like web browsers)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2500,   // Min buffer before playback starts (2.5 sec - fast start like browser)
                30000,  // Max buffer size (30 sec)
                1500,   // Buffer for playback (1.5 sec)
                3000    // Buffer for rebuffering (3 sec)
            )
            .build()
        
        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
                // Enable shuffle and repeat modes for Android Auto controls
                shuffleModeEnabled = false
                repeatMode = Player.REPEAT_MODE_ALL

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        if (retryCount < maxRetries && currentMediaItem != null) {
                            val waitMs = when (retryCount) {
                                0 -> 2000L
                                1 -> 4000L
                                else -> 6000L
                            }
                            retryCount++
                            scope.launch {
                                delay(waitMs)
                                prepare()
                                play()
                            }
                        }
                        // Don't auto-skip - user will manually skip if needed
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        retryCount = 0
                        mediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { id ->
                            scope.launch(Dispatchers.IO) { repo.pingClick(id) }
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

        // Prefetch top stations and load them as playlist for next/prev controls
        scope.launch {
            topStations = runCatching { repo.topVotedAac(120) }.getOrDefault(emptyList())
            // Pre-load stations as playlist for Android Auto next/prev controls
            if (topStations.isNotEmpty()) {
                val mediaItems = topStations.map { playableFromStation(it) }
                player?.setMediaItems(mediaItems)
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
        session?.run {
            player?.release()
            release()
        }
        player = null
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private fun browsable(id: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

    private fun playableFromStation(st: Station): MediaItem =
        MediaItem.Builder()
            .setMediaId(st.stationuuid)
            .setUri(st.urlResolved)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(st.name)
                    .setArtist(st.countryCode ?: "")
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()

    companion object {
        const val MEDIA_ID_ROOT = "root"
        const val MEDIA_ID_TOP = "top"
        const val MEDIA_ID_FAV = "fav"
    }
}
