package io.github.aleixrodriala.quadern.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

/**
 * A classic Bluetooth headset's microphone only works over the link phone calls use (SCO). It takes
 * a second or two to come up, and while it's on the headset is in call mode: whatever it plays
 * sounds like a call.
 *
 * [connect] asks for it and reports when it comes up, or when it goes down: it didn't come up in
 * time, a phone call took it, the headset went away. Reports come on the main thread and stop with
 * [disconnect], which the caller must call to let the headset go, also after a report of down.
 */
class ScoLink(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Any()

    /** Bumped by every connect and disconnect, so reports meant for an earlier request are dropped. */
    private var request = 0
    private var onChange: ((Boolean) -> Unit)? = null
    private var up = false
    private var requested = false
    /** An AudioManager.OnCommunicationDeviceChangedListener (Android 12+). */
    private var deviceListener: Any? = null
    private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun connect(input: AudioDeviceInfo, onChange: (up: Boolean) -> Unit) {
        disconnect()
        val id = request
        this.onChange = onChange
        main.postDelayed({ update(id, false, failed = true) }, timeout, CONNECT_TIMEOUT_MS)
        val asked = runCatching { if (Build.VERSION.SDK_INT >= 31) connectS(input, id) else connectLegacy(id) }.getOrDefault(false)
        if (!asked) main.post { update(id, false, failed = true) }
    }

    @Synchronized
    fun disconnect() {
        request++
        onChange = null
        up = false
        main.removeCallbacksAndMessages(timeout)
        runCatching { if (Build.VERSION.SDK_INT >= 31) disconnectS() else disconnectLegacy() }
        requested = false
    }

    /** [nowUp] is how the link stands; [failed] means it won't come up, rather than not yet. */
    @Synchronized
    private fun update(id: Int, nowUp: Boolean, failed: Boolean) {
        if (id != request) return
        val report = onChange ?: return
        if (nowUp) {
            main.removeCallbacksAndMessages(timeout)
            if (!up) {
                up = true
                report(true)
            }
        } else if (up || failed) {
            // Said once; the caller lets go of the headset from here.
            main.removeCallbacksAndMessages(timeout)
            up = false
            onChange = null
            report(false)
        }
    }

    @RequiresApi(31)
    private fun connectS(input: AudioDeviceInfo, id: Int): Boolean {
        // Asked for by the headset's speaker side; its microphone comes with it.
        val headsets = audio.availableCommunicationDevices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val headset = headsets.firstOrNull { it.address == input.address } ?: headsets.firstOrNull() ?: return false
        // Other devices are announced on the way up; only Bluetooth itself means it's there.
        val listener = AudioManager.OnCommunicationDeviceChangedListener { d ->
            update(id, d?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO, failed = false)
        }
        audio.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
        deviceListener = listener
        if (!audio.setCommunicationDevice(headset)) return false
        requested = true
        // Already up (another app is using it): nothing will be announced.
        if (audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) main.post { update(id, true, failed = false) }
        return true
    }

    @RequiresApi(31)
    private fun disconnectS() {
        (deviceListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let(audio::removeOnCommunicationDeviceChangedListener)
        deviceListener = null
        if (requested) audio.clearCommunicationDevice()
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(id: Int): Boolean {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> update(id, true, failed = false)
                    // Registering replays how things stand; only a change after that is news.
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> if (!isInitialStickyBroadcast) update(id, false, failed = true)
                    AudioManager.SCO_AUDIO_STATE_ERROR -> update(id, false, failed = true)
                }
            }
        }
        // Only the system sends it, on Android 11 and older (where receivers take no export flag).
        context.registerReceiver(r, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        receiver = r
        audio.startBluetoothSco()
        requested = true
        return true
    }

    @Suppress("DEPRECATION")
    private fun disconnectLegacy() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        if (requested) audio.stopBluetoothSco()
    }

    private companion object {
        /** Long enough for a slow headset; the phone records meanwhile, so nothing waits on it. */
        const val CONNECT_TIMEOUT_MS = 8_000L
    }
}
