package io.github.aleixrodriala.quadern

import io.github.aleixrodriala.quadern.audio.AdtsIndex
import io.github.aleixrodriala.quadern.audio.AudioSpec
import io.github.aleixrodriala.quadern.audio.ChunkPlanner
import io.github.aleixrodriala.quadern.data.NotesRepository
import io.github.aleixrodriala.quadern.util.formatDuration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkPlannerTest {
    private val speech = 150.toByte()
    private val quiet = 5.toByte()

    /** Levels for [minutes] of speech with a short pause every [pauseEverySec] seconds. */
    private fun levels(minutes: Int, pauseEverySec: Int = 7): ByteArray {
        val n = minutes * 60 * 10
        return ByteArray(n) { i -> if ((i / 10) % pauseEverySec == 0 && i % 10 < 6) quiet else speech }
    }

    @Test fun waitsForLookaheadWhileRecording() {
        val l = levels(6)
        assertNull(ChunkPlanner.nextCut(l, l.size, 0, 6 * 60_000L, final = false))
    }

    @Test fun cutsInsideWindowAtAPause() {
        val l = levels(10)
        val cut = ChunkPlanner.nextCut(l, l.size, 0, 10 * 60_000L, final = false)!!
        assertTrue("cut $cut in 4-6 min", cut in ChunkPlanner.MIN_MS..ChunkPlanner.MAX_MS)
        assertEquals("cut lands on a quiet bucket", quiet, l[(cut / 100).toInt()])
    }

    @Test fun finalRemainderBecomesOneChunkWhenShortEnough() {
        val l = levels(7)
        assertEquals(7 * 60_000L, ChunkPlanner.nextCut(l, l.size, 0, 7 * 60_000L, final = true))
    }

    @Test fun longFinalAudioIsStillSplitAndNoChunkExceedsHardMax() {
        val l = levels(60)
        var start = 0L
        val total = 60 * 60_000L
        var chunks = 0
        while (start < total) {
            val end = ChunkPlanner.nextCut(l, l.size, start, total, final = true)!!
            assertTrue(end > start)
            assertTrue("chunk ${end - start} ms <= hard max", end - start <= ChunkPlanner.HARD_MAX_MS)
            start = end
            chunks++
        }
        assertTrue(chunks in 10..15)
    }

    @Test fun continuousSpeechStillCutsWithinWindow() {
        val l = ByteArray(20 * 60 * 10) { speech }
        val cut = ChunkPlanner.nextCut(l, l.size, 0, 20 * 60_000L, final = true)!!
        assertTrue(cut in ChunkPlanner.MIN_MS..ChunkPlanner.MAX_MS + 1000)
    }

    @Test fun silenceDetection() {
        val l = ByteArray(600) { quiet }
        assertTrue(ChunkPlanner.isSilent(l, l.size, 0, 60_000))
        l[300] = speech
        assertTrue(!ChunkPlanner.isSilent(l, l.size, 0, 60_000))
    }

    @Test fun missingLevelsMeanUpload() {
        val l = ByteArray(600) { quiet }
        assertTrue("a few trailing buckets missing is normal", ChunkPlanner.isSilent(l, 595, 0, 60_000))
        assertTrue("3 s of levels lost in a crash: upload", !ChunkPlanner.isSilent(l, 570, 0, 60_000))
    }

    @Test fun levelMapping() {
        assertEquals(0, ChunkPlanner.levelOf(0.0, 4800))
        // Full-scale sine RMS ≈ -3 dBFS → near the top.
        val rms = 32767 / Math.sqrt(2.0)
        assertTrue(ChunkPlanner.levelOf(rms * rms * 4800, 4800) > 240)
    }
}

class AdtsTest {
    private fun writeFrames(file: File, sizes: List<Int>, truncateLastBy: Int = 0) {
        file.outputStream().use { out ->
            sizes.forEachIndexed { i, size ->
                val bytes = AudioSpec.adtsHeader(size) + ByteArray(size) { (i + it).toByte() }
                out.write(if (i == sizes.lastIndex && truncateLastBy > 0) bytes.copyOf(bytes.size - truncateLastBy) else bytes)
            }
        }
    }

    @Test fun indexesCompleteFrames() {
        val f = File.createTempFile("adts", ".aac")
        writeFrames(f, listOf(100, 200, 150))
        val idx = AdtsIndex.scan(f)
        assertEquals(3, idx.frameCount)
        assertEquals(f.length(), idx.validBytes)
        assertEquals(7L, idx.offset(0))
        assertEquals(200, idx.length(1))
    }

    @Test fun ignoresHalfWrittenTrailingFrame() {
        val f = File.createTempFile("adts", ".aac")
        writeFrames(f, listOf(100, 200, 150), truncateLastBy = 40)
        val idx = AdtsIndex.scan(f)
        assertEquals(2, idx.frameCount)
        assertEquals((7 + 100 + 7 + 200).toLong(), idx.validBytes)
    }

    @Test fun ignoresTruncatedHeader() {
        val f = File.createTempFile("adts", ".aac")
        writeFrames(f, listOf(100))
        f.appendBytes(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x4C))
        assertEquals(1, AdtsIndex.scan(f).frameCount)
    }

    @Test fun frameTimeMath() {
        assertEquals(1_000_000L, AudioSpec.frameToMs(46_875))
        assertEquals(46_875L, AudioSpec.msToFrame(1_000_000))
        assertEquals(21_333L, AudioSpec.frameToUs(1))
        assertEquals(byteArrayOf(0x11, 0x88.toByte()).toList(), AudioSpec.audioSpecificConfig().toList())
    }
}

class FormattingTest {
    @Test fun autoTitleUsesFirstSentence() {
        assertEquals("Buy milk and call the plumber tomorrow", NotesRepository.autoTitle("Buy milk and call the plumber tomorrow. Also the car."))
    }

    @Test fun autoTitleTruncatesAtWord() {
        val t = NotesRepository.autoTitle("so I was thinking that we could maybe restructure the whole onboarding flow and then also the pricing page")
        assertTrue(t.length <= 61)
        assertTrue(t.endsWith("…"))
    }

    @Test fun durations() {
        assertEquals("0:07", formatDuration(7_400))
        assertEquals("12:34", formatDuration(754_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }
}
