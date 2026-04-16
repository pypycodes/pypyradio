package com.pypyradio.aacplayer.data.api

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
        @Query("tag") tag: String? = null,
        @Query("country") country: String? = null,
        @Query("countrycode") countryCode: String? = null,
        @Query("language") language: String? = null,
        @Query("codec") codec: String? = null,
        @Query("hidebroken") hideBroken: Boolean = true,
        @Query("limit") limit: Int = 50,
        @Query("order") order: String = "votes",
        @Query("reverse") reverse: Boolean = true
    ): List<StationDto>

    @GET("json/url/{stationuuid}")
    suspend fun pingClick(@Path("stationuuid") id: String): Response<Unit>

    @GET("json/stations/byuuid/{stationuuid}")
    suspend fun getStationByUuid(@Path("stationuuid") id: String): List<StationDto>
}

data class StationDto(
    val stationuuid: String,
    val name: String,
    @Json(name = "url_resolved") val urlResolved: String?,
    val url: String?,
    val homepage: String?,
    val favicon: String?,
    val tags: String?,
    @Json(name = "countrycode") val countryCode: String?,
    val language: String?,
    val codec: String?,
    val bitrate: Int?,
    @Json(name = "lastcheckok") val lastCheckOk: Int?
)
