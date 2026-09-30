package io.github.aleixrodriala.noteai.transcription

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.aleixrodriala.noteai.NoteApp
import io.github.aleixrodriala.noteai.audio.AdtsFrameSource
import io.github.aleixrodriala.noteai.audio.M4aWriter
import io.github.aleixrodriala.noteai.audio.Mp4FrameSource
import io.github.aleixrodriala.noteai.data.Chunk
import io.github.aleixrodriala.noteai.data.ChunkStatus
import io.github.aleixrodriala.noteai.data.RecordingState
import io.github.aleixrodriala.noteai.data.TranscriptionStatus
import io.github.aleixrodriala.noteai.util.Notifications
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Drains the chunk queue. Every chunk result is saved as soon as it arrives, so being stopped,
 * killed or rescheduled at any moment loses at most the uploads in flight.
 *
 * Outcomes per chunk:
 *  - transient (network, 5xx, 429): counted, retried by WorkManager's exponential backoff, and only
 *    marked failed after [MAX_ATTEMPTS]; losing connectivity just pauses the queue until it's back
 *  - auth (session expired): the note waits in NEEDS_AUTH until the user signs in again
 *  - not configured: NEEDS_SETUP until a provider is set up
 *  - permanent (provider refused the audio): the fallback provider gets a go, else FAILED
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as NoteApp).container
    private val dao = container.db.notes()
    private val files = container.files

    override suspend fun doWork(): Result {
        val settings = container.settings.current()
        val primary = runCatching { container.providers.create(settings.provider, settings) }
        val fallback = settings.fallback?.takeIf { it != settings.provider }
            ?.let { runCatching { container.providers.create(it, settings) }.getOrNull() }

        var retryLater = false
        val touched = mutableSetOf<String>()
        val attemptedThisRun = mutableSetOf<Pair<String, Int>>()

        try {
        while (true) {
            val pending = dao.pendingChunks().filter { (it.noteId to it.idx) !in attemptedThisRun }
            if (pending.isEmpty()) break

            val provider = primary.getOrNull() ?: fallback
            if (provider == null) {
                val msg = (primary.exceptionOrNull() as? SttException)?.message ?: "Set up a transcription provider in Settings"
                for (noteId in pending.map { it.noteId }.toSet()) dao.setStatus(noteId, TranscriptionStatus.NEEDS_SETUP, msg)
                break
            }

            val batch = pending.take(PARALLEL)
            batch.forEach { attemptedThisRun += it.noteId to it.idx }
            for (noteId in batch.map { it.noteId }.toSet()) {
                touched += noteId
                dao.setStatus(noteId, TranscriptionStatus.RUNNING, null)
            }

            val outcomes = coroutineScope {
                batch.map { chunk -> async { chunk to runChunk(chunk, provider, fallback, settings.language) } }.awaitAll()
            }

            var stop = false
            for ((chunk, outcome) in outcomes) {
                when (outcome) {
                    is Outcome.Done -> {
                        dao.updateChunk(chunk.copy(status = ChunkStatus.DONE, text = outcome.text, lastError = null,
                            provider = outcome.provider.name, updatedAt = now()))
                        files.chunkFile(chunk.noteId, chunk.idx).delete()
                        container.repository.assembleTranscript(chunk.noteId)
                    }
                    is Outcome.Failed -> handleFailure(chunk, outcome.error).also { if (it) { retryLater = true; stop = true } }
                }
            }
            // A transient failure usually means the network or the service is down: stop hammering
            // and let WorkManager's backoff (or the connectivity constraint) decide when to resume.
            if (stop) break
        }
        } finally {
            // Also runs when the worker is stopped or cancelled, so no note is left "Transcribing".
            withContext(kotlinx.coroutines.NonCancellable) {
                for (noteId in touched) runCatching { settle(noteId, retryLater) }
            }
        }
        return if (retryLater) Result.retry() else Result.success()
    }

    private sealed interface Outcome {
        data class Done(val text: String, val provider: ProviderId) : Outcome
        data class Failed(val error: SttException) : Outcome
    }

    private suspend fun runChunk(chunk: Chunk, provider: SttProvider, fallback: SttProvider?, language: String?): Outcome {
        val audio = try {
            extract(chunk)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Transient: a cut can fail for passing reasons (storage full, interrupted run); a truly
            // unreadable file still gives up after MAX_ATTEMPTS.
            Log.e(TAG, "Couldn't cut chunk ${chunk.noteId}/${chunk.idx}", e)
            return Outcome.Failed(SttException.Transient("Couldn't read the audio: ${e.message}"))
        }
        return try {
            Outcome.Done(safely(provider) { transcribe(audio, language) }, provider.id)
        } catch (e: SttException) {
            Log.w(TAG, "${provider.id} failed on ${chunk.noteId}/${chunk.idx}: ${e.message}")
            val tryFallback = fallback != null && fallback !== provider &&
                (e is SttException.Permanent || e is SttException.Auth || e is SttException.NotConfigured ||
                    (e is SttException.Transient && chunk.attempts + 1 >= FALLBACK_AFTER))
            if (!tryFallback) return Outcome.Failed(e)
            try {
                Outcome.Done(safely(fallback!!) { transcribe(audio, language) }, fallback.id)
            } catch (e2: SttException) {
                Log.w(TAG, "Fallback ${fallback!!.id} failed too: ${e2.message}")
                Outcome.Failed(e)
            }
        }
    }

    /** Maps anything a provider throws that isn't an [SttException] into one; cancellation passes through. */
    private suspend fun safely(provider: SttProvider, block: suspend SttProvider.() -> String): String = try {
        provider.block()
    } catch (e: SttException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        // e.g. a malformed custom server URL: retrying can't help, the user has to fix settings.
        throw SttException.NotConfigured("${provider.id.label}: ${e.message ?: "invalid settings"}")
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected ${provider.id} failure", e)
        throw SttException.Transient("${provider.id.label}: ${e.message ?: e.javaClass.simpleName}", e)
    }

    /** Returns true when the chunk should be retried later. */
    private suspend fun handleFailure(chunk: Chunk, error: SttException): Boolean {
        val attempts = chunk.attempts + 1
        return when (error) {
            is SttException.Transient -> {
                val giveUp = attempts >= MAX_ATTEMPTS
                dao.updateChunk(chunk.copy(attempts = attempts, lastError = error.message,
                    status = if (giveUp) ChunkStatus.FAILED else ChunkStatus.PENDING, updatedAt = now()))
                !giveUp
            }
            is SttException.Auth -> {
                dao.updateChunk(chunk.copy(attempts = attempts, lastError = error.message, updatedAt = now()))
                dao.setStatus(chunk.noteId, TranscriptionStatus.NEEDS_AUTH, error.message)
                Notifications.showNeedsSignIn(applicationContext)
                false
            }
            is SttException.NotConfigured -> {
                dao.updateChunk(chunk.copy(attempts = attempts, lastError = error.message, updatedAt = now()))
                dao.setStatus(chunk.noteId, TranscriptionStatus.NEEDS_SETUP, error.message)
                false
            }
            is SttException.Permanent -> {
                dao.updateChunk(chunk.copy(attempts = attempts, lastError = error.message, status = ChunkStatus.FAILED, updatedAt = now()))
                false
            }
        }
    }

    /** Sets the note's overall status from its chunks once this run is done with it. */
    private suspend fun settle(noteId: String, retryScheduled: Boolean) {
        val note = dao.getNote(noteId) ?: return
        if (note.transcriptionStatus == TranscriptionStatus.NEEDS_AUTH || note.transcriptionStatus == TranscriptionStatus.NEEDS_SETUP) return
        val chunks = dao.chunksFor(noteId)
        val failed = chunks.firstOrNull { it.status == ChunkStatus.FAILED }
        val pending = chunks.filter { it.status == ChunkStatus.PENDING }
        when {
            failed != null && pending.isEmpty() -> dao.setStatus(noteId, TranscriptionStatus.FAILED, failed.lastError ?: "Transcription failed")
            pending.isNotEmpty() -> dao.setStatus(noteId, TranscriptionStatus.QUEUED,
                if (retryScheduled) pending.firstNotNullOfOrNull { it.lastError } else null)
            // Still recording: the next chunks are on their way; finalize will queue them.
            else -> dao.setStatus(noteId, TranscriptionStatus.DONE, null)
        }
        if (note.recordingState == RecordingState.RECORDED && pending.isEmpty()) container.repository.assembleTranscript(noteId)
    }

    private suspend fun extract(chunk: Chunk): File = withContext(Dispatchers.IO) {
        val out = files.chunkFile(chunk.noteId, chunk.idx)
        if (out.length() > 0) return@withContext out // cut on an earlier attempt
        val m4a = files.m4a(chunk.noteId)
        val adts = files.adts(chunk.noteId)
        val source = when {
            m4a.exists() -> Mp4FrameSource(m4a)
            adts.exists() -> AdtsFrameSource(adts)
            else -> throw java.io.IOException("Audio file is missing")
        }
        source.use { M4aWriter.write(it, chunk.startFrame, chunk.endFrame, out) }
        out
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notifications.transcribing(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_TRANSCRIBING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_TRANSCRIBING, n)
        }
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TAG = "TranscriptionWorker"
        const val PARALLEL = 3
        const val MAX_ATTEMPTS = 12
        const val FALLBACK_AFTER = 3
    }
}
