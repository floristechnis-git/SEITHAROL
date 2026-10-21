// java/com/seitharol/data/resolver/StreamResolver.kt
package com.seitharol.data.resolver

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

private val AudioQuality.saavnSuffix: String
    get() = when (this.name.uppercase()) {
        "LOW" -> "_96.mp4"
        "NORMAL", "MEDIUM" -> "_160.mp4"
        else -> "_320.mp4"
    }


data class TrackMetadata(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Int = 0,
    val thumbnailUrl: String = "",
    val encryptedMediaUrl: String? = null,
    val directStreamUrl: String? = null,
    val source: String = "JIOSAAVN"
)

object StreamResolver {

    private val BANNED_REMIX_KEYWORDS = listOf(
        "slowed", "reverb", "lofi", "lo-fi", "cover", "karaoke",
        "instrumental", "mashup", "nightcore", "8d", "bass boosted",
        "ringtone", "whatsapp status", "reaction", "live performance", "dj "
    )

    /**
     * Resolves the exact canonical audio stream for [track].
     */
    suspend fun resolveExactStreamUrl(
        track: TrackMetadata,
        quality: AudioQuality = AudioQuality.HIGH
    ): String? = withContext(Dispatchers.IO) {
        // 1. If a verified direct stream URL is already attached, use it
        if (!track.directStreamUrl.isNullOrBlank() && track.directStreamUrl.startsWith("http")) {
            return@withContext track.directStreamUrl
        }

        // 2. Direct videoId Passthrough: If track has a valid YouTube videoId or watch?v/watch?id, resolve directly (NO re-search)
        val extractedYtId = InnerTubeStreamExtractor.extractVideoId(track.id)
        if (!extractedYtId.isNullOrBlank()) {
            return@withContext InnerTubeStreamExtractor.resolveExactYoutubeStreamById(extractedYtId, quality).getOrNull()
        }

        // 3. If encryptedMediaUrl from JioSaavn is present, decrypt it directly
        if (!track.encryptedMediaUrl.isNullOrBlank()) {
            decryptJioSaavnUrl(track.encryptedMediaUrl, quality)?.let { return@withContext it }
        }

        // 4. For non-YouTube tracks without videoId, try JioSaavn exact match
        resolveFromJioSaavnExact(track, quality)
    }

    /**
     * Decrypts JioSaavn's DES-ECB encrypted_media_url using key "38346591".
     */
    fun decryptJioSaavnUrl(encryptedBase64: String, quality: AudioQuality): String? {
        return try {
            val keySpec = SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decodedBytes = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
            val decrypted = String(cipher.doFinal(decodedBytes), Charsets.UTF_8).trim()
            decrypted.replace("_96.mp4", quality.saavnSuffix)
                .replace("_160.mp4", quality.saavnSuffix)
                .replace("_320.mp4", quality.saavnSuffix)
                .replace("http://", "https://")
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveFromJioSaavnExact(track: TrackMetadata, quality: AudioQuality): String? {
        return try {
            val cleanTitle = cleanTitleForMatch(track.title)
            val primaryArtist = track.artist.split(",", "&", "feat.", "ft.").first().trim()
            val query = URLEncoder.encode("$cleanTitle $primaryArtist", "UTF-8")
            val apiUrl = "https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&includeMetaTags=1&query=$query"

            val searchJson = httpGet(apiUrl) ?: return null
            val root = JSONObject(searchJson)
            val songsArray = root.optJSONObject("songs")?.optJSONArray("data") ?: return null

            var bestSongId: String? = null
            var bestScore = Int.MIN_VALUE

            for (i in 0 until songsArray.length()) {
                val item = songsArray.getJSONObject(i)
                val candidateId = item.optString("id")
                val candidateTitle = decodeHtml(item.optString("title"))
                val candidateSub = decodeHtml(
                    item.optString("more_info") + " " + item.optString("description")
                )

                if (containsUnwantedVariant(originalTitle = track.title, candidateTitle = candidateTitle)) {
                    continue
                }

                val score = computeMatchScore(
                    targetTitle = track.title,
                    targetArtist = primaryArtist,
                    targetDuration = track.durationSeconds,
                    candidateTitle = candidateTitle,
                    candidateArtist = candidateSub,
                    candidateDuration = 0
                )
                if (score > bestScore) {
                    bestScore = score
                    bestSongId = candidateId
                }
            }

            if (bestSongId != null && bestScore >= 40) {
                val detailUrl = "https://www.jiosaavn.com/api.php?__call=song.getDetails&cc=in&_marker=0%3F_marker%3D0&_format=json&pids=$bestSongId"
                val detailJson = httpGet(detailUrl) ?: return null
                val detailObj = JSONObject(detailJson).optJSONObject(bestSongId) ?: return null
                val encryptedUrl = detailObj.optString("encrypted_media_url")
                if (encryptedUrl.isNotBlank()) {
                    return decryptJioSaavnUrl(encryptedUrl, quality)
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }


    fun containsUnwantedVariant(originalTitle: String, candidateTitle: String): Boolean {
        val origLower = originalTitle.lowercase()
        val candLower = candidateTitle.lowercase()
        return BANNED_REMIX_KEYWORDS.any { keyword ->
            candLower.contains(keyword) && !origLower.contains(keyword)
        }
    }

    private fun computeMatchScore(
        targetTitle: String,
        targetArtist: String,
        targetDuration: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateDuration: Int
    ): Int {
        var score = 0
        val cleanTargetTitle = cleanTitleForMatch(targetTitle).lowercase()
        val cleanCandTitle = cleanTitleForMatch(candidateTitle).lowercase()
        val targetArtistLower = targetArtist.lowercase()
        val candArtistLower = candidateArtist.lowercase()

        val targetTokens = cleanTargetTitle.split(" ").filter { it.length >= 2 }
        val matchedTokens = targetTokens.count { cleanCandTitle.contains(it) }
        val titleMatchRatio = if (targetTokens.isNotEmpty()) matchedTokens.toFloat() / targetTokens.size else 1f
        if (cleanCandTitle != cleanTargetTitle && !cleanCandTitle.contains(cleanTargetTitle) && titleMatchRatio < 0.75f) {
            return -1000
        }

        val artistMatches = targetArtistLower.isBlank() ||
            candArtistLower.contains(targetArtistLower) ||
            cleanCandTitle.contains(targetArtistLower)
        if (!artistMatches) return -1000

        score += (titleMatchRatio * 80).toInt()
        if (cleanCandTitle == cleanTargetTitle) score += 60
        if (candArtistLower.contains(targetArtistLower)) score += 40
        if (candArtistLower.contains("- topic") || candArtistLower.contains("vevo")) score += 30

        if (targetDuration > 0 && candidateDuration > 0) {
            val diff = abs(targetDuration - candidateDuration)
            score += when {
                diff <= 3 -> 40
                diff <= 8 -> 20
                else -> -100
            }
        }
        return score
    }

    private fun cleanTitleForMatch(raw: String): String {
        return raw.replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            .replace(Regex("(?i)official\\s*(music\\s*)?(video|audio|lyric\\s*video)"), "")
            .trim()
    }

    private fun decodeHtml(input: String): String {
        return input.replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
    }

    private fun httpGet(urlStr: String): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
        }
        return if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
    }
}
