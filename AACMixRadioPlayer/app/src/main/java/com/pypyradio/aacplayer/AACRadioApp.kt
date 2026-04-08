package com.pypyradio.aacplayer

import android.app.Application

class AACRadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Notification channel is created by RadioPlaybackService
    }
}
