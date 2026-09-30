package io.github.aleixrodriala.quadern.whisper

/** Raw bindings to `libquadern_whisper.so` (src/main/cpp/whisper_jni.cpp). Use [WhisperContext]. */
internal object WhisperJni {
    init {
        // Pulls in libggml.so / libggml-base.so as DT_NEEDED; the CPU backend variant is dlopen()ed
        // lazily by loadModel()/systemInfo().
        System.loadLibrary("quadern_whisper")
    }

    /** Returns an opaque handle; throws IllegalStateException if whisper.cpp rejects the model. */
    @JvmStatic external fun loadModel(modelPath: String): Long

    @JvmStatic external fun freeModel(handle: Long)

    /** Aborts every transcription that snapshotted an older [cancelGeneration]. Any thread. */
    @JvmStatic external fun requestCancel(handle: Long)

    @JvmStatic external fun cancelGeneration(handle: Long): Long

    /** UTF-8 bytes of the concatenated segment texts. beamSize <= 1 means greedy decoding. */
    @JvmStatic external fun transcribe(
        handle: Long,
        startGeneration: Long,
        samples: FloatArray,
        language: String?,
        threads: Int,
        beamSize: Int,
        progress: ProgressSink?,
    ): ByteArray

    @JvmStatic external fun systemInfo(): String
}

/** Receives progress from native code; `onProgress` is looked up by name (kept by consumer-rules.pro). */
internal class ProgressSink(private val callback: (Int) -> Unit) {
    fun onProgress(percent: Int) = callback(percent)
}
