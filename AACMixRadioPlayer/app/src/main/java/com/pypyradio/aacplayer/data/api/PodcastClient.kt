package com.pypyradio.aacplayer.data.api

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object PodcastClient {
    
    private const val ITUNES_BASE_URL = "https://itunes.apple.com/"
    private const val PODCAST_INDEX_BASE_URL = "https://api.podcastindex.org/api/1.0/"
    
    // Podcast Index API Key (free - register at podcastindex.org)
    // Set your API key here, or leave empty to use iTunes only
    private const val PODCAST_INDEX_API_KEY = "WY8BZZUQPGSMBTS87EDN"
    private const val PODCAST_INDEX_API_SECRET = "xCmx3XgrUDeMf3bnj8sGFqagP5mCfnkR4X7haXTc"
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val iTunesRetrofit = Retrofit.Builder()
        .baseUrl(ITUNES_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    private fun createPodcastIndexClient(): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            
            val requestBuilder = chain.request().newBuilder()
                .addHeader("User-Agent", "pypyradio/1.0")
            
            // Add auth headers if API key is configured
            if (PODCAST_INDEX_API_KEY.isNotBlank()) {
                requestBuilder.addHeader("X-Auth-Key", PODCAST_INDEX_API_KEY)
                requestBuilder.addHeader("X-Auth-Date", timestamp)
                
                // Add authorization hash if secret is available
                if (PODCAST_INDEX_API_SECRET.isNotBlank()) {
                    val authString = PODCAST_INDEX_API_KEY + PODCAST_INDEX_API_SECRET + timestamp
                    val authHash = sha1(authString)
                    requestBuilder.addHeader("Authorization", authHash)
                }
            }
            
            chain.proceed(requestBuilder.build())
        }
        
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .build()
    }
    
    private val podcastIndexRetrofit = Retrofit.Builder()
        .baseUrl(PODCAST_INDEX_BASE_URL)
        .client(createPodcastIndexClient())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    val iTunesApi: ITunesPodcastApi = iTunesRetrofit.create(ITunesPodcastApi::class.java)
    val podcastIndexApi: PodcastIndexApi = podcastIndexRetrofit.create(PodcastIndexApi::class.java)
    
    // Check if Podcast Index is configured
    val isPodcastIndexEnabled: Boolean get() = PODCAST_INDEX_API_KEY.isNotBlank()
    
    private fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
