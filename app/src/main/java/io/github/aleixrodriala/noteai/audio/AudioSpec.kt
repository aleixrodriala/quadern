package io.github.aleixrodriala.noteai.audio

/**
 * The one audio format the app records: AAC-LC, 48 kHz, mono, 64 kbit/s (~29 MB per hour).
 *
 * While recording, frames are appended to a raw ADTS stream. ADTS has no index and no trailer, so a
 * file cut off at any byte (process killed, battery pulled) is still valid up to its last complete
 * frame. When recording ends it is remuxed, without re-encoding, into an .m4a for playback/sharing.
 */
object AudioSpec {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 1
    const val BIT_RATE = 64_000
    const val SAMPLES_PER_FRAME = 1024
    const val MIME = "audio/mp4a-latm"

    /** ADTS sampling_frequency_index for 48 kHz. */
    const val SAMPLING_INDEX = 3

    /** AAC object type 2 = Low Complexity. */
    const val OBJECT_TYPE_LC = 2

    /** Level meter resolution: one value per 100 ms. */
    const val LEVEL_INTERVAL_MS = 100
    const val SAMPLES_PER_LEVEL = SAMPLE_RATE * LEVEL_INTERVAL_MS / 1000

    fun frameToUs(frame: Long): Long = frame * SAMPLES_PER_FRAME * 1_000_000L / SAMPLE_RATE
    fun frameToMs(frame: Long): Long = frame * SAMPLES_PER_FRAME * 1_000L / SAMPLE_RATE
    fun msToFrame(ms: Long): Long = (ms * SAMPLE_RATE + SAMPLES_PER_FRAME * 500L) / (SAMPLES_PER_FRAME * 1000L)

    /** AudioSpecificConfig (csd-0) for AAC-LC, 48 kHz, mono. */
    fun audioSpecificConfig(): ByteArray {
        val v = (OBJECT_TYPE_LC shl 11) or (SAMPLING_INDEX shl 7) or (CHANNELS shl 3)
        return byteArrayOf((v shr 8).toByte(), v.toByte())
    }

    /** 7-byte ADTS header (no CRC) for a raw AAC frame of [payloadSize] bytes. */
    fun adtsHeader(
        payloadSize: Int,
        out: ByteArray = ByteArray(7),
        objectType: Int = OBJECT_TYPE_LC,
        samplingIndex: Int = SAMPLING_INDEX,
        channels: Int = CHANNELS,
    ): ByteArray {
        val len = payloadSize + 7
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte() // MPEG-4, layer 0, no CRC
        out[2] = (((objectType - 1) shl 6) or (samplingIndex shl 2) or (channels shr 2)).toByte()
        out[3] = (((channels and 3) shl 6) or (len shr 11)).toByte()
        out[4] = ((len shr 3) and 0xFF).toByte()
        out[5] = (((len and 7) shl 5) or 0x1F).toByte()
        out[6] = 0xFC.toByte()
        return out
    }
}
