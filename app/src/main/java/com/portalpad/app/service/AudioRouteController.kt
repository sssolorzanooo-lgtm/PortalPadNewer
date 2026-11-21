package com.portalpad.app.service

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.portalpad.app.data.AudioOutputMode
import com.portalpad.app.data.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class AudioSinkCategory {
    BLUETOOTH,
    EXTERNAL_DISPLAY,
    PHONE,
}

data class DetectedAudioDevice(
    val type: Int,
    val address: String,
    val name: String,
    val category: AudioSinkCategory,
    val priority: Int,
)

data class AudioStatusSnapshot(
    val mode: AudioOutputMode = AudioOutputMode.AUTO,
    val hasBluetooth: Boolean = false,
    val bluetoothName: String? = null,
    val hasExternalDisplayAudio: Boolean = false,
    val externalDisplayAudioName: String? = null,
    val hasPhoneAudio: Boolean = true,
    val phoneAudioName: String? = null,
    val activeRouteLabel: String = "System default",
    val activeCategory: AudioSinkCategory? = null,
    val externalDisplayActive: Boolean = false,
    val forcedByPrivilege: Boolean = false,
)

/**
 * Enforces the user's selected media audio output policy while an external display
 * session is active and dynamically shifts to Bluetooth if a Bluetooth audio sink
 * connects mid-session.
 */
