package io.github.aleixrodriala.noteai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.github.aleixrodriala.noteai.AppContainer
import io.github.aleixrodriala.noteai.NoteApp
import io.github.aleixrodriala.noteai.data.InsightsStatus
import io.github.aleixrodriala.noteai.data.NoteWithProgress
import io.github.aleixrodriala.noteai.data.RecordingState
import io.github.aleixrodriala.noteai.data.TranscriptionStatus

enum class Tone { Neutral, Busy, Recording, Problem }

data class StatusInfo(val text: String, val tone: Tone, val actionable: Boolean = false) {
    /** The status without its numbers: "Transcribing · 2/3" and "3/3" are the same step, updated in place. */
    val kind: String get() = text.replace(Regex("\\d+"), "#")
}

/** One line describing where a note is in its life, or null when it's simply done. */
fun statusOf(n: NoteWithProgress, isLive: Boolean, importProgress: Float? = null): StatusInfo? {
    if (n.recordingState == RecordingState.RECORDING) {
        return when {
            importProgress != null ->
                StatusInfo(if (importProgress > 0f) "Importing audio · ${(importProgress * 100).toInt()}%" else "Importing audio", Tone.Busy)
            isLive -> StatusInfo("Recording", Tone.Recording)
            else -> StatusInfo("Saving recording…", Tone.Busy)
        }
    }
    val progress = if (n.chunksTotal > 1) " · ${n.chunksDone}/${n.chunksTotal}" else ""
    return when (n.transcriptionStatus) {
        TranscriptionStatus.DONE -> null
        TranscriptionStatus.IDLE -> StatusInfo("Not transcribed · Tap to transcribe", Tone.Neutral, actionable = true)
        TranscriptionStatus.QUEUED ->
            if (n.transcriptionError != null) StatusInfo("Will retry · ${n.transcriptionError}", Tone.Problem)
            else StatusInfo("Waiting to transcribe$progress", Tone.Busy)
        TranscriptionStatus.RUNNING -> StatusInfo("Transcribing$progress", Tone.Busy)
        TranscriptionStatus.FAILED -> StatusInfo("Transcription failed · Tap to retry", Tone.Problem, actionable = true)
        TranscriptionStatus.NEEDS_AUTH -> StatusInfo("Sign in to ChatGPT again to transcribe", Tone.Problem, actionable = true)
        TranscriptionStatus.NEEDS_SETUP -> StatusInfo(n.transcriptionError ?: "Set up transcription in Settings", Tone.Problem, actionable = true)
    }
}

/** The step after transcription, for the list: only while a summary is on its way. */
fun insightsStatusOf(n: NoteWithProgress): StatusInfo? = when {
    n.insightsStatus != InsightsStatus.PENDING || n.transcriptionStatus != TranscriptionStatus.DONE -> null
    n.insightsAttempts > 0 -> StatusInfo("Summary will retry", Tone.Neutral)
    else -> StatusInfo("Writing summary", Tone.Busy)
}

/** The note's title, or a placeholder (shown muted) until one is written. */
fun displayTitle(n: NoteWithProgress, isImporting: Boolean = false): String = n.title.ifBlank {
    when {
        isImporting -> "Shared audio"
        n.recordingState == RecordingState.RECORDING -> "New recording"
        n.transcriptionStatus == TranscriptionStatus.DONE && n.transcript.isBlank() -> "Untitled note"
        else -> "Voice note"
    }
}

val LocalContainer = compositionLocalOf<AppContainer> { error("no container") }

@Composable
fun containerFromContext(): AppContainer = (LocalContext.current.applicationContext as NoteApp).container
