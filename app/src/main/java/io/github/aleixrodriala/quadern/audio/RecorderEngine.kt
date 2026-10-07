package io.github.aleixrodriala.quadern.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Microphone → [AacAdtsSink] on one dedicated audio thread.
 *
 * Durability: every encoded frame goes straight to the append-only ADTS file, fsync'd every few
 * seconds, so a crash, a kill or even a power loss costs at most those few seconds. Pausing releases
 * the microphone (the privacy indicator goes away) and resumes into the same file with a continuous
 * timeline; switching to another microphone does the same, without the pause.
 */
class RecorderEngine(
    private val adtsFile: File,
    private val levelsFile: File,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the audio thread every 100 ms of captured audio. */
        fun onLevel(level: Int)

        /** Capture failed in a way that can't be recovered; the audio so far is safe on disk. */
        fun onFatalError(error: Throwable)

        /** The microphone Android is recording from, once known and whenever it changes. Any thread. */
        fun onRouted(device: AudioDeviceInfo) {}

        /** [device] couldn't be recorded from; recording goes on with Android's default microphone. */
        fun onDeviceFailed(device: AudioDeviceInfo) {}
    }

    private val stopRequested = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val lock = Object()
    private var worker: Thread? = null
    private val device = AtomicReference<AudioDeviceInfo?>(null)
    private val rerouted = AtomicBoolean(false)
    private val routing = AudioRouting.OnRoutingChangedListener { r -> r.routedDevice?.let(listener::onRouted) }

    /** Encoded frames on disk. */
    val framesWritten = AtomicLong(0)

    val isPaused: Boolean get() = paused.get()

    fun start() {
        check(worker == null) { "already started" }
        worker = thread(name = "recorder", priority = Thread.MAX_PRIORITY) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                loop()
            } catch (t: Throwable) {
                Log.e(TAG, "Recorder failed", t)
                listener.onFatalError(t)
            }
        }
    }

    fun pause() = paused.set(true)

    /**
     * Records from [device] from now on; null leaves the choice to Android. Takes effect within one
     * read while recording (no audio is lost but the few ms the switch takes), or on resume.
     */
    fun setDevice(device: AudioDeviceInfo?) {
        if (this.device.getAndSet(device)?.id != device?.id) rerouted.set(true)
    }

    fun resume() {
        paused.set(false)
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * Stops capture, drains the encoder and closes the files. Returns true once the audio thread has
     * exited; false if it's still stuck after [timeoutMs] (the file must not be touched then).
     */
    fun stop(timeoutMs: Long = 30_000): Boolean {
        stopRequested.set(true)
        synchronized(lock) { lock.notifyAll() }
        val w = worker ?: return true
        w.join(timeoutMs)
        return !w.isAlive
    }

    /** The chosen microphone, or Android's default if that one won't record. */
    private fun openMic(): AudioRecord {
        val wanted = device.get() ?: return openMic(null)
        return try {
            openMic(wanted)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't record from ${wanted.productName}, using the default microphone", e)
            dropDevice(wanted)
            openMic(null)
        }
    }

    @SuppressLint("MissingPermission") // checked by the UI before the service starts
    private fun openMic(preferred: AudioDeviceInfo?): AudioRecord {
        val min = AudioRecord.getMinBufferSize(AudioSpec.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // ~1 s of buffering absorbs scheduling hiccups when the screen is off.
        val size = maxOf(min * 4, AudioSpec.SAMPLE_RATE * 2)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, AudioSpec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone unavailable")
        }
        try {
            if (preferred != null) {
                check(record.setPreferredDevice(preferred)) { "Not a microphone" }
            }
            record.addOnRoutingChangedListener(routing, null)
            record.startRecording()
            if (preferred != null) {
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone didn't start" }
            }
        } catch (e: Exception) {
            close(record)
            throw e
        }
        record.routedDevice?.let(listener::onRouted)
        return record
    }

    private fun close(record: AudioRecord) {
        record.removeOnRoutingChangedListener(routing)
        runCatching { record.stop() }
        record.release()
    }

    /** Stops asking for [failed], unless something else was asked for meanwhile. */
    private fun dropDevice(failed: AudioDeviceInfo) {
        if (device.compareAndSet(failed, null)) listener.onDeviceFailed(failed)
    }

    private fun loop() {
        var record: AudioRecord? = null
        val pcm = ShortArray(AudioSpec.SAMPLES_PER_FRAME * 2)
        val sink = AacAdtsSink(adtsFile, levelsFile, onLevel = listener::onLevel)
        sink.use {
            try {
                while (!stopRequested.get()) {
                    if (paused.get()) {
                        record?.let(::close)
                        record = null
                        sink.sync()
                        synchronized(lock) { while (paused.get() && !stopRequested.get()) lock.wait(500) }
                        continue
                    }
                    if (rerouted.getAndSet(false)) {
                        record?.let(::close)
                        record = null
                    }
                    val rec = record ?: openMic().also { record = it }
                    val n = rec.read(pcm, 0, pcm.size)
                    val chosen: AudioDeviceInfo? = rec.preferredDevice
                    when {
                        n == AudioRecord.ERROR_DEAD_OBJECT -> {
                            // The audio server restarted (it happens); reopen the mic and carry on.
                            Log.w(TAG, "AudioRecord died, reopening")
                            runCatching { close(rec) }
                            record = null
                        }
                        n < 0 && chosen != null -> {
                            // A headset that stopped working mustn't end the note: carry on with the phone.
                            Log.w(TAG, "AudioRecord.read failed on ${chosen.productName}: $n")
                            runCatching { close(rec) }
                            record = null
                            dropDevice(chosen)
                        }
                        n < 0 -> throw IllegalStateException("AudioRecord.read failed: $n")
                        n > 0 -> {
                            sink.write(pcm, n)
                            framesWritten.set(sink.framesWritten.get())
                            sink.syncIfDue()
                        }
                    }
                }
            } finally {
                record?.let(::close)
            }
        }
        // close() drained the encoder's last frames.
        framesWritten.set(sink.framesWritten.get())
    }

    private companion object {
        const val TAG = "RecorderEngine"
    }
}
