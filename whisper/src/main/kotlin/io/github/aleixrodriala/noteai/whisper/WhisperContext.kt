package io.github.aleixrodriala.noteai.whisper

import java.io.FileNotFoundException
import java.io.IOException

/**
 * A loaded whisper.cpp model. Create with [load], release with [close] (the model and its buffers
 * live in native memory, tens to hundreds of MB, and are not freed by the GC).
 *
 * Thread-safety: all methods may be called from any thread. Transcriptions on one context run one
 * at a time; concurrent [transcribe] calls queue. [cancel] and [close] don't wait for the model to
 * finish a window: the running transcription aborts at the next ggml op. Two short phases can't be
 * interrupted: the up-front log-mel spectrogram of the whole input (~20 ms per minute of audio on
 * the emulator) and, with language = null, the language-detection encode of the first window.
 */
class WhisperContext private constructor(private var ptr: Long) : java.io.Closeable {

    /** Serialises transcriptions and the final free — whisper.cpp keeps per-context state. */
    private val transcribeLock = Any()

    /** Guards [ptr]. Only held for a moment, never across a transcription, so cancel() can't block. */
    private val handleLock = Any()

    /** Set while the thread holding [transcribeLock] is inside whisper_full (reentrancy guard). */
    private var busy = false
    private var closeWhenIdle = false

    companion object {
        /**
         * Beam search with 5 beams (1 would be greedy). Measured on 5 min of speech it removed
         * greedy's dropped phrases and repetition loops for ~1.3-1.8x the time; see README, "Decoding".
         */
        internal const val DEFAULT_BEAM_SIZE = 5

        /** Loads a ggml model file. Throws IOException/IllegalStateException on failure. Blocking. */
        fun load(modelPath: String): WhisperContext {
            val file = java.io.File(modelPath)
            if (!file.isFile) throw FileNotFoundException("Whisper model not found: $modelPath")
            if (!file.canRead()) throw IOException("Whisper model not readable: $modelPath")
            return WhisperContext(WhisperJni.loadModel(file.absolutePath))
        }

        /** e.g. "AVX2 = 0 | NEON = 1 | ..." for diagnostics */
        fun systemInfo(): String = WhisperJni.systemInfo()
    }

    /**
     * Transcribes 16 kHz mono float PCM in [-1,1]. Blocking; call from a background thread.
     * language: ISO code like "en"/"es", or null for auto-detect. threads: 0 = sensible default (big cores).
     * onProgress: 0..100 (may be called from a native thread). Returns the full text, segments joined, trimmed.
     * Supports cancellation: if the calling coroutine/thread calls cancel() on this context, abort ASAP and
     * throw java.util.concurrent.CancellationException.
     *
     * Also throws IllegalArgumentException for an unknown language code or negative [threads],
     * IllegalStateException if the context is closed or whisper.cpp fails, and rethrows anything
     * [onProgress] throws (which also aborts the transcription).
     */
    fun transcribe(
        samples: FloatArray,
        language: String?,
        threads: Int = 0,
        onProgress: ((Int) -> Unit)? = null,
    ): String = transcribeInternal(samples, language, threads, onProgress, DEFAULT_BEAM_SIZE)

    internal fun transcribeInternal(
        samples: FloatArray,
        language: String?,
        threads: Int,
        onProgress: ((Int) -> Unit)?,
        beamSize: Int,
    ): String {
        require(threads >= 0) { "threads must be >= 0 (0 = default), was $threads" }
        // Snapshot the cancel generation before queueing on the lock: cancel() then also reaches a
        // call that is still waiting for an earlier transcription, but never a later call.
        val generation = synchronized(handleLock) { WhisperJni.cancelGeneration(openHandle()) }
        synchronized(transcribeLock) {
            check(!busy) { "transcribe() must not be called from its own onProgress callback" }
            val handle = synchronized(handleLock) { openHandle() }
            busy = true
            try {
                val utf8 = WhisperJni.transcribe(
                    handle, generation, samples, language, threads, beamSize, onProgress?.let(::ProgressSink),
                )
                return String(utf8, Charsets.UTF_8).trim()
            } finally {
                busy = false
                if (closeWhenIdle) release()
            }
        }
    }

    /**
     * Aborts, as soon as possible, every [transcribe] call on this context that is running or
     * queued at the time of the call; each of them throws CancellationException. Calls made
     * afterwards are unaffected. No-op if nothing is running or the context is closed. Any thread.
     */
    fun cancel() {
        synchronized(handleLock) {
            if (ptr != 0L) WhisperJni.requestCancel(ptr)
        }
    }

    /**
     * Frees the model. A transcription in progress is cancelled first (it throws
     * CancellationException) and close() waits for it to unwind (see the class docs for how long
     * that can take). Idempotent.
     */
    override fun close() {
        cancel()
        synchronized(transcribeLock) {
            // Called from onProgress on the transcribing thread: free once whisper_full returns.
            if (busy) {
                closeWhenIdle = true
                return
            }
            release()
        }
    }

    private fun openHandle(): Long {
        check(ptr != 0L) { "WhisperContext is closed" }
        return ptr
    }

    private fun release() {
        synchronized(handleLock) {
            if (ptr != 0L) {
                WhisperJni.freeModel(ptr)
                ptr = 0L
            }
        }
    }
}
