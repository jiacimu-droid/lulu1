package com.jiacimu.lulu

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Ephemeral visual actuator for the SAME phone turn.
 * No personality, memory, extra model call, invented action names or durable side store.
 */
internal object Live2DVisualActionBus {
    data class Head(val yaw: Float? = null, val pitch: Float? = null, val roll: Float? = null)
    data class Cue(
        val callId: String, val characterId: String,
        val expression: String? = null, val motion: Pair<String, Int>? = null,
        val head: Head? = null,
        val sequence: Long,
    )
    private val mutableCues = MutableStateFlow<Map<String, Cue>>(emptyMap())
    val cues = mutableCues.asStateFlow()
    private val visibleStages = mutableMapOf<String, String>()
    private val availableAngles = mutableMapOf<String, Set<String>>()
    private var sequence = 0L

    @Synchronized
    fun stageReady(characterId: String, callId: String) {
        if (characterId.isNotBlank() && callId.isNotBlank()) visibleStages[characterId] = callId
    }

    @Synchronized
    fun stageClosed(characterId: String, callId: String) {
        if (visibleStages[characterId] == callId) {
            visibleStages.remove(characterId)
            availableAngles.remove(characterId)
        }
        if (mutableCues.value[characterId]?.callId == callId) {
            mutableCues.value = mutableCues.value - characterId
        }
    }

    @Synchronized
    fun stageParameters(characterId: String, callId: String, ids: String) {
        if (visibleStages[characterId] != callId) return
        availableAngles[characterId] = ids.split(',').map(String::trim)
            .filter { it in setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ") }.toSet()
    }

    @Synchronized
    fun capabilityNote(context: Context, characterId: String, callId: String): String {
        if (visibleStages[characterId] != callId || callId.isBlank()) return ""
        val controls = Live2DAssets.controls(context, characterId)
        val angles = availableAngles[characterId].orEmpty()
        if (controls.expressions.isEmpty() && controls.motions.isEmpty() && angles.isEmpty()) return ""
        val expressions = controls.expressions.take(20).joinToString("、").ifBlank { "无" }
        val motions = controls.motions.take(20).joinToString("、") { "${it.first}#${it.second}" }.ifBlank { "无" }
        val headFields = listOfNotNull(
            "yaw".takeIf { "ParamAngleX" in angles }?.let { "yaw=左右转头" },
            "pitch".takeIf { "ParamAngleY" in angles }?.let { "pitch=抬头低头" },
            "roll".takeIf { "ParamAngleZ" in angles }?.let { "roll=歪头" },
        ).joinToString("、")
        return """
            【当前可视电话：本角色已加载成功的 Live2D 身体】
            当前你拥有可操作的 Live2D 身体，是否表达由你自主决定，不要求每一句话配动作。
            若这一刻确实有表情或动作意图，可在同一次回复的 JSON 附加 live2d。
            例如 live2d={"expression":"表情原名"}，或 live2d={"motion":{"group":"动作组原名","index":0}}。
            有真实需要时可在一个 live2d 中同时写 expression、motion、head；
            支持头部参数才可用 live2d={"head":{"yaw":-12,"pitch":4,"roll":0}}。
            可选角度控制=$headFields；每个角度只使用 -25 至 25 范围。
            当前模型实际支持：表情=$expressions；动作=$motions。
            只填写上面列出的真实名字和索引，沿用自己当前的心理状态、关系、情绪变化与表达意图自然决定。
            一个动作若无必要，就保持原模型自带的呼吸眨眼和动作状态；不要每轮随机换表情，
            不要为了显得活泼而固定摆头、反复点头或重复同一动作。
            文本 gesture 和台词里写「笑了」「挥手」并不能驱动身体，
            真想执行模型支持的对应动作才输出 live2d；执行器可能因资源缺失拒绝动作。
            这是身体视觉操作，不是台词、不是新的 API 回合，也不代表任何现实接触。
            若视觉不可用/视频关闭，程序会跳过，不可在正文假称动作已成功执行。
        """.trimIndent()
    }

    /** Enforces the exact imported model manifest at execution time, independent of the LLM. */
    @Synchronized
    fun publish(context: Context, characterId: String, callId: String, request: JSONObject?): Boolean {
        if (request == null || visibleStages[characterId] != callId || callId.isBlank()) return false
        val controls = Live2DAssets.controls(context, characterId)
        val expression = request.optString("expression").trim().takeIf(String::isNotBlank)
        val motion = request.optJSONObject("motion")?.let { item ->
            val group = item.optString("group")
            val index = item.optInt("index", -1)
            (group to index).takeIf { it in controls.motions }
        }
        val selected = expression?.takeIf { it in controls.expressions }
        val allowed = availableAngles[characterId].orEmpty()
        val proposed = request.optJSONObject("head")
        fun bounded(key: String, required: String): Float? {
            if (proposed == null || required !in allowed || !proposed.has(key)) return null
            val value = proposed.optDouble(key, Double.NaN)
            return value.takeIf { it.isFinite() }?.coerceIn(-25.0, 25.0)?.toFloat()
        }
        val angles = Head(
            yaw = bounded("yaw", "ParamAngleX"),
            pitch = bounded("pitch", "ParamAngleY"),
            roll = bounded("roll", "ParamAngleZ"),
        ).takeIf { it.yaw != null || it.pitch != null || it.roll != null }
        if (selected == null && motion == null && angles == null) return false
        sequence++
        mutableCues.value = mutableCues.value + (characterId to Cue(
            callId, characterId, selected, motion, angles, sequence
        ))
        return true
    }
}
