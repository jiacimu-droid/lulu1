package com.jiacimu.lulu

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Route selection is independent of playback volume. Headphones are preferred on entry. */
internal class CallAudioRoute(
    private val manager: AudioManager,
    private val onRoute: (Boolean, String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var active = false
    private var forceSpeaker = false
    private var preferPrivate = false
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var previousMicrophoneMuted = false
    private var startedSco = false
    private val handler = Handler(Looper.getMainLooper())
    private val retryRoute = Runnable { refresh() }
    private val routeChanged = if (Build.VERSION.SDK_INT >= 31) AudioManager.OnCommunicationDeviceChangedListener { device ->
        if (active) {
            onRoute(device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, device?.let { label(it.type) } ?: "系统输出")
        }
    } else null
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refresh(); retrySelection() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refresh(); retrySelection() }
    }

    fun start() {
        if (active) return
        previousMode = manager.mode
        previousSpeaker = manager.isSpeakerphoneOn
        previousMicrophoneMuted = manager.isMicrophoneMute
        forceSpeaker = false; preferPrivate = false; active = true
        manager.isMicrophoneMute = false
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.registerAudioDeviceCallback(callback, handler)
        if (Build.VERSION.SDK_INT >= 31) routeChanged?.let {
            manager.addOnCommunicationDeviceChangedListener({ action -> handler.post(action) }, it)
        }
        refresh()
        retrySelection()
    }

    private fun retrySelection() {
        handler.removeCallbacks(retryRoute)
        // Bluetooth communication profiles may appear after recording starts.
        handler.postDelayed(retryRoute, 750)
    }

    fun microphone(muted: Boolean) { if (active) manager.isMicrophoneMute = muted }

    fun speaker(enabled: Boolean) {
        forceSpeaker = enabled; preferPrivate = !enabled
        refresh()
    }

    @Suppress("DEPRECATION")
    fun refresh() {
        if (!active) return
        runCatching {
            val devices = if (Build.VERSION.SDK_INT >= 31) manager.availableCommunicationDevices
                else manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            val headset = devices.filter { headsetPriority(it.type) < 100 }.minByOrNull { headsetPriority(it.type) }
            val desired = when {
                forceSpeaker -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                headset != null -> headset
                preferPrivate -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                else -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            }
            preferredOutput = desired
            // Communication output selection alone does not guarantee Android
            // will capture from the same headset microphone. Bind the input
            // explicitly: headset mic for SCO/LE/USB/wired, otherwise phone mic.
            val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            val inputType = preferredMicrophoneType(desired?.type)
            preferredInput = inputs.firstOrNull { it.type == inputType }
                ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (Build.VERSION.SDK_INT >= 31) {
                if (desired == null) { manager.clearCommunicationDevice(); onRoute(false, "系统输出") }
                else {
                    if (manager.communicationDevice?.id != desired.id)
                        check(manager.setCommunicationDevice(desired)) { "系统未接受音频设备切换，请检查耳机连接后重试" }
                    val actual = manager.communicationDevice
                    onRoute(actual?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, actual?.let { label(it.type) } ?: "正在切换")
                }
            } else {
                val speaker = desired?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                manager.isSpeakerphoneOn = speaker
                val bluetooth = desired?.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
                if (bluetooth && !startedSco && !manager.isBluetoothScoOn) {
                    manager.startBluetoothSco(); manager.isBluetoothScoOn = true; startedSco = true
                } else if (!bluetooth && startedSco) {
                    manager.stopBluetoothSco(); manager.isBluetoothScoOn = false; startedSco = false
                }
                onRoute(speaker, desired?.let { label(it.type) } ?: "系统输出")
            }
        }.onFailure { onError(it.message ?: "音频设备切换失败") }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (!active) return
        active = false
        preferredOutput = null
        preferredInput = null
        handler.removeCallbacks(retryRoute)
        manager.unregisterAudioDeviceCallback(callback)
        if (Build.VERSION.SDK_INT >= 31) {
            routeChanged?.let { manager.removeOnCommunicationDeviceChangedListener(it) }
            manager.clearCommunicationDevice()
        }
        if (startedSco) { manager.stopBluetoothSco(); manager.isBluetoothScoOn = false; startedSco = false }
        if (Build.VERSION.SDK_INT < 31) manager.isSpeakerphoneOn = previousSpeaker
        manager.isMicrophoneMute = previousMicrophoneMuted
        manager.mode = previousMode
    }

    companion object {
        @Volatile var preferredOutput: AudioDeviceInfo? = null
            private set
        @Volatile var preferredInput: AudioDeviceInfo? = null
            private set

        /** Output-only earbuds use the handset mic; duplex headsets use their own mic. */
        fun preferredMicrophoneType(outputType: Int?): Int = when (outputType) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioDeviceInfo.TYPE_WIRED_HEADSET
            AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceInfo.TYPE_USB_HEADSET
            AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioDeviceInfo.TYPE_BLE_HEADSET
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            else -> AudioDeviceInfo.TYPE_BUILTIN_MIC
        }

        fun headsetPriority(type: Int): Int = when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> 0
            AudioDeviceInfo.TYPE_BLE_HEADSET -> 1
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
            AudioDeviceInfo.TYPE_HEARING_AID -> 3
            else -> 100
        }
        private fun label(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙耳机"
            AudioDeviceInfo.TYPE_HEARING_AID -> "助听设备"
            else -> "耳机"
        }
    }
}
