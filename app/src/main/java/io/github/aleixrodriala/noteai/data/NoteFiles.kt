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

    fun readLevels(id: String): ByteArray = levels(id).takeIf { it.exists() }?.readBytes() ?: ByteArray(0)

    /** The audio file playback should use right now. */
    fun playable(id: String): File? = m4a(id).takeIf { it.exists() }

    fun sizeOf(id: String): Long = File(root, id).listFiles()?.sumOf { it.length() } ?: 0

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        File(context.cacheDir, "chunks").listFiles { f -> f.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    fun totalBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
