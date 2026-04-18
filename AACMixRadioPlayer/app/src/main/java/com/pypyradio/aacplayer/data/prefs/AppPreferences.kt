package com.pypyradio.aacplayer.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SoundMode {
    DEFAULT, STUDY, NIGHT, LOUD
}

class AppPreferences(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val _autoSkipEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SKIP, true))
    val autoSkipEnabled: StateFlow<Boolean> = _autoSkipEnabled
    
    private val _soundMode = MutableStateFlow(SoundMode.valueOf(prefs.getString(KEY_SOUND_MODE, SoundMode.DEFAULT.name) ?: SoundMode.DEFAULT.name))
    val soundMode: StateFlow<SoundMode> = _soundMode
    
    fun setAutoSkipEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SKIP, enabled).apply()
        _autoSkipEnabled.value = enabled
    }
    
    fun setSoundMode(mode: SoundMode) {
        prefs.edit().putString(KEY_SOUND_MODE, mode.name).apply()
        _soundMode.value = mode
    }
    
    fun isAutoSkipEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_SKIP, true)
    
    fun getSoundMode(): SoundMode = SoundMode.valueOf(prefs.getString(KEY_SOUND_MODE, SoundMode.DEFAULT.name) ?: SoundMode.DEFAULT.name)
    
    companion object {
        private const val PREFS_NAME = "pypyradio_prefs"
        private const val KEY_AUTO_SKIP = "auto_skip_on_error"
        private const val KEY_SOUND_MODE = "sound_mode"
        
        @Volatile
        private var instance: AppPreferences? = null
        
        fun get(context: Context): AppPreferences {
            return instance ?: synchronized(this) {
                instance ?: AppPreferences(context.applicationContext).also { instance = it }
            }
        }
    }
}
