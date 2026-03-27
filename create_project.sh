#!/usr/bin/env bash
set -e

APP=AACMixRadioPlayer
PKG=com/example/aacmixradio

echo "Creating Android project structure..."

mkdir -p $APP/app/src/main/java/$PKG/{core,data/{api,db,model,repo},playback,ui/{components,screens,vm}}
mkdir -p $APP/app/src/main/res/{values,xml,drawable,mipmap}

#################################
# settings.gradle.kts
#################################
cat > $APP/settings.gradle.kts <<'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "AACMixRadioPlayer"
include(":app")
EOF

#################################
# Root build.gradle.kts
#################################
cat > $APP/build.gradle.kts <<'EOF'
plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("com.google.devtools.ksp") version "1.9.23-1.0.20" apply false
}
EOF

#################################
# gradle.properties
#################################
cat > $APP/gradle.properties <<'EOF'
org.gradle.jvmargs=-Xmx3g
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
EOF

#################################
# app/build.gradle.kts
#################################
cat > $APP/app/build.gradle.kts <<'EOF'
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

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
EOF

#################################
# AndroidManifest.xml
#################################
cat > $APP/app/src/main/AndroidManifest.xml <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

    <application
        android:label="AAC Radio"
        android:icon="@mipmap/ic_launcher">

        <meta-data
            android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc"/>

        <meta-data
            android:name="androidx.car.app.TintableAttributionIcon"
            android:resource="@drawable/ic_car_attribution"/>

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>

        <service
            android:name=".playback.RadioPlaybackService"
            android:exported="true"
            android:foregroundServiceType="mediaPlayback"
            android:icon="@mipmap/ic_launcher">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService"/>
                <action android:name="android.media.browse.MediaBrowserService"/>
            </intent-filter>
        </service>

    </application>
</manifest>
EOF

#################################
# automotive_app_desc.xml
#################################
cat > $APP/app/src/main/res/xml/automotive_app_desc.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
EOF

#################################
# strings.xml
#################################
cat > $APP/app/src/main/res/values/strings.xml <<'EOF'
<resources>
    <string name="app_name">AAC Radio</string>
</resources>
EOF

#################################
# Car attribution icon (monochrome)
#################################
cat > $APP/app/src/main/res/drawable/ic_car_attribution.xml <<'EOF'
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M12,3A9,9 0 1,0 21,12A9,9 0 0,0 12,3ZM11,7h2v6h-2zm0,8h2v2h-2z"/>
</vector>
EOF

#################################
# MainActivity.kt
#################################
cat > $APP/app/src/main/java/$PKG/MainActivity.kt <<'EOF'
package com.example.aacmixradio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.aacmixradio.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
EOF

#################################
# App.kt
#################################
cat > $APP/app/src/main/java/$PKG/ui/App.kt <<'EOF'
package com.example.aacmixradio.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.aacmixradio.ui.components.NowPlayingBar

@Composable
fun App() {
    MaterialTheme {
        Scaffold(
            bottomBar = { NowPlayingBar() }
        ) {
            Text("AAC Radio App Ready", modifier = androidx.compose.ui.Modifier.padding(it))
        }
    }
}
EOF

#################################
# NowPlayingBar.kt
#################################
cat > $APP/app/src/main/java/$PKG/ui/components/NowPlayingBar.kt <<'EOF'
package com.example.aacmixradio.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*

@Composable
fun NowPlayingBar() {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Now Playing",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("Stop")
        }
    }
}
EOF

#################################
# RadioPlaybackService.kt
#################################
cat > $APP/app/src/main/java/$PKG/playback/RadioPlaybackService.kt <<'EOF'
package com.example.aacmixradio.playback

import android.content.Intent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibrarySession

class RadioPlaybackService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build()
        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {}).build()
    }

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo)
        = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        player.stop()
        player.clearMediaItems()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        player.release()
        session.release()
        super.onDestroy()
    }
}
EOF

echo "✅ Project created successfully"
echo "👉 Open $APP in Android Studio"
``
