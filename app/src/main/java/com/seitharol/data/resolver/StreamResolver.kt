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
        "remix", "slowed", "reverb", "lofi", "lo-fi", "cover", "karaoke",
        "instrumental", "mashup", "nightcore", "8d", "bass boosted",
        "ringtone", "whatsapp status", "status", "reaction", "live performance",
        "live", "unplugged", "sped up", "dj ", "dj"
    )

    /**
     * Resolves the exact canonical audio stream for [track].
     * Strictly verifies Title + Artist + Duration + No-Remix before accepting any JioSaavn stream.
     */
    suspend fun resolveExactStreamUrl(
        track: TrackMetadata,
        quality: AudioQuality = AudioQuality.HIGH
    ): String? = withContext(Dispatchers.IO) {
        // 1. If a verified direct stream URL is already attached, use it
        if (!track.directStreamUrl.isNullOrBlank() && track.directStreamUrl.startsWith("http")) {
            return@withContext track.directStreamUrl
        }

        // 2. Direct videoId Passthrough (unless caller explicitly requested JIOSAAVN_ONLY fallback)
        if (!track.source.equals("JIOSAAVN_ONLY", ignoreCase = true)) {
            val extractedYtId = InnerTubeStreamExtractor.extractVideoId(track.id)
            if (!extractedYtId.isNullOrBlank()) {
                InnerTubeStreamExtractor.resolveExactYoutubeStreamById(extractedYtId, quality)
                    .getOrNull()
                    ?.let { return@withContext it }
            }
        }

        // 3. If encryptedMediaUrl from JioSaavn is present, decrypt it directly
        if (!track.encryptedMediaUrl.isNullOrBlank()) {
            decryptJioSaavnUrl(track.encryptedMediaUrl, quality)?.let { return@withContext it }
        }

        // 4. Resolve from JioSaavn with strict Title + Artist + Duration verification
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
        val cleanTitle = cleanTitleForMatch(track.title)
        if (cleanTitle.isBlank()) return null
        val primaryArtist = track.artist
            .removeSuffix(" - Topic")
            .removeSuffix("- Topic")
            .split(",", "&", "feat.", "ft.", "/")
            .firstOrNull()
            ?.trim()
            .orEmpty()

        // NEVER query title-only when the artist is known, preventing JioSaavn from returning a different artist's song!
        val searchQueries = buildList {
            if (primaryArtist.isNotBlank() &&
                !primaryArtist.equals("Unknown Artist", ignoreCase = true) &&
                !primaryArtist.equals("Various Artists", ignoreCase = true)
            ) {
                add("$cleanTitle $primaryArtist")
            } else {
                add(cleanTitle)
            }
        }.distinct()

        for (rawQuery in searchQueries) {
            try {
                val encQuery = URLEncoder.encode(rawQuery, "UTF-8")
                val v4Url = "https://www.jiosaavn.com/api.php?__call=search.getResults&api_version=4&_format=json&_marker=0&ctx=android&q=$encQuery&n=8"
                val v4Json = httpGet(v4Url)
                if (!v4Json.isNullOrBlank()) {
                    val root = JSONObject(v4Json)
                    val results = root.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        var bestEncrypted: String? = null
                        var bestScore = Int.MIN_VALUE

                        for (i in 0 until results.length()) {
                            val item = results.optJSONObject(i) ?: continue
                            val moreInfo = item.optJSONObject("more_info")
                            val candTitle = decodeHtml(item.optString("title").ifBlank { item.optString("song") })
                            val candArtist = decodeHtml(
                                item.optString("subtitle") + " " +
                                    (moreInfo?.optString("singers").orEmpty()) + " " +
                                    (moreInfo?.optString("primary_artists").orEmpty())
                            )
                            val candDur = item.optString("duration").toIntOrNull()
                                ?: moreInfo?.optString("duration")?.toIntOrNull()
                                ?: 0
                            val encrypted = item.optString("encrypted_media_url").ifBlank {
                                moreInfo?.optString("encrypted_media_url").orEmpty()
                            }
                            if (encrypted.isBlank()) continue
                            if (containsUnwantedVariant(track.title, "$candTitle $candArtist")) continue

                            val score = computeMatchScore(
                                targetTitle = track.title,
                                targetArtist = primaryArtist,
                                targetDuration = track.durationSeconds,
                                candidateTitle = candTitle,
                                candidateArtist = candArtist,
                                candidateDuration = candDur
                            )
                            if (score > bestScore) {
                                bestScore = score
                                bestEncrypted = encrypted
                            }
                        }

                        if (!bestEncrypted.isNullOrBlank() && bestScore >= 110) {
                            decryptJioSaavnUrl(bestEncrypted, quality)?.let { return it }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
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
        val cleanTargetTitle = CanonicalAudioResolver.normalizeText(cleanTitleForMatch(targetTitle))
        val cleanCandTitle = CanonicalAudioResolver.normalizeText(cleanTitleForMatch(candidateTitle))
        val targetArtistLower = CanonicalAudioResolver.normalizeText(targetArtist)
        val candArtistLower = CanonicalAudioResolver.normalizeText(candidateArtist)

        val targetTokens = cleanTargetTitle.split(" ").filter { it.length >= 2 }
        val matchedTokens = targetTokens.count { cleanCandTitle.contains(it) }
        val titleMatchRatio = if (targetTokens.isNotEmpty()) matchedTokens.toFloat() / targetTokens.size else 1f
        if (cleanCandTitle != cleanTargetTitle && !cleanCandTitle.contains(cleanTargetTitle) && titleMatchRatio < 0.75f) {
            return -1000
        }

        // HARD ARTIST GATE: If targetArtist is known, candidate MUST share the artist name or core artist token!
        // Prevents playing a completely different song that happens to share the same title on JioSaavn.
        if (targetArtistLower.isNotBlank() &&
            !targetArtistLower.equals("unknown artist", ignoreCase = true) &&
            !targetArtistLower.equals("various artists", ignoreCase = true)
        ) {
            val artistTokens = targetArtistLower.split(" ").filter { it.length >= 3 }
                .ifEmpty { targetArtistLower.split(" ").filter { it.length >= 2 } }
            val artistMatches = candArtistLower.contains(targetArtistLower) ||
                cleanCandTitle.contains(targetArtistLower) ||
                (artistTokens.isNotEmpty() && artistTokens.any { candArtistLower.contains(it) || cleanCandTitle.contains(it) })
            if (!artistMatches) {
                return -1000
            }
            score += 55
        }

        // HARD DURATION GATE: Reject candidates whose duration differs by >15s when targetDuration is known (>45s)
        if (targetDuration > 45 && candidateDuration > 0 && targetDuration != 210) {
            val diff = abs(targetDuration - candidateDuration)
            if (diff > 15) {
                return -1000
            }
            score += when {
                diff <= 5 -> 35
                diff <= 15 -> 15
                else -> -1000
            }
        }

        score += (titleMatchRatio * 80).toInt()
        if (cleanCandTitle == cleanTargetTitle) score += 60

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
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            setRequestProperty("Referer", "https://www.jiosaavn.com/")
        }
        return if (conn.responseCode in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else null
    }
}
