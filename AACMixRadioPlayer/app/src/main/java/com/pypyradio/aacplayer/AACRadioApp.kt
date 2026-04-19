package com.pypyradio.aacplayer

import android.app.Application
import com.google.android.gms.security.ProviderInstaller
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class AACRadioApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        
        // Update security provider for SSL fixes on old Android versions
        try {
            ProviderInstaller.installIfNeeded(this)
        } catch (e: Exception) {
            // Log or ignore if Play Services are missing/outdated
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.04) // Up to 4% of free disk space
                    .build()
            }
            .respectCacheHeaders(false) // Ignore bad cache headers from station APIs
            .crossfade(true)
            .build()
    }
}
