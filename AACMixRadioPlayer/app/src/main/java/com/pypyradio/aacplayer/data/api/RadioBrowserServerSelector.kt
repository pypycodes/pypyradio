package com.pypyradio.aacplayer.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object RadioBrowserServerSelector {

    private val servers = listOf(
        "https://de1.api.radio-browser.info/",
        "https://nl1.api.radio-browser.info/",
        "https://at1.api.radio-browser.info/",
        "https://de2.api.radio-browser.info/",
        "https://fi1.api.radio-browser.info/"
    )

    @Volatile private var selectedBaseUrl: String? = null

    suspend fun getBaseUrl(http: OkHttpClient): String {
        selectedBaseUrl?.let { return it }

        // Try servers directly - more reliable than DNS lookup
        val chosen = withContext(Dispatchers.IO) {
            servers.shuffled().firstOrNull { base ->
                val req = Request.Builder().url(base + "json/stats").get().build()
                runCatching { http.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
            } ?: servers.first()
        }

        selectedBaseUrl = chosen
        return chosen
    }

    fun invalidate() { selectedBaseUrl = null }
}
