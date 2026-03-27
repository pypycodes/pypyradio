#!/usr/bin/env bash
set -euo pipefail

APP="AACMixRadioPlayer"
PKG_DIR="com/example/aacmixradio"
PKG="com.example.aacmixradio"

rm -rf "$APP"
mkdir -p "$APP"

# ---------- directories ----------
mkdir -p "$APP/app/src/main/java/$PKG_DIR"/{core,data/{api,db,model,repo},playback,ui/{components,screens,vm}}
mkdir -p "$APP/app/src/main/res"/{values,xml,drawable,mipmap-anydpi-v26}

# ---------- settings.gradle.kts ----------
cat > "$APP/settings.gradle.kts" <<'EOF'
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "AACMixRadioPlayer"
include(":app")
EOF

# ---------- root build.gradle.kts ----------
cat > "$APP/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("com.google.devtools.ksp") version "1.9.23-1.0.20" apply false
}
EOF

# ---------- gradle.properties ----------
cat > "$APP/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
EOF

# ---------- app/build.gradle.kts ----------
cat > "$APP/app/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.aacmixradio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.aacmixradio"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.11" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3:1.2.1")

    // Lifecycle / ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Retrofit + OkHttp + Moshi
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coil (favicons)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Media3 (ExoPlayer + Session + HLS)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
}
EOF

# ---------- proguard-rules.pro ----------
cat > "$APP/app/proguard-rules.pro" <<'EOF'
# no-op (minify disabled by default)
EOF

# ---------- AndroidManifest.xml ----------
cat > "$APP/app/src/main/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".AACRadioApp"
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:supportsRtl="true">

        <!-- Android Auto discovery -->
        <meta-data
            android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc" />

        <!-- Attribution icon (monochrome) for car UI -->
        <meta-data
            android:name="androidx.car.app.TintableAttributionIcon"
            android:resource="@drawable/ic_car_attribution" />

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Media3 library service for background + Android Auto -->
        <service
            android:name=".playback.RadioPlaybackService"
            android:exported="true"
            android:foregroundServiceType="mediaPlayback"
            android:icon="@mipmap/ic_launcher">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
                <action android:name="android.media.browse.MediaBrowserService" />
            </intent-filter>
        </service>

    </application>
</manifest>
EOF

# ---------- res/values/strings.xml ----------
cat > "$APP/app/src/main/res/values/strings.xml" <<'EOF'
<resources>
    <string name="app_name">AAC Radio Player</string>
    <string name="notif_channel_name">Playback</string>
</resources>
EOF

# ---------- res/xml/automotive_app_desc.xml ----------
cat > "$APP/app/src/main/res/xml/automotive_app_desc.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
EOF

# ---------- car attribution icon (monochrome vector) ----------
cat > "$APP/app/src/main/res/drawable/ic_car_attribution.xml" <<'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,3A9,9 0 1,0 21,12A9,9 0 0,0 12,3ZM11,7h2v6h-2zm0,8h2v2h-2z"/>
</vector>
EOF

# ---------- launcher icons (minimal adaptive icon) ----------
cat > "$APP/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml" <<'EOF'
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/black"/>
    <foreground android:drawable="@android:color/white"/>
</adaptive-icon>
EOF
cp "$APP/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml" \
   "$APP/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"

# ---------- AACRadioApp.kt (notification channel) ----------
cat > "$APP/app/src/main/java/$PKG_DIR/AACRadioApp.kt" <<'EOF'
package com.example.aacmixradio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class AACRadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "playback",
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
EOF

# ---------- MainActivity.kt (requests notifications permission on Android 13+) ----------
cat > "$APP/app/src/main/java/$PKG_DIR/MainActivity.kt" <<'EOF'
package com.example.aacmixradio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.aacmixradio.ui.AppRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        setContent { AppRoot() }
    }
}
EOF

# ---------- core/Constants.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/core/Constants.kt" <<'EOF'
package com.example.aacmixradio.core

