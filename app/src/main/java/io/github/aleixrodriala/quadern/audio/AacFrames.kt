package io.github.aleixrodriala.quadern.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Raw AAC frames (without ADTS headers) addressed by frame index. */
interface AacFrameSource : Closeable {
    val frameCount: Long

    /** Calls [block] for every frame in [startFrame, endFrame) in order. The buffer is reused. */
    fun forEachFrame(startFrame: Long, endFrame: Long, block: (buf: ByteArray, size: Int, frame: Long) -> Unit)
}

/**
 * Index of an ADTS file: where each complete frame starts and how long it is. Scanning stops at the
 * first incomplete or corrupt frame, which is exactly what a recording cut off mid-write ends with.
 */
class AdtsIndex private constructor(
    private val offsets: LongArray,
    private val lengths: IntArray,
    val frameCount: Long,
    /** Bytes up to the end of the last complete frame. */
    val validBytes: Long,
) {
    fun offset(frame: Long) = offsets[frame.toInt()]
    fun length(frame: Long) = lengths[frame.toInt()]

    companion object {
        fun scan(file: File): AdtsIndex {
            var offsets = LongArray(4096)
            var lengths = IntArray(4096)
            var count = 0
            var pos = 0L
            val header = ByteArray(7)
            val total = file.length()
            BufferedInputStream(FileInputStream(file), 1 shl 16).use { input ->
                while (pos + 7 <= total) {
                    if (input.readNBytes(header, 0, 7) < 7) break
                    val b0 = header[0].toInt() and 0xFF
                    val b1 = header[1].toInt() and 0xFF
                    if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) break
                    val len = ((header[3].toInt() and 0x03) shl 11) or
                        ((header[4].toInt() and 0xFF) shl 3) or
                        ((header[5].toInt() and 0xE0) ushr 5)
                    val headerLen = if (b1 and 0x01 == 0) 9 else 7 // CRC present when protection_absent == 0
                    if (len < headerLen || pos + len > total) break
                    if (count == offsets.size) {
                        offsets = offsets.copyOf(count * 2)
                        lengths = lengths.copyOf(count * 2)
                    }
                    offsets[count] = pos + headerLen
                    lengths[count] = len - headerLen
                    count++
                    val skip = (len - 7).toLong()
                    if (input.skipNBytes2(skip) < skip) {
                        count--
                        break
                    }
                    pos += len
                }
            }
            return AdtsIndex(offsets, lengths, count.toLong(), pos)
        }

        private fun BufferedInputStream.skipNBytes2(n: Long): Long {
            var left = n
            while (left > 0) {
                val s = skip(left)
                if (s <= 0) {
                    if (read() == -1) break
                    left--
                } else left -= s
            }
            return n - left
        }
    }
}

class AdtsFrameSource(file: File) : AacFrameSource {
    private val index = AdtsIndex.scan(file)
    private val raf = RandomAccessFile(file, "r")
    override val frameCount: Long get() = index.frameCount

    override fun forEachFrame(startFrame: Long, endFrame: Long, block: (ByteArray, Int, Long) -> Unit) {
        val end = minOf(endFrame, index.frameCount)
        var buf = ByteArray(2048)
        for (f in startFrame until end) {
            val len = index.length(f)
            if (len > buf.size) buf = ByteArray(len)
            raf.seek(index.offset(f))
            raf.readFully(buf, 0, len)
            block(buf, len, f)
        }
    }

    override fun close() = raf.close()
}

class Mp4FrameSource(file: File) : AacFrameSource {
    private val extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
    private val format: MediaFormat

    init {
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == AudioSpec.MIME
        } ?: throw IOException("No AAC track in ${file.name}")
        extractor.selectTrack(track)
        format = extractor.getTrackFormat(track)
    }

    override val frameCount: Long =
        if (format.containsKey(MediaFormat.KEY_DURATION)) AudioSpec.msToFrame(format.getLong(MediaFormat.KEY_DURATION) / 1000) else 0

    override fun forEachFrame(startFrame: Long, endFrame: Long, block: (ByteArray, Int, Long) -> Unit) {
        extractor.seekTo(AudioSpec.frameToUs(startFrame), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val bb = ByteBuffer.allocate(8192)
        var arr = ByteArray(8192)
        while (true) {
            val t = extractor.sampleTime
            if (t < 0) break
            val frame = (t * AudioSpec.SAMPLE_RATE + 500_000L * AudioSpec.SAMPLES_PER_FRAME) /
                (1_000_000L * AudioSpec.SAMPLES_PER_FRAME)
            if (frame >= endFrame) break
            bb.clear()
            val size = extractor.readSampleData(bb, 0)
            if (size < 0) break
            if (frame >= startFrame) {
                if (size > arr.size) arr = ByteArray(size)
                bb.get(arr, 0, size)
                block(arr, size, frame)
            }
            if (!extractor.advance()) break
        }
    }

    override fun close() = extractor.release()
}

object M4aWriter {
    /**
     * Writes frames [startFrame, endFrame) of [source] into an .m4a at [out], atomically (temp file,
     * then rename). No re-encoding, so this is fast: about a second per hour of audio.
     * Returns the number of frames written.
     */
    fun write(source: AacFrameSource, startFrame: Long, endFrame: Long, out: File): Long {
        // A unique temp name, so two overlapping writers of the same file can never clobber each other.
        val tmp = File.createTempFile(out.name + ".", ".tmp", out.parentFile)
        try {
            return writeVia(source, startFrame, endFrame, tmp, out)
        } finally {
            tmp.delete() // no-op once renamed into place
        }
    }

    private fun writeVia(source: AacFrameSource, startFrame: Long, endFrame: Long, tmp: File, out: File): Long {
        val muxer = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var written = 0L
        try {
            val format = MediaFormat.createAudioFormat(AudioSpec.MIME, AudioSpec.SAMPLE_RATE, AudioSpec.CHANNELS).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, AudioSpec.BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setByteBuffer("csd-0", ByteBuffer.wrap(AudioSpec.audioSpecificConfig()))
            }
            val track = muxer.addTrack(format)
            muxer.start()
            val info = MediaCodec.BufferInfo()
            var direct = ByteBuffer.allocateDirect(4096)
            source.forEachFrame(startFrame, endFrame) { buf, size, frame ->
                if (size > direct.capacity()) direct = ByteBuffer.allocateDirect(size)
                direct.clear()
                direct.put(buf, 0, size)
                direct.flip()
                info.set(0, size, AudioSpec.frameToUs(frame - startFrame), MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(track, direct, info)
                written++
            }
            if (written == 0L) throw IOException("No audio frames to write")
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
        // Make the new file durable before the caller deletes the only other copy of the audio.
        RandomAccessFile(tmp, "rw").use { it.fd.sync() }
        val readable = runCatching { Mp4FrameSource(tmp).use { it.frameCount } }.getOrDefault(0L)
        if (written == 0L || readable <= 0L || !tmp.renameTo(out)) {
            throw IOException("Could not write ${out.name}")
        }
        runCatching {
            java.nio.channels.FileChannel.open(out.parentFile!!.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        }
        return written
    }
}
