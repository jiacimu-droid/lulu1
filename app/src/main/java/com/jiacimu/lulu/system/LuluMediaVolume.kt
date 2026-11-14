package com.jiacimu.lulu.system

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.json.JSONObject
import kotlin.math.roundToInt

/** A bounded, permission-checked music-volume action. Never alters ringer/DND or call gain. */
internal object LuluMediaVolume {
    private const val PREFS = "lulu_role_volume_restore"

    fun control(
        context: Context, characterId: String, action: String,
        targetPercent: Int = 0, deltaPercent: Int = 0,
    ): String {
        val result = JSONObject()
        val authorized = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
            .getBoolean("$characterId:media", false)
        if (!authorized) return result.put("success", false)
            .put("error", "角色尚未取得媒体音量操作授权").toString()
        return runCatching {
            val manager = context.getSystemService(AudioManager::class.java)
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val before = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val route = com.jiacimu.lulu.CallAudioRoute.preferredOutput
                ?: if (android.os.Build.VERSION.SDK_INT >= 31) manager.communicationDevice else null
            val headphoneTypes = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET)
            val headphones = if (route != null) route.type in headphoneTypes else
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type in headphoneTypes
                }
            val maxPercent = if (headphones) 35 else 65
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val beforePercent = before * 100 / max
            val requestedPercent = when (action.lowercase()) {
                "set" -> targetPercent
                "adjust" -> beforePercent + deltaPercent.coerceIn(-50, 50)
                else -> beforePercent
            }
            var requestedIndex = before
            var accepted = true
            when (action.lowercase()) {
                "status" -> Unit
                "restore" -> {
                    val saved = prefs.getInt("before:$characterId", -1)
                    if (saved < 0) {
                        accepted = false
                        result.put("error", "本角色没有可恢复的原始媒体音量")
                    } else {
                        requestedIndex = saved.coerceIn(0, max)
                        manager.setStreamVolume(AudioManager.STREAM_MUSIC, requestedIndex, 0)
                        if (manager.getStreamVolume(AudioManager.STREAM_MUSIC) == requestedIndex)
                            prefs.edit().remove("before:$characterId").commit()
                    }
                }
                "set", "adjust" -> {
                    val bounded = requestedPercent.coerceIn(0, maxPercent)
                    requestedIndex = (bounded * max / 100f).roundToInt().coerceIn(0, max)
                    if (!prefs.contains("before:$characterId"))
                        prefs.edit().putInt("before:$characterId", before).commit()
                    manager.setStreamVolume(AudioManager.STREAM_MUSIC, requestedIndex, 0)
                }
                else -> {
                    accepted = false
                    result.put("error", "仅支持 status、set、adjust、restore")
                }
            }
            val after = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val changed = after != before
            val verified = accepted && (action.lowercase() == "status" || after == requestedIndex)
            val capped = action.lowercase() in setOf("set", "adjust") &&
                requestedPercent > maxPercent
            val status = when {
                !verified -> "failed"
                capped && !changed -> "at_safe_limit"
                capped -> "capped"
                !changed -> "unchanged"
                else -> "verified"
            }
            val message = when {
                !verified -> result.optString("error").ifBlank { "系统没有达到目标媒体音量，请检查设备音频路由" }
                capped && !changed -> "媒体音量已在设备安全上限，未继续调大"
                capped -> "已按安全上限调整媒体音量，未达到请求值"
                !changed -> "前后音量相同，没有发生变化"
                else -> "读取确认媒体音量确实变化"
            }
            result.put("success", verified).put("status", status)
                .put("action", action.lowercase())
                .put("previousPercent", beforePercent)
                .put("actualPercent", after * 100 / max)
                .put("requestedPercent", requestedPercent)
                .put("safeCapPercent", maxPercent)
                .put("changed", changed).put("clamped", capped)
                .put("outputDevice", route?.type ?: -1)
                .put("headphonesDetected", headphones)
                .put("stream", "music")
                .put("verification", message + "；这是STREAM_MUSIC的真实读数，不代表通话语音音量、外部播放器响度或扬声器响度一定同步变化")
                .toString()
        }.getOrElse { error ->
            result.put("success", false).put("status", "failed")
                .put("error", error.message.orEmpty().ifBlank { "媒体音量读取或设置失败" }.take(160))
                .toString()
        }
    }

}