object Constants {
    // Radio-Browser suggests descriptive User-Agent
    const val USER_AGENT = "AACRadioPlayer/1.0 (Android)"
}
EOF

# ---------- data/model/Station.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/data/model/Station.kt" <<'EOF'
package com.example.aacmixradio.data.model

data class Station(
    val stationuuid: String,
    val name: String,
    val urlResolved: String,
    val homepage: String?,
    val favicon: String?,
    val tags: String?,
    val countryCode: String?,
    val language: String?,
    val codec: String?,
    val bitrate: Int?,
    val lastCheckOk: Int?
)
EOF

# ---------- data/db/FavoriteStationEntity.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/data/db/FavoriteStationEntity.kt" <<'EOF'
package com.example.aacmixradio.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_stations")
data class FavoriteStationEntity(
    @PrimaryKey val stationuuid: String,
    val name: String,
    val urlResolved: String,
    val favicon: String?,
    val countryCode: String?,
    val codec: String?,
    val bitrate: Int?
)
EOF

# ---------- data/db/FavoriteStationDao.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/data/db/FavoriteStationDao.kt" <<'EOF'
package com.example.aacmixradio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteStationDao {
    @Query("SELECT * FROM favorite_stations ORDER BY name ASC")
    fun observeAll(): Flow<List<FavoriteStationEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_stations WHERE stationuuid = :id)")
    suspend fun isFavorite(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteStationEntity)

    @Query("DELETE FROM favorite_stations WHERE stationuuid = :id")
    suspend fun delete(id: String)
}
EOF

# ---------- data/db/AppDatabase.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/data/db/AppDatabase.kt" <<'EOF'
package com.example.aacmixradio.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FavoriteStationEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoritesDao(): FavoriteStationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aac_radio.db"
                ).build().also { INSTANCE = it }
            }
    }
}
EOF

# ---------- data/api/RadioBrowserApi.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/data/api/RadioBrowserApi.kt" <<'EOF'
package com.example.aacmixradio.data.api

import com.squareup.moshi.Json
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface RadioBrowserApi {

    @GET("json/stations/topvote/{limit}")
    suspend fun topVoted(@Path("limit") limit: Int = 50): List<StationDto>

    @GET("json/stations/search")
    suspend fun searchStations(
        @Query("name") name: String? = null,
        @Query("codec") codec: String? = null,
        @Query("hidebroken") hideBroken: Boolean = true,
        @Query("limit") limit: Int = 50,
        @Query("order") order: String = "votes",
        @Query("reverse") reverse: Boolean = true
    ): List<StationDto>

    @GET("json/url/{stationuuid}")
    suspend fun pingClick(@Path("stationuuid") id: String): Response<Unit>
}

data class StationDto(
    val stationuuid: String,
    val name: String,
    @Json(name = "url_resolved") val urlResolved: String,
    val homepage: String?,
    val favicon: String?,
    val tags: String?,
    @Json(name = "countrycode") val countryCode: String?,
    val language: String?,
    val codec: String?,
    val bitrate: Int?,
    @Json(name = "lastcheckok") val lastCheckOk: Int?
)
EOF

# ---------- data/api/RadioBrowserServerSelector.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/data/api/RadioBrowserServerSelector.kt" <<'EOF'
package com.example.aacmixradio.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import kotlin.random.Random

object RadioBrowserServerSelector {

    private val fallback = listOf(
        "https://de1.api.radio-browser.info/",
        "https://nl1.api.radio-browser.info/",
        "https://at1.api.radio-browser.info/"
    )

    @Volatile private var selectedBaseUrl: String? = null

