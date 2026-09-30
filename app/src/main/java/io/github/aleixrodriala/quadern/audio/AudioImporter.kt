package io.github.aleixrodriala.quadern.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusException
import io.github.jaredmdobson.concentus.OpusPacketInfo
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Turns any audio file Android can read (m4a, mp3, ogg/opus voice messages, wav...) into the app's
 * own format, so imported audio goes through exactly the same chunking and transcription path as a
 * recording.
 *
 * Every MediaCodec buffer costs about a millisecond of overhead however little it holds, which caps
 * a one-frame-per-buffer pipeline at ~20x real time. So:
 *  - AAC-LC 48 kHz mono (what the Pixel Recorder and Quadern write) is already the app's format: its
 *    frames are copied as they are and only decoded to measure loudness, many frames per buffer.
 *  - Anything else is decoded (many frames per buffer where the decoder allows) and re-encoded.
 *  - Long files are split into segments converted in parallel, each by its own codecs, and joined
 *    on frame boundaries.
 */
object AudioImporter {
    private const val TAG = "AudioImporter"

    /**
     * Frames packed into one decoder buffer. Codec2's AAC decoder only hands a buffer back once its
     * whole output fits its 128-frame delay ring (past that it silently drops input), hence 32 for
     * mono and 16 for anything that might be stereo.
     */
    private const val FRAMES_PER_BUFFER = 32
    private const val DECODER_INPUT_SIZE = 1 shl 16

    /** The longest opus packet, 120 ms, in samples per channel. */
    private const val MAX_OPUS_FRAME = 5_760

    /** A codec that produces nothing for this long has stalled: give up rather than hang the import. */
    private const val STALL_MS = 10_000L

    /** Segments start on multiples of this, which is whole AAC frames (1024) and whole levels (4800) alike: 1.6 s. */
    private const val GRID = 76_800L

    /**
     * One pipeline only runs ~10x real time on opus (it mostly waits on the codec process), so even
     * a one-minute voice message is worth splitting; each segment costs two codec start-ups.
     */
    private const val MIN_SEGMENT = 5L * AudioSpec.SAMPLE_RATE
    private val MAX_WORKERS = minOf(8, Runtime.getRuntime().availableProcessors())

    /** Audio decoded before a segment and thrown away, so a decoder started mid-stream has settled. */
    private const val DECODE_PREROLL = 12_000L

    /**
     * The encoder whose priming delay is known, and that delay: 2048 samples, two whole frames,
     * measured on a Pixel 9. Segments are only encoded in parallel with it, so their frames line up.
     */
    private const val SOFTWARE_AAC_ENCODER = "c2.android.aac.encoder"
    private const val ENCODER_DELAY = 2_048L

    /** Audio encoded before a segment's first kept frame (whole frames), so the encoder has settled. */
    private const val ENCODE_PREROLL = 4_096L

    /** Audio encoded past a segment's end, so its last kept frame is complete. */
    private const val ENCODE_POSTROLL = ENCODER_DELAY + 2_048L

    /**
     * Formats whose decoders line up with the timestamps when started mid-stream (opus drops the
     * same pre-skip at every fresh start, so all segments shift alike); others stay in one piece.
     */
    private val SPLITTABLE = setOf(
        MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_MPEG, MediaFormat.MIMETYPE_AUDIO_OPUS,
        MediaFormat.MIMETYPE_AUDIO_RAW, MediaFormat.MIMETYPE_AUDIO_FLAC,
    )

    /** Containers whose seeks land on exact timestamps (MP4, ADTS, WAV, FLAC); MP3 and Ogg only estimate. */
    private val EXACT_SEEK = setOf(MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_RAW, MediaFormat.MIMETYPE_AUDIO_FLAC)

