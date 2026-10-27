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
    /** Milliseconds from the start of audible TTS, not from API request time. */
    data class Beat(val atMs: Int, val expression: String? = null,
        val head: Head? = null, val body: Body? = null, val face: Face? = null,
        val durationMs: Int? = null)
    // No hard-coded wolf persona: these are semantic channels only when the
    // currently loaded Cubism model confirms each actual parameter ID.
    internal val faceChannels = linkedMapOf(
        "gazeX" to listOf("ParamEyeBallX"),
        "gazeY" to listOf("ParamEyeBallY"),
        "smile" to listOf("ParamMouthForm"),
        "eyeSmile" to listOf("ParamEyeLSmile", "ParamEyeRSmile"),
        "eyeOpen" to listOf("ParamEyeLOpen", "ParamEyeROpen"),
        "browX" to listOf("ParamBrowLX", "ParamBrowRX"),
        "browY" to listOf("ParamBrowLY", "ParamBrowRY"),
        "browAngle" to listOf("ParamBrowLAngle", "ParamBrowRAngle"),
        "browForm" to listOf("ParamBrowLForm", "ParamBrowRForm"),
        "squint" to listOf("ParamEyeLSquint", "ParamEyeRSquint"),
        "cheekPuff" to listOf("ParamCheekPuff"),
        "pout" to listOf("ParamMouthFunnel"),
    )
    private val positiveFaceChannels = setOf("eyeOpen", "eyeSmile", "squint", "cheekPuff", "pout")
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
        val immediate: Boolean = false,
        val beats: List<Beat> = emptyList(),
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

    /** True only while this call actually has a visible, ready character body. */
    @Synchronized
    fun isStageVisible(characterId: String, callId: String): Boolean =
        characterId.isNotBlank() && callId.isNotBlank() &&
            visibleStages[characterId] == callId

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
        val motions = controls.motions.joinToString("、") { (group, index) ->
            Live2DMotionCatalog.describe(group, index) ?: "$group#$index"
        }.ifBlank { "无" }
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
            【当前可视电话：已确认显示的 Live2D 身体】
            这是普通聊天也能自主使用的身体能力，无需等用户要求“做个表情”。
            在这同一次决定里，结合本人此刻感受、关系、这句话的意思与语气，选择要说的话与愿意显露的神态/小动作；
            它们是同一个人的一次表达，不另起人格、不增加模型调用。也允许不动或安静地只用神态回应。
            你可以在正常说话的同时主动使用这个真实数字身体，不必等用户点动作按钮，也不需要第二次模型调用。自然聊天可有点头、转头、歪头、笑眼、好奇探头、害羞低头等反应；由你根据本人的真实感受选择，没有想动时可以静止。
            【必须选择结构化可执行动作】只在文字中写“我歪歪头/笑了/跳起来”，身体是不会动的；同一次 reply JSON 要明确填写 live2d 才能播放：
            {"action":"reply","text":"自己真正要说的话","live2d":{"performance":{"feeling":"thinking","movement":"glance","intensity":0.35,"side":"left"}}}。
            若更想用这套皮套已有的完整动作，可以同时选择真实 motion，例如 {"live2d":{"motion":{"group":"LuluWolf","index":3},"performance":{"feeling":"curious"}}}；group/index 必须来自下方当前皮套目录。
            feeling 与 movement 可单独选，也可组合；intensity 为0至1，省略为0.45；side=left|right。
            ${Live2DConversationPerformance.capabilityNote(supported)}
            这些名字是你主动选择的视觉意图，不是系统根据台词关键词判情绪。不要为了填字段强迫自己笑、害羞、点头；
            普通交谈的神态也可以只有轻微嘴角、眉眼、视线和身体变化，不需要每轮换整张表情或循环摆动。
            当这句话本人确实带着笑意说，选 smile/happy/amused；确实委屈、含泪时可选 aggrieved/tearful。
            程序会将选定的眉眼、嘴角、头颈与身体组合并随整段语音保留；PCM 嘴巴开合独立控制，不会抹掉嘴角笑意。
            不从说话文本关键词自动推断情绪，也不强制每句都做动作。
            程序会把所选意图编成短暂的起势、变化、收敛，只操作下方确认存在的参数，随真实语音开始执行并平滑恢复。
            思考不必伴随点头，笑意不必伴随亲密；组合、强弱、是否显露由本人决定，例子不是固定人设或聊天脚本。

            需要精确控制时，仍可同时填写 expression、motion、head、body、face 或 beats；显式数值优先于 performance。
            真实表情/外观 expression 原名=$expressions；真实预制 motion（group#index=名字·适合时；配表情与神态为默认搭配）=$motions。选 motion 时若不另外指定表情/神态，默认带目录里的配表情与神态；想换就明确写 expression/face。
            若明确答应展示模型已有表情（如星星眼），须在 live2d.expression 选择对应真实名字，不能只口头答应；没有资源不能虚构。
            若真的兴奋想呈现特殊眼睛，只选真实存在的星星眼；本皮套不再使用爱心眼，原来配爱心眼的动作仍能播放，程序只替换眼睛效果。流泪、吐舌等强表情仅按本人当下需要明确选择；轻微笑意或羞赧不会自动升级成它们。
            兽耳、耳机、麦克风是外观开关，不当情绪用；没有预制手部动作不能声称已经挥手或捂嘴。
            可用 head=$headFields（-25至25），body=$bodyFields（-10至10）；face=$faceFields。
            face 每项-1至1；eyeSmile、squint、cheekPuff、pout、eyeOpen 为0至1，eyeOpen=1正常睁眼；
            gazeX/Y是视线，smile是嘴角，eyeSmile是笑眼，browX/Y/Angle/Form是眉形；不用嘴巴开合假装发声。
            自己想演绎更具体的先后变化时才写 beats，例如
            {"beats":[{"atMs":0,"face":{"smile":0.2}},
            {"atMs":700,"head":{"roll":4},"body":{"roll":1},"face":{"smile":0.5,"eyeSmile":0.3},"durationMs":900}]}。
            beats 的 atMs 从本轮真实音频播放开始计时，durationMs 可选，表示本拍参数保持多久再平滑还原；显式 beats 优先。
            沉默时所选小动作直接执行，不必硬配台词；文字 gesture 或括号描述本身不能驱动模型。
            正在发完整词句时不能同时吐舌或用口部动作阻碍发声；播放执行器会等真实PCM停顿或语音结束才执行这类表情。
            没有PCM不能猜停顿。笑眼、脸红和轻微姿态可以伴随语气，但应与实际要说的话一致。
            视频关闭或身体未就绪时不会执行，不能把选择或描述当作已成功展示。没有身体意图时可省略 live2d。
        """.trimIndent()
    }

    /**
     * Speech recognition and language models do not always emit an expression
     * file's literal basename. Resolve semantic aliases ONLY against actual
     * exp3 entries found in this role's on-device model manifest. Never
     * fabricate an expression that the model doesn't have.
     */
    internal fun resolveExpression(requested: String, names: List<String>): String? {
        val clean = requested.trim()
        if (clean.isEmpty()) return null
        fun normalized(value: String): String =
            value.trim().lowercase(java.util.Locale.ROOT)
                .replace(Regex("[\\s_\\-·.（）()\\[\\]]+"), "")
                .removeSuffix("exp3")
        val key = normalized(clean)
        val isHeart = listOf("爱心眼", "愛心眼", "心形眼", "hearteye",
            "loveeye", "heartshape").any { normalized(clean).contains(normalized(it)) }
        if (isHeart) {
            // Keep the user's selected motion; only replace the disliked eye
            // overlay with a real supported alternative or no overlay.
            return names.firstOrNull { actual ->
                val n = normalized(actual)
                listOf("星星眼", "星眼", "stareye", "starryeye", "sparkleeye")
                    .any(n::contains)
            } ?: names.firstOrNull { normalized(it).contains("脸红") ||
                normalized(it).contains("blush") }
        }
        names.firstOrNull { normalized(it) == key }?.let { return it }
        // Only high-confidence names, e.g. "星星眼" vs "小狼-星星眼".
        // Don't select vaguely similar expressions such as "笑眼".
        val isStar = key.contains("星星眼") || key.contains("星眼") ||
            key.contains("stareye") || key.contains("starryeye") ||
            key.contains("starry") || key.contains("sparkleeye")
        if (isStar) return names.firstOrNull {
            val label = normalized(it)
            label.contains("星星眼") || label.contains("星眼") ||
                label.contains("stareye") || label.contains("starryeye")
        }
        val aliases = mapOf(
            "爱心眼" to listOf("爱心眼", "心形眼", "hearteye"),
            "脸红" to listOf("脸红", "害羞", "blush"),
            "流泪" to listOf("流泪", "眼泪", "tears"),
            "吐舌" to listOf("吐舌", "舌头", "tongue"),
        )
        val hits = aliases.values.firstOrNull { variants ->
            variants.any { normalized(it) == key }
        } ?: return null
        return names.firstOrNull { actual ->
            hits.any { word -> normalized(actual).contains(normalized(word)) }
        }
    }

    /** Enforces the exact imported model manifest at execution time, independent of the LLM. */
    @Synchronized
    fun publish(context: Context, characterId: String, callId: String, chosen: JSONObject?): Boolean {
        if (chosen == null || visibleStages[characterId] != callId || callId.isBlank()) return false
        val request = Live2DConversationPerformance.expand(chosen)
        val controls = Live2DAssets.controls(context, characterId)
        val expression = request.optString("expression").trim().takeIf(String::isNotBlank)
        val motion = request.optJSONObject("motion")?.let { item ->
            val group = item.optString("group")
            val index = item.optInt("index", -1)
            (group to index).takeIf { it in controls.motions }
        }
        // The character explicitly chose a tearful PERFORMANCE. If this
        // imported model offers a real tear exp3, combine it with the same
        // chosen face/body pose; never fabricate tears or choose dark-face.
        val selected = expression?.let { resolveExpression(it, controls.expressions) }
            ?: if (expression == null &&
                request.optJSONObject("performance")?.optString("feeling") == "tearful")
                resolveExpression("流泪", controls.expressions) else null
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
        // A chosen catalog motion carries its combined performance: the
        // catalog's suggested expression and face apply only when the role
        // did not specify them. Explicit values always win.
        val catalogEntry = motion?.takeIf { it.first == "LuluWolf" }
            ?.let { Live2DMotionCatalog.entries[it.second] }
        var filledExpression = selected
        var filledFace = face
        if (catalogEntry != null) {
            if (filledExpression == null && catalogEntry.expression != null) {
                filledExpression = resolveExpression(catalogEntry.expression, controls.expressions)
            }
            if (filledFace == null && catalogEntry.face.isNotEmpty()) {
                val filledValues = mutableMapOf<String, Float>()
                for ((key, ids) in faceChannels) {
                    val value = catalogEntry.face[key] ?: continue
                    if (!ids.all { it in allowed }) continue
                    filledValues[key] = value.coerceIn(
                        if (key in positiveFaceChannels) 0f else -1f, 1f)
                }
                filledFace = filledValues.takeIf { it.isNotEmpty() }?.let(::Face)
            }
        }
        // A model may plan a small progression during ONE paid speech turn.
        // Filter each beat against THIS imported model, not a global catalogue.
        val beatsJson = request.optJSONArray("beats")
        val beats = buildList {
            if (beatsJson != null) for (i in 0 until minOf(beatsJson.length(), 16)) {
                val item = beatsJson.optJSONObject(i) ?: continue
                if (!item.has("atMs")) continue
                val atMs = item.optInt("atMs", -1)
                if (atMs !in 0..30_000) continue
                val name = item.optString("expression").trim()
                    .takeIf(String::isNotBlank)
                    ?.let { resolveExpression(it, controls.expressions) }
                val beatHeadJson = item.optJSONObject("head")
                fun headNumber(key: String, id: String): Float? =
                    if (beatHeadJson == null || id !in allowed || !beatHeadJson.has(key)) null
                    else beatHeadJson.optDouble(key, Double.NaN).takeIf(Double::isFinite)
                        ?.coerceIn(-25.0, 25.0)?.toFloat()
                val beatHead = Head(
                    yaw = headNumber("yaw", "ParamAngleX"),
                    pitch = headNumber("pitch", "ParamAngleY"),
                    roll = headNumber("roll", "ParamAngleZ"),
                ).takeIf { it.yaw != null || it.pitch != null || it.roll != null }
                val beatBodyJson = item.optJSONObject("body")
                fun bodyNumber(key: String, id: String): Float? =
                    if (beatBodyJson == null || id !in allowed || !beatBodyJson.has(key)) null
                    else beatBodyJson.optDouble(key, Double.NaN).takeIf(Double::isFinite)
                        ?.coerceIn(-10.0, 10.0)?.toFloat()
                val beatBody = Body(
                    yaw = bodyNumber("yaw", "ParamBodyAngleX"),
                    pitch = bodyNumber("pitch", "ParamBodyAngleY"),
                    roll = bodyNumber("roll", "ParamBodyAngleZ"),
                ).takeIf { it.yaw != null || it.pitch != null || it.roll != null }
                val beatFaceJson = item.optJSONObject("face")
                val beatFaceValues = mutableMapOf<String, Float>()
                if (beatFaceJson != null) for ((key, ids) in faceChannels) {
                    if (!beatFaceJson.has(key) || !ids.all { it in allowed }) continue
                    val value = beatFaceJson.optDouble(key, Double.NaN)
                    if (value.isFinite()) {
                        beatFaceValues[key] = value.coerceIn(
                            if (key in positiveFaceChannels) 0.0 else -1.0, 1.0).toFloat()
                    }
                }
                val beatFace = beatFaceValues.takeIf { it.isNotEmpty() }?.let(::Face)
                val durationMs = if (item.has("durationMs")) item.optInt("durationMs", -1)
                    .takeIf { it in 1..30_000 } else null
                if (name != null || beatHead != null || beatBody != null || beatFace != null) {
                    add(Beat(atMs, name, beatHead, beatBody, beatFace, durationMs))
                }
            }
        }.sortedBy(Beat::atMs)
        if (filledExpression == null && motion == null && angles == null && body == null &&
            filledFace == null && beats.isEmpty()) return false
        sequence++
        mutableCues.value = mutableCues.value + (characterId to Cue(
            callId, characterId, filledExpression, motion, angles, body, filledFace, sequence,
            immediate = request.optBoolean("_executeNow", false),
            beats = beats,
        ))
        return true
    }
}