    suspend fun getBaseUrl(http: OkHttpClient): String {
        selectedBaseUrl?.let { return it }

        val candidates = withContext(Dispatchers.IO) {
            runCatching {
                // Radio-Browser recommends DNS lookup: all.api.radio-browser.info
                InetAddress.getAllByName("all.api.radio-browser.info")
                    .mapNotNull { addr ->
                        val host = addr.hostName.takeIf { it.isNotBlank() }
                        host?.let { "https://$it/" }
                    }
            }.getOrDefault(emptyList())
        }.ifEmpty { fallback }

        val shuffled = candidates.shuffled(Random(System.nanoTime()))

        val chosen = withContext(Dispatchers.IO) {
            shuffled.firstOrNull { base ->
                val req = Request.Builder().url(base + "json/stats").get().build()
                runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
            } ?: fallback.first()
        }

        selectedBaseUrl = chosen
        return chosen
    }

    fun invalidate() { selectedBaseUrl = null }
}
EOF

# ---------- data/api/RadioBrowserClient.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/data/api/RadioBrowserClient.kt" <<'EOF'
package com.example.aacmixradio.data.api

import com.example.aacmixradio.core.Constants
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object RadioBrowserClient {

    private val userAgentInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("User-Agent", Constants.USER_AGENT)
            .build()
        chain.proceed(req)
    }

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(userAgentInterceptor)
        .addInterceptor(logging)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val apiCache = ConcurrentHashMap<String, RadioBrowserApi>()

    suspend fun api(): RadioBrowserApi {
        val base = RadioBrowserServerSelector.getBaseUrl(okHttp)
        return apiCache.getOrPut(base) {
            Retrofit.Builder()
                .baseUrl(base)
                .client(okHttp)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
                .create(RadioBrowserApi::class.java)
        }
    }
}
EOF

# ---------- data/repo/StreamProbe.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/data/repo/StreamProbe.kt" <<'EOF'
package com.example.aacmixradio.data.repo

import okhttp3.OkHttpClient
import okhttp3.Request

object StreamProbe {

    fun urlLooksAac(url: String): Boolean {
        val u = url.lowercase()
        return listOf("aac", "aacp", ".m4a", ".mp4").any { it in u }
    }

    suspend fun isAacByHeaders(http: OkHttpClient, url: String): Boolean {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .get()
            .build()

        return runCatching {
            http.newCall(req).execute().use { resp ->
                val ct = resp.header("Content-Type")?.lowercase().orEmpty()
                ct.contains("audio/aac") ||
                        ct.contains("audio/aacp") ||
                        ct.contains("audio/mp4") ||
                        ct.contains("application/vnd.apple.mpegurl") // HLS container; can carry AAC
            }
        }.getOrDefault(false)
    }
}
EOF

# ---------- data/repo/StationRepository.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/data/repo/StationRepository.kt" <<'EOF'
package com.example.aacmixradio.data.repo

