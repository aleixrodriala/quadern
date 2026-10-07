package io.github.aleixrodriala.quadern.recording

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.aleixrodriala.quadern.MainActivity
import io.github.aleixrodriala.quadern.NoteApp
import io.github.aleixrodriala.quadern.R
import io.github.aleixrodriala.quadern.audio.AudioSpec
import io.github.aleixrodriala.quadern.audio.Microphones
import io.github.aleixrodriala.quadern.audio.RecorderEngine
import io.github.aleixrodriala.quadern.audio.ScoLink
import io.github.aleixrodriala.quadern.util.Notifications
import io.github.aleixrodriala.quadern.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The foreground service that owns the microphone. Being a `microphone` foreground service with an
 * ongoing notification is what lets recording continue with the screen off or the app swiped away;
 * a partial wake lock keeps the CPU awake to drain the mic buffer.
 *
 * If Android kills it anyway, the audio is already on disk: the restart (START_STICKY) or the next
 * app launch finalizes it as an "interrupted" note and transcribes it.
 */
class RecordingService : Service() {
    private val container by lazy { (application as NoteApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycle = Mutex()
    private val sco by lazy { ScoLink(this) }

    private var session: Session? = null
    private var audioFocus: AudioFocusRequest? = null

    private class Session(val noteId: String, val engine: RecorderEngine) {
        val levels = LevelBuffer()
        @Volatile var silenced = false
        var tickJob: Job? = null
        var wakeLock: PowerManager.WakeLock? = null

        // What's being recorded from, as Android says, and how a picked microphone is doing.
        @Volatile var mic: Microphones.Mic? = null
        @Volatile var micConnecting: Microphones.Mic? = null
        @Volatile var micUnavailable: Microphones.Mic? = null

        // Changed only under the lifecycle lock.
        /** The microphone picked for this note, while it's connected; null leaves it to Android. */
        var chosen: AudioDeviceInfo? = null
        /** The Bluetooth headset whose call link was asked for, and whether it's up yet. */
        var scoFor: AudioDeviceInfo? = null
        var scoUp = false
    }

    /** Growable byte buffer the audio thread appends to and the service snapshots. */
    private class LevelBuffer {
        private var data = ByteArray(36_000)
        var size = 0
            private set

        @Synchronized fun add(level: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = level.toByte()
        }

        @Synchronized fun snapshot(): Pair<ByteArray, Int> = data.copyOf(size) to size

        /** The last [n] levels and the total count, read together. */
        @Synchronized fun tail(n: Int): Pair<List<Int>, Int> =
            (maxOf(0, size - n) until size).map { data[it].toInt() and 0xFF } to size
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Enter the foreground immediately: Android requires it within seconds of
                // startForegroundService(), and it's what grants background microphone access.
                if (!enterForeground()) {
                    container.recording.emit(RecordingController.Event.Failed(getString(R.string.error_mic_start), null))
                    stopSelf()
                    return START_NOT_STICKY
                }
                scope.launch { begin() }
            }
            ACTION_PAUSE -> scope.launch { setPaused(true) }
            ACTION_RESUME -> scope.launch { setPaused(false) }
            ACTION_STOP -> scope.launch { finish(discard = false) }
            ACTION_DISCARD -> scope.launch { finish(discard = true) }
            ACTION_SET_MIC -> intent.getStringExtra(EXTRA_MIC)?.let { key -> scope.launch { setMic(key) } }
            null -> {
                // Restarted by the system after being killed mid-recording. We can't reopen the mic
                // from the background, but everything recorded so far is on disk: save it.
                Log.w(TAG, "Restarted after process death; recovering recordings")
                scope.launch {
                    val recovered = container.repository.recoverInterrupted(container.recording::isLive)
                    if (recovered > 0) Notifications.showInterrupted(this@RecordingService)
                    if (session == null) stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, Notifications.ID_RECORDING, buildNotification(0, paused = false, silenced = false), type)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Couldn't start foreground service", e)
        false
    }

    private suspend fun begin() = lifecycle.withLock {
        if (session != null) return@withLock
        // Claim the id before the row exists so crash recovery never mistakes it for an orphan.
        val id = java.util.UUID.randomUUID().toString()
        container.recording.own(id)
        try {
            beginLocked(id)
        } catch (e: Throwable) {
            // Cancelled or failed before the session existed: don't strand the row or the UI.
            Log.e(TAG, "Couldn't start recording", e)
            withContext(NonCancellable) {
                session = null
                unregisterAudioWatchers()
                releaseAudioFocus()
                sco.disconnect()
                container.recording.release(id)
                runCatching { container.repository.finalizeRecording(id, interrupted = true) }
                container.recording.emit(RecordingController.Event.Failed(getString(R.string.error_mic_start), null))
                ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    private suspend fun beginLocked(id: String) {
        val repo = container.repository
        val note = repo.createRecording(id)
        val files = container.files
        lateinit var s: Session
        val engine = RecorderEngine(files.adts(note.id), files.levels(note.id), object : RecorderEngine.Listener {
            override fun onLevel(level: Int) {
                s.levels.add(level)
                publish(s)
            }

            override fun onFatalError(error: Throwable) {
                scope.launch { finish(discard = false, error = error) }
            }

            override fun onRouted(device: AudioDeviceInfo) {
                val mic = Microphones.describe(device) ?: return
                if (mic != s.mic) {
                    s.mic = mic
                    publish(s)
                }
            }

            override fun onDeviceFailed(device: AudioDeviceInfo) {
                scope.launch { micGone(s, device) }
            }
        })
        s = Session(note.id, engine)
        s.wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "quadern:recording")
            .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
        session = s
        registerAudioWatchers()
        holdAudioFocus()
        // The microphone picked last time, if it's connected.
        s.chosen = container.settings.current().microphone?.let(container.microphones::find)
        applyMic(s)
        engine.start()
        publish(s)
        s.tickJob = scope.launch { tick(s) }
    }

    private fun publish(s: Session) {
        val (levels, levelCount) = s.levels.tail(LIVE_LEVELS)
        container.recording.publish(
            RecordingController.Live(
                noteId = s.noteId,
                elapsedMs = AudioSpec.frameToMs(s.engine.framesWritten.get()),
                paused = s.engine.isPaused,
                levels = levels,
                levelCount = levelCount,
                silenced = s.silenced,
                mic = s.mic,
                micConnecting = s.micConnecting,
                micUnavailable = s.micUnavailable,
            )
        )
    }

    private suspend fun tick(s: Session) {
        var lastPaused: Boolean? = null
        var lastSilenced = false
        var seconds = 0L
        while (scope.isActive && session === s) {
            delay(1_000)
            seconds++
            val paused = s.engine.isPaused
            if (paused != lastPaused || s.silenced != lastSilenced) {
                lastPaused = paused
                lastSilenced = s.silenced
                updateNotification(s)
                publish(s)
            }
            if (seconds % 15 == 0L) {
                val frames = s.engine.framesWritten.get()
                container.repository.updateRecordingProgress(s.noteId, frames)
                val (levels, count) = s.levels.snapshot()
                runCatching { container.repository.sealChunks(s.noteId, levels, count, frames, final = false) }
                    .onFailure { Log.w(TAG, "Couldn't seal chunks", it) }
                if (filesDir.usableSpace < MIN_FREE_BYTES) {
                    Log.w(TAG, "Storage almost full, stopping")
                    finish(discard = false, error = IllegalStateException(getString(R.string.error_storage_full)))
                    return
                }
            }
        }
    }

    private suspend fun setPaused(paused: Boolean) = lifecycle.withLock {
        val s = session ?: return@withLock
        if (paused) s.engine.pause() else s.engine.resume()
        updateNotification(s)
        publish(s)
    }

    // --- which microphone ---

    private suspend fun setMic(key: String) {
        container.settings.setMicrophone(key)
        lifecycle.withLock {
            val s = session ?: return@withLock
            s.chosen = container.microphones.find(key)
            s.micUnavailable = null
            applyMic(s)
            publish(s)
        }
    }

    /**
     * Points the recorder at the chosen microphone. A classic Bluetooth headset first needs its call
     * link; the phone records until it's up, so no word waits on it.
     */
    private fun applyMic(s: Session) {
        val chosen = s.chosen
        val headset = chosen?.takeIf { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (headset != null && s.scoFor?.id != headset.id) {
            s.scoFor = headset
            s.scoUp = false
            sco.connect(headset) { up -> scope.launch { scoChanged(s, headset, up) } }
        }
        s.engine.setDevice(if (headset != null && !s.scoUp) null else chosen)
        if (headset == null && s.scoFor != null) {
            // The recorder is off the headset now: let it go back to music.
            s.scoFor = null
            s.scoUp = false
            sco.disconnect()
        }
        s.micConnecting = headset?.takeIf { !s.scoUp }?.let(Microphones::describe)
    }

    private suspend fun scoChanged(s: Session, headset: AudioDeviceInfo, up: Boolean) {
        // Didn't come up in time, or a call took it: recording a silent link would lose everything.
        if (!up) return micGone(s, headset)
        lifecycle.withLock {
            if (session !== s || s.scoFor?.id != headset.id) return@withLock
            s.scoUp = true
            applyMic(s)
            publish(s)
        }
    }

    /** [device] stopped working or went away: carry on with Android's choice, and say so. */
    private suspend fun micGone(s: Session, device: AudioDeviceInfo) = lifecycle.withLock {
        if (session !== s || s.chosen?.id != device.id) return@withLock
        s.chosen = null
        s.micUnavailable = Microphones.describe(device)
        applyMic(s)
        publish(s)
    }

    /** The microphone picked last time just connected, or came back: record with it. */
    private suspend fun micAdded(s: Session, added: List<AudioDeviceInfo>) {
        val key = container.settings.current().microphone ?: return
        lifecycle.withLock {
            if (session !== s || s.chosen != null) return@withLock
            s.chosen = Microphones.usable(added, Microphones::describe).firstOrNull { it.second.key == key }?.first ?: return@withLock
            s.micUnavailable = null
            applyMic(s)
            publish(s)
        }
    }

    private suspend fun finish(discard: Boolean, error: Throwable? = null) = withContext(NonCancellable) {
        lifecycle.withLock {
            val s = session ?: return@withLock
            session = null
            // The recorder screen keeps its last frame until the note is saved, instead of blanking.
            container.recording.setFinishing(true)
            try {
                s.tickJob?.cancel()
                val exited = withContext(Dispatchers.IO) { s.engine.stop() }
                s.wakeLock?.let { if (it.isHeld) it.release() }
                unregisterAudioWatchers()
                releaseAudioFocus()
                if (s.scoFor != null) sco.disconnect()
                container.recording.publish(null)
                val repo = container.repository
                if (!exited) {
                    // The audio thread is wedged inside the codec. Leave the file alone: finalizing now
                    // could delete it under a thread that's still writing. Recovery handles it on the
                    // next start (the note stays "recording" and unowned until then).
                    Log.e(TAG, "Recorder thread didn't stop; deferring finalize to recovery")
                    container.recording.release(s.noteId)
                    container.recording.emit(RecordingController.Event.Failed(getString(R.string.error_recording_failed), s.noteId))
                } else if (discard) {
                    repo.delete(s.noteId)
                    container.recording.release(s.noteId)
                } else {
                    runCatching { repo.finalizeRecording(s.noteId, interrupted = error != null) }
                        .onFailure { Log.e(TAG, "Finalize failed; recovery will retry", it) }
                    container.recording.release(s.noteId)
                    val event = if (error != null) {
                        RecordingController.Event.Failed(error.message ?: getString(R.string.error_recording_failed), s.noteId)
                    } else {
                        RecordingController.Event.Finished(s.noteId)
                    }
                    container.recording.emit(event)
                }
            } finally {
                container.recording.setFinishing(false)
            }
            ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // --- "another app took the microphone" detection ---

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val s = session ?: return
            // Our own capture is the one from this app; when a call or another recorder takes
            // priority Android keeps us running but feeds silence and flags us as silenced.
            val silenced = configs.any { it.isClientSilenced }
            if (silenced != s.silenced) {
                s.silenced = silenced
                publish(s)
            }
        }
    }

    // --- microphones coming and going ---

    private val deviceCallback = object : AudioDeviceCallback() {
        // Also called once on registration, with everything already connected.
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            val s = session ?: return
            scope.launch { micAdded(s, added.toList()) }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val s = session ?: return
            removed.forEach { scope.launch { micGone(s, it) } }
        }
    }

    private fun registerAudioWatchers() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching { audio.registerAudioRecordingCallback(recordingCallback, Handler(Looper.getMainLooper())) }
        runCatching { audio.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper())) }
    }

    private fun unregisterAudioWatchers() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching { audio.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { audio.unregisterAudioDeviceCallback(deviceCallback) }
    }

    // --- other audio ---

    /**
     * Music and other audio pause while a note records, through pauses too, and pick up again when it
     * ends: from the speaker they'd be recorded, and in a headset they'd talk over you.
     */
    private fun holdAudioFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        // Nothing to do when another app takes it back (a call, say): recording goes on regardless.
        runCatching { (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(request) }
        audioFocus = request
    }

    private fun releaseAudioFocus() {
        audioFocus?.let { runCatching { (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it) } }
        audioFocus = null
    }

    // --- notification ---

    private fun updateNotification(s: Session) {
        val n = buildNotification(AudioSpec.frameToMs(s.engine.framesWritten.get()), s.engine.isPaused, s.silenced)
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(Notifications.ID_RECORDING, n) }
    }

