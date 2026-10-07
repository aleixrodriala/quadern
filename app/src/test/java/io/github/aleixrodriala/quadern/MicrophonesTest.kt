package io.github.aleixrodriala.quadern

import android.media.AudioDeviceInfo
import io.github.aleixrodriala.quadern.audio.Microphones
import io.github.aleixrodriala.quadern.audio.Microphones.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicrophonesTest {
    private data class Input(val type: Int, val name: String?)

    private fun usable(vararg inputs: Input) = Microphones.usable(inputs.toList()) { Microphones.describe(it.type, it.name) }

    @Test fun phoneFirstAndOnceEvenWithSeveralBuiltInMics() {
        val mics = usable(
            Input(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Pixel Buds Pro"),
            Input(AudioDeviceInfo.TYPE_BUILTIN_MIC, "Pixel 9"),
            Input(AudioDeviceInfo.TYPE_BUILTIN_MIC, "Pixel 9"),
        )
        assertEquals(listOf("phone", "bluetooth:Pixel Buds Pro"), mics.map { it.second.key })
        assertEquals("Phone", mics.first().second.name)
    }

    @Test fun aHeadsetWithLeAudioIsOfferedOnceWithLeAudio() {
        val mics = usable(
            Input(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Galaxy Buds"),
            Input(AudioDeviceInfo.TYPE_BLE_HEADSET, "Galaxy Buds"),
        )
        assertEquals(1, mics.size)
        assertEquals(Kind.BLUETOOTH_LE, mics.single().second.kind)
        assertEquals(AudioDeviceInfo.TYPE_BLE_HEADSET, mics.single().first.type)
    }

    @Test fun bothKindsOfBluetoothShareAKey() {
        assertEquals(
            Microphones.describe(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")?.key,
            Microphones.describe(AudioDeviceInfo.TYPE_BLE_HEADSET, "Buds")?.key,
        )
    }

    @Test fun thingsThatArentMicrophonesAreLeftOut() {
        for (type in listOf(AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_FM_TUNER, AudioDeviceInfo.TYPE_HDMI)) {
            assertNull("type $type", Microphones.describe(type, "x"))
        }
    }

    @Test fun namesWhenTheDeviceHasNone() {
        assertEquals("USB microphone", Microphones.describe(AudioDeviceInfo.TYPE_USB_DEVICE, " ")?.name)
        assertEquals("Bluetooth headset", Microphones.describe(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, null)?.name)
        // A wired headset is reported under the phone's name.
        assertEquals("Wired headset", Microphones.describe(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Pixel 9")?.name)
    }

    @Test fun inASentence() {
        assertEquals("the phone", Microphones.describe(AudioDeviceInfo.TYPE_BUILTIN_MIC, "Pixel 9")?.inSentence)
        assertEquals("the wired headset", Microphones.describe(AudioDeviceInfo.TYPE_WIRED_HEADSET, null)?.inSentence)
        assertEquals("the USB microphone", Microphones.describe(AudioDeviceInfo.TYPE_USB_HEADSET, "")?.inSentence)
        assertEquals("Pixel Buds Pro", Microphones.describe(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Pixel Buds Pro")?.inSentence)
    }
}
