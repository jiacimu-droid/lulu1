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
        val head: Head? = null, val body: Body? = null, val face: Face? = null)
    // No hard-coded wolf persona: these are semantic channels only when the
    // currently loaded Cubism model confirms each actual parameter ID.
    private val faceChannels = linkedMapOf(
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
            一次自然说话也可以分几次变化小表情：在 live2d 里填写 beats 数组，
            例如 {"beats":[{"atMs":0,"face":{"smile":0.45,"eyeSmile":0.3}},
            {"atMs":1250,"face":{"smile":0.75,"eyeOpen":0.65},"head":{"roll":5}},
            {"atMs":2600,"face":{"smile":0.3},"head":{"roll":0}}]}。
            atMs 从这一轮真实语音开始播放时计时（毫秒），动作随播放推进；
            要表达先憋笑、再忍不住笑、最后收敛时才用这样的连续节拍。
            beats 仅在实际音频播放中运行，不会多调用一次模型。
            通常只需1至3个自然变化；沉默时仍可只用单个 face/head，
            不为了凑动作改变人格，也不把文字描述误认为已完成身体动作。
            若对方明确要求你做某个表情（比如“变成星星眼”），应先核对下方真实表情清单；
            如果确实存在对应表情，这一轮就输出 live2d.expression 来执行，不能只在台词里答应。
            若清单中没有该表情，不要虚构，也不要声称已经做到了。
            例如 live2d={"expression":"表情原名"}，或 live2d={"motion":{"group":"动作组原名","index":0}}。
            有真实需要时可在一个 live2d 中同时写 expression、motion、head、body、face；
            可用头部角度=$headFields（-25至25）；身体姿态=$bodyFields（-10至10）。
            当前可用面部语义参数=$faceFields；每项限 -1 至 1，其中 eyeSmile、squint、
            cheekPuff、pout、eyeOpen 只能填 0 至 1；eyeOpen=1 为正常睁眼，0.4 为轻轻眯眼。
            例如 face={"smile":0.7,"eyeSmile":0.5,"eyeOpen":0.65,"browY":0.15}。
            gazeX/Y 表示眼神，smile 正值表示嘴角上扬，eyeOpen 是上下眼睑开合，
            eyeSmile 与眯眼可组合为笑弯眼，browX/Y 为眉毛左右/上下，browAngle 为倾斜，browForm 为眉形。
            可自主演绎多参数的小表情：例如想专注看着对方，可以用 gazeX/Y 接近0 配合轻微眉眼变化；
            想调皮可以选真实存在的吐舌表情，也可以仅轻歪头、嘴角上扬；难过时可以选择流泪或克制的低眉。
            说话不必只用嘴巴张合：如果真的在笑，可以自主选择笑眼+轻眯眼+嘴角改变；
            想憋笑时也可以选略眯眼、轻微嘴角变化与克制的身体姿态。
            一段自然说话里可以有微表情，不必为了保持对视就一直板着脸。
            这些仅是能力例子，不是按情绪关键词机械派发动作；由当前自身感受、关系和本轮说话意图决定。
            同一回合可以组合一个真实表情、少量头部/躯干姿态和眉眼变化，也可以什么都不做。
            你也可以自主选择只用眼神/表情/轻微姿势回应而暂时不说话；此时仍需输出 live2d，
            由执行器立即展示，不必强迫自己发一段台词。
            视觉动作应当与这轮真正准备说出口的话、语气和自己内心状态一致，而不是从表情文件中随便挑一个。
            表情是一小段有开始和结束的情绪，不是整通电话永久锁定的脸；说完自然恢复常态。
            若打算认真说话，不要突然选择吐舌、爱心眼等与语气冲突的强表情。
            对微妙情绪优先用眼神、嘴角、眉眼及轻微姿态组合，强烈表情只在确有对应情绪的时刻使用。
            眼神和微表情只在当前真实想法需要时短暂出现，并平滑还原；不要以眨眼或随机晃头冒充自主行为。
            当前模型实际支持：表情/外观切换=$expressions；预制动作=$motions。
            切换兽耳、耳机、麦克风属于外观，不要当作情绪动作；没有 motion 就不能假称挥手、跳舞。
            支持的头部转动/倾斜和脸部参数可以组合小动作，但不等于已有真实手臂骨骼动作。
            ${if (controls.motions.isEmpty()) "当前模型没有预制的手部动作文件，不能声称已经执行“抬手捂嘴笑”；可以真实地用眯眼、嘴角、轻微歪头表达憋笑，但不能伪装成抬手。" else "要展示“捂嘴笑”等手部动作，必须先确认下方真实动作清单确有对应动作；仅有嘴角、眉眼参数不等于手臂能动。"}

            说话时嘴巴由本地真实语音播放驱动，禁止用 live2d.face 假装发声。
            当用户明确要求你展示一个模型现有的表情（例如星星眼），请在同一轮回复的
            live2d.expression 中选择对应的真实表情文件名，不能只口头答应却没有动作指令；
            如果真实模型没有这种表情，也不要假装已实现。
            只填写上面列出的真实名字和索引，沿用自己当前的心理状态、关系、情绪变化与表达意图自然决定。
            一个动作若无必要，就保持面对用户的默认自然姿态和眨眼/轻微呼吸；不要每轮随机换表情，
            不要为了显得活泼而固定摆头、反复点头或重复同一动作。
            文本 gesture 和台词里写「笑了」「挥手」并不能驱动身体，
            真想执行模型支持的对应动作才输出 live2d；执行器可能因资源缺失拒绝动作。
            这是身体视觉操作，不是台词、不是新的 API 回合，也不代表任何现实接触。
            若视觉不可用/视频关闭，程序会跳过，不可在正文假称动作已成功执行。
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
        names.firstOrNull { it == clean }?.let { return it }
        fun normalized(value: String): String =
            value.trim().lowercase(java.util.Locale.ROOT)
                .replace(Regex("[\\s_\\-·.（）()\\[\\]]+"), "")
                .removeSuffix("exp3")
        val key = normalized(clean)
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
    fun publish(context: Context, characterId: String, callId: String, request: JSONObject?): Boolean {
        if (request == null || visibleStages[characterId] != callId || callId.isBlank()) return false
        val controls = Live2DAssets.controls(context, characterId)
        val expression = request.optString("expression").trim().takeIf(String::isNotBlank)
        val motion = request.optJSONObject("motion")?.let { item ->
            val group = item.optString("group")
            val index = item.optInt("index", -1)
            (group to index).takeIf { it in controls.motions }
        }
        val selected = expression?.let { resolveExpression(it, controls.expressions) }
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
                if (name != null || beatHead != null || beatBody != null || beatFace != null) {
                    add(Beat(atMs, name, beatHead, beatBody, beatFace))
                }
            }
        }.sortedBy(Beat::atMs)
        if (selected == null && motion == null && angles == null && body == null &&
            face == null && beats.isEmpty()) return false
        sequence++
        mutableCues.value = mutableCues.value + (characterId to Cue(
            callId, characterId, selected, motion, angles, body, face, sequence,
            immediate = request.optBoolean("_executeNow", false),
            beats = beats,
        ))
        return true
    }
}
