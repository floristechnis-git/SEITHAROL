package com.seitharol.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.seitharol.data.resolver.AudioQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("Use Device Theme"),
    DARK("Dark Mode"),
    LIGHT("Light Mode")
}

data class PersistedSettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val streamingQuality: AudioQuality = AudioQuality.NORMAL,
    val downloadQuality: AudioQuality = AudioQuality.NORMAL,
    val eqBandsDb: List<Float> = listOf(0f, 0f, 0f, 0f, 0f),
    val eqEnabled: Boolean = false,
    val sleepTimerMinutes: Int = 0,
    val gaplessPlayback: Boolean = true,
    val audioNormalization: Boolean = false
)

class AppSettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("seitharol_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadFromPrefs())
    val state: StateFlow<PersistedSettingsState> = _state.asStateFlow()

    private fun loadFromPrefs(): PersistedSettingsState {
        val themeName = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val streamName = prefs.getString(KEY_STREAM_QUALITY, AudioQuality.NORMAL.name) ?: AudioQuality.NORMAL.name
        val downloadName = prefs.getString(KEY_DOWNLOAD_QUALITY, AudioQuality.NORMAL.name) ?: AudioQuality.NORMAL.name
        val bands = (0 until 5).map { idx ->
            prefs.getFloat("${KEY_EQ_BAND_PREFIX}$idx", 0f)
        }
        return PersistedSettingsState(
            themeMode = runCatching { ThemeMode.valueOf(themeName) }.getOrDefault(ThemeMode.SYSTEM),
            streamingQuality = runCatching { AudioQuality.valueOf(streamName) }.getOrDefault(AudioQuality.NORMAL),
            downloadQuality = runCatching { AudioQuality.valueOf(downloadName) }.getOrDefault(AudioQuality.NORMAL),
            eqBandsDb = bands,
            eqEnabled = prefs.getBoolean(KEY_EQ_ENABLED, false),
            sleepTimerMinutes = prefs.getInt(KEY_SLEEP_TIMER, 0),
            gaplessPlayback = prefs.getBoolean(KEY_GAPLESS, true),
            audioNormalization = prefs.getBoolean(KEY_NORMALIZATION, false)
        )
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _state.value = _state.value.copy(themeMode = mode)
    }

    fun setStreamingQuality(quality: AudioQuality) {
        prefs.edit().putString(KEY_STREAM_QUALITY, quality.name).apply()
        _state.value = _state.value.copy(streamingQuality = quality)
    }

    fun setDownloadQuality(quality: AudioQuality) {
        prefs.edit().putString(KEY_DOWNLOAD_QUALITY, quality.name).apply()
        _state.value = _state.value.copy(downloadQuality = quality)
    }

    fun setEqBand(bandIndex: Int, gainDb: Float) {
        val clamped = gainDb.coerceIn(-12f, 12f)
        prefs.edit().putFloat("${KEY_EQ_BAND_PREFIX}$bandIndex", clamped).apply()
        val updated = _state.value.eqBandsDb.toMutableList().apply {
            if (bandIndex in indices) this[bandIndex] = clamped
        }
        _state.value = _state.value.copy(eqBandsDb = updated)
    }

    /**
     * Resets all Equalizer bands back to 0 dB flat response and persists immediately.
     */
    fun resetEqualizer() {
        val editor = prefs.edit()
        for (i in 0 until 5) {
            editor.putFloat("${KEY_EQ_BAND_PREFIX}$i", 0f)
        }
        editor.apply()
        _state.value = _state.value.copy(eqBandsDb = listOf(0f, 0f, 0f, 0f, 0f))
    }

    fun setEqEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EQ_ENABLED, enabled).apply()
        _state.value = _state.value.copy(eqEnabled = enabled)
    }

    fun setSleepTimer(minutes: Int) {
        prefs.edit().putInt(KEY_SLEEP_TIMER, minutes).apply()
        _state.value = _state.value.copy(sleepTimerMinutes = minutes)
    }

    fun setGaplessPlayback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GAPLESS, enabled).apply()
        _state.value = _state.value.copy(gaplessPlayback = enabled)
    }

    fun setAudioNormalization(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NORMALIZATION, enabled).apply()
        _state.value = _state.value.copy(audioNormalization = enabled)
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_STREAM_QUALITY = "streaming_quality"
        private const val KEY_DOWNLOAD_QUALITY = "download_quality"
        private const val KEY_EQ_BAND_PREFIX = "eq_band_"
        private const val KEY_EQ_ENABLED = "eq_enabled"
        private const val KEY_SLEEP_TIMER = "sleep_timer_min"
        private const val KEY_GAPLESS = "gapless_playback"
        private const val KEY_NORMALIZATION = "audio_normalization"
    }
}
