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
    val audioQuality: String = "Data Saver (96 kbps)",
    val streamingQuality: String = "LOW", // "LOW", "NORMAL", "HIGH" - Data Saver default
    val downloadQuality: String = "LOW", // "LOW", "NORMAL", "HIGH" - Data Saver default
    val normalizeVolume: Boolean = true,
    val bassBoostLevel: Float = 0f,
    val crossfadeSeconds: Int = 0,
    val sleepTimerMinutes: Int = 0, // 0 = "Don't stop"
    val eqBandsDb: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
    val eqEnabled: Boolean = false,
    val gaplessPlayback: Boolean = true,
    val audioNormalization: Boolean = false,
    val stopImmediatelyOnNewTrack: Boolean = true
)

class AppPreferences private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("seitharol_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private val _recentSearches = MutableStateFlow(loadRecentSearches())
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = loadSettings()
        _recentSearches.value = loadRecentSearches()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
    }

    companion object {
        @Volatile
        private var INSTANCE: AppPreferences? = null

        fun getInstance(context: Context): AppPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }

        operator fun invoke(context: Context): AppPreferences = getInstance(context)
    }

    private fun loadSettings(): UserSettings {
        val bands = (0 until 5).map { idx ->
            prefs.getFloat("eq_band_$idx", 0f)
        }
        return UserSettings(
            onboarded = prefs.getBoolean("onboarded", false),
            userName = prefs.getString("user_name", "") ?: "",
            userAge = prefs.getString("user_age", "") ?: "",
            themeMode = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM",
            audioQuality = prefs.getString("audio_quality", "Data Saver (96 kbps)") ?: "Data Saver (96 kbps)",
            streamingQuality = prefs.getString("streaming_quality", "LOW") ?: "LOW",
            downloadQuality = prefs.getString("download_quality", "LOW") ?: "LOW",
            normalizeVolume = prefs.getBoolean("normalize_volume", true),
            bassBoostLevel = prefs.getFloat("bass_boost", 0f),
            crossfadeSeconds = prefs.getInt("crossfade_sec", 0),
            sleepTimerMinutes = prefs.getInt("sleep_timer_min", 0),
            eqBandsDb = bands,
            eqEnabled = prefs.getBoolean("eq_enabled", false),
            gaplessPlayback = prefs.getBoolean("gapless_playback", true),
            audioNormalization = prefs.getBoolean("audio_normalization", false),
            stopImmediatelyOnNewTrack = prefs.getBoolean("stop_immediately_on_new_track", true)
        )
    }

    fun setStopImmediatelyOnNewTrack(enabled: Boolean) {
        prefs.edit().putBoolean("stop_immediately_on_new_track", enabled).apply()
        _settings.value = loadSettings()
    }

    private fun loadRecentSearches(): List<String> {
        val raw = prefs.getString("recent_searches_list", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|||").filter { it.isNotBlank() }.take(4)
    }

    fun addRecentSearch(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        val current = loadRecentSearches().toMutableList()
        current.removeAll { it.equals(clean, ignoreCase = true) }
        current.add(0, clean)
        val updated = current.take(4)
        prefs.edit().putString("recent_searches_list", updated.joinToString("|||")).apply()
        _recentSearches.value = updated
    }

    fun removeRecentSearch(query: String) {
        val current = loadRecentSearches().toMutableList()
        current.removeAll { it.equals(query.trim(), ignoreCase = true) }
        prefs.edit().putString("recent_searches_list", current.joinToString("|||")).apply()
        _recentSearches.value = current
    }

    fun clearRecentSearches() {
        prefs.edit().remove("recent_searches_list").apply()
        _recentSearches.value = emptyList()
    }

    internal fun setThemeModeDirect(mode: String) {
        val current = prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM"
        if (!current.equals(mode, ignoreCase = true)) {
            prefs.edit().putString("theme_mode", mode).apply()
            _settings.value = loadSettings()
        }
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
        _settings.value = loadSettings()
        runCatching {
            val tm = when (mode.uppercase()) {
                "LIGHT" -> com.seitharol.data.preferences.ThemeMode.LIGHT
                "DARK" -> com.seitharol.data.preferences.ThemeMode.DARK
                else -> com.seitharol.data.preferences.ThemeMode.SYSTEM
            }
            com.seitharol.data.preferences.AppSettingsManager.getInstance(appContext).setThemeModeFromExternal(tm)
        }
    }

    fun setStreamingQuality(quality: String) {
        val current = prefs.getString("streaming_quality", "LOW") ?: "LOW"
        if (current.equals(quality, ignoreCase = true)) return
        prefs.edit().putString("streaming_quality", quality).apply()
        _settings.value = loadSettings()
        runCatching {
            val aq = when (quality.uppercase()) {
                "LOW" -> com.seitharol.data.resolver.AudioQuality.LOW
                "NORMAL" -> com.seitharol.data.resolver.AudioQuality.NORMAL
                "HIGH" -> com.seitharol.data.resolver.AudioQuality.HIGH
                else -> com.seitharol.data.resolver.AudioQuality.NORMAL
            }
            com.seitharol.data.preferences.AppSettingsManager.getInstance(appContext).setStreamingQuality(aq)
        }
    }

    fun setDownloadQuality(quality: String) {
        val current = prefs.getString("download_quality", "LOW") ?: "LOW"
        if (current.equals(quality, ignoreCase = true)) return
        prefs.edit().putString("download_quality", quality).apply()
        _settings.value = loadSettings()
        runCatching {
            val aq = when (quality.uppercase()) {
                "LOW" -> com.seitharol.data.resolver.AudioQuality.LOW
                "NORMAL" -> com.seitharol.data.resolver.AudioQuality.NORMAL
                "HIGH" -> com.seitharol.data.resolver.AudioQuality.HIGH
                else -> com.seitharol.data.resolver.AudioQuality.NORMAL
            }
            com.seitharol.data.preferences.AppSettingsManager.getInstance(appContext).setDownloadQuality(aq)
        }
    }

    fun setEqBand(bandIndex: Int, gainDb: Float) {
        val clamped = gainDb.coerceIn(-12f, 12f)
        prefs.edit().putFloat("eq_band_$bandIndex", clamped).apply()
        _settings.value = loadSettings()
        runCatching {
            com.seitharol.data.preferences.AppSettingsManager.getInstance(appContext).setEqBand(bandIndex, clamped)
        }
    }

    fun resetEqualizer() {
        val editor = prefs.edit()
        for (i in 0 until 5) {
            editor.putFloat("eq_band_$i", 0f)
        }
        editor.apply()
        _settings.value = loadSettings()
        runCatching {
            com.seitharol.data.preferences.AppSettingsManager.getInstance(appContext).resetEqualizer()
        }
    }

    fun setEqEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("eq_enabled", enabled).apply()
        _settings.value = loadSettings()
        runCatching {
            com.seitharol.data.preferences.AppSettingsManager.getInstance(appContext).setEqEnabled(enabled)
        }
    }

    fun setGaplessPlayback(enabled: Boolean) {
        prefs.edit().putBoolean("gapless_playback", enabled).apply()
        _settings.value = loadSettings()
        runCatching {
            com.seitharol.data.preferences.AppSettingsManager.getInstance(appContext).setGaplessPlayback(enabled)
        }
    }

    fun setAudioNormalization(enabled: Boolean) {
        prefs.edit().putBoolean("audio_normalization", enabled).apply()
        _settings.value = loadSettings()
        runCatching {
            com.seitharol.data.preferences.AppSettingsManager.getInstance(appContext).setAudioNormalization(enabled)
        }
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
        runCatching {
            com.seitharol.data.preferences.AppSettingsManager.getInstance(appContext).setSleepTimer(minutes)
        }
    }
}
