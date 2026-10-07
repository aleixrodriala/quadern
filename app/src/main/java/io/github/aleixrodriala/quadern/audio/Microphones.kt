package io.github.aleixrodriala.quadern.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The microphones a note can be recorded with: the phone's own, and any headset or microphone
 * that's plugged in or paired.
 */
class Microphones(context: Context) {
    enum class Kind { PHONE, WIRED, USB, BLUETOOTH_LE, BLUETOOTH }

    data class Mic(
        /** The same every time the device connects, so a choice can be remembered. */
        val key: String,
        val kind: Kind,
        val name: String,
    ) {
        /** How a sentence names it: "the phone", "Pixel Buds Pro". */
        val inSentence: String
            get() = when {
                kind == Kind.PHONE -> "the phone"
                kind == Kind.WIRED -> "the wired headset"
                key.endsWith(':') -> "the $name" // no name of its own: "the USB microphone"
                else -> name
            }
    }

    private val audio = context.getSystemService(AudioManager::class.java)

    /** The microphones connected right now, each with the device to record from. */
    fun connected(): List<Pair<AudioDeviceInfo, Mic>> =
        usable(audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList(), ::describe)

    /** The connected device for a remembered [key], if it's there. */
    fun find(key: String): AudioDeviceInfo? = connected().firstOrNull { it.second.key == key }?.first

    /** [connected], kept up to date as things are plugged in, paired or switched off. */
    val available: Flow<List<Mic>> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            // Also called once on registration, with everything already connected.
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { trySend(connected().map { it.second }) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { trySend(connected().map { it.second }) }
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { audio.unregisterAudioDeviceCallback(callback) }
    }.conflate().distinctUntilChanged()

    companion object {
        fun describe(device: AudioDeviceInfo): Mic? = describe(device.type, device.productName)

        fun describe(type: Int, productName: CharSequence?): Mic? {
            val name = productName?.toString()?.trim().orEmpty()
            return when (type) {
                // Phones with several microphones list each one; they're one choice here.
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> Mic("phone", Kind.PHONE, "Phone")
                // Named after the phone, not the headset: the name says nothing.
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> Mic("wired", Kind.WIRED, "Wired headset")
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                    Mic("usb:$name", Kind.USB, name.ifEmpty { "USB microphone" })
                // One key for both kinds of Bluetooth, so a headset that can do either is the same choice.
                AudioDeviceInfo.TYPE_BLE_HEADSET -> Mic("bluetooth:$name", Kind.BLUETOOTH_LE, name.ifEmpty { "Bluetooth headset" })
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Mic("bluetooth:$name", Kind.BLUETOOTH, name.ifEmpty { "Bluetooth headset" })
                else -> null
            }
        }

        /**
         * The microphones among [devices] worth offering, the phone first, each once. A headset that
         * has both LE Audio and a classic Bluetooth microphone is offered with LE Audio, which sounds
         * better and needs no call link.
         */
        fun <T> usable(devices: List<T>, describe: (T) -> Mic?): List<Pair<T, Mic>> =
            devices.mapNotNull { d -> describe(d)?.let { d to it } }
                .sortedBy { it.second.kind.ordinal }
                .distinctBy { it.second.key }
    }
}
