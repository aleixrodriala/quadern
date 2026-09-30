package io.github.aleixrodriala.noteai.transcription.local

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.aleixrodriala.noteai.transcription.ProviderId
import io.github.aleixrodriala.noteai.transcription.SttException
import io.github.aleixrodriala.noteai.transcription.SttProvider
import io.github.aleixrodriala.noteai.whisper.WhisperContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** whisper.cpp on the phone's CPU. One model stays loaded; one transcription runs at a time. */
class LocalWhisperProvider private constructor(private val modelFile: File) : SttProvider {
    override val id = ProviderId.LOCAL

    private val mutex = Mutex()
    private var context: WhisperContext? = null

    override suspend fun transcribe(audio: File, language: String?): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            val samples = try {
                PcmDecoder.decode16k(audio)
            } catch (e: Exception) {
                throw SttException.Permanent("Couldn't decode audio: ${e.message}")
            }
            if (samples.isEmpty()) return@withContext ""
            val ctx = context ?: try {
                WhisperContext.load(modelFile.absolutePath).also { context = it }
            } catch (e: Throwable) {
                throw SttException.NotConfigured("The Whisper model couldn't be loaded. Delete and download it again.")
            }
            val handle = coroutineContext.job.invokeOnCompletion { if (it != null) ctx.cancel() }
            try {
                ctx.transcribe(samples, language)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw SttException.Permanent("On-device transcription failed: ${e.message}")
            } finally {
                handle.dispose()
            }
        }
    }

    companion object {
        private var instance: LocalWhisperProvider? = null

        @Synchronized
        fun get(modelFile: File): LocalWhisperProvider {
            instance?.let { if (it.modelFile == modelFile) return it }
            instance?.context?.close()
            return LocalWhisperProvider(modelFile).also { instance = it }
        }
    }
}

/** Decodes an .m4a chunk to the 16 kHz mono float PCM whisper.cpp wants. */
object PcmDecoder {
    fun decode16k(file: File): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).first {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = ShortArrayBuilder()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    codec.outputFormat.let {
                        rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                } else if (o >= 0) {
                    val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    val shorts = out.asShortBuffer()
                    val frame = ShortArray(shorts.remaining())
                    shorts.get(frame)
                    pcm.addMono(frame, channels)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        return resampleTo16k(pcm.toArray(), rate)
    }

    private class ShortArrayBuilder {
        private var data = ShortArray(1 shl 20)
        private var size = 0
        fun addMono(frame: ShortArray, channels: Int) {
            val n = frame.size / channels
            if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
            if (channels == 1) {
                frame.copyInto(data, size)
            } else {
                for (i in 0 until n) {
                    var sum = 0
                    for (c in 0 until channels) sum += frame[i * channels + c]
                    data[size + i] = (sum / channels).toShort()
                }
            }
            size += n
        }
        fun toArray() = data.copyOf(size)
    }

    /** Windowed-sinc low-pass then decimation; exact 3:1 for our 48 kHz recordings. */
    fun resampleTo16k(input: ShortArray, rate: Int): FloatArray {
        if (rate == 16_000) return FloatArray(input.size) { input[it] / 32768f }
        val ratio = rate / 16_000.0
        val cutoff = 0.9 * 8_000 / rate // normalized to the input rate, just under the new Nyquist
        val half = 16
        val taps = FloatArray(2 * half + 1) { k ->
            val n = k - half
            val sinc = if (n == 0) 2 * cutoff else sin(2 * PI * cutoff * n) / (PI * n)
            val window = 0.54 - 0.46 * cos(2 * PI * k / (2 * half))
            (sinc * window).toFloat()
        }
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (j in 0 until outLen) {
            val center = (j * ratio).toInt()
            var acc = 0f
            for (k in -half..half) {
                val idx = center + k
                if (idx in input.indices) acc += input[idx] * taps[k + half]
            }
            out[j] = (acc / 32768f).coerceIn(-1f, 1f)
        }
        return out
    }
}
