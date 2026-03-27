# AAC Mix Radio Player

A modern Android radio streaming app with 30,000+ stations worldwide.

## Features
- 🎵 Stream 30,000+ radio stations
- 🌍 Browse by country, genre, or search
- ❤️ Save favorite stations
- 🎧 Background playback with media controls
- 🚗 Android Auto support
- 🎨 Material Design 3 UI

## Building the App

### Prerequisites

| Requirement | Auto-installed? | Installation |
|-------------|-----------------|--------------|
| Gradle 8.7 | ✅ Yes (via wrapper) | Automatic |
| Dependencies | ✅ Yes | Automatic |
| Java JDK 17+ | ❌ No | See below |
| Android SDK | ❌ No | See below |

**Install Java 17:**
```bash
# Ubuntu/Debian
sudo apt install openjdk-17-jdk

# Fedora
sudo dnf install java-17-openjdk-devel

# macOS
brew install openjdk@17

# Windows - Download from https://adoptium.net/
```

**Install Android SDK:**
- Install [Android Studio](https://developer.android.com/studio) (includes SDK), OR
- Install command-line tools and set `ANDROID_HOME`:
  ```bash
  export ANDROID_HOME=$HOME/Android/Sdk
  ```

### Quick Build (Debug)
```bash
# Clone the repository
git clone https://github.com/yourusername/pypyradio.git
cd pypyradio/AACMixRadioPlayer

# Linux/macOS
chmod +x gradlew
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug

# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

### Release Build (Signed)

#### Option 1: Using keystore.properties file
```bash
# Copy the template
cp keystore.properties.template keystore.properties

# Edit keystore.properties with your values:
# storeFile=path/to/your/keystore.jks
# storePassword=your_password
# keyAlias=your_alias
# keyPassword=your_key_password

# Build
./gradlew assembleRelease bundleRelease
```

#### Option 2: Using environment variables
```bash
export STOREFILE=/path/to/your/keystore.jks
export STOREPASSWORD=your_password
export KEYALIAS=your_alias
export KEYPASSWORD=your_key_password

./gradlew assembleRelease bundleRelease
```

### Build Outputs
| File | Location |
|------|----------|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `app/build/outputs/apk/release/pypyradio-{version}.apk` |
| Release AAB | `app/build/outputs/bundle/release/app-release.aab` |
| Mapping file | `app/build/outputs/mapping/release/mapping.txt` |

## Creating a Keystore

If you don't have a keystore, create one:
```bash
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias mykey
```

## Project Structure
```
AACMixRadioPlayer/
├── app/
│   ├── src/main/
│   │   ├── java/com/pypyradio/aacplayer/
│   │   │   ├── data/          # API, database, models
│   │   │   ├── playback/      # ExoPlayer service
│   │   │   └── ui/            # Compose screens
│   │   └── res/               # Resources
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── gradle.properties
├── keystore.properties.template
└── README.md
```

## Tech Stack
- Kotlin
- Jetpack Compose
- Media3 (ExoPlayer)
- Room Database
- Retrofit + Moshi
- Coroutines

## License
MIT License