class AudioRouteController(
    private val context: Context,
    private val prefs: PreferencesRepository,
    private val backendProvider: () -> BoundShellBackend?,
    private val scope: CoroutineScope,
) {
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _status = MutableStateFlow(AudioStatusSnapshot())
    val status: StateFlow<AudioStatusSnapshot> = _status.asStateFlow()

    @Volatile private var started = false
    @Volatile private var externalConnected = false
    @Volatile private var currentMode = AudioOutputMode.AUTO
    @Volatile private var hasAppliedOverride = false
    private var prefCollectorJob: Job? = null
    private var delayedShiftJob: Job? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val btAdded = addedDevices.orEmpty().any {
                it.isSink && classifyOutputDevice(it.type)?.first == AudioSinkCategory.BLUETOOTH
            }
            Log.d(TAG, "onAudioDevicesAdded count=${addedDevices?.size ?: 0} btAdded=$btAdded ext=$externalConnected")
            refreshSnapshotOnly()
            if (externalConnected) {
                reapplyRouting(reason = if (btAdded) "bt-added" else "device-added")
                // Give Bluetooth A2DP / BLE Audio policy ~400ms to finish handshake if BT just attached.
                if (btAdded) {
                    delayedShiftJob?.cancel()
                    delayedShiftJob = scope.launch(Dispatchers.IO) {
                        delay(450L)
                        if (externalConnected) {
                            reapplyRouting(reason = "bt-added-settle")
                        }
                    }
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val btRemoved = removedDevices.orEmpty().any {
                it.isSink && classifyOutputDevice(it.type)?.first == AudioSinkCategory.BLUETOOTH
            }
            Log.d(TAG, "onAudioDevicesRemoved count=${removedDevices?.size ?: 0} btRemoved=$btRemoved ext=$externalConnected")
            refreshSnapshotOnly()
            if (externalConnected) {
                reapplyRouting(reason = if (btRemoved) "bt-removed" else "device-removed")
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        runCatching {
            audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }.onFailure { Log.w(TAG, "registerAudioDeviceCallback failed", it) }
        prefCollectorJob?.cancel()
        prefCollectorJob = scope.launch {
            prefs.audioOutputMode.collectLatest { mode ->
                currentMode = mode
                refreshSnapshotOnly()
                if (externalConnected) {
                    reapplyRouting(reason = "pref-changed:${mode.id}")
                }
            }
        }
        refreshSnapshotOnly()
    }

    fun stop() {
        if (!started) return
        started = false
        delayedShiftJob?.cancel()
        delayedShiftJob = null
        prefCollectorJob?.cancel()
        prefCollectorJob = null
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        externalConnected = false
        clearOverrideSync()
        refreshSnapshotOnly()
    }

    fun onExternalDisplayStateChanged(connected: Boolean) {
        val prev = externalConnected
        externalConnected = connected
        Log.d(TAG, "onExternalDisplayStateChanged prev=$prev -> connected=$connected")
        if (connected) {
            scope.launch(Dispatchers.IO) {
                currentMode = runCatching { prefs.audioOutputMode.first() }.getOrDefault(currentMode)
                reapplyRouting(reason = if (!prev) "external-connect" else "external-reconcile")
                // Re-apply once more shortly after connect so HDMI/USB-C DP audio sinks
                // that finish enumerating ~300-600ms after DisplayManager attach are caught.
                delay(550L)
                if (externalConnected) {
                    reapplyRouting(reason = "external-connect-settle")
                }
            }
        } else {
            delayedShiftJob?.cancel()
            scope.launch(Dispatchers.IO) {
                clearOverrideSync()
                refreshSnapshotOnly()
            }
        }
    }

    fun refreshSnapshotOnly() {
        val detected = scanOutputDevices()
        val mode = currentMode
        val resolved = resolveTargetDevice(mode, detected)
        val bt = detected.filter { it.category == AudioSinkCategory.BLUETOOTH }.minByOrNull { it.priority }
        val ext = detected.filter { it.category == AudioSinkCategory.EXTERNAL_DISPLAY }.minByOrNull { it.priority }
        val phone = detected.filter { it.category == AudioSinkCategory.PHONE }.minByOrNull { it.priority }
        val activeLabel = when {
            !externalConnected -> "Standby (connect external display)"
            resolved != null -> resolved.name
            else -> when (mode) {
                AudioOutputMode.BLUETOOTH -> "Bluetooth not connected"
                AudioOutputMode.EXTERNAL_DISPLAY -> "External display audio unavailable"
                AudioOutputMode.PHONE -> "Phone audio"
                AudioOutputMode.AUTO -> "System default"
            }
        }
        _status.value = AudioStatusSnapshot(
            mode = mode,
            hasBluetooth = bt != null,
            bluetoothName = bt?.name,
            hasExternalDisplayAudio = ext != null,
            externalDisplayAudioName = ext?.name,
            hasPhoneAudio = phone != null,
            phoneAudioName = phone?.name,
            activeRouteLabel = activeLabel,
            activeCategory = resolved?.category,
            externalDisplayActive = externalConnected,
            forcedByPrivilege = hasAppliedOverride && externalConnected,
        )
    }

    fun reapplyRouting(reason: String) {
        scope.launch(Dispatchers.IO) {
            reapplyRoutingSync(reason)
        }
    }

    private fun reapplyRoutingSync(reason: String) {
        if (!externalConnected) {
            refreshSnapshotOnly()
            return
        }
        val detected = scanOutputDevices()
        val mode = currentMode
        val target = resolveTargetDevice(mode, detected)
        val backend = backendProvider()
        if (target == null) {
            if (hasAppliedOverride && backend?.isReady == true) {
                runCatching { backend.clearPreferredMediaAudioDevice() }
                hasAppliedOverride = false
            }
            Log.d(TAG, "reapplyRouting($reason): mode=${mode.id} no matching sink available")
            refreshSnapshotOnly()
            return
        }
        var applied = false
        if (backend?.isReady == true) {
            applied = runCatching {
                backend.setPreferredMediaAudioDevice(target.type, target.address)
            }.getOrDefault(false)
            if (applied) {
                hasAppliedOverride = true
            }
        }
        Log.i(
            TAG,
            "reapplyRouting($reason): mode=${mode.id} target=${target.category}/${target.type}/'${target.name}' " +
                "addr='${target.address}' backendApplied=$applied",
        )
        refreshSnapshotOnly()
    }

    private fun clearOverrideSync() {
        if (!hasAppliedOverride) return
        val backend = backendProvider()
        if (backend?.isReady == true) {
            runCatching { backend.clearPreferredMediaAudioDevice() }
        }
        hasAppliedOverride = false
    }

    private fun scanOutputDevices(): List<DetectedAudioDevice> {
        val raw = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull() ?: return emptyList()
        return raw.mapNotNull { dev ->
            val (category, prio) = classifyOutputDevice(dev.type) ?: return@mapNotNull null
            val addr = runCatching { dev.address }.getOrNull().orEmpty()
            val friendlyName = runCatching {
                dev.productName?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }.getOrNull() ?: fallbackDeviceName(dev.type)
            DetectedAudioDevice(
                type = dev.type,
                address = addr,
                name = friendlyName,
                category = category,
                priority = prio,
            )
        }
    }

    companion object {
        private const val TAG = "AudioRouteController"
        private const val TYPE_DISPLAY_PORT_COMPAT = 30

        private val displayPortTypeValue: Int? by lazy {
            runCatching {
                AudioDeviceInfo::class.java.getField("TYPE_DISPLAY_PORT").getInt(null)
            }.getOrNull()
        }

        fun classifyOutputDevice(type: Int): Pair<AudioSinkCategory, Int>? {
            if (Build.VERSION.SDK_INT >= 31) {
                when (type) {
                    AudioDeviceInfo.TYPE_BLE_HEADSET -> return AudioSinkCategory.BLUETOOTH to 1
                    AudioDeviceInfo.TYPE_BLE_SPEAKER -> return AudioSinkCategory.BLUETOOTH to 2
                    AudioDeviceInfo.TYPE_HDMI_EARC -> return AudioSinkCategory.EXTERNAL_DISPLAY to 3
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (type == AudioDeviceInfo.TYPE_BLE_BROADCAST) {
                    return AudioSinkCategory.BLUETOOTH to 4
                }
            }
            if (Build.VERSION.SDK_INT >= 34) {
                if (type == AudioDeviceInfo.TYPE_DOCK_ANALOG) {
                    return AudioSinkCategory.EXTERNAL_DISPLAY to 5
                }
            }
            if (displayPortTypeValue != null && type == displayPortTypeValue) {
                return AudioSinkCategory.EXTERNAL_DISPLAY to 1
            }
            return when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioSinkCategory.BLUETOOTH to 3
                AudioDeviceInfo.TYPE_HEARING_AID -> AudioSinkCategory.BLUETOOTH to 5
                AudioDeviceInfo.TYPE_HDMI -> AudioSinkCategory.EXTERNAL_DISPLAY to 2
                AudioDeviceInfo.TYPE_HDMI_ARC -> AudioSinkCategory.EXTERNAL_DISPLAY to 3
                AudioDeviceInfo.TYPE_DOCK -> AudioSinkCategory.EXTERNAL_DISPLAY to 4
                AudioDeviceInfo.TYPE_USB_HEADSET -> AudioSinkCategory.EXTERNAL_DISPLAY to 6
                AudioDeviceInfo.TYPE_USB_DEVICE -> AudioSinkCategory.EXTERNAL_DISPLAY to 7
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioSinkCategory.EXTERNAL_DISPLAY to 8
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioSinkCategory.PHONE to 1
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioSinkCategory.PHONE to 2
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioSinkCategory.PHONE to 3
                else -> if (type == TYPE_DISPLAY_PORT_COMPAT) {
                    // On API 33+, 30 is TYPE_BLE_BROADCAST (handled above); only map if DisplayPort isn't separate.
                    null
                } else null
            }
        }

        fun resolveTargetDevice(
            mode: AudioOutputMode,
            available: List<DetectedAudioDevice>,
        ): DetectedAudioDevice? {
            fun bestFor(category: AudioSinkCategory): DetectedAudioDevice? =
                available.filter { it.category == category }.minByOrNull { it.priority }

            return when (mode) {
                AudioOutputMode.AUTO ->
                    bestFor(AudioSinkCategory.BLUETOOTH)
                        ?: bestFor(AudioSinkCategory.EXTERNAL_DISPLAY)
                        ?: bestFor(AudioSinkCategory.PHONE)
                AudioOutputMode.BLUETOOTH -> bestFor(AudioSinkCategory.BLUETOOTH)
                AudioOutputMode.EXTERNAL_DISPLAY -> bestFor(AudioSinkCategory.EXTERNAL_DISPLAY)
                AudioOutputMode.PHONE -> bestFor(AudioSinkCategory.PHONE)
            }
        }

        private fun fallbackDeviceName(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth audio"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Bluetooth hearing aid"
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> "External display audio"
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB-C external audio"
            AudioDeviceInfo.TYPE_DOCK -> "External dock audio"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            else -> "Audio output"
        }
    }
}
