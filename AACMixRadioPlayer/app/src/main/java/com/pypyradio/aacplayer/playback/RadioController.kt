package com.pypyradio.aacplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object RadioController {

    @Volatile
    private var controller: MediaController? = null

    suspend fun get(context: Context): MediaController {
        controller?.let { 
            if (it.isConnected) return it 
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val appContext = context.applicationContext
                val token = SessionToken(appContext, ComponentName(appContext, RadioPlaybackService::class.java))
                val future = MediaController.Builder(appContext, token).buildAsync()
                
                future.addListener({
                    try {
                        val result = future.get()
                        controller = result
                        cont.resume(result)
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }, { it.run() })
                
                cont.invokeOnCancellation {
                    future.cancel(true)
                }
            }
        }
    }

    fun release() {
        controller?.release()
        controller = null
    }
}
