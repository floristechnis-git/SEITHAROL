package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Enterprise-grade error formatter and network status handler.
 * Completely eliminates developer-mode reports, stack traces, and internal provider logs
 * from leaking into user-facing UI and snackbars.
 */
object ErrorFormatter {

    /**
     * Checks whether the device currently has an active, validated internet connection.
     */
    fun isOnline(context: Context?): Boolean {
        if (context == null) return true
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Translates any raw Exception or Throwable into a polished, professional message.
     */
    fun toUserFriendlyMessage(
        throwable: Throwable?,
        context: Context? = null,
        trackTitle: String? = null,
        hasNextTrack: Boolean = false
    ): String {
        val nextSuffix = if (hasNextTrack) " Playing next song." else " Please try again."

        val raw = throwable?.message.orEmpty()
        val lower = raw.lowercase()
        if (lower.contains("offline mode") || lower.contains("not cached locally") || lower.contains("not available on device")) {
            return "Track not available offline. Connect to internet or download first."
        }

        // 1. Check network connectivity
        if (context != null && !isOnline(context)) {
            return "No internet connection. Playing downloaded offline tracks only."
        }

        if (throwable == null) {
            return if (!trackTitle.isNullOrBlank()) {
                "Unable to play \"$trackTitle\".$nextSuffix"
            } else {
                "Unable to load audio. Please check your connection."
            }
        }

        // 2. Walk cause chain to detect root networking/system exceptions
        var curr: Throwable? = throwable
        while (curr != null) {
            when (curr) {
                is UnknownHostException -> {
                    return "No internet connection. Please check your network."
                }
                is SocketTimeoutException -> {
                    return "Connection timed out. Please check your internet connection."
                }
                is ConnectException -> {
                    return "Unable to connect to audio server. Please check your network."
                }
                is SSLException -> {
                    return "Secure connection error. Please verify your date, time, and network."
                }
            }
            curr = curr.cause
        }

        // 3. Inspect message text for key patterns
        return when {
            lower.contains("unknownhost") ||
                lower.contains("no address associated") ||
                lower.contains("network is unreachable") ||
                lower.contains("failed to connect") ||
                lower.contains("enonetwork") -> {
                "No internet connection. Please check your network."
            }
            lower.contains("timeout") || lower.contains("timed out") -> {
                "Connection timed out. Please check your internet connection."
            }
            lower.contains("403") || lower.contains("forbidden") || lower.contains("unauthorized") -> {
                "Playback source temporarily restricted.$nextSuffix"
            }
            lower.contains("404") || lower.contains("not found") -> {
                "Track is currently unavailable.$nextSuffix"
            }
            lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("bad gateway") -> {
                "Server is temporarily busy. Please try again shortly."
            }
            !trackTitle.isNullOrBlank() -> {
                "Unable to play \"$trackTitle\".$nextSuffix"
            }
            else -> {
                "Unable to play this track.$nextSuffix"
            }
        }
    }

    /**
     * Sanitizes any raw notification string before presenting it to the user.
     */
    fun sanitizeNotice(rawMessage: String, context: Context? = null): String {
        val lower = rawMessage.lowercase()

        // 1. Always pass through legitimate non-error user notifications even when in Airplane Mode
        if (lower.startsWith("sleep timer") ||
            lower.startsWith("added to") ||
            lower.startsWith("removed from") ||
            lower.startsWith("playlist created") ||
            lower.startsWith("profile details") ||
            lower.startsWith("stream cache") ||
            lower.startsWith("airplane mode") ||
            lower.startsWith("track not available offline") ||
            lower.startsWith("sponsorblock: skipped") ||
            lower.startsWith("skipped sponsor")
        ) {
            return rawMessage
        }

        if (context != null && !isOnline(context)) {
            return "No internet connection. Please check your network."
        }

        // Detect and sanitize any dev-mode reports, stack traces, or fallback messages
        if (lower.contains("exception") ||
            lower.contains("unknownhost") ||
            lower.contains("no address associated") ||
            lower.contains("network is unreachable") ||
            lower.contains("failed to connect") ||
            lower.contains("failed after 3 retry") ||
            lower.contains("stream interrupted for") ||
            lower.contains("retrying fallback") ||
            lower.contains("playback note:") ||
            lower.contains("could not resolve stream") ||
            lower.contains("spotifymatcher") ||
            lower.contains("jiosaavndirect") ||
            lower.contains("innertubedirect") ||
            lower.contains("studiomaster") ||
            lower.contains("retry attempt")
        ) {
            return when {
                lower.contains("unknownhost") ||
                    lower.contains("no address associated") ||
                    lower.contains("network is unreachable") ||
                    lower.contains("failed to connect") -> {
                    "No internet connection. Please check your network."
                }
                lower.contains("offline") -> {
                    "Track not available offline. Connect to internet or download first."
                }
                lower.contains("retrying fallback") || lower.contains("stream interrupted") -> {
                    "Reconnecting audio stream..."
                }
                else -> {
                    "Unable to play track. Please try again."
                }
            }
        }

        return rawMessage
    }
}
