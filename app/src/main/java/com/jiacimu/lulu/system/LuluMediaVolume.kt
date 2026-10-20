package com.jiacimu.lulu.system

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.json.JSONObject
import kotlin.math.roundToInt

/** A bounded, permission-checked music-volume action. Never alters ringer/DND or call gain. */
internal object LuluMediaVolume {
    private const val PREFS = "lulu_role_volume_restore"

    fun control(context: Context, characterId: String, action: String, targetPercent: Int = 0): String {
        val result = JSONObject()
        val authorized = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
            .getBoolean("$characterId:media", false)
        if (!authorized) return result.put("success", false)
            .put("error", "角色没有媒体控制授权，音量保持不变").toString()
        return runCatching {
            val manager = context.getSystemService(AudioManager::class.java)
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val before = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val headphones = devices.any {
                it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            val maxPercent = if (headphones) 35 else 65
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val currentPercent = before * 100 / max
            when (action.lowercase()) {
                "status" -> result.put("success", true)
                "restore" -> {
                    val saved = prefs.getInt("before:$characterId", -1)
                    if (saved < 0) {
                        result.put("success", false).put("error", "没有此角色保存的调整前音量")
                    } else {
                        manager.setStreamVolume(AudioManager.STREAM_MUSIC, saved.coerceIn(0, max), 0)
                        val actual = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val restored = actual == saved.coerceIn(0, max)
                        if (restored) prefs.edit().remove("before:$characterId").commit()
                        result.put("success", restored).put("restored", restored)
                    }
                }
                "set" -> {
                    val bounded = targetPercent.coerceIn(0, maxPercent)
                    val target = (bounded * max / 100f).roundToInt()
                    if (!prefs.contains("before:$characterId")) {
                        prefs.edit().putInt("before:$characterId", before).commit()
                    }
                    manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    result.put("success", manager.getStreamVolume(AudioManager.STREAM_MUSIC) == target)
                        .put("requestedPercent", targetPercent).put("safeCapPercent", maxPercent)
                        .put("headphonesDetected", headphones)
                }
                else -> result.put("success", false).put("error", "仅支持 status、set、restore")
            }
            val after = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            result.put("previousPercent", currentPercent)
                .put("actualPercent", after * 100 / max)
                .put("stream", "music")
                .put("note", "仅修改媒体音量，不修改电话音量、铃声模式或勿扰状态")
            result.toString()
        }.getOrElse { error ->
            result.put("success", false).put("error", error.message.orEmpty().take(160)).toString()
        }
    }
}
