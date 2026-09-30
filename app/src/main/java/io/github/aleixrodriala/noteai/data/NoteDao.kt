package io.github.aleixrodriala.noteai.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

private const val WITH_PROGRESS = """
    SELECT n.*,
        (SELECT COUNT(*) FROM chunks c WHERE c.noteId = n.id) AS chunksTotal,
        (SELECT COUNT(*) FROM chunks c WHERE c.noteId = n.id AND c.status = 'DONE') AS chunksDone
    FROM notes n
"""

@Dao
interface NoteDao {
    @Query("$WITH_PROGRESS ORDER BY n.createdAt DESC")
    fun observeNotes(): Flow<List<NoteWithProgress>>

    @Query(
        """$WITH_PROGRESS WHERE n.title LIKE :pattern OR n.summary LIKE :pattern OR n.tags LIKE :pattern
            OR n.transcript LIKE :pattern ORDER BY n.createdAt DESC"""
    )
    fun searchNotes(pattern: String): Flow<List<NoteWithProgress>>

    /** Notes tagged exactly [tag] that also match [pattern] ("%" matches them all). */
    @Query(
        """$WITH_PROGRESS WHERE (',' || n.tags || ',') LIKE '%,' || :tag || ',%'
            AND (n.title LIKE :pattern OR n.summary LIKE :pattern OR n.transcript LIKE :pattern)
            ORDER BY n.createdAt DESC"""
    )
    fun notesWithTag(tag: String, pattern: String): Flow<List<NoteWithProgress>>

    @Query("SELECT tags FROM notes WHERE tags != ''")
    fun observeTagStrings(): Flow<List<String>>

    @Query("SELECT tags FROM notes WHERE tags != ''")
    suspend fun tagStrings(): List<String>

    @Query("$WITH_PROGRESS WHERE n.id = :id")
    fun observeNote(id: String): Flow<NoteWithProgress?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNote(id: String): Note?

    @Query("SELECT * FROM notes WHERE recordingState = 'RECORDING'")
    suspend fun notesStillRecording(): List<Note>

    @Query("SELECT * FROM notes WHERE transcriptionStatus IN ('QUEUED', 'RUNNING')")
    suspend fun notesAwaitingTranscription(): List<Note>

    @Query("SELECT * FROM notes WHERE transcriptionStatus IN ('NEEDS_AUTH', 'NEEDS_SETUP')")
    suspend fun blockedNotes(): List<Note>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNote(note: Note)

    @Query("SELECT id FROM notes")
    suspend fun allIds(): List<String>