    private fun buildNotification(elapsedMs: Long, paused: Boolean, silenced: Boolean): Notification {
        fun action(icon: Int, label: Int, action: String) = NotificationCompat.Action(
            icon, getString(label),
            PendingIntent.getService(this, action.hashCode(), Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
        )
        val title = when {
            silenced -> getString(R.string.notification_mic_busy)
            paused -> getString(R.string.notification_paused)
            else -> getString(R.string.notification_recording)
        }
        return NotificationCompat.Builder(this, Notifications.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .apply {
                if (paused) {
                    setContentText(formatDuration(elapsedMs))
                } else {
                    setUsesChronometer(true)
                    setWhen(System.currentTimeMillis() - elapsedMs)
                    setShowWhen(true)
                }
            }
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openAppIntent(this, MainActivity.ROUTE_RECORD))
            .addAction(
                if (paused) action(R.drawable.ic_play, R.string.action_resume, ACTION_RESUME)
                else action(R.drawable.ic_pause, R.string.action_pause, ACTION_PAUSE)
            )
            .addAction(action(R.drawable.ic_stop, R.string.action_stop_save, ACTION_STOP))
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not stop a recording.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Destroyed while recording without a stop request (system pressure, user force-stop from
        // the task manager): close the file cleanly; recovery turns it into a note.
        session?.let { s ->
            session = null
            s.tickJob?.cancel()
            s.wakeLock?.let { if (it.isHeld) it.release() }
            unregisterAudioWatchers()
            releaseAudioFocus()
            if (s.scoFor != null) sco.disconnect()
            container.recording.publish(null)
            // Stop and finalize off the main thread; only touch the file once the recorder exited.
            container.appScope.launch(Dispatchers.IO) {
                if (s.engine.stop()) container.repository.finalizeRecording(s.noteId, interrupted = true)
                container.recording.release(s.noteId)
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.github.aleixrodriala.quadern.START"
        const val ACTION_PAUSE = "io.github.aleixrodriala.quadern.PAUSE"
        const val ACTION_RESUME = "io.github.aleixrodriala.quadern.RESUME"
        const val ACTION_STOP = "io.github.aleixrodriala.quadern.STOP"
        const val ACTION_DISCARD = "io.github.aleixrodriala.quadern.DISCARD"
        const val ACTION_SET_MIC = "io.github.aleixrodriala.quadern.SET_MIC"
        const val EXTRA_MIC = "mic"
        private const val TAG = "RecordingService"
        private const val LIVE_LEVELS = 120
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
    }
}