import com.example.aacmixradio.data.api.RadioBrowserClient
import com.example.aacmixradio.data.api.StationDto
import com.example.aacmixradio.data.db.FavoriteStationDao
import com.example.aacmixradio.data.db.FavoriteStationEntity
import com.example.aacmixradio.data.model.Station
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StationRepository(private val favoritesDao: FavoriteStationDao) {

    fun observeFavorites(): Flow<List<Station>> =
        favoritesDao.observeAll().map { list ->
            list.map { e ->
                Station(
                    stationuuid = e.stationuuid,
                    name = e.name,
                    urlResolved = e.urlResolved,
                    homepage = null,
                    favicon = e.favicon,
                    tags = null,
                    countryCode = e.countryCode,
                    language = null,
                    codec = e.codec,
                    bitrate = e.bitrate,
                    lastCheckOk = 1
                )
            }
        }

    suspend fun topVotedAac(limit: Int = 120): List<Station> = coroutineScope {
        val api = RadioBrowserClient.api()
        val all = api.topVoted(limit)

        val withCodec = all.filter { !it.codec.isNullOrBlank() }
        val noCodec = all.filter { it.codec.isNullOrBlank() }

        val strictAac = withCodec
            .filter { it.codec!!.contains("AAC", ignoreCase = true) }
            .filter { it.lastCheckOk == null || it.lastCheckOk == 1 }

        val probeCandidates = noCodec
            .filter { it.lastCheckOk == null || it.lastCheckOk == 1 }
            .take(40)

        val probed = probeCandidates.map { dto ->
            async {
                val url = dto.urlResolved
                val ok = StreamProbe.urlLooksAac(url) ||
                        StreamProbe.isAacByHeaders(RadioBrowserClient.okHttp, url)
                ok to dto
            }
        }.awaitAll()
            .filter { it.first }
            .map { it.second }

        (strictAac + probed)
            .distinctBy { it.stationuuid }
            .map { it.toDomain() }
    }

    suspend fun searchAac(name: String, limit: Int = 80): List<Station> {
        val api = RadioBrowserClient.api()
        val result = api.searchStations(
            name = name,
            codec = "aac",
            hidebroken = true,
            limit = limit,
            order = "votes",
            reverse = true
        )
        return result.filter { dto ->
            val c = dto.codec
            c?.contains("AAC", ignoreCase = true) == true ||
                    (c.isNullOrBlank() && StreamProbe.urlLooksAac(dto.urlResolved))
        }.map { it.toDomain() }
    }

    suspend fun toggleFavorite(station: Station) {
        val isFav = favoritesDao.isFavorite(station.stationuuid)
        if (isFav) {
            favoritesDao.delete(station.stationuuid)
        } else {
            favoritesDao.upsert(
                FavoriteStationEntity(
                    stationuuid = station.stationuuid,
                    name = station.name,
                    urlResolved = station.urlResolved,
                    favicon = station.favicon,
                    countryCode = station.countryCode,
                    codec = station.codec,
                    bitrate = station.bitrate
                )
            )
        }
    }

    suspend fun pingClick(stationuuid: String) {
        runCatching { RadioBrowserClient.api().pingClick(stationuuid) }
    }

    private fun StationDto.toDomain(): Station =
        Station(
            stationuuid = stationuuid,
            name = name,
            urlResolved = urlResolved,
            homepage = homepage,
            favicon = favicon,
            tags = tags,
            countryCode = countryCode,
            language = language,
            codec = codec,
            bitrate = bitrate,
            lastCheckOk = lastCheckOk
        )
}
EOF

