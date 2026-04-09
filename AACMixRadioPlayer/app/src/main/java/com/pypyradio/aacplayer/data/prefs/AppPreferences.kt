package com.pypyradio.aacplayer.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppPreferences(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _autoSkipEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SKIP, true))
    val autoSkipEnabled: StateFlow<Boolean> = _autoSkipEnabled
    
    fun setAutoSkipEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SKIP, enabled).apply()
        _autoSkipEnabled.value = enabled
    }
    
    fun isAutoSkipEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_SKIP, true)
    
    companion object {
        private const val PREFS_NAME = "pypyradio_prefs"
        private const val KEY_AUTO_SKIP = "auto_skip_on_error"
        
        @Volatile
        private var instance: AppPreferences? = null
        
        fun get(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext).also { instance = it }
            }
        }
    }
}
