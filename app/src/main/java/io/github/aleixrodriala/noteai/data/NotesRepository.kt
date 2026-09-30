package io.github.aleixrodriala.noteai.data

import android.util.Log
import io.github.aleixrodriala.noteai.audio.AdtsFrameSource
import io.github.aleixrodriala.noteai.audio.AdtsIndex
import io.github.aleixrodriala.noteai.audio.AudioImporter
import io.github.aleixrodriala.noteai.audio.AudioSpec
import io.github.aleixrodriala.noteai.audio.ChunkPlanner
import io.github.aleixrodriala.noteai.audio.M4aWriter
import io.github.aleixrodriala.noteai.insights.InsightsPrompt
import io.github.aleixrodriala.noteai.insights.InsightsScheduler
import io.github.aleixrodriala.noteai.transcription.TranscriptionScheduler
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class NotesRepository(
    private val dao: NoteDao,
    val files: NoteFiles,
    private val settings: SettingsRepository,
    private val scheduler: TranscriptionScheduler,
    private val insights: InsightsScheduler,
    /** Whether a summary service is chosen and set up (signed in, key present); checked without network. */
    private val summariesReady: suspend () -> Boolean,
) {
    /** Serializes chunk planning and finalization so the recorder, recovery and worker never race. */
    private val mutex = Mutex()

    /** All notes, or those matching [query]. "#tag" searches tags only. */
    /** Notes matching the words in [query], only those tagged [tag] when one is picked. */
    fun observeNotes(query: String, tag: String? = null): Flow<List<NoteWithProgress>> {
        val q = query.trim()
        return when {
            tag != null -> dao.notesWithTag(tag, "%$q%")
            q.isEmpty() -> dao.observeNotes()
            else -> dao.searchNotes("%$q%")
        }
    }

    /** Tags in use, most used first. */
    fun observeTags(limit: Int = 12): Flow<List<String>> =
        dao.observeTagStrings().map { InsightsPrompt.topTags(it, limit) }.distinctUntilChanged()

    fun observeNote(id: String): Flow<NoteWithProgress?> = dao.observeNote(id)

    suspend fun getNote(id: String) = dao.getNote(id)

    suspend fun createRecording(id: String = UUID.randomUUID().toString()): Note {
        val now = System.currentTimeMillis()
        val note = Note(id = id, createdAt = now, updatedAt = now)
        files.dir(note.id)
        dao.insertNote(note)
        durable()
        return note
    }

    /** Makes the last commits survive a power cut (see [NoteDao.checkpoint]). */
    private suspend fun durable() {
        runCatching { dao.checkpoint() }.onFailure { Log.w(TAG, "Checkpoint failed", it) }
    }

    suspend fun updateRecordingProgress(id: String, frames: Long) =
        dao.updateProgress(id, AudioSpec.frameToMs(frames), files.sizeOf(id))

    /**
     * Cuts off every chunk that's ready in the audio captured so far and queues it, so long
     * recordings are mostly transcribed by the time you press stop.
     */
    suspend fun sealChunks(id: String, levels: ByteArray, levelCount: Int, availableFrames: Long, final: Boolean): Int =
        mutex.withLock { sealChunksLocked(id, levels, levelCount, availableFrames, final) }

    private suspend fun sealChunksLocked(id: String, levels: ByteArray, levelCount: Int, availableFrames: Long, final: Boolean): Int {
        val last = dao.lastChunk(id)
        var start = last?.endFrame ?: 0L
        var idx = (last?.idx ?: -1) + 1
        val availableMs = AudioSpec.frameToMs(availableFrames)
        val created = mutableListOf<Chunk>()
        while (start < availableFrames) {
            val cutMs = ChunkPlanner.nextCut(levels, levelCount, AudioSpec.frameToMs(start), availableMs, final) ?: break
            val end = if (cutMs >= availableMs) availableFrames else AudioSpec.msToFrame(cutMs).coerceIn(start + 1, availableFrames)
            val startMs = AudioSpec.frameToMs(start)
            val endMs = AudioSpec.frameToMs(end)
            // Nothing to hear: skip the upload (and Whisper's habit of hallucinating on silence).
            val skip = endMs - startMs < MIN_CHUNK_MS || ChunkPlanner.isSilent(levels, levelCount, startMs, endMs)
            created += Chunk(id, idx++, start, end, status = if (skip) ChunkStatus.DONE else ChunkStatus.PENDING)
            start = end
        }
        if (created.isEmpty()) return 0
        dao.insertChunks(created)
        val pending = created.count { it.status == ChunkStatus.PENDING }
        if (pending > 0 && settings.current().autoTranscribe) {
            val note = dao.getNote(id)
            // Any state except "waiting on the user" must let the new chunks into the queue.
            if (note != null && note.transcriptionStatus !in setOf(
                    TranscriptionStatus.QUEUED, TranscriptionStatus.RUNNING,
                    TranscriptionStatus.NEEDS_AUTH, TranscriptionStatus.NEEDS_SETUP,
                )
            ) dao.setStatus(id, TranscriptionStatus.QUEUED, null)
            scheduler.enqueue()
        }
        return pending
    }

    /**
     * Turns the crash-safe ADTS stream into the final .m4a, plans the remaining chunks and queues
     * transcription. Idempotent: safe to run again after a crash at any point.
     */
    suspend fun finalizeRecording(id: String, interrupted: Boolean) = mutex.withLock {
        val note = dao.getNote(id) ?: return@withLock
        val adts = files.adts(id)
        val m4a = files.m4a(id)
        var durationMs = note.durationMs
        if (adts.exists()) {
            val frames = withContext(Dispatchers.IO) {
                val index = AdtsIndex.scan(adts)
                // Drop a half-written trailing frame so the file is clean for any reader.
                if (index.validBytes < adts.length()) RandomAccessFile(adts, "rw").use { it.setLength(index.validBytes) }
                index.frameCount
            }
            if (frames == 0L) {
                Log.i(TAG, "Recording $id has no audio, removing it")
                dao.deleteNote(id)
                files.delete(id)
                return@withLock
            }
            durationMs = AudioSpec.frameToMs(frames)
            val levels = files.readLevels(id)
            val levelCount = minOf(levels.size, ((durationMs + AudioSpec.LEVEL_INTERVAL_MS - 1) / AudioSpec.LEVEL_INTERVAL_MS).toInt())
            sealChunksLocked(id, levels, levelCount, frames, final = true)
            // The ADTS stream stays until the finished note is on disk: a power cut before that
            // simply finalizes again from it.
            withContext(Dispatchers.IO) { AdtsFrameSource(adts).use { src -> M4aWriter.write(src, 0, frames, m4a) } }
        } else if (!m4a.exists()) {
            Log.w(TAG, "Recording $id has no audio file, removing it")
            dao.deleteNote(id)
            files.delete(id)
            return@withLock
        }

        val chunks = dao.chunksFor(id)
        val anyPending = chunks.any { it.status == ChunkStatus.PENDING }
        val firstFailed = chunks.firstOrNull { it.status == ChunkStatus.FAILED }
        val auto = settings.current().autoTranscribe
        val fresh = dao.getNote(id) ?: return@withLock
        val current = fresh.transcriptionStatus
        val status = when {
            current == TranscriptionStatus.NEEDS_AUTH || current == TranscriptionStatus.NEEDS_SETUP -> current
            anyPending && (auto || current != TranscriptionStatus.IDLE) ->
                if (current == TranscriptionStatus.RUNNING) current else TranscriptionStatus.QUEUED
            anyPending -> TranscriptionStatus.IDLE
            firstFailed != null -> TranscriptionStatus.FAILED
            else -> TranscriptionStatus.DONE
        }
        dao.updateNote(
            fresh.copy(
                recordingState = RecordingState.RECORDED,
                interrupted = fresh.interrupted || interrupted,
                durationMs = durationMs,
                sizeBytes = files.sizeOf(id) - (if (adts.exists() && m4a.exists()) adts.length() else 0),
                transcriptionStatus = status,
                transcriptionError = if (status == TranscriptionStatus.FAILED) firstFailed?.lastError else fresh.transcriptionError,
                updatedAt = System.currentTimeMillis(),
            )
        )
        durable()
        withContext(Dispatchers.IO) { adts.delete() }
        if (status == TranscriptionStatus.DONE) assembleLocked(id)
        if (status == TranscriptionStatus.QUEUED) scheduler.enqueue(now = true)
    }

    /**
     * After a crash, reboot or kill: finishes every recording that no live recorder owns. The audio
     * up to the last few seconds is on disk; it becomes a normal note marked "interrupted".
     */
    suspend fun recoverInterrupted(isLive: (String) -> Boolean): Int {
        var recovered = 0
        for (note in dao.notesStillRecording()) {
            if (isLive(note.id)) continue
            runCatching { finalizeRecording(note.id, interrupted = true) }
                .onFailure { Log.e(TAG, "Couldn't recover ${note.id}", it) }
            recovered++
        }
        return recovered
    }

    /**
     * Finds audio on disk that has no note (a power cut undid the row, or the app died between
     * creating the folder and the row) and turns it into an interrupted note. Only folders last
     * written before [startedAt] count, so nothing this process is recording or importing is touched.
     */
    suspend fun adoptOrphans(startedAt: Long, isLive: (String) -> Boolean): Int {
        val known = dao.allIds().toHashSet()
        var adopted = 0
        for (dir in files.noteDirs()) {
            val id = dir.name
            if (id in known || isLive(id)) continue
            val adts = files.adts(id)
            val m4a = files.m4a(id)
            val newest = dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: dir.lastModified()
            if (newest >= startedAt) continue
            val audio = when {
                adts.exists() && adts.length() > 0 -> adts
                m4a.exists() && m4a.length() > 0 -> m4a
                else -> {
                    // An empty folder: a recording that never got its first second of audio.
                    files.delete(id)
                    continue
                }
            }
            val endedAt = audio.lastModified()
            if (audio == m4a) {
                // Finished audio whose note was lost: turn it back into the stream (a lossless frame
                // copy for our own files) so it goes through finalizing like any recording.
                val ok = withContext(Dispatchers.IO) {
                    runCatching { AudioImporter.import(m4a, adts, files.levels(id)) }
                        .onFailure { Log.e(TAG, "Couldn't read orphaned audio $id", it); adts.delete() }.isSuccess
                }
                if (!ok) continue
            }
            val durationMs = withContext(Dispatchers.IO) { AudioSpec.frameToMs(AdtsIndex.scan(adts).frameCount) }
            val createdAt = (endedAt - durationMs).coerceAtLeast(0)
            Log.w(TAG, "Adopting orphaned recording $id (${durationMs} ms)")
            runCatching {
                dao.insertNote(Note(id = id, createdAt = createdAt, updatedAt = createdAt, durationMs = durationMs))
                durable()
                adopted++
            }.onFailure { Log.e(TAG, "Couldn't adopt $id", it) }
        }
        return adopted
    }

    /** Re-queues work that was in flight when the process died, and nudges the queue. */
    suspend fun resumeTranscriptions() {
        val waiting = dao.notesAwaitingTranscription()
        for (n in waiting) if (n.transcriptionStatus == TranscriptionStatus.RUNNING) dao.setStatus(n.id, TranscriptionStatus.QUEUED, null)
        if (waiting.isNotEmpty()) scheduler.enqueue(now = true)
        if (dao.countAwaitingInsights() > 0) insights.enqueue(now = true)
    }

    /** Joins finished chunks into the note's transcript (and titles it if it has no title yet). */
    suspend fun assembleTranscript(id: String) = mutex.withLock { assembleLocked(id) }

    private suspend fun assembleLocked(id: String) {
        val note = dao.getNote(id) ?: return
        val text = dao.chunksFor(id).filter { it.status == ChunkStatus.DONE }.map { it.text.trim() }
            .filter { it.isNotEmpty() }.joinToString("\n\n")
        if (!note.transcriptEdited) dao.setTranscript(id, text, edited = false)
        // Without anything to write a title, the first words make one. Otherwise the note keeps its
        // placeholder until the written title arrives, instead of flashing a long first sentence.
        if (note.titleIsAuto && text.isNotBlank() && note.insightsStatus != InsightsStatus.DONE && !summariesExpected()) {
            dao.setTitle(id, autoTitle(text), isAuto = true)
        }
        queueInsightsIfDone(id)
    }

    private suspend fun summariesExpected() = settings.current().summarizer != null && summariesReady()

    /** Gives a first-words title to every transcribed note that won't get a written one (no service, or it failed). */
    suspend fun fillMissingTitles() {
        for (n in dao.notesWithoutTitle()) dao.setTitleIfMissing(n.id, autoTitle(n.transcript))
    }

    /** Once the whole note is transcribed, queue its title, summary and tags. */
    private suspend fun queueInsightsIfDone(id: String) {
        val note = dao.getNote(id) ?: return
        if (note.recordingState != RecordingState.RECORDED || note.transcriptionStatus != TranscriptionStatus.DONE) return
        if (note.insightsStatus != InsightsStatus.NONE || note.transcript.isBlank()) return
        if (!settings.current().summarize) return
        if (!summariesExpected()) {
            // Nothing can write it yet: park it until settings change, and title it now, offline.
            dao.setInsightsStatus(id, InsightsStatus.BLOCKED)
            fillMissingTitles()
            return
        }
        dao.setInsightsStatus(id, InsightsStatus.PENDING)
        insights.enqueue(now = true)
    }

    /** An empty name hands the title back to the app: a quick one now, a written one after the summary. */
    suspend fun rename(id: String, title: String) {
        val name = title.trim()
        if (name.isNotEmpty()) return dao.setTitle(id, name, isAuto = false)
        val note = dao.getNote(id) ?: return
        if (note.transcript.isNotBlank() && summariesExpected() && note.recordingState == RecordingState.RECORDED) {
            dao.setTitle(id, "", isAuto = true)
            summarizeAgain(id)
        } else {
            dao.setTitle(id, if (note.transcript.isBlank()) "" else autoTitle(note.transcript), isAuto = true)
        }
    }

    /** Writes the title, summary and tags again (after editing the transcript, or with another service). */
    suspend fun summarizeAgain(id: String) {
        val note = dao.getNote(id) ?: return
        if (note.transcript.isBlank() || note.recordingState != RecordingState.RECORDED) return
        dao.setInsightsStatus(id, InsightsStatus.PENDING)
        insights.enqueue(now = true)
    }

    /** Summaries were just turned on (or off). */
    suspend fun onSummarizeChanged(enabled: Boolean) {
        if (enabled) {
            if (dao.queueMissingInsights() > 0) insights.enqueue(now = true)
        } else {
            dao.moveInsights(InsightsStatus.PENDING, InsightsStatus.NONE)
            dao.moveInsights(InsightsStatus.BLOCKED, InsightsStatus.NONE)
            fillMissingTitles()
        }
    }

    /**
     * Saves an edited transcript. Any change gets a fresh summary: even a two-word edit ("do not")
     * can change what the note means, and a stale summary is worse than a reworded one.
     */
    suspend fun editTranscript(id: String, text: String) {
        val before = dao.getNote(id) ?: return
        dao.setTranscript(id, text, edited = true)
        when {
            text.isBlank() -> dao.clearInsights(id, InsightsStatus.NONE)
            text != before.transcript && before.insightsStatus != InsightsStatus.NONE -> summarizeAgain(id)
        }
    }

    /** Retries failed chunks (or starts transcription for a note that was never transcribed). */
    suspend fun transcribe(id: String) {
        mutex.withLock {
            dao.resetFailedChunks(id)
            dao.setStatus(id, TranscriptionStatus.QUEUED, null)
        }
        scheduler.enqueue(now = true)
    }

    /** Throws away the transcript and transcribes everything again (e.g. with another provider). */
    suspend fun retranscribe(id: String) {
        mutex.withLock {
            dao.resetAllChunks(id)
            dao.setTranscript(id, "", edited = false)
            dao.clearInsights(id, InsightsStatus.NONE)
            dao.setStatus(id, TranscriptionStatus.QUEUED, null)
        }
        scheduler.enqueue(now = true)
    }

    /** Retry everything that's waiting on sign-in or setup, after the user fixed it. */
    suspend fun retryBlocked() {
        var any = false
        mutex.withLock {
            for (n in dao.blockedNotes()) {
                dao.resetFailedChunks(n.id)
                dao.setStatus(n.id, TranscriptionStatus.QUEUED, null)
                any = true
            }
        }
        if (any) scheduler.enqueue(now = true)
        if (dao.moveInsights(InsightsStatus.BLOCKED, InsightsStatus.PENDING) > 0) insights.enqueue(now = true)
    }

    suspend fun delete(id: String) {
        // Folder out of the way first: if the process dies next, the note can't return as an orphan.
        withContext(Dispatchers.IO) { files.trash(id) }
        mutex.withLock { dao.deleteNote(id) }
        withContext(Dispatchers.IO) { files.delete(id) }
    }

    companion object {
        private const val TAG = "NotesRepository"
        private const val MIN_CHUNK_MS = 700L

        fun autoTitle(transcript: String): String {
            val firstLine = transcript.trim().lineSequence().first()
            val sentenceEnd = Regex("[.!?。！？]").find(firstLine)?.range?.first
            var t = if (sentenceEnd != null && sentenceEnd in 12..80) firstLine.substring(0, sentenceEnd) else firstLine
            if (t.length > 60) {
                val cut = t.lastIndexOf(' ', 60).takeIf { it > 30 } ?: 60
                t = t.substring(0, cut).trimEnd(',', ';', ':', ' ') + "…"
            }
            return t.trim()
        }
    }
}