    /** Returns the number of AAC frames written. [onProgress] gets 0..1 as the file is converted. */
    fun import(source: File, adts: File, levels: File, onProgress: (Float) -> Unit = {}): Long {
        val started = SystemClock.elapsedRealtime()
        val probe = MediaExtractor()
        val format: MediaFormat
        val track: Int
        val firstUs: Long
        try {
            probe.setDataSource(source.absolutePath)
            track = (0 until probe.trackCount).firstOrNull {
                probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("That file has no audio")
            probe.selectTrack(track)
            format = probe.getTrackFormat(track)
            firstUs = probe.sampleTime.coerceAtLeast(0)
        } finally {
            probe.release()
        }
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val total = durationUs * AudioSpec.SAMPLE_RATE / 1_000_000
        val copy = isOwnFormat(format)
        val splittable = copy || (mime in SPLITTABLE && hasCodec(SOFTWARE_AAC_ENCODER))
        val segments = plan(if (splittable) total else 0)
        // Progress never goes backwards, even when a failed parallel run starts over in one piece.
        var shown = 0f
        val report: (Float) -> Unit = { p -> if (p > shown) { shown = p; onProgress(p) } }
        val parts = try {
            convert(source, track, format, firstUs, copy, segments, total, adts, report)
        } catch (e: Exception) {
            if (segments.size == 1 || e is InterruptedException) throw e
            // Some phones can't run this many codecs at once: slower, but it still imports.
            Log.w(TAG, "Parallel import failed, retrying in one piece", e)
            convert(source, track, format, firstUs, copy, listOf(Segment(0, 0, Long.MAX_VALUE)), total, adts, report)
        }
        if (parts.size > 1) {
            FileOutputStream(adts).channel.use { out ->
                for (p in parts) {
                    FileInputStream(p.file).channel.use { var pos = 0L; while (pos < it.size()) pos += it.transferTo(pos, it.size() - pos, out) }
                    p.file.delete()
                }
            }
        }
        FileOutputStream(levels).use { out -> parts.forEach { out.write(it.levels) } }
        val frames = parts.sumOf { it.frames }

        val tookMs = SystemClock.elapsedRealtime() - started
        val audioMs = AudioSpec.frameToMs(frames)
        Log.i(TAG, "${if (copy) "Copied" else "Converted"} ${audioMs / 1000}s of $mime in ${tookMs}ms (${audioMs / tookMs.coerceAtLeast(1)}x, ${parts.size} segments)")
        return frames
    }

    /** Converts every segment, in parallel when there are several; their parts are in timeline order. */
    private fun convert(
        source: File, track: Int, format: MediaFormat, firstUs: Long, copy: Boolean,
        segments: List<Segment>, total: Long, adts: File, onProgress: (Float) -> Unit,
    ): List<Part> {
        val job = Job(source, track, format, firstUs, copy, segments, Progress(total, segments.size, onProgress))
        if (segments.size == 1) return listOf(job.run(segments[0], adts))
        val pool = Executors.newFixedThreadPool(segments.size)
        try {
            val futures = segments.map { s -> pool.submit(Callable { job.run(s, File(adts.path + ".part${s.index}")) }) }
            try {
                return futures.map { it.get() }
            } catch (e: Exception) {
                // Stop the others and wait for them to let go of their codecs before cleaning up.
                job.cancelled.set(true)
                futures.forEach { runCatching { it.get() } }
                segments.forEach { File(adts.path + ".part${it.index}").delete() }
                throw (e as? ExecutionException)?.cause ?: e
            }
        } finally {
            pool.shutdown()
        }
    }

    /** AAC-LC, 48 kHz, mono: frames can go into the note as they are. */
    private fun isOwnFormat(format: MediaFormat): Boolean {
        if (format.getString(MediaFormat.KEY_MIME) != AudioSpec.MIME) return false
        val csd = format.getByteBuffer("csd-0") ?: return false
        val config = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
        return config.contentEquals(AudioSpec.audioSpecificConfig())
    }

    private fun hasCodec(name: String) =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { it.name == name }

    /** A stretch of the output timeline, in 48 kHz samples; the last one runs to the end of the file. */
    private class Segment(val index: Int, val start: Long, val end: Long) {
        val last get() = end == Long.MAX_VALUE
    }

    private fun plan(total: Long): List<Segment> {
        val n = minOf(MAX_WORKERS.toLong(), total / MIN_SEGMENT).toInt()
        if (n <= 1) return listOf(Segment(0, 0, Long.MAX_VALUE))
        val bounds = (1 until n).map { k -> (total * k / n + GRID / 2) / GRID * GRID }.distinct()
        val starts = listOf(0L) + bounds
        return starts.mapIndexed { i, s -> Segment(i, s, starts.getOrElse(i + 1) { Long.MAX_VALUE }) }
    }

    private class Part(val file: File, val frames: Long, val levels: ByteArray)

    private class Job(
        val source: File,
        val track: Int,
        val format: MediaFormat,
        val firstUs: Long,
        val copy: Boolean,
        val segments: List<Segment>,
        val progress: Progress,
    ) {
        val cancelled = AtomicBoolean(false)

        fun run(segment: Segment, out: File): Part {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(source.absolutePath)
                extractor.selectTrack(track)
                return if (copy) copySegment(extractor, segment, out) else transcodeSegment(extractor, segment, out)
            } finally {
                extractor.release()
            }
        }

        /** Output-timeline sample of a media timestamp. */
        private fun sampleAt(us: Long) = ((us - firstUs) * AudioSpec.SAMPLE_RATE + 500_000) / 1_000_000
        private fun usAt(sample: Long) = firstUs + sample * 1_000_000 / AudioSpec.SAMPLE_RATE

        /**
         * Positions [extractor] at or just before [sample]. MP3 and Ogg timestamps after a seek are
         * estimates (seconds off on a long file), which would misplace the segment, so there it skips
         * forward from the start instead: packets only, nothing decoded, and the timestamps stay exact.
         */
        private fun seekBefore(extractor: MediaExtractor, sample: Long) {
            if (sample <= 0) return
            if (format.getString(MediaFormat.KEY_MIME) !in EXACT_SEEK) {
                while (!cancelled.get()) {
                    val us = extractor.sampleTime
                    if (us < 0 || sampleAt(us) >= sample) break
                    extractor.advance()
                }
            } else {
                extractor.seekTo(usAt(sample), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
        }

        /** Copies the segment's frames into [out], decoding them on the side only to measure loudness. */
        private fun copySegment(extractor: MediaExtractor, segment: Segment, out: File): Part {
            val firstFrame = segment.start / AudioSpec.SAMPLES_PER_FRAME
            val endFrame = if (segment.last) Long.MAX_VALUE else segment.end / AudioSpec.SAMPLES_PER_FRAME
            seekBefore(extractor, segment.start - DECODE_PREROLL)
            val decoder = MediaCodec.createDecoderByType(AudioSpec.MIME)
            val file = BufferedOutputStream(FileOutputStream(out), 1 shl 16)
            val levels = LevelWindow(segment)
            val pcm = PcmReader()
            val header = ByteArray(7)
            val sample = ByteBuffer.allocate(1 shl 14)
            var pending = 0 // a frame read but not yet sent: it didn't fit in the last buffer
            var pendingUs = 0L
            var frames = 0L
            try {
                decoder.configure(
                    MediaFormat.createAudioFormat(AudioSpec.MIME, AudioSpec.SAMPLE_RATE, AudioSpec.CHANNELS).apply {
                        setInteger(MediaFormat.KEY_IS_ADTS, 1)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, DECODER_INPUT_SIZE)
                        // Codec2's decoder strips the ADTS headers and still wants the config.
                        setByteBuffer("csd-0", ByteBuffer.wrap(AudioSpec.audioSpecificConfig()))
                    },
                    null, null, 0,
                )
                decoder.start()
                pump(decoder, feed = { input ->
                    input.clear()
                    var firstUs = -1L
                    var packed = 0
                    while (packed < FRAMES_PER_BUFFER && !cancelled.get()) {
                        if (pending == 0) {
                            val n = extractor.readSampleData(sample, 0)
                            if (n < 0) break
                            val frame = (sampleAt(extractor.sampleTime) + AudioSpec.SAMPLES_PER_FRAME / 2) / AudioSpec.SAMPLES_PER_FRAME
                            if (frame >= endFrame) break
                            if (frame >= firstFrame) {
                                file.write(AudioSpec.adtsHeader(n, header))
                                file.write(sample.array(), 0, n)
                                frames++
                            }
                            pendingUs = extractor.sampleTime
                            progress.at(segment.index, sampleAt(pendingUs) - segment.start)
                            extractor.advance()
                            pending = n
                        }
                        if (input.remaining() < pending + header.size) break
                        input.put(AudioSpec.adtsHeader(pending, header))
                        input.put(sample.array(), 0, pending)
                        if (firstUs < 0) firstUs = pendingUs
                        pending = 0
                        packed++
                    }
                    (if (packed == 0) -1 else input.position()) to firstUs
                }) { buf, info, outFormat ->
                    levels.add(sampleAt(info.presentationTimeUs), pcm.read(buf, info, outFormat))
                    !cancelled.get()
                }
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
                file.close()
            }
            return Part(out, frames, levels.bytes())
        }

        /** Decodes the segment to 48 kHz mono and re-encodes it, keeping exactly its own frames. */
        private fun transcodeSegment(extractor: MediaExtractor, segment: Segment, out: File): Part {
            val split = segments.size > 1
            val encodeFrom = (segment.start - ENCODE_PREROLL).coerceAtLeast(0)
            val encodeTo = if (segment.last) Long.MAX_VALUE else segment.end + ENCODE_POSTROLL
            seekBefore(extractor, encodeFrom - DECODE_PREROLL)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // Many frames per buffer where the decoder takes them: mp3 as is, AAC wrapped in ADTS
            // headers that describe the file's own config (when ADTS can: plain AAC, up to stereo).
            val adtsConfig = if (mime == MediaFormat.MIMETYPE_AUDIO_AAC && channels <= 2) adtsConfig(format) else null
            val adtsWrap = adtsConfig != null
            val pack = when {
                mime == MediaFormat.MIMETYPE_AUDIO_MPEG -> FRAMES_PER_BUFFER
                adtsWrap -> FRAMES_PER_BUFFER / 2
                else -> 1
            }
            val decoderFormat = MediaFormat(format).apply {
                if (pack > 1) setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, DECODER_INPUT_SIZE)
                if (adtsWrap) setInteger(MediaFormat.KEY_IS_ADTS, 1)
                // The timestamps place the audio (encoder priming included), not the decoder trimming it.
                removeKey(MediaFormat.KEY_ENCODER_DELAY)
                removeKey(MediaFormat.KEY_ENCODER_PADDING)
            }
            // Opus (WhatsApp, Telegram) is decoded right here: Android's decoder takes one 20 ms packet
            // per call, and those calls, not the decoding, were most of the import.
            val inApp = mime == MediaFormat.MIMETYPE_AUDIO_OPUS && channels <= 2 && opusMappingFamily(format) == 0
            // The opus pre-skip: Android's decoder drops it at whatever packet it starts on, but only the
            // stream's first packet is timestamped as if it had; that one's audio starts that much early.
            val startDelay = if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS) opusCodecDelay(format) else 0L
            val decoder = if (inApp) null else MediaCodec.createDecoderByType(mime)
            val sink = try {
                AacAdtsSink(
                    out, levelsFile = null, buffered = true,
                    encoderName = if (split) SOFTWARE_AAC_ENCODER else null,
                    // Frame j of this encoder covers timeline encodeFrom + j*1024 - ENCODER_DELAY: skip the
                    // pre-roll so the first kept one lines up with where the previous segment stops.
                    skipFrames = (segment.start - encodeFrom) / AudioSpec.SAMPLES_PER_FRAME,
                    maxFrames = if (segment.last) Long.MAX_VALUE else (segment.end - segment.start) / AudioSpec.SAMPLES_PER_FRAME,
                )
            } catch (t: Throwable) {
                decoder?.release()
                throw t
            }
            val levels = LevelWindow(segment)
            val pcm = PcmReader()
            val resampler = Resampler()
            val header = ByteArray(7)
            val sample = ByteBuffer.allocate(1 shl 18)
            var pending = 0
            var pendingUs = 0L
            // The first packet given to the decoder; its audio is where the decoded stream starts.
            var fedUs = Long.MIN_VALUE
            // Where the next decoded sample sits on the timeline, from the first decoded buffer on.
            var cursor = -1L
            var anchored = false
            // Hand the encoder big blocks: far fewer round trips than one per decoded buffer.
            val batch = ShortArray(16 * 1024)
            var batched = 0
            fun encode(pcm: ShortArray, from: Int, to: Int) {
                var i = from
                while (i < to) {
                    val n = minOf(to - i, batch.size - batched)
                    System.arraycopy(pcm, i, batch, batched, n)
                    batched += n
                    i += n
                    if (batched == batch.size) {
                        sink.write(batch, batched)
                        batched = 0
                    }
                }
            }
            /** Takes 48 kHz mono starting at timeline sample [at] (only the first call's is used); false when done. */
            fun decoded(mono: ShortArray, at: Long): Boolean {
                if (!anchored) {
                    anchored = true
                    cursor = at
                    // Started late (shouldn't happen for audio): keep the timeline with silence.
                    if (cursor > encodeFrom) {
                        val gap = ShortArray((cursor - encodeFrom).toInt())
                        encode(gap, 0, gap.size)
                        levels.add(encodeFrom, gap)
                    }
                }
                // Only this segment's stretch (plus the encoder's pre- and post-roll) is encoded.
                val from = (encodeFrom - cursor).coerceIn(0, mono.size.toLong()).toInt()
                val to = if (encodeTo == Long.MAX_VALUE) mono.size else (encodeTo - cursor).coerceIn(0, mono.size.toLong()).toInt()
                if (to > from) encode(mono, from, to)
                levels.add(cursor, mono)
                cursor += mono.size
                progress.at(segment.index, cursor - segment.start)
                return !cancelled.get() && cursor < encodeTo
            }
            try {
                if (decoder == null) {
                    decodeOpus(extractor, channels, encodeTo) { mono, us ->
                        decoded(mono, sampleAt(us) - if (us == firstUs) startDelay else 0)
                    }
                } else {
                    decoder.configure(decoderFormat, null, null, 0)
                    decoder.start()
                    pump(decoder, feed = { input ->
                        input.clear()
                        var firstUs = -1L
                        var packed = 0
                        while (packed < pack && !cancelled.get()) {
                            if (pending == 0) {
                                val n = extractor.readSampleData(sample, 0)
                                if (n < 0) break
                                pendingUs = extractor.sampleTime
                                if (sampleAt(pendingUs) >= encodeTo) break
                                extractor.advance()
                                pending = n
                            }
                            val need = pending + if (adtsWrap) header.size else 0
                            if (input.remaining() < need) {
                                if (packed == 0) throw IOException("Audio frame too large ($pending bytes)")
                                break
                            }
                            if (adtsConfig != null) input.put(adtsConfig.header(pending, header))
                            input.put(sample.array(), 0, pending)
                            if (firstUs < 0) firstUs = pendingUs
                            if (fedUs == Long.MIN_VALUE) fedUs = pendingUs
                            pending = 0
                            packed++
                        }
                        (if (packed == 0) -1 else input.position()) to firstUs
                    }) { buf, info, outFormat ->
                        val mono = resampler.process(pcm.read(buf, info, outFormat), outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        // Not the output's timestamp: decoders label a stream's priming (negative
                        // timestamps in MP4) as if it started at zero.
                        decoded(mono, sampleAt(fedUs) + if (fedUs > firstUs) startDelay else 0)
                    }
                }
                if (batched > 0) sink.write(batch, batched)
            } finally {
                if (decoder != null) {
                    runCatching { decoder.stop() }
                    decoder.release()
                }
                sink.close()
            }
            // A short segment would shift everything after it.
            if (!segment.last && sink.framesWritten.get() < (segment.end - segment.start) / AudioSpec.SAMPLES_PER_FRAME && !cancelled.get()) {
                throw IOException("Segment ${segment.index} came out short")
            }
            return Part(out, sink.framesWritten.get(), levels.bytes())
        }

        /**
         * Decodes opus packets from where [extractor] stands until [encodeTo], in this thread. [onPcm]
         * gets each packet's audio as 48 kHz mono, pre-skip included, with its timestamp; false stops.
         */
        private inline fun decodeOpus(extractor: MediaExtractor, channels: Int, encodeTo: Long, onPcm: (ShortArray, Long) -> Boolean) {
            val decoder = OpusDecoder(AudioSpec.SAMPLE_RATE, channels)
            // The header's output gain (Q7.8 dB), which Android's decoder applies too.
            format.getByteBuffer("csd-0")?.takeIf { it.remaining() >= 18 }?.let { head ->
                decoder.gain = head.duplicate().order(ByteOrder.LITTLE_ENDIAN).getShort(head.position() + 16).toInt()
            }
            val sample = ByteBuffer.allocate(1 shl 16)
            val out = ShortArray(MAX_OPUS_FRAME * channels)
            var mono = ShortArray(0)
            while (!cancelled.get()) {
                val n = extractor.readSampleData(sample, 0)
                if (n < 0) break
                val us = extractor.sampleTime
                if (sampleAt(us) >= encodeTo) break
                extractor.advance()
                val frames = try {
                    decoder.decode(sample.array(), 0, n, out, 0, MAX_OPUS_FRAME, false)
                } catch (e: OpusException) {
                    // A damaged packet: conceal it like a lost one, so the timeline stays in step.
                    val lost = runCatching { OpusPacketInfo.getNumSamples(sample.array(), 0, n, AudioSpec.SAMPLE_RATE) }.getOrDefault(0)
                    decoder.decode(null, 0, 0, out, 0, lost.takeIf { it in 1..MAX_OPUS_FRAME } ?: 960, false)
                }
                if (mono.size != frames) mono = ShortArray(frames)
                if (channels == 1) {
                    System.arraycopy(out, 0, mono, 0, frames)
                } else {
                    for (i in 0 until frames) mono[i] = ((out[2 * i] + out[2 * i + 1]) / 2).toShort()
                }
                if (!onPcm(mono, us)) break
            }
        }
    }

