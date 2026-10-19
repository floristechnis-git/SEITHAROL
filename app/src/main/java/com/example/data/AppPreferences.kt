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
    val themeMode: String = "SYSTEM", // "SYSTEM", "LIGHT", "DARK"
    val audioQuality: String = "High (256 kbps)",
    val streamingQuality: String = "HIGH", // "LOW", "NORMAL", "HIGH"
    val downloadQuality: String = "HIGH", // "NORMAL", "HIGH"
    val normalizeVolume: Boolean = true,
    val bassBoostLevel: Float = 0f,
    val crossfadeSeconds: Int = 0,
    val sleepTimerMinutes: Int = 0, // 0 = "Don't stop"
    val eqBandsDb: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
    val eqEnabled: Boolean = false,
    val gaplessPlayback: Boolean = true,
    val audioNormalization: Boolean = false
)

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("seitharol_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun loadSettings(): UserSettings {
        val bands = (0 until 5).map { idx ->
            prefs.getFloat("eq_band_$idx", 0f)
        }
        return UserSettings(
            onboarded = prefs.getBoolean("onboarded", false),
            userName = prefs.getString("user_name", "") ?: "",
            userAge = prefs.getString("user_age", "") ?: "",
            themeMode = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM",
            audioQuality = prefs.getString("audio_quality", "High (256 kbps)") ?: "High (256 kbps)",
            streamingQuality = prefs.getString("streaming_quality", "HIGH") ?: "HIGH",
            downloadQuality = prefs.getString("download_quality", "HIGH") ?: "HIGH",
            normalizeVolume = prefs.getBoolean("normalize_volume", true),
            bassBoostLevel = prefs.getFloat("bass_boost", 0f),
            crossfadeSeconds = prefs.getInt("crossfade_sec", 0),
            sleepTimerMinutes = prefs.getInt("sleep_timer_min", 0),
            eqBandsDb = bands,
            eqEnabled = prefs.getBoolean("eq_enabled", false),
            gaplessPlayback = prefs.getBoolean("gapless_playback", true),
            audioNormalization = prefs.getBoolean("audio_normalization", false)
        )
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
        _settings.value = loadSettings()
    }

    fun setStreamingQuality(quality: String) {
        prefs.edit().putString("streaming_quality", quality).apply()
        _settings.value = loadSettings()
    }

    fun setDownloadQuality(quality: String) {
        prefs.edit().putString("download_quality", quality).apply()
        _settings.value = loadSettings()
    }

    fun setEqBand(bandIndex: Int, gainDb: Float) {
        val clamped = gainDb.coerceIn(-12f, 12f)
        prefs.edit().putFloat("eq_band_$bandIndex", clamped).apply()
        _settings.value = loadSettings()
    }

    fun resetEqualizer() {
        val editor = prefs.edit()
        for (i in 0 until 5) {
            editor.putFloat("eq_band_$i", 0f)
        }
        editor.apply()
        _settings.value = loadSettings()
    }

    fun setEqEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("eq_enabled", enabled).apply()
        _settings.value = loadSettings()
    }

    fun setGaplessPlayback(enabled: Boolean) {
        prefs.edit().putBoolean("gapless_playback", enabled).apply()
        _settings.value = loadSettings()
    }

    fun setAudioNormalization(enabled: Boolean) {
        prefs.edit().putBoolean("audio_normalization", enabled).apply()
        _settings.value = loadSettings()
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

    fun saveSleepTimer(minutes: Int) {
        prefs.edit()
            .putInt("sleep_timer_min", minutes)
            .apply()
        _settings.value = loadSettings()
    }
}
