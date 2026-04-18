package com.pypyradio.aacplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.pypyradio.aacplayer.ui.TvAppRoot

/**
 * Entry point for Android TV.
 * This activity handles orientation (landscape) and D-pad interaction
 * by utilizing the Compose for TV libraries.
 */
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvAppRoot()
        }
    }
}