# ---------- playback/RadioController.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/playback/RadioController.kt" <<'EOF'
package com.example.aacmixradio.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RadioController {

    private var controller: MediaController? = null

    suspend fun get(context: Context): MediaController {
        controller?.let { return it }

        return withContext(Dispatchers.Main) {
            val token = SessionToken(context, ComponentName(context, RadioPlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            val deferred = CompletableDeferred<MediaController>()
            future.addListener({ deferred.complete(future.get()) }, { it.run() })
            deferred.await().also { controller = it }
        }
    }

    fun release() {
        controller?.release()
        controller = null
    }
}
EOF

# ---------- playback/RadioPlaybackService.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/playback/RadioPlaybackService.kt" <<'EOF'
package com.example.aacmixradio.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.DefaultMediaNotificationProvider
import com.example.aacmixradio.MainActivity
import com.example.aacmixradio.data.db.AppDatabase
import com.example.aacmixradio.data.model.Station
import com.example.aacmixradio.data.repo.StationRepository
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
        ): LibraryResult<MediaItem> {
            val root = MediaItem.Builder()
                .setMediaId(MEDIA_ID_ROOT)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("AAC Radio")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return LibraryResult.ofItem(root, params)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): LibraryResult<MutableList<MediaItem>> {

            val items = when (parentId) {
                MEDIA_ID_ROOT -> mutableListOf(
                    browsable(MEDIA_ID_TOP, "Top AAC"),
                    browsable(MEDIA_ID_FAV, "Favorites")
                )
                MEDIA_ID_TOP -> topStations.map { playableFromStation(it) }.toMutableList()
                MEDIA_ID_FAV -> favoriteStations.map { playableFromStation(it) }.toMutableList()
                else -> mutableListOf()
            }

            return LibraryResult.ofItemList(items, params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): MediaSession.MediaItemsWithStartPosition {
            retryCount = 0
            val id = mediaItems.getOrNull(startIndex)?.mediaId
            if (!id.isNullOrBlank()) {
                scope.launch(Dispatchers.IO) { repo.pingClick(id) }
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Notification provider (foreground)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("playback")
                .setNotificationId(1001)
                .build()
        )

        repo = StationRepository(AppDatabase.get(this).favoritesDao())

        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        if (retryCount < maxRetries && currentMediaItem != null) {
                            val waitMs = when (retryCount) {
                                0 -> 1200L
                                1 -> 3000L
                                else -> 7000L
                            }
                            retryCount++
                            scope.launch {
                                delay(waitMs)
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

        // Prefetch top AAC
        scope.launch {
            topStations = runCatching { repo.topVotedAac(120) }.getOrDefault(emptyList())
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
EOF

# ---------- ui/vm/StationsViewModel.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/ui/vm/StationsViewModel.kt" <<'EOF'
package com.example.aacmixradio.ui.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aacmixradio.data.db.AppDatabase
import com.example.aacmixradio.data.model.Station
import com.example.aacmixradio.data.repo.StationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val loading: Boolean = false,
    val error: String? = null,
    val stations: List<Station> = emptyList(),
    val query: String = ""
)

class StationsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = StationRepository(AppDatabase.get(app).favoritesDao())

    private val _browse = MutableStateFlow(UiState(loading = true))
    val browse: StateFlow<UiState> = _browse

    val favorites = repo.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { loadTop() }

    fun setQuery(q: String) { _browse.value = _browse.value.copy(query = q) }

    fun loadTop() = viewModelScope.launch {
        _browse.value = _browse.value.copy(loading = true, error = null)
        runCatching { repo.topVotedAac(120) }
            .onSuccess { _browse.value = _browse.value.copy(loading = false, stations = it, error = null) }
            .onFailure { _browse.value = _browse.value.copy(loading = false, stations = emptyList(), error = it.message ?: "Failed") }
    }

    fun search() = viewModelScope.launch {
        val q = _browse.value.query.trim()
        if (q.isEmpty()) return@launch loadTop()
        _browse.value = _browse.value.copy(loading = true, error = null)
        runCatching { repo.searchAac(q, 80) }
            .onSuccess { _browse.value = _browse.value.copy(loading = false, stations = it, error = null) }
            .onFailure { _browse.value = _browse.value.copy(loading = false, stations = emptyList(), error = it.message ?: "Failed") }
    }

    fun toggleFavorite(station: Station) = viewModelScope.launch {
        repo.toggleFavorite(station)
    }
}
EOF

# ---------- ui/components/NowPlayingBar.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/ui/components/NowPlayingBar.kt" <<'EOF'
package com.example.aacmixradio.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.example.aacmixradio.playback.RadioController

@Composable
fun NowPlayingBar() {
    val context = LocalContext.current

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var title by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        controller = RadioController.get(context)
    }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                title = player.currentMediaItem?.mediaMetadata?.title?.toString()
                isPlaying = player.isPlaying
            }
        }
        c.addListener(listener)
        title = c.currentMediaItem?.mediaMetadata?.title?.toString()
        isPlaying = c.isPlaying
        onDispose { c.removeListener(listener) }
    }

    val shownTitle = title ?: return

    Surface(tonalElevation = 2.dp, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Now Playing", style = MaterialTheme.typography.labelMedium)
                Text(
                    shownTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause")
            }
            IconButton(onClick = {
                controller?.stop()
                title = null
                isPlaying = false
            }) {
                Icon(Icons.Default.Stop, contentDescription = "Stop")
            }
        }
    }
}
EOF