    /**
     * Puts everything committed so far on disk. In WAL mode Android doesn't fsync each commit, so a
     * power cut can undo recent ones; a checkpoint syncs the log and the database file.
     */
    @RawQuery
    suspend fun checkpoint(query: SupportSQLiteQuery = SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")): Int

    @Update
    suspend fun updateNote(note: Note)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    @Query("UPDATE notes SET durationMs = :durationMs, sizeBytes = :sizeBytes, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: String, durationMs: Long, sizeBytes: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET transcriptionStatus = :status, transcriptionError = :error, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: TranscriptionStatus, error: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET title = :title, titleIsAuto = :isAuto, updatedAt = :now WHERE id = :id")
    suspend fun setTitle(id: String, title: String, isAuto: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET transcript = :transcript, transcriptEdited = :edited, updatedAt = :now WHERE id = :id")
    suspend fun setTranscript(id: String, transcript: String, edited: Boolean, now: Long = System.currentTimeMillis())

    // --- title, summary and tags ---

    @Query(
        """SELECT * FROM notes WHERE insightsStatus = 'PENDING' AND transcriptionStatus = 'DONE'
            AND recordingState = 'RECORDED' ORDER BY createdAt DESC"""
    )
    suspend fun notesAwaitingInsights(): List<Note>

    @Query("SELECT COUNT(*) FROM notes WHERE insightsStatus = 'PENDING'")
    suspend fun countAwaitingInsights(): Int

    /** A fresh request or reset: starts a new generation, so older requests' outcomes are ignored. */
    @Query("UPDATE notes SET insightsStatus = :status, insightsAttempts = 0, insightsGen = insightsGen + 1 WHERE id = :id")
    suspend fun setInsightsStatus(id: String, status: InsightsStatus)

    /**
     * Records the outcome of a summary request, unless the note moved on meanwhile: a newer request
     * (generation), a reset, or an edited transcript.
     */
    @Query(
        """UPDATE notes SET insightsStatus = :status, insightsAttempts = :attempts
            WHERE id = :id AND insightsStatus = 'PENDING' AND insightsGen = :gen AND transcript = :transcript"""
    )
    suspend fun setInsightsOutcome(id: String, gen: Int, transcript: String, status: InsightsStatus, attempts: Int): Int

    @Query("UPDATE notes SET insightsStatus = :to, insightsAttempts = 0, insightsGen = insightsGen + 1 WHERE insightsStatus = :from")
    suspend fun moveInsights(from: InsightsStatus, to: InsightsStatus): Int

    /** Transcribed notes that never got a summary (e.g. summaries were off at the time). */
    @Query(
        """UPDATE notes SET insightsStatus = 'PENDING', insightsAttempts = 0, insightsGen = insightsGen + 1
            WHERE insightsStatus IN ('NONE', 'FAILED', 'BLOCKED') AND transcriptionStatus = 'DONE'
            AND recordingState = 'RECORDED' AND transcript != '' AND summary = '' AND tags = ''"""
    )
    suspend fun queueMissingInsights(): Int

    /**
     * Saves a finished summary, only if the note still waits for one about [transcript]. The title only
     * replaces an automatic one, never a name the user gave. Returns 0 when the result is stale.
     */
    @Query(
        """UPDATE notes SET summary = :summary, tags = :tags, insightsStatus = 'DONE', insightsAttempts = 0,
            title = CASE WHEN titleIsAuto = 1 AND :title != '' THEN :title ELSE title END, updatedAt = :now
            WHERE id = :id AND insightsStatus = 'PENDING' AND insightsGen = :gen AND transcript = :transcript"""
    )
    suspend fun applyInsights(
        id: String, gen: Int, transcript: String, title: String, summary: String, tags: String, now: Long = System.currentTimeMillis(),
    ): Int

    /** Transcribed notes still without a title, and no written one on the way. */
    @Query(
        """SELECT * FROM notes WHERE title = '' AND titleIsAuto = 1 AND transcript != ''
            AND insightsStatus != 'PENDING'"""
    )
    suspend fun notesWithoutTitle(): List<Note>

    @Query("UPDATE notes SET title = :title WHERE id = :id AND titleIsAuto = 1 AND title = ''")
    suspend fun setTitleIfMissing(id: String, title: String)

    @Query("UPDATE notes SET summary = '', tags = '', insightsStatus = :status, insightsAttempts = 0, insightsGen = insightsGen + 1 WHERE id = :id")
    suspend fun clearInsights(id: String, status: InsightsStatus)

    // --- chunks ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChunks(chunks: List<Chunk>)

    @Update
    suspend fun updateChunk(chunk: Chunk)

    @Query("SELECT * FROM chunks WHERE noteId = :noteId ORDER BY idx")
    suspend fun chunksFor(noteId: String): List<Chunk>

    @Query("SELECT * FROM chunks WHERE noteId = :noteId ORDER BY idx DESC LIMIT 1")
    suspend fun lastChunk(noteId: String): Chunk?

    @Query(
        """
        SELECT c.* FROM chunks c JOIN notes n ON n.id = c.noteId
        WHERE c.status = 'PENDING' AND n.transcriptionStatus IN ('QUEUED', 'RUNNING')
        ORDER BY n.createdAt ASC, c.idx ASC
        """
    )
    suspend fun pendingChunks(): List<Chunk>

    @Query("UPDATE chunks SET status = 'PENDING', attempts = 0, lastError = NULL WHERE noteId = :noteId AND status = 'FAILED'")
    suspend fun resetFailedChunks(noteId: String)

    @Query("UPDATE chunks SET status = 'PENDING', attempts = 0, lastError = NULL, text = '', provider = NULL WHERE noteId = :noteId")
    suspend fun resetAllChunks(noteId: String)
}
