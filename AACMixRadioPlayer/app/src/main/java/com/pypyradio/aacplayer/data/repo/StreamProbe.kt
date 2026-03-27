package com.pypyradio.aacplayer.data.repo

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
