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
    data class Body(val yaw: Float? = null, val pitch: Float? = null, val roll: Float? = null)
    data class Face(val values: Map<String, Float>)
    // No hard-coded wolf persona: these are semantic channels only when the
    // currently loaded Cubism model confirms each actual parameter ID.
    private val faceChannels = linkedMapOf(
        "gazeX" to listOf("ParamEyeBallX"),
        "gazeY" to listOf("ParamEyeBallY"),
        "smile" to listOf("ParamMouthForm"),
        "eyeSmile" to listOf("ParamEyeLSmile", "ParamEyeRSmile"),
        "browY" to listOf("ParamBrowLY", "ParamBrowRY"),
        "squint" to listOf("ParamEyeLSquint", "ParamEyeRSquint"),
        "cheekPuff" to listOf("ParamCheekPuff"),
        "pout" to listOf("ParamMouthFunnel"),
    )
    private val positiveFaceChannels = setOf("eyeSmile", "squint", "cheekPuff", "pout")
    private val validParameters = faceChannels.values.flatten().toSet() +
        setOf("ParamAngleX", "ParamAngleY", "ParamAngleZ",
            "ParamBodyAngleX", "ParamBodyAngleY", "ParamBodyAngleZ")
    data class Cue(
        val callId: String, val characterId: String,
        val expression: String? = null, val motion: Pair<String, Int>? = null,
        val head: Head? = null,
        val body: Body? = null,
        val face: Face? = null,
        val sequence: Long,
    )
    private val mutableCues = MutableStateFlow<Map<String, Cue>>(emptyMap())
    val cues = mutableCues.asStateFlow()
    private val visibleStages = mutableMapOf<String, String>()
    private val availableParameters = mutableMapOf<String, Set<String>>()
    private var sequence = 0L

    @Synchronized
    fun stageReady(characterId: String, callId: String) {
        if (characterId.isNotBlank() && callId.isNotBlank()) visibleStages[characterId] = callId
    }

    @Synchronized
    fun stageClosed(characterId: String, callId: String) {
        if (visibleStages[characterId] == callId) {
            visibleStages.remove(characterId)
            availableParameters.remove(characterId)
        }
        if (mutableCues.value[characterId]?.callId == callId) {
            mutableCues.value = mutableCues.value - characterId
        }
    }

    @Synchronized
    fun stageParameters(characterId: String, callId: String, ids: String) {
        if (visibleStages[characterId] != callId) return
        availableParameters[characterId] = ids.split(',').map(String::trim)
            .filter { it in validParameters }.toSet()
    }

    @Synchronized
    fun capabilityNote(context: Context, characterId: String, callId: String): String {
        if (visibleStages[characterId] != callId || callId.isBlank()) return ""
        val controls = Live2DAssets.controls(context, characterId)
        val supported = availableParameters[characterId].orEmpty()
        if (controls.expressions.isEmpty() && controls.motions.isEmpty() && supported.isEmpty()) return ""
        val expressions = controls.expressions.take(20).joinToString("、").ifBlank { "无" }
        val motions = controls.motions.take(20).joinToString("、") { "${it.first}#${it.second}" }.ifBlank { "无" }
        val headFields = listOfNotNull(
            "yaw".takeIf { "ParamAngleX" in supported }?.let { "yaw=左右转头" },
            "pitch".takeIf { "ParamAngleY" in supported }?.let { "pitch=抬头低头" },
            "roll".takeIf { "ParamAngleZ" in supported }?.let { "roll=歪头" },
        ).joinToString("、")
        val bodyFields = listOfNotNull(
            "yaw".takeIf { "ParamBodyAngleX" in supported }?.let { "yaw=身体左右转" },
            "pitch".takeIf { "ParamBodyAngleY" in supported }?.let { "pitch=身体前后" },
            "roll".takeIf { "ParamBodyAngleZ" in supported }?.let { "roll=身体侧倾" },
        ).joinToString("、")
        val faceFields = faceChannels.entries.filter { (_, ids) ->
            ids.all { it in supported }
        }.joinToString("、") { (key, _) -> key }
        return """
            【当前可视电话：本角色已加载成功的 Live2D 身体】
            当前你拥有可操作的 Live2D 身体，是否表达由你自主决定，不要求每一句话配动作。
            若这一刻确实有表情或动作意图，可在同一次回复的 JSON 附加 live2d。
            例如 live2d={"expression":"表情原名"}，或 live2d={"motion":{"group":"动作组原名","index":0}}。
            有真实需要时可在一个 live2d 中同时写 expression、motion、head、body、face；
            可用头部角度=$headFields（-25至25）；身体姿态=$bodyFields（-10至10）。
            当前可用面部语义参数=$faceFields；每项限 -1 至 1，其中 eyeSmile、squint、
            cheekPuff、pout 只能填 0 至 1；例如 face={"gazeX":-0.5,"smile":0.7,"eyeSmile":0.4}。
            gazeX/Y 表示眼神，smile 正值表示嘴角上扬，browY 为眉毛上下。
            眼神和微表情只在当前真实想法需要时短暂出现，并平滑还原；不要以眨眼或随机晃头冒充自主行为。
            当前模型实际支持：表情/外观切换=$expressions；预制动作=$motions。
            切换兽耳、耳机、麦克风属于外观，不要当作情绪动作；没有 motion 就不能假称挥手、跳舞。
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
        val allowed = availableParameters[characterId].orEmpty()
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
        val bodyJson = request.optJSONObject("body")
        fun boundedBody(key: String, required: String): Float? {
            if (bodyJson == null || required !in allowed || !bodyJson.has(key)) return null
            return bodyJson.optDouble(key, Double.NaN).takeIf(Double::isFinite)
                ?.coerceIn(-10.0, 10.0)?.toFloat()
        }
        val body = Body(
            yaw = boundedBody("yaw", "ParamBodyAngleX"),
            pitch = boundedBody("pitch", "ParamBodyAngleY"),
            roll = boundedBody("roll", "ParamBodyAngleZ"),
        ).takeIf { it.yaw != null || it.pitch != null || it.roll != null }
        val faceJson = request.optJSONObject("face")
        val faceValues = mutableMapOf<String, Float>()
        if (faceJson != null) for ((key, ids) in faceChannels) {
            if (!faceJson.has(key) || !ids.all { it in allowed }) continue
            val number = faceJson.optDouble(key, Double.NaN)
            if (number.isFinite()) {
                val min = if (key in positiveFaceChannels) 0.0 else -1.0
                faceValues[key] = number.coerceIn(min, 1.0).toFloat()
            }
        }
        val face = faceValues.takeIf { it.isNotEmpty() }?.let(::Face)
        if (selected == null && motion == null && angles == null && body == null && face == null) return false
        sequence++
        mutableCues.value = mutableCues.value + (characterId to Cue(
            callId, characterId, selected, motion, angles, body, face, sequence
        ))
        return true
    }
}
