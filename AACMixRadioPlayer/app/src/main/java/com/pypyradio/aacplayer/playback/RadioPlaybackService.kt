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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.DefaultMediaNotificationProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.pypyradio.aacplayer.MainActivity
import com.pypyradio.aacplayer.data.db.AppDatabase
import com.pypyradio.aacplayer.data.model.Station
import com.pypyradio.aacplayer.data.prefs.AppPreferences
import com.pypyradio.aacplayer.data.repo.StationRepository
import kotlinx.coroutines.*

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
    private lateinit var prefs: AppPreferences
    private var cachedStations: List<Station> = SAMPLE_STATIONS

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        Log.i(TAG, "===== SERVICE CREATED =====")
        super.onCreate()

        try {
            // Initialize data layer
            val db = AppDatabase.get(this)
            stationRepo = StationRepository(db.favoritesDao())
            prefs = AppPreferences(this)

            // Create player
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

           // Create session
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

            // Load stations
            loadStations()

            Log.i(TAG, "Service initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Initialization error", e)
            throw e
        }
    }

    private fun acquireWakeLocks() {
        try {
            val wifiMgr = getSystemService(Context.WIFI_SERVICE) as? WifiManager
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
                val loaded = stationRepo.topVotedAac(limit = 100)
                if (loaded.isNotEmpty()) {
                    cachedStations = loaded
                    Log.d(TAG, "Loaded ${loaded.size} stations from API")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Station load error", e)
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
                        browsableItem(MEDIA_ID_BY_LANGUAGE, "By Language")
                    )
                    MEDIA_ID_TOP_STATIONS -> cachedStations.take(50).map(::playableItem)
                    MEDIA_ID_FAVORITES -> cachedStations.take(20).map(::playableItem)
                    MEDIA_ID_BY_LANGUAGE -> listOf(
                        browsableItem(MEDIA_ID_ENGLISH, "English"),
                        browsableItem(MEDIA_ID_HINDI, "Hindi")
                    )
                    MEDIA_ID_ENGLISH -> cachedStations
                        .filter { it.language?.lowercase() == "english" }
                        .map(::playableItem)
                    MEDIA_ID_HINDI -> cachedStations
                        .filter { it.language?.lowercase() == "hindi" }
                        .map(::playableItem)
                    else -> emptyList()
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
                        .setArtist(station.countryCode ?: "")
                        .setGenre(station.tags ?: "Radio")
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
    }
}
