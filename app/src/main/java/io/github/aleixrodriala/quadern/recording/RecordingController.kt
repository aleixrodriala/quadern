package io.github.aleixrodriala.quadern.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.aleixrodriala.quadern.audio.Microphones
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI sees of the recorder, and how it asks the [RecordingService] to act. */
class RecordingController(private val context: Context) {
    data class Live(
        val noteId: String,
        val elapsedMs: Long,
        val paused: Boolean,
        /** Most recent levels (0..255), oldest first, for the live waveform. */
        val levels: List<Int>,
        /** How many levels the recording has so far; the last of [levels] is number levelCount - 1. */
        val levelCount: Int = levels.size,
        /** Another app (e.g. a phone call) took the microphone; we're recording silence. */
        val silenced: Boolean = false,
        /** The microphone being recorded from, once Android says. */
        val mic: Microphones.Mic? = null,
        /** A Bluetooth headset getting ready; [mic] records until it is. */
        val micConnecting: Microphones.Mic? = null,
        /** Was picked but couldn't be used, or went away; recording goes on with [mic]. */
        val micUnavailable: Microphones.Mic? = null,
    )

    sealed interface Event {
        data class Finished(val noteId: String) : Event
        data class Failed(val message: String, val noteId: String?) : Event
    }

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    private val _finishing = MutableStateFlow(false)
    /** Stop or discard was requested and the service is still saving; the result comes as an [Event] (or none, when discarded). */
    val finishing: StateFlow<Boolean> = _finishing.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** Notes created by this process that may still be recording (their service is starting or running). */
    private val ownedNoteIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** True if [noteId] belongs to a recorder in this process, so recovery must leave it alone. */
    fun isLive(noteId: String): Boolean = noteId in ownedNoteIds

    internal fun own(noteId: String) { ownedNoteIds += noteId }
    internal fun release(noteId: String) { ownedNoteIds -= noteId }

    /** Must be called while the app is in the foreground (Android only lets a visible app open the mic). */
    fun start() {
        if (_live.value != null || _starting.value) return
        _starting.value = true
        try {
            ContextCompat.startForegroundService(context, intent(RecordingService.ACTION_START))
        } catch (e: Exception) {
            // Android refuses a microphone service when the app isn't in the foreground.
            _starting.value = false
            _events.tryEmit(Event.Failed("Couldn't start recording. Open Quadern and try again.", null))
        }
    }

    fun pause() = send(RecordingService.ACTION_PAUSE)
    fun resume() = send(RecordingService.ACTION_RESUME)
    fun stop() = send(RecordingService.ACTION_STOP)
    fun discard() = send(RecordingService.ACTION_DISCARD)

    /** Records from now on with the microphone [key] (see Microphones.Mic), and next time too. */
    fun selectMic(key: String) = send(RecordingService.ACTION_SET_MIC) { putExtra(RecordingService.EXTRA_MIC, key) }

    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        if (_live.value == null) return
        context.startService(intent(action).apply(extras))
    }

    private fun intent(action: String) = Intent(context, RecordingService::class.java).setAction(action)

    // --- called by the service ---

    internal fun publish(live: Live?) {
        _live.value = live
        if (live != null) _starting.value = false
    }

    internal fun setFinishing(value: Boolean) {
        _finishing.value = value
    }

    internal fun startFailed() {
        _starting.value = false
    }

    internal fun emit(event: Event) {
        _starting.value = false
        _events.tryEmit(event)
    }
}
