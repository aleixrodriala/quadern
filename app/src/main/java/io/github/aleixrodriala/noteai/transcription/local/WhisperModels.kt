package io.github.aleixrodriala.noteai.transcription.local

import android.content.Context
import io.github.aleixrodriala.noteai.data.AppSettings
import io.github.aleixrodriala.noteai.transcription.SttException
import io.github.aleixrodriala.noteai.transcription.SttProvider
import io.github.aleixrodriala.noteai.transcription.providers.Http
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request

/** Whisper models for on-device transcription: catalog, download (resumable), and the engine. */
class WhisperModels(private val context: Context, private val onInstalled: () -> Unit = {}) {
    data class Model(val id: String, val file: String, val bytes: Long, val label: String, val note: String)

    sealed interface Download {
        data class Running(val done: Long, val total: Long) : Download
        data class Failed(val message: String) : Download
    }

    val catalog = listOf(
        Model("tiny-q8_0", "ggml-tiny-q8_0.bin", 43_537_433, "Tiny", "Fastest, rough accuracy"),
        Model("base-q8_0", "ggml-base-q8_0.bin", 81_768_585, "Base", "Fast, decent accuracy"),
        Model("small-q5_1", "ggml-small-q5_1.bin", 190_085_487, "Small", "Good accuracy, slower"),
        Model("large-v3-turbo-q5_0", "ggml-large-v3-turbo-q5_0.bin", 574_041_195, "Large v3 Turbo", "Best accuracy, needs a fast phone"),
    )

    private val dir = File(context.filesDir, "whisper").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())
    val downloads: StateFlow<Map<String, Download>> = _downloads.asStateFlow()

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    fun model(id: String) = catalog.firstOrNull { it.id == id }
    fun fileFor(m: Model) = File(dir, m.file)

    private fun scanInstalled(): Set<String> = catalog.filter { File(dir, it.file).length() == it.bytes }.map { it.id }.toSet()

    fun download(id: String) {
        val m = model(id) ?: return
        if (jobs[id]?.isActive == true) return
        jobs[id] = scope.launch {
            val part = File(dir, m.file + ".part")
            try {
                var have = part.length()
                _downloads.update { it + (id to Download.Running(have, m.bytes)) }
                val request = Request.Builder()
                    .url("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${m.file}")
                    .apply { if (have > 0) header("Range", "bytes=$have-") }
                    .build()
                // The model host redirects to a CDN; this client follows redirects (no secrets here).
                Http.client.newBuilder().followRedirects(true).followSslRedirects(true).build()
                    .newCall(request).execute().use { r ->
                        if (r.code == 200) have = 0 // server ignored the range: start over
                        else if (r.code != 206) throw IOException("Download failed (${r.code})")
                        RandomAccessFile(part, "rw").use { out ->
                            out.setLength(have)
                            out.seek(have)
                            val input = r.body.byteStream()
                            val buf = ByteArray(1 shl 16)
                            var lastEmit = 0L
                            while (isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                have += n
                                if (have - lastEmit > 512 * 1024) {
                                    lastEmit = have
                                    _downloads.update { it + (id to Download.Running(have, m.bytes)) }
                                }
                            }
                        }
                    }
                if (part.length() != m.bytes) throw IOException("Download incomplete, tap to resume")
                if (!part.renameTo(fileFor(m))) throw IOException("Couldn't save the model")
                _downloads.update { it - id }
                _installed.value = scanInstalled()
                onInstalled()
            } catch (e: Exception) {
                _downloads.update { it + (id to Download.Failed(e.message ?: "Download failed")) }
            }
        }
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        _downloads.update { it - id }
    }

    fun delete(id: String) {
        cancel(id)
        val m = model(id) ?: return
        fileFor(m).delete()
        File(dir, m.file + ".part").delete()
        _installed.value = scanInstalled()
    }

    suspend fun createProvider(settings: AppSettings): SttProvider {
        val m = model(settings.whisperModel) ?: catalog[1]
        val file = fileFor(m)
        if (file.length() != m.bytes) throw SttException.NotConfigured("Download the ${m.label} model in Settings to transcribe on this device")
        return LocalWhisperProvider.get(file)
    }
}