    /** Opus pre-skip in 48 kHz samples, from the codec delay (csd-1, nanoseconds) MediaExtractor provides. */
    private fun opusCodecDelay(format: MediaFormat): Long {
        val csd = format.getByteBuffer("csd-1") ?: return 0
        if (csd.remaining() < 8) return 0
        val ns = csd.duplicate().order(ByteOrder.nativeOrder()).long
        return ns * AudioSpec.SAMPLE_RATE / 1_000_000_000
    }

    /** The OpusHead channel mapping family: 0 is one mono or stereo stream; others are multistream. */
    private fun opusMappingFamily(format: MediaFormat): Int {
        val head = format.getByteBuffer("csd-0") ?: return -1
        if (head.remaining() < 19) return -1
        return head.get(head.position() + 18).toInt() and 0xFF
    }

    /** What an ADTS header for this AAC track says, or null if ADTS can't describe it. */
    private class AdtsConfig(val objectType: Int, val samplingIndex: Int, val channels: Int) {
        fun header(payloadSize: Int, out: ByteArray) = AudioSpec.adtsHeader(payloadSize, out, objectType, samplingIndex, channels)
    }

    private fun adtsConfig(format: MediaFormat): AdtsConfig? {
        val csd = format.getByteBuffer("csd-0") ?: return null
        if (csd.remaining() < 2) return null
        val b0 = csd.get(csd.position()).toInt() and 0xFF
        val b1 = csd.get(csd.position() + 1).toInt() and 0xFF
        val objectType = b0 shr 3
        val samplingIndex = ((b0 and 7) shl 1) or (b1 shr 7)
        val channels = (b1 shr 3) and 0xF
        // ADTS carries only the four original object types (so explicit HE-AAC signalling is out) and indexed rates.
        if (objectType !in 1..4 || samplingIndex > 12 || channels !in 1..2) return null
        return AdtsConfig(objectType, samplingIndex, channels)
    }

