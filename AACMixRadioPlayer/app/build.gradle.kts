import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Load keystore properties (supports environment variables or properties file)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

// Allow environment variables to override properties file
fun getKeystoreProperty(key: String): String? {
    return System.getenv(key.uppercase().replace(".", "_")) 
        ?: keystoreProperties.getProperty(key)
}

android {
    namespace = "com.pypyradio.aacplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pypyradio.aacplayer"
        minSdk = 24
        targetSdk = 35
        versionCode = 30
        versionName = "1.0.30"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = getKeystoreProperty("storeFile")
            if (!storeFilePath.isNullOrBlank()) {
                // Use rootProject.file() for paths relative to project root
                storeFile = rootProject.file(storeFilePath)
                storePassword = getKeystoreProperty("storePassword") ?: ""
                keyAlias = getKeystoreProperty("keyAlias") ?: ""
                keyPassword = getKeystoreProperty("keyPassword") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only use release signing if keystore is configured
            val releaseSigningConfig = signingConfigs.findByName("release")
            if (releaseSigningConfig?.storeFile != null) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Output APK with versioned filename
    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (variant.buildType.name == "release") {
                output.outputFileName = "pypyradio-${variant.versionName}.apk"
            }
        }
    }
    
    // Set AAB bundle filename with version
    bundle {
        storeArchive {
            enable = true
        }
    }
}

// Separate task to rename AAB after build
tasks.register("renameReleaseAab") {
    description = "Renames the release AAB to include version name"
    group = "build"
    
    // Run after bundleRelease finishes
    dependsOn("bundleRelease")
    
    doLast {
        val bundleDir = layout.buildDirectory.dir("outputs/bundle/release").get().asFile
        val versionName = android.defaultConfig.versionName
        bundleDir.listFiles()?.filter { it.extension == "aab" }?.forEach { aab ->
            val newName = "pypyradio-${versionName}.aab"
            val newFile = File(bundleDir, newName)
            if (aab.name != newName && aab.renameTo(newFile)) {
                println("Renamed AAB to: $newName")
            }
        }
    }
}

// Make bundleRelease automatically trigger rename
tasks.named("bundleRelease") {
    finalizedBy("renameReleaseAab")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")

    // Media3 (ExoPlayer + MediaSession)
    val media3Version = "1.2.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Retrofit + Moshi + Gson
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.moshi:moshi:1.15.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Guava (required for Media3 ListenableFuture and ImmutableList)
    implementation("com.google.guava:guava:32.1.3-android")
}