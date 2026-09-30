package io.github.aleixrodriala.quadern.audio

/**
 * Decides where to split a recording into transcription chunks, using the 100 ms level meter.
 *
 * Chunks aim for 4–6 minutes and are cut at the quietest half-second in that window, so words are
 * not cut in half. ChatGPT's endpoint silently drops audio past ~10.7 minutes, so a chunk is never
 * allowed to exceed [HARD_MAX_MS].
 */
object ChunkPlanner {
    const val MIN_MS = 4 * 60_000L
    const val MAX_MS = 6 * 60_000L
    const val HARD_MAX_MS = 8 * 60_000L

    /** How much audio past [MAX_MS] must exist before cutting, so the quietest point is really known. */
    const val LOOKAHEAD_MS = 30_000L

    /** Level byte (0..255 maps to -60..0 dBFS) below which a chunk counts as silence. */
    const val SILENCE_LEVEL = 40 // ≈ -50 dBFS

    private const val SMOOTH = 5 // buckets (500 ms)
    private const val MAX_MISSING_LEVELS = 10 // 1 s

    /**
     * Returns the end (ms) of the chunk starting at [startMs], or null when more audio is needed.
     * [final] means the recording is over, so the remainder always becomes the last chunk(s).
     */
    fun nextCut(levels: ByteArray, levelCount: Int, startMs: Long, availableMs: Long, final: Boolean): Long? {
        val remaining = availableMs - startMs
        if (remaining <= 0) return null
        if (final && remaining <= HARD_MAX_MS) return availableMs
        if (!final && remaining < MAX_MS + LOOKAHEAD_MS) return null

        val from = ((startMs + MIN_MS) / AudioSpec.LEVEL_INTERVAL_MS).toInt()
        val to = minOf(((startMs + MAX_MS) / AudioSpec.LEVEL_INTERVAL_MS).toInt(), levelCount - SMOOTH)
        if (to <= from) return startMs + MAX_MS // no level data (shouldn't happen): cut blind

        var window = 0
        for (i in from until from + SMOOTH) window += levels[i].toInt() and 0xFF
        var best = window
        var bestAt = from
        for (i in from + 1..to) {
            window += (levels[i + SMOOTH - 1].toInt() and 0xFF) - (levels[i - 1].toInt() and 0xFF)
            // "<=" prefers the later of equally quiet spots, giving fuller chunks.
            if (window <= best) {
                best = window
                bestAt = i
            }
        }
        val cutBucket = bestAt + SMOOTH / 2
        return cutBucket.toLong() * AudioSpec.LEVEL_INTERVAL_MS
    }

    /** True if every level in [startMs, endMs) is below [SILENCE_LEVEL]: nothing worth uploading. */
    fun isSilent(levels: ByteArray, levelCount: Int, startMs: Long, endMs: Long): Boolean {
        val from = (startMs / AudioSpec.LEVEL_INTERVAL_MS).toInt()
        val to = (endMs / AudioSpec.LEVEL_INTERVAL_MS).toInt()
        // Levels normally trail the audio by a bucket or two (partial last bucket, encoder priming).
        // After a crash they can lag by seconds; if more than that is missing, upload to be safe.
        if (from < 0 || to <= from || to > levelCount + MAX_MISSING_LEVELS) return false
        for (i in from until minOf(to, levelCount)) if ((levels[i].toInt() and 0xFF) >= SILENCE_LEVEL) return false
        return true
    }

    /** Level byte for a block of 16-bit samples: RMS in dBFS, -60..0 mapped to 0..255. */
    fun levelOf(sumSquares: Double, count: Int): Int {
        if (count == 0) return 0
        val rms = kotlin.math.sqrt(sumSquares / count) / 32768.0
        val db = if (rms <= 1e-6) -120.0 else 20 * kotlin.math.log10(rms)
        return (((db + 60.0) / 60.0).coerceIn(0.0, 1.0) * 255).toInt()
    }
}