# ---------- ui/screens/BrowseScreen.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/ui/screens/BrowseScreen.kt" <<'EOF'
package com.example.aacmixradio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.aacmixradio.data.model.Station
import com.example.aacmixradio.playback.RadioController
import com.example.aacmixradio.ui.vm.StationsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(vm: StationsViewModel, onGoFavorites: () -> Unit) {
    val state by vm.browse.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browse AAC Stations") },
                actions = {
                    IconButton(onClick = onGoFavorites) {
                        Icon(Icons.Default.Favorite, contentDescription = "Favorites")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = state.query,
                    onValueChange = vm::setQuery,
                    label = { Text("Search station name") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { vm.search() }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error: ${state.error}")
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.stations, key = { it.stationuuid }) { st ->
                            StationRow(
                                st = st,
                                onPlay = {
                                    val url = st.urlResolved
                                    if (url.isNotBlank()) {
                                        LaunchedEffect(st.stationuuid) {
                                            val c = RadioController.get(context)
                                            val item = androidx.media3.common.MediaItem.Builder()
                                                .setMediaId(st.stationuuid)
                                                .setUri(url)
                                                .setMediaMetadata(
                                                    androidx.media3.common.MediaMetadata.Builder()
                                                        .setTitle(st.name)
                                                        .build()
                                                )
                                                .build()
                                            c.setMediaItem(item)
                                            c.prepare()
                                            c.play()
                                        }
                                    }
                                },
                                onFavorite = { vm.toggleFavorite(st) }
                            )
                            Divider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationRow(st: Station, onPlay: () -> Unit, onFavorite: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(model = st.favicon, contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(st.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(st.countryCode, st.codec, st.bitrate?.let { "${it}kbps" }).joinToString(" • ")
            Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onFavorite) { Icon(Icons.Default.Favorite, contentDescription = "Favorite") }
        IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
    }
}
EOF

# ---------- ui/screens/FavoritesScreen.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/ui/screens/FavoritesScreen.kt" <<'EOF'
package com.example.aacmixradio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.aacmixradio.data.model.Station
import com.example.aacmixradio.playback.RadioController
import com.example.aacmixradio.ui.vm.StationsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(vm: StationsViewModel, onBack: () -> Unit) {
    val favs by vm.favorites.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favorites") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        if (favs.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No favorites yet")
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(favs, key = { it.stationuuid }) { st ->
                    FavRow(
                        st = st,
                        onPlay = {
                            LaunchedEffect(st.stationuuid) {
                                val c = RadioController.get(context)
                                val item = androidx.media3.common.MediaItem.Builder()
                                    .setMediaId(st.stationuuid)
                                    .setUri(st.urlResolved)
                                    .setMediaMetadata(
                                        androidx.media3.common.MediaMetadata.Builder()
                                            .setTitle(st.name)
                                            .build()
                                    )
                                    .build()
                                c.setMediaItem(item)
                                c.prepare()
                                c.play()
                            }
                        },
                        onRemove = { vm.toggleFavorite(st) }
                    )
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun FavRow(st: Station, onPlay: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(model = st.favicon, contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(st.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(st.countryCode, st.codec, st.bitrate?.let { "${it}kbps" }).joinToString(" • ")
            Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onRemove) { Text("Remove") }
        IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, contentDescription = "Play") }
    }
}
EOF

# ---------- ui/AppRoot.kt ----------
cat > "$APP/app/src/main/java/$PKG_DIR/ui/AppRoot.kt" <<'EOF'
package com.example.aacmixradio.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aacmixradio.ui.components.NowPlayingBar
import com.example.aacmixradio.ui.screens.BrowseScreen
import com.example.aacmixradio.ui.screens.FavoritesScreen
import com.example.aacmixradio.ui.vm.StationsViewModel

@Composable
fun AppRoot() {
    val vm: StationsViewModel = viewModel()
    var screen by remember { mutableStateOf("browse") }

    MaterialTheme {
        Scaffold(bottomBar = { NowPlayingBar() }) { _ ->
            when (screen) {
                "browse" -> BrowseScreen(vm = vm, onGoFavorites = { screen = "fav" })
                else -> FavoritesScreen(vm = vm, onBack = { screen = "browse" })
            }
        }
    }
}
EOF

echo "✅ Created FULL project at: $APP"
echo "👉 Open '$APP' folder in Android Studio and Run."
``