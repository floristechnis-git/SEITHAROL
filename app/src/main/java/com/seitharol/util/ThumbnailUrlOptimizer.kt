package com.seitharol.util

import android.content.Context
import android.net.ConnectivityManager
import com.seitharol.data.preferences.ThumbnailQuality

enum class ThumbnailTargetSize(val lowPx: Int, val mediumPx: Int, val highPx: Int) {
    LIST_THUMB(lowPx = 96, mediumPx = 160, highPx = 240),
    CARD_MEDIUM(lowPx = 180, mediumPx = 320, highPx = 500),
    HERO_LARGE(lowPx = 320, mediumPx = 544, highPx = 1000)
}

object ThumbnailUrlOptimizer {

    fun optimize(
        rawUrl: String?,
        quality: ThumbnailQuality = ThumbnailQuality.AUTO,
        targetSize: ThumbnailTargetSize = ThumbnailTargetSize.LIST_THUMB,
        context: Context? = null
    ): String {
        if (rawUrl.isNullOrBlank()) return ""
        val effectiveQuality = when (quality) {
            ThumbnailQuality.AUTO -> if (isMeteredConnection(context)) ThumbnailQuality.LOW else ThumbnailQuality.MEDIUM
            else -> quality
        }

        val targetPx = when (effectiveQuality) {
            ThumbnailQuality.LOW -> targetSize.lowPx
            ThumbnailQuality.MEDIUM -> targetSize.mediumPx
            ThumbnailQuality.HIGH, ThumbnailQuality.AUTO -> targetSize.highPx
        }

        val url = rawUrl.trim().replace("http://", "https://")

        // 1. Google / YouTube Music lh3.googleusercontent.com sizing (=wXXX-hXXX)
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            val base = url.substringBefore("=w").substringBefore("=s")
            return "$base=w$targetPx-h$targetPx-l90-rj"
        }

        // 2. YouTube i.ytimg.com video thumbnails
        if (url.contains("ytimg.com/vi/")) {
            val targetVariant = when (effectiveQuality) {
                ThumbnailQuality.LOW -> "default.jpg"       // 120x90
                ThumbnailQuality.MEDIUM -> "mqdefault.jpg"  // 320x180
                ThumbnailQuality.HIGH, ThumbnailQuality.AUTO -> {
                    if (targetSize == ThumbnailTargetSize.HERO_LARGE) "hqdefault.jpg" else "mqdefault.jpg"
                }
            }
            return url.replace(Regex("(maxresdefault|sddefault|hqdefault|mqdefault|default)\\.jpg"), targetVariant)
        }

        // 3. JioSaavn CDN thumbnails (50x50, 150x150, 500x500)
        if (url.contains("saavncdn.com")) {
            val saavnDim = when {
                targetPx <= 160 -> "150x150"
                else -> "500x500"
            }
            return url.replace(Regex("\\d+x\\d+"), saavnDim)
        }

        // 4. Spotify scdn.co image hashes (ab67616d00001e02 = 300x300, ab67616d00004851 = 64x64, ab67616d0000b273 = 640x640)
        if (url.contains("i.scdn.co/image/")) {
            return when {
                effectiveQuality == ThumbnailQuality.LOW && targetSize == ThumbnailTargetSize.LIST_THUMB ->
                    url.replace("ab67616d0000b273", "ab67616d00001e02")
                targetSize != ThumbnailTargetSize.HERO_LARGE ->
                    url.replace("ab67616d0000b273", "ab67616d00001e02")
                else -> url
            }
        }

        return url
    }

    private fun isMeteredConnection(context: Context?): Boolean {
        if (context == null) return false
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.isActiveNetworkMetered ?: false
        }.getOrDefault(false)
    }
}
