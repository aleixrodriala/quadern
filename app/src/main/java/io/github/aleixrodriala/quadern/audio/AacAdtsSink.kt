package io.github.aleixrodriala.quadern.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * 48 kHz mono 16-bit PCM in → AAC frames appended to an ADTS file, plus one loudness byte per
 * 100 ms appended to a levels file. Used by the live recorder and by audio import.
 *
 * Not thread-safe: one thread writes. [framesWritten] and [levelsWritten] may be read from anywhere.
 */
class AacAdtsSink(
    adtsFile: File,
    /** Where loudness goes; null to skip measuring it. */
    levelsFile: File?,
    private val startFrames: Long = 0,
    startLevels: Long = 0,
    /**
     * Buffer file writes. Much faster, but a crash loses what's buffered, so only for imports,
     * which start over anyway; the live recorder writes every frame through.
     */
    buffered: Boolean = false,
    /** A specific encoder, e.g. one whose priming delay is known; null for the device's default. */
    encoderName: String? = null,
    /** Encoded frames to leave out at the start, and the most to keep after them (import segments). */
    private val skipFrames: Long = 0,
    private val maxFrames: Long = Long.MAX_VALUE,
    private val onLevel: (Int) -> Unit = {},
) : Closeable {
    val framesWritten = AtomicLong(startFrames)
    val levelsWritten = AtomicLong(startLevels)

    private val encoder: MediaCodec
    private val fileOut: FileOutputStream
    private val levelsOut: FileOutputStream?

    init {
        // Nothing stays open if any of these fails (e.g. no codec left while importing in parallel).
        val codec = startEncoder(encoderName)
        var file: FileOutputStream? = null
        var levels: FileOutputStream? = null
        try {
            file = FileOutputStream(adtsFile, true)
            levels = levelsFile?.let { FileOutputStream(it, true) }
        } catch (t: Throwable) {
            runCatching { file?.close() }
            codec.release()
            throw t
        }
        encoder = codec
        fileOut = file
        levelsOut = levels
    }

    private val out: OutputStream = if (buffered) BufferedOutputStream(fileOut, 1 shl 16) else fileOut
    private val info = MediaCodec.BufferInfo()
    private val header = ByteArray(7)
    private var frameBuf = ByteArray(2048)
    private var bytes = ByteBuffer.allocate(8192).order(ByteOrder.nativeOrder())
    private var samplesFed = startFrames * AudioSpec.SAMPLES_PER_FRAME
    private var encoded = 0L
    private val pendingLevels = ByteArrayOutputStream()
    private val meter = LevelMeter { level ->
        pendingLevels.write(level)
        levelsWritten.incrementAndGet()
        onLevel(level)
    }
    private var lastSync = SystemClock.elapsedRealtime()
    private var closed = false

    fun write(pcm: ShortArray, count: Int) {
        if (levelsOut != null) meter.add(pcm, count)
        if (bytes.capacity() < count * 2) bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.nativeOrder())
        bytes.clear()
        bytes.asShortBuffer().put(pcm, 0, count)
        bytes.limit(count * 2)
        feed(bytes, eos = false)
        drain(untilEos = false)
    }

    /** fsyncs audio and levels if a few seconds passed, so at most that much is lost on power loss. */
    fun syncIfDue(everyMs: Long = 3_000) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSync >= everyMs) sync()
    }

    fun sync() {
        if (levelsOut != null && pendingLevels.size() > 0) {
            pendingLevels.writeTo(levelsOut)
            pendingLevels.reset()
        }
        out.flush()
        fileOut.fd.sync()
        levelsOut?.fd?.sync()
        lastSync = SystemClock.elapsedRealtime()
    }

    /** Flushes the encoder (so the last fraction of a second isn't lost) and closes the files. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            bytes.clear()
            bytes.limit(0)
            feed(bytes, eos = true)
            drain(untilEos = true)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { sync() }
            out.close()
            levelsOut?.close()
        }
    }

    private fun feed(data: ByteBuffer, eos: Boolean) {
        var waitingSince = 0L
        while (true) {
            var idx = encoder.dequeueInputBuffer(0)
            if (idx < 0) {
                // Input queue full: make room by collecting finished frames, then wait briefly.
                drain(untilEos = false)
                idx = encoder.dequeueInputBuffer(5_000)
            }
            if (idx < 0) {
                // An encoder that takes nothing for this long has stalled: fail instead of hanging.
                val now = SystemClock.elapsedRealtime()
                if (waitingSince == 0L) waitingSince = now
                if (now - waitingSince > 10_000) throw IOException("The audio encoder stopped responding")
                continue
            }
            waitingSince = 0L
            if (idx >= 0) {
                val input = encoder.getInputBuffer(idx)!!
                input.clear()
                val n = minOf(input.remaining(), data.remaining())
                val slice = data.duplicate().apply { limit(position() + n) }
                input.put(slice)
                data.position(data.position() + n)
                val ptsUs = samplesFed * 1_000_000L / AudioSpec.SAMPLE_RATE
                samplesFed += n / 2
                val done = !data.hasRemaining()
                encoder.queueInputBuffer(idx, 0, n, ptsUs, if (eos && done) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                if (done) return
            }
        }
    }

    private fun drain(untilEos: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (true) {
            val idx = encoder.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // Never hang on a codec that doesn't signal end-of-stream.
                if (untilEos && SystemClock.elapsedRealtime() < deadline) continue else return
            }
            if (idx < 0) continue // output format / buffers changed
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!isConfig && info.size > 0 && encoded++ >= skipFrames && framesWritten.get() - startFrames < maxFrames) {
                val buf = encoder.getOutputBuffer(idx)!!
                buf.position(info.offset)
                if (info.size > frameBuf.size) frameBuf = ByteArray(info.size)
                buf.get(frameBuf, 0, info.size)
                out.write(AudioSpec.adtsHeader(info.size, header))
                out.write(frameBuf, 0, info.size)
                framesWritten.incrementAndGet()
            }
            encoder.releaseOutputBuffer(idx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }
}

/** Loudness of 48 kHz mono PCM: one level (0..255) per 100 ms, handed to [onLevel] as each completes. */
class LevelMeter(private val onLevel: (Int) -> Unit) {
    private var sum = 0.0
    private var count = 0

    fun add(pcm: ShortArray, n: Int) {
        for (i in 0 until n) {
            val s = pcm[i].toDouble()
            sum += s * s
            if (++count == AudioSpec.SAMPLES_PER_LEVEL) {
                onLevel(ChunkPlanner.levelOf(sum, count))
                sum = 0.0
                count = 0
            }
        }
    }
}

private fun startEncoder(name: String?): MediaCodec {
    val codec = name?.let { MediaCodec.createByCodecName(it) } ?: MediaCodec.createEncoderByType(AudioSpec.MIME)
    try {
        val format = MediaFormat.createAudioFormat(AudioSpec.MIME, AudioSpec.SAMPLE_RATE, AudioSpec.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AudioSpec.BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    } catch (t: Throwable) {
        codec.release()
        throw t
    }
    return codec
}
