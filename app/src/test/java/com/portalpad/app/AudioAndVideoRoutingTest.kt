package com.portalpad.app

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import androidx.test.core.app.ApplicationProvider
import com.portalpad.app.data.AudioOutputMode
import com.portalpad.app.data.PreferencesRepository
import com.portalpad.app.service.AudioRouteController
import com.portalpad.app.service.AudioSinkCategory
import com.portalpad.app.service.DetectedAudioDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AudioAndVideoRoutingTest {

    @Test
    fun audioOutputMode_roundTripsIdsAndDefaultsToAuto() {
        for (mode in AudioOutputMode.entries) {
            assertEquals(mode, AudioOutputMode.fromId(mode.id))
        }
        assertEquals(AudioOutputMode.AUTO, AudioOutputMode.fromId(null))
        assertEquals(AudioOutputMode.AUTO, AudioOutputMode.fromId(""))
        assertEquals(AudioOutputMode.AUTO, AudioOutputMode.fromId("unknown_audio_mode"))
    }

    @Test
    fun preferencesRepository_defaultsAndPersistsVideoForwardAndAudioMode() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = PreferencesRepository(context)

        assertTrue(prefs.forwardVideoOnConnect.first())
        assertEquals(AudioOutputMode.AUTO, prefs.audioOutputMode.first())

        prefs.setForwardVideoOnConnect(false)
        prefs.setAudioOutputMode(AudioOutputMode.EXTERNAL_DISPLAY)
        assertFalse(prefs.forwardVideoOnConnect.first())
        assertEquals(AudioOutputMode.EXTERNAL_DISPLAY, prefs.audioOutputMode.first())

        prefs.setAudioOutputMode(AudioOutputMode.BLUETOOTH)
        assertEquals(AudioOutputMode.BLUETOOTH, prefs.audioOutputMode.first())

        prefs.setAudioOutputMode(AudioOutputMode.PHONE)
        assertEquals(AudioOutputMode.PHONE, prefs.audioOutputMode.first())

        prefs.setForwardVideoOnConnect(true)
        prefs.setAudioOutputMode(AudioOutputMode.AUTO)
        assertTrue(prefs.forwardVideoOnConnect.first())
        assertEquals(AudioOutputMode.AUTO, prefs.audioOutputMode.first())
    }

    @Test
    fun resolveTargetDevice_autoPrioritizesBluetoothThenExternalThenPhone() {
        val phone = DetectedAudioDevice(
            type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            address = "",
            name = "Phone speaker",
            category = AudioSinkCategory.PHONE,
            priority = 3,
        )
        val ext = DetectedAudioDevice(
            type = AudioDeviceInfo.TYPE_HDMI,
            address = "hdmi0",
            name = "HDMI Monitor",
            category = AudioSinkCategory.EXTERNAL_DISPLAY,
            priority = 2,
        )
        val bt = DetectedAudioDevice(
            type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            address = "AA:BB:CC:DD:EE:FF",
            name = "Earbuds",
            category = AudioSinkCategory.BLUETOOTH,
            priority = 3,
        )

        // Only phone available -> phone
        assertEquals(
            phone,
            AudioRouteController.resolveTargetDevice(AudioOutputMode.AUTO, listOf(phone)),
        )
        // Phone + External display -> External display wins over phone
        assertEquals(
            ext,
            AudioRouteController.resolveTargetDevice(AudioOutputMode.AUTO, listOf(phone, ext)),
        )
        // Bluetooth connects mid-session -> shifts immediately to Bluetooth
        assertEquals(
            bt,
            AudioRouteController.resolveTargetDevice(AudioOutputMode.AUTO, listOf(phone, ext, bt)),
        )
        // Explicit PHONE override ignores BT and External display
        assertEquals(
            phone,
            AudioRouteController.resolveTargetDevice(AudioOutputMode.PHONE, listOf(phone, ext, bt)),
        )
        // Explicit EXTERNAL_DISPLAY override ignores BT
        assertEquals(
            ext,
            AudioRouteController.resolveTargetDevice(AudioOutputMode.EXTERNAL_DISPLAY, listOf(phone, ext, bt)),
        )
        // Explicit BLUETOOTH returns null if BT is disconnected
        assertNull(
            AudioRouteController.resolveTargetDevice(AudioOutputMode.BLUETOOTH, listOf(phone, ext)),
        )
    }

    @Test
    fun classifyOutputDevice_mapsCommonSinksCorrectly() {
        assertEquals(
            AudioSinkCategory.BLUETOOTH,
            AudioRouteController.classifyOutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)?.first,
        )
        assertEquals(
            AudioSinkCategory.EXTERNAL_DISPLAY,
            AudioRouteController.classifyOutputDevice(AudioDeviceInfo.TYPE_HDMI)?.first,
        )
        assertEquals(
            AudioSinkCategory.EXTERNAL_DISPLAY,
            AudioRouteController.classifyOutputDevice(AudioDeviceInfo.TYPE_USB_HEADSET)?.first,
        )
        assertEquals(
            AudioSinkCategory.PHONE,
            AudioRouteController.classifyOutputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)?.first,
        )
        assertEquals(
            AudioSinkCategory.PHONE,
            AudioRouteController.classifyOutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)?.first,
        )
    }
}
