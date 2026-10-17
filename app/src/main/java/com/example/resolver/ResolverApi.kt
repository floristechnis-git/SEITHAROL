package com.example.resolver

import com.example.data.model.Track

/**
 * Resolver SPI and domain models as specified in PROJECT CASCADE (§A.3, §A.5, §H.4).
 */

enum class Capability {
    SIGNATURE,
    THROTTLE,
    PO_TOKEN,
    AGE_RESTRICTED,
    MEMBERS_ONLY,
    LIVE,
    PODCAST,
    UPLOAD
}

enum class TrustZone {
    ZONE_1_DEVICE,          // Always trusted: InnerTube transport, Kotlin decipher, Media3
    ZONE_2_OWN_RESOLVER,    // User-operated: FastAPI + yt-dlp or Piped-Backend (§A.6)
    ZONE_3_COMMUNITY_RESOLVER // Federated Piped / Invidious nodes (§A.6)
}

enum class FailureKind(val retryable: Boolean) {
    NETWORK_TRANSIENT(true),
    HTTP_429(true),
    HTTP_5XX(true),
    STREAM_EXPIRED(true),
    SIGNATURE_FAILED(true),
    PO_REQUIRED(true),
    PLAYER_CONTEXT_REJECTED(true),
    SCHEMA_DRIFT(true),
    LOGIN_REQUIRED(false),
    AGE_RESTRICTED(false),
    MEMBERS_ONLY(false),
    REGION_LOCKED(false),
    UNAVAILABLE(false),
    DECODER_UNSUPPORTED(false)
}

data class FormatPolicy(
    val codecs: List<String> = listOf("opus", "aac"),
    val maxBitrate: Int = 256000,
    val preferContainer: String = "any",
    val allowProgressive: Boolean = false
)

data class ResolveRequest(
    val videoId: String,
    val title: String? = null,
    val artist: String? = null,
    val kind: String = "song",
    val accountMode: String = "guest",
    val formatPolicy: FormatPolicy = FormatPolicy(),
    val visitorData: String? = null,
    val poToken: String? = null,
    val clientHint: String = "WEB_REMIX"
)

data class FormatItem(
    val id: String,
    val codec: String,
    val container: String,
    val bitrate: Int,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val byteLength: Long = 0L,
    val url: String,
    val rangeSupported: Boolean = true
)

data class Chapter(
    val startMs: Long,
    val endMs: Long,
    val title: String
)

data class ResolveResult(
    val videoId: String,
    val resolveId: String,
    val expiresAt: Long,
    val durationMs: Long,
    val title: String,
    val artist: String,
    val artUrl: String,
    val formats: List<FormatItem>,
    val chapters: List<Chapter> = emptyList(),
    val warnings: List<String> = emptyList(),
    val resolverId: String,
    val packVersion: String
)

data class ResolvedStream(
    val videoId: String,
    val streamUrl: String,
    val itag: Int,
    val codec: String,
    val bitrate: String,
    val expiresAtMs: Long,
    val isLocal: Boolean,
    val resolvedBy: String,
    val latencyMs: Long
)

interface Resolver {
    val id: String
    val priority: Int
    val capabilities: Set<Capability>
    val trust: TrustZone
    suspend fun resolve(req: ResolveRequest): ResolveResult
}