    /** Loudness of just a segment's own stretch of the timeline. */
    private class LevelWindow(private val segment: Segment) {
        private val out = ByteArrayOutputStream()
        private val meter = LevelMeter { out.write(it) }
        private var next = segment.start

        /** [pcm] starts at timeline sample [at]; samples already measured or outside the segment are skipped. */
        fun add(at: Long, pcm: ShortArray) {
            val from = (next - at).coerceAtLeast(0)
            val to = if (segment.last) pcm.size.toLong() else (segment.end - at).coerceIn(0, pcm.size.toLong())
            if (from >= to) return
            if (from == 0L) {
                meter.add(pcm, to.toInt())
            } else {
                meter.add(pcm.copyOfRange(from.toInt(), to.toInt()), (to - from).toInt())
            }
            next = at + to
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    /**
     * Runs [decoder] until the input ends or [onOutput] returns false, never waiting while it could
     * make progress: fills every free input slot, takes every finished output, and only blocks
     * (briefly) when it's busy with both. [feed] fills an input buffer and returns its size (negative
     * at the end) and timestamp.
     */
    private inline fun pump(
        decoder: MediaCodec,
        feed: (ByteBuffer) -> Pair<Int, Long>,
        onOutput: (ByteBuffer, MediaCodec.BufferInfo, MediaFormat) -> Boolean,
    ) {
        val info = MediaCodec.BufferInfo()
        var outFormat = decoder.outputFormat
        var inputDone = false
        var lastOutput = SystemClock.elapsedRealtime()
        while (true) {
            var fed = false
            while (!inputDone) {
                val i = decoder.dequeueInputBuffer(0)
                if (i < 0) break
                val (n, us) = feed(decoder.getInputBuffer(i)!!)
                if (n < 0) {
                    decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    decoder.queueInputBuffer(i, 0, n, us, 0)
                }
                fed = true
            }
            val o = decoder.dequeueOutputBuffer(info, if (fed) 0 else 5_000)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (SystemClock.elapsedRealtime() - lastOutput > STALL_MS) throw IOException("The audio decoder stopped responding")
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outFormat = decoder.outputFormat
            } else if (o >= 0) {
                lastOutput = SystemClock.elapsedRealtime()
                val more = info.size <= 0 || onOutput(decoder.getOutputBuffer(o)!!, info, outFormat)
                decoder.releaseOutputBuffer(o, false)
                if (!more || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            }
        }
    }

    /** Reads a decoder's PCM output (16-bit, or 8-bit, float, 24/32-bit from WAV) as 16-bit mono, reusing its arrays. */
    private class PcmReader {
        private var interleaved = ShortArray(0)
        private var mono = ShortArray(0)

        fun read(buf: ByteBuffer, info: MediaCodec.BufferInfo, format: MediaFormat): ShortArray {
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            buf.order(ByteOrder.nativeOrder())
            val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            val bytes = when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                else -> throw IOException("Unsupported audio sample format ($encoding)")
            }
            val n = info.size / bytes
            if (interleaved.size != n) interleaved = ShortArray(n)
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> buf.asShortBuffer().get(interleaved)
                AudioFormat.ENCODING_PCM_8BIT -> for (i in 0 until n) interleaved[i] = (((buf.get().toInt() and 0xFF) - 128) shl 8).toShort()
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> for (i in 0 until n) {
                    // Little-endian; the top two bytes are the 16-bit sample.
                    buf.get()
                    val lo = buf.get().toInt() and 0xFF
                    interleaved[i] = ((buf.get().toInt() shl 8) or lo).toShort()
                }
                AudioFormat.ENCODING_PCM_32BIT -> for (i in 0 until n) interleaved[i] = (buf.getInt() shr 16).toShort()
                AudioFormat.ENCODING_PCM_FLOAT -> for (i in 0 until n) {
                    interleaved[i] = (buf.getFloat().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
            }
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            if (channels <= 1) return interleaved
            val frames = n / channels
            if (mono.size != frames) mono = ShortArray(frames)
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) sum += interleaved[i * channels + c]
                mono[i] = (sum / channels).toShort()
            }
            return mono
        }
    }

