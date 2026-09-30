package io.github.aleixrodriala.noteai.data

import android.content.Context
import java.io.File

/**
 * Where a note's audio lives: `files/notes/<id>/`.
 *  - `recording.aac`: the crash-safe ADTS stream written while recording (deleted after finalizing)
 *  - `audio.m4a`: the final file for playback, sharing and re-transcription
 *  - `levels.bin`: one loudness byte per 100 ms, for the waveform and chunk boundaries
 */
class NoteFiles(private val context: Context) {
    private val root = File(context.filesDir, "notes")

    fun dir(id: String) = File(root, id).apply { mkdirs() }
    fun adts(id: String) = File(dir(id), "recording.aac")
    fun m4a(id: String) = File(dir(id), "audio.m4a")
    fun levels(id: String) = File(dir(id), "levels.bin")

    fun chunkFile(id: String, idx: Int): File =
        File(context.cacheDir, "chunks").apply { mkdirs() }.resolve("$id-$idx.m4a")

    /** Every note folder on disk, whether or not the database knows it. */
    fun noteDirs(): List<File> {
        // Leftovers of a delete that was cut short.
        root.listFiles { f -> f.name.endsWith(TRASHED) }?.forEach { it.deleteRecursively() }
        return root.listFiles { f -> f.isDirectory }?.toList().orEmpty()
    }

    fun readLevels(id: String): ByteArray = levels(id).takeIf { it.exists() }?.readBytes() ?: ByteArray(0)

    /** The audio file playback should use right now. */
    fun playable(id: String): File? = m4a(id).takeIf { it.exists() }

    fun sizeOf(id: String): Long = File(root, id).listFiles()?.sumOf { it.length() } ?: 0

    /**
     * Renames the folder out of the way first, so a note deleted just before the process died
     * can't come back as an orphan (see [noteDirs]).
     */
    fun trash(id: String) {
        val dir = File(root, id)
        if (dir.exists()) dir.renameTo(File(root, "$id$TRASHED"))
    }

    fun delete(id: String) {
        trash(id)
        File(root, "$id$TRASHED").deleteRecursively()
        File(root, id).deleteRecursively()
        File(context.cacheDir, "chunks").listFiles { f -> f.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    fun totalBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val TRASHED = ".deleted"
    }
}
