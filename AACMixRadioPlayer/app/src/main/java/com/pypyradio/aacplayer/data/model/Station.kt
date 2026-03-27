package com.pypyradio.aacplayer.data.model

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
