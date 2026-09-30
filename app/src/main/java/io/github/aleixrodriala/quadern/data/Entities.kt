package io.github.aleixrodriala.quadern.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class RecordingState {
    /** Audio is being captured right now (or the app died while it was). */
    RECORDING,

    /** Capture ended and the audio file is final. */
    RECORDED,
}

enum class TranscriptionStatus {
    /** Nothing requested yet (auto-transcribe off). */
    IDLE,

    /** Waiting for the worker (or for network). */
    QUEUED,
    RUNNING,
    DONE,

    /** A chunk failed permanently or ran out of attempts; the user can retry. */
    FAILED,

    /** The ChatGPT session expired and could not be refreshed. */
    NEEDS_AUTH,

    /** No usable provider: not signed in, missing API key, or model not downloaded. */
    NEEDS_SETUP,
}

enum class ChunkStatus { PENDING, DONE, FAILED }

/** Where a note's title, summary and tags are (they're written from the finished transcript). */
enum class InsightsStatus {
    /** Not requested: summaries are off, or the transcript isn't finished. */
    NONE,

    /** Waiting for (or being written by) the summary worker. */
    PENDING,
    DONE,

    /** Gave up after repeated failures; the user can ask again. */
    FAILED,

    /** No service can write summaries right now (not signed in, no key); retried when that changes. */
    BLOCKED,
}

@Entity(tableName = "notes", indices = [Index("createdAt")])
data class Note(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val title: String = "",
    /** False once the user renamed the note; auto titles never overwrite a manual one. */
    val titleIsAuto: Boolean = true,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val recordingState: RecordingState = RecordingState.RECORDING,
    /** Capture was cut short by a crash, reboot or the system killing the app. */
    val interrupted: Boolean = false,
    val transcript: String = "",
    /** The user edited the transcript; re-transcribing asks before replacing it. */
    val transcriptEdited: Boolean = false,
    val transcriptionStatus: TranscriptionStatus = TranscriptionStatus.IDLE,
    val transcriptionError: String? = null,
    val updatedAt: Long = createdAt,
    /** One or two short paragraphs about what the note says. Empty for very short notes. */
    @ColumnInfo(defaultValue = "") val summary: String = "",
    /** Lowercase topic tags joined with commas, e.g. "work,family". */
    @ColumnInfo(defaultValue = "") val tags: String = "",
    @ColumnInfo(defaultValue = "NONE") val insightsStatus: InsightsStatus = InsightsStatus.NONE,
    @ColumnInfo(defaultValue = "0") val insightsAttempts: Int = 0,
    /**
     * Bumped every time a summary is (re)requested or reset. A request only writes its outcome if the
     * generation it started with is still current, so a slow old request can't overwrite a fresh one.
     */
    @ColumnInfo(defaultValue = "0") val insightsGen: Int = 0,
)

/**
 * A slice of a note's audio that is transcribed on its own. Long recordings are split at quiet
 * moments into chunks of a few minutes, because ChatGPT's endpoint silently truncates uploads longer
 * than ~10 minutes, and because small uploads retry cheaply on flaky networks.
 *
 * Boundaries are in AAC frames (1024 samples each), which map exactly to time.
 */
@Entity(
    tableName = "chunks",
    primaryKeys = ["noteId", "idx"],
    foreignKeys = [
        ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class Chunk(
    val noteId: String,
    val idx: Int,
    val startFrame: Long,
    val endFrame: Long,
    val status: ChunkStatus = ChunkStatus.PENDING,
    val text: String = "",
    val attempts: Int = 0,
    val lastError: String? = null,
    val provider: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A note plus chunk progress, for the list and detail screens. */
data class NoteWithProgress(
    val id: String,
    val createdAt: Long,
    val title: String,
    val titleIsAuto: Boolean,
    val durationMs: Long,
    val sizeBytes: Long,
    val recordingState: RecordingState,
    val interrupted: Boolean,
    val transcript: String,
    val transcriptEdited: Boolean,
    val transcriptionStatus: TranscriptionStatus,
    val transcriptionError: String?,
    val updatedAt: Long,
    val summary: String,
    val tags: String,
    val insightsStatus: InsightsStatus,
    val insightsAttempts: Int,
    val insightsGen: Int,
    @ColumnInfo(name = "chunksTotal") val chunksTotal: Int,
    @ColumnInfo(name = "chunksDone") val chunksDone: Int,
) {
    val tagList: List<String> get() = splitTags(tags)
}

fun splitTags(tags: String): List<String> = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
