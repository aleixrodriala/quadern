package io.github.aleixrodriala.noteai.whisper

import android.os.ParcelFileDescriptor
import android.system.Os
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * End-to-end tests against a real model and real speech. Test data is pushed with adb first:
 *
 *   adb shell mkdir -p /data/local/tmp/whisper-test
 *   adb push ggml-tiny.en.bin clip_30.f32 clip_300.f32 /data/local/tmp/whisper-test/
 *   (optional) adb push ggml-base.bin /data/local/tmp/whisper-test/
 *
 * Audio files are raw 16 kHz mono little-endian float32 (`ffmpeg -i in.m4a -ar 16000 -ac 1 -f f32le out.f32`).
 * Instrumentation args override names: -e model ggml-base.en.bin -e dir /data/local/tmp/whisper-test
 * Greedy vs beam-search timing runs only with -e benchmark true; -e cpuBackend <lib file> forces a
 * ggml CPU variant (e.g. to compare libggml-cpu-android_armv8.6_1.so with the auto-picked one).
 */
@RunWith(AndroidJUnit4::class)
class WhisperContextTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val dir = args.getString("dir") ?: "/data/local/tmp/whisper-test"
    private val modelName = args.getString("model") ?: "ggml-tiny.en.bin"

    @Test
    fun systemInfoReportsSelectedCpuBackend() {
        val info = WhisperContext.systemInfo()
        log("systemInfo: $info")
        assertTrue(info, info.contains("CPU_BACKEND = libggml-cpu-"))
    }

    @Test
    fun transcribesThirtySecondClip() {
        val samples = readPcm("clip_30.f32")
        WhisperContext.load(testFile(modelName).path).use { ctx ->
            val progress = Collections.synchronizedList(mutableListOf<Int>())
            val t0 = SystemClock.elapsedRealtime()
            val text = ctx.transcribe(samples, "en") { progress += it }
            val ms = SystemClock.elapsedRealtime() - t0
            log("RESULT clip_30 model=$modelName time=${ms}ms progress=$progress\n$text")
            assertTrue("suspiciously short transcript: '$text'", text.length > 100)
            assertEquals(text.trim(), text)
            assertEquals(100, progress.last())
            assertEquals(progress.sorted().distinct(), progress)
        }
    }

    @Test
    fun transcribesFiveMinutes() {
        val samples = readPcm("clip_300.f32")
        assertTrue(samples.size >= 4_800_000)
        WhisperContext.load(testFile(modelName).path).use { ctx ->
            var firstProgressMs = -1L
            val t0 = SystemClock.elapsedRealtime()
            // The first callback (0 %) comes right after the up-front log-mel spectrogram, the one
            // phase cancel() can't interrupt, so this measures the worst-case cancel latency.
            val text = ctx.transcribe(samples, "en") {
                if (firstProgressMs < 0) firstProgressMs = SystemClock.elapsedRealtime() - t0
            }
            val ms = SystemClock.elapsedRealtime() - t0
            val words = text.split(Regex("\\s+")).size
            log("RESULT clip_300 model=$modelName samples=${samples.size} time=${ms}ms mel=${firstProgressMs}ms words=$words\n$text")
            // ~150 wpm narration: a transcript that stops after the first 30 s window would be ~75 words.
            assertTrue("only $words words for 5 minutes of speech", words > 400)
        }
    }

    @Test
    fun cancelAbortsQuicklyAndContextStaysUsable() {
        val longAudio = readPcm("clip_300.f32")
        WhisperContext.load(testFile(modelName).path).use { ctx ->
            val started = CountDownLatch(1)
            val outcome = AtomicReference<Throwable?>()
            val worker = thread(name = "whisper-cancel-test") {
                try {
                    ctx.transcribe(longAudio, "en") { started.countDown() }
                    outcome.set(AssertionError("transcribe() returned instead of being cancelled"))
                } catch (t: Throwable) {
                    outcome.set(t)
                }
            }
            assertTrue("no progress within 60 s", started.await(60, TimeUnit.SECONDS))
            SystemClock.sleep(1500) // let it get deep into encoding/decoding
            val t0 = SystemClock.elapsedRealtime()
            ctx.cancel()
            worker.join(30_000)
            val latency = SystemClock.elapsedRealtime() - t0
            log("RESULT cancel latency=${latency}ms outcome=${outcome.get()}")
            assertTrue("worker still running", !worker.isAlive)
            assertTrue("expected CancellationException, got ${outcome.get()}", outcome.get() is CancellationException)
            assertTrue("cancel took ${latency}ms", latency < 3000)

            // cancel() is not sticky: the next call runs normally.
            val text = ctx.transcribe(readPcm("clip_30.f32"), "en")
            assertTrue(text.length > 100)
        }
    }

    @Test
    fun closeFromTheFinalProgressCallbackCancels() {
        val ctx = WhisperContext.load(testFile(modelName).path)
        try {
            ctx.transcribe(readPcm("clip_30.f32"), "en") { if (it == 100) ctx.close() }
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        try {
            ctx.transcribe(FloatArray(16_000), "en")
            fail("expected IllegalStateException: closed")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun concurrentCallsAreSerialisedAndAgree() {
        val samples = readPcm("clip_30.f32")
        WhisperContext.load(testFile(modelName).path).use { ctx ->
            val results = Collections.synchronizedList(mutableListOf<String>())
            val workers = List(2) { thread { results += ctx.transcribe(samples, "en", threads = 2) } }
            workers.forEach { it.join(300_000) }
            assertEquals(2, results.size)
            assertEquals(results[0], results[1])
        }
    }

    @Test
    fun errorsAreReported() {
        try {
            WhisperContext.load("$dir/does-not-exist.bin")
            fail("expected FileNotFoundException")
        } catch (_: FileNotFoundException) {
        }
        val ctx = WhisperContext.load(testFile(modelName).path)
        assertEquals("", ctx.transcribe(FloatArray(0), null))
        try {
            ctx.transcribe(FloatArray(16_000), "en", threads = -1)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        val boom = RuntimeException("boom")
        try {
            ctx.transcribe(readPcm("clip_30.f32"), "en") { throw boom }
            fail("expected the callback's exception")
        } catch (e: RuntimeException) {
            assertTrue(e === boom)
        }
        ctx.close()
        ctx.close() // idempotent
        ctx.cancel() // no-op after close
        try {
            ctx.transcribe(FloatArray(16_000), "en")
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun multilingualModelAutoDetectsLanguage() {
        val model = File(dir, "ggml-base.bin")
        assumeTrue("push ggml-base.bin to run this test", canRead(model))
        val samples = readPcm("clip_30.f32")
        WhisperContext.load(testFile("ggml-base.bin").path).use { ctx ->
            val t0 = SystemClock.elapsedRealtime()
            val auto = ctx.transcribe(samples, null)
            val ms = SystemClock.elapsedRealtime() - t0
            log("RESULT clip_30 model=ggml-base.bin language=auto time=${ms}ms\n$auto")
            assertTrue(auto.length > 100)
            try {
                ctx.transcribe(samples, "xx")
                fail("expected IllegalArgumentException for an unknown language")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    /** Greedy vs beam search 5 on the same audio. Opt-in: -e benchmark true [-e model ...]. */
    @Test
    fun benchmarkGreedyVsBeamSearch() {
        assumeTrue(args.getString("benchmark") == "true")
        val samples = readPcm(args.getString("clip") ?: "clip_30.f32")
        WhisperContext.load(testFile(modelName).path).use { ctx ->
            ctx.transcribe(samples.copyOf(16_000 * 5), "en") // warm-up
            for (beam in listOf(1, 5, 1, 5)) {
                val t0 = SystemClock.elapsedRealtime()
                val text = ctx.transcribeInternal(samples, "en", 0, null, beam)
                val ms = SystemClock.elapsedRealtime() - t0
                log("RESULT benchmark model=$modelName beam=$beam time=${ms}ms\n$text")
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun log(msg: String) = msg.chunked(3000).forEach { Log.i(TAG, it) }

    private fun canRead(f: File) = f.isFile && f.canRead()

    /**
     * Files under /data/local/tmp are not readable by apps on every Android version (SELinux);
     * if a direct read fails, stream the file through the shell into this app's cache dir.
     */
    private fun testFile(name: String): File {
        val src = File(dir, name)
        if (canRead(src)) return src
        val dst = File(instrumentation.targetContext.cacheDir, name)
        if (!dst.isFile) {
            val pfd = instrumentation.uiAutomation.executeShellCommand("cat ${src.path}")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                dst.outputStream().use { input.copyTo(it, 1 shl 20) }
            }
        }
        assumeTrue("missing test file ${src.path} (push it with adb)", dst.length() > 0)
        return dst
    }

    private fun readPcm(name: String): FloatArray {
        val bytes = FileInputStream(testFile(name)).use { it.readBytes() }
        val floats = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }

    companion object {
        private const val TAG = "WhisperTest"

        /** -e cpuBackend libggml-cpu-android_armv8.6_1.so forces a CPU variant (A/B benchmarks). */
        @JvmStatic
        @BeforeClass
        fun forceCpuBackend() {
            val forced = InstrumentationRegistry.getArguments().getString("cpuBackend") ?: return
            Os.setenv("NOTEAI_WHISPER_CPU_BACKEND", forced, true)
        }
    }
}
