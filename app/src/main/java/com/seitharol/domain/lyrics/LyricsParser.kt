package com.seitharol.domain.lyrics

data class SyncedLyricLine(
    val timestampMs: Long,
    val endTimestampMs: Long,
    val primaryText: String,
    val translationText: String? = null,
    val isInstrumentalGap: Boolean = false
)

object LyricsParser {

    private const val INSTRUMENTAL_GAP_THRESHOLD_MS = 4500L
    private val LRC_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?](.*)""")

    /**
     * Parses raw LRC or plain lyrics, cleans up `{Original}^{Translation}` caret artifacts,
     * and injects instrumental gap indicators for intros and vocal pauses >= 4.5s.
     */
    fun parseSyncedLyrics(rawLrc: String, totalTrackDurationMs: Long): List<SyncedLyricLine> {
        val rawEntries = mutableListOf<Triple<Long, String, String?>>()

        rawLrc.lineSequence().forEach { line ->
            val match = LRC_REGEX.find(line.trim())
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val fracStr = match.groupValues[3].padEnd(3, '0').take(3)
                val ms = fracStr.toLongOrNull() ?: 0L
                val timeMs = (min * 60_000L) + (sec * 1_000L) + ms
                val (primary, translation) = cleanCaretTranslation(match.groupValues[4])
                rawEntries.add(Triple(timeMs, primary, translation))
            }
        }

        if (rawEntries.isEmpty()) return emptyList()
        rawEntries.sortBy { it.first }

        val output = mutableListOf<SyncedLyricLine>()

        // 1. Instrumental Intro check
        val firstVocalTime = rawEntries.first().first
        if (firstVocalTime >= INSTRUMENTAL_GAP_THRESHOLD_MS) {
            output.add(
                SyncedLyricLine(
                    timestampMs = 0L,
                    endTimestampMs = firstVocalTime,
                    primaryText = "",
                    isInstrumentalGap = true
                )
            )
        }

        // 2. Vocal lines & mid-song instrumental breaks
        for (i in rawEntries.indices) {
            val (startMs, primary, translation) = rawEntries[i]
            val nextStartMs = if (i + 1 < rawEntries.size) {
                rawEntries[i + 1].first
            } else {
                maxOf(totalTrackDurationMs, startMs + 5000L)
            }

            if (primary.isBlank()) {
                // Explicit empty timestamp interval -> Instrumental break
                output.add(
                    SyncedLyricLine(
                        timestampMs = startMs,
                        endTimestampMs = nextStartMs,
                        primaryText = "",
                        isInstrumentalGap = true
                    )
                )
            } else {
                val vocalDuration = nextStartMs - startMs
                // If pause between this line and next is > 8 seconds, show instrumental indicator after 4.5s
                if (vocalDuration > 8000L) {
                    val vocalEnd = startMs + 4500L
                    output.add(
                        SyncedLyricLine(
                            timestampMs = startMs,
                            endTimestampMs = vocalEnd,
                            primaryText = primary,
                            translationText = translation,
                            isInstrumentalGap = false
                        )
                    )
                    output.add(
                        SyncedLyricLine(
                            timestampMs = vocalEnd,
                            endTimestampMs = nextStartMs,
                            primaryText = "",
                            isInstrumentalGap = true
                        )
                    )
                } else {
                    output.add(
                        SyncedLyricLine(
                            timestampMs = startMs,
                            endTimestampMs = nextStartMs,
                            primaryText = primary,
                            translationText = translation,
                            isInstrumentalGap = false
                        )
                    )
                }
            }
        }

        return output
    }

    /**
     * Splits `{hindi lyrics}^{english translation}` on `^` and strips all `{` and `}` characters.
     */
    fun cleanCaretTranslation(rawText: String): Pair<String, String?> {
        val sanitized = rawText.replace("{", "").replace("}", "").trim()
        if (!sanitized.contains("^")) {
            return sanitized to null
        }
        val parts = sanitized.split("^")
        val primary = parts[0].trim()
        val secondary = parts.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(primary, ignoreCase = true) }
        return primary to secondary
    }
}
