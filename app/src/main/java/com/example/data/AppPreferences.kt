package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserSettings(
    val onboarded: Boolean = false,
    val userName: String = "",
    val userAge: String = "",
    val audioQuality: String = "High (256 kbps)",
    val normalizeVolume: Boolean = true,
    val bassBoostLevel: Float = 0f,
    val crossfadeSeconds: Int = 0,
    val sleepTimerMinutes: Int = 0 // 0 = "Don't stop"
)

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("seitharol_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun loadSettings(): UserSettings {
        return UserSettings(
            onboarded = prefs.getBoolean("onboarded", false),
            userName = prefs.getString("user_name", "") ?: "",
            userAge = prefs.getString("user_age", "") ?: "",
            audioQuality = prefs.getString("audio_quality", "High (256 kbps)") ?: "High (256 kbps)",
            normalizeVolume = prefs.getBoolean("normalize_volume", true),
            bassBoostLevel = prefs.getFloat("bass_boost", 0f),
            crossfadeSeconds = prefs.getInt("crossfade_sec", 0),
            sleepTimerMinutes = prefs.getInt("sleep_timer_min", 0)
        )
    }

    fun saveOnboarding(name: String, age: String) {
        prefs.edit()
            .putBoolean("onboarded", true)
            .putString("user_name", name.trim())
            .putString("user_age", age.trim())
            .apply()
        _settings.value = loadSettings()
    }

    fun updateAccount(name: String, age: String) {
        prefs.edit()
            .putString("user_name", name.trim())
            .putString("user_age", age.trim())
            .apply()
        _settings.value = loadSettings()
    }

    fun updateSoundSettings(
        audioQuality: String,
        normalizeVolume: Boolean,
        bassBoostLevel: Float,
        crossfadeSeconds: Int
    ) {
        prefs.edit()
            .putString("audio_quality", audioQuality)
            .putBoolean("normalize_volume", normalizeVolume)
            .putFloat("bass_boost", bassBoostLevel)
            .putInt("crossfade_sec", crossfadeSeconds)
            .apply()
        _settings.value = loadSettings()
    }

    /**
     * @param minutes Pass 0 for "Don't stop"
     */
    fun saveSleepTimer(minutes: Int) {
        prefs.edit()
            .putInt("sleep_timer_min", minutes)
            .apply()
        _settings.value = loadSettings()
    }
}