    /** Overall progress of all segments together, reported from any of their threads. */
    private class Progress(private val total: Long, segments: Int, private val onProgress: (Float) -> Unit) {
        private val done = AtomicLongArray(segments)
        private var reported = -1

        fun at(segment: Int, samples: Long) {
            if (total <= 0) return
            done.set(segment, samples.coerceAtLeast(0))
            var sum = 0L
            for (i in 0 until done.length()) sum += done.get(i)
            val percent = (sum * 100 / total).toInt().coerceIn(0, 100)
            synchronized(this) {
                if (percent > reported) {
                    reported = percent
                    onProgress(percent / 100f)
                }
            }
        }
    }

    /** Streaming linear-interpolation resampler to 48 kHz; state carries across buffers. */
    private class Resampler {
        private var pos = 0.0
        private var prev: Short = 0

        fun process(input: ShortArray, rate: Int): ShortArray {
            if (rate == AudioSpec.SAMPLE_RATE) return input
            if (input.isEmpty()) return input
            val step = rate.toDouble() / AudioSpec.SAMPLE_RATE
            val out = ShortArray(((input.size - pos) / step).toInt().coerceAtLeast(0) + 1)
            var k = 0
            // Position is measured from the sample *before* this buffer (prev), so -1 maps to prev.
            while (pos < input.size && k < out.size) {
                val i = pos.toInt()
                val frac = pos - i
                val a = if (i == 0) prev.toDouble() else input[i - 1].toDouble()
                val b = input[i].toDouble()
                out[k++] = (a + (b - a) * frac).toInt().toShort()
                pos += step
            }
            pos -= input.size
            prev = input.last()
            return out.copyOf(k)
        }
    }
}
