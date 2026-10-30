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
    /** One validated phone turn can independently contain speech and a body
     * action. Cosmetic text normalization is not a reason to discard the
     * chosen visual cue; a rejected/empty turn still cannot animate. */
    internal fun canPublishPhoneVisual(
        invalidBlame: Boolean, rewriting: Boolean, callId: String, spoken: String,
    ): Boolean = !invalidBlame && !rewriting &&
        callId.isNotBlank() && spoken.isNotBlank()

    data class Head(val yaw: Float? = null, val pitch: Float? = null, val roll: Float? = null)
    data class Body(val yaw: Float? = null, val pitch: Float? = null, val roll: Float? = null)
    data class Face(val values: Map<String, Float>)
    data class FollowUpMotion(val motion: Pair<String, Int>, val transition: String,
        val metadata: Live2DMotionMetadata? = null)
    /** Milliseconds from the start of audible TTS, not from API request time. */
    data class Beat(val atMs: Int, val expression: String? = null,
        val head: Head? = null, val body: Body? = null, val face: Face? = null,
        val durationMs: Int? = null,
        val anchor: String? = null, val segmentIndex: Int = 0,
        val motion: Pair<String, Int>? = null,
        val segmentId: String? = null, val transition: String = "blend",
        val motionMetadata: Live2DMotionMetadata? = null,
        val followUps: List<FollowUpMotion> = emptyList())
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
        val speechParts: List<Live2DDialoguePlan.Part> = emptyList(),
    )
    private val mutableCues = MutableStateFlow<Map<String, Cue>>(emptyMap())
    val cues = mutableCues.asStateFlow()
    private val visibleStages = mutableMapOf<String, String>()
    private val availableParameters = mutableMapOf<String, Set<String>>()
    private var sequence = 0L
    // An ephemeral renderer receipt for the SAME role and phone session.
    // A planned Cue is not proof that Cubism started an authored motion.
    private data class MotionReceipt(
        val callId: String, val sequence: Long, val motion: Pair<String, Int>,
        val segmentId: String, val started: Boolean,
    )
    private val recentMotionReceipts = mutableMapOf<String, MutableList<MotionReceipt>>()

    @Synchronized
    fun stageReady(characterId: String, callId: String) {
        if (characterId.isNotBlank() && callId.isNotBlank()) {
            if (visibleStages[characterId] != callId) recentMotionReceipts.remove(characterId)
            visibleStages[characterId] = callId
        }
    }

    @Synchronized
    fun stageClosed(characterId: String, callId: String) {
        if (visibleStages[characterId] == callId) {
            visibleStages.remove(characterId)
            availableParameters.remove(characterId)
        }
        if (recentMotionReceipts[characterId]?.any { it.callId == callId } == true)
            recentMotionReceipts.remove(characterId)
        if (mutableCues.value[characterId]?.callId == callId) {
            mutableCues.value = mutableCues.value - characterId
        }
    }

    /**
     * Accept only the current Cubism player's acknowledgement for a motion
     * selected by this character in this exact, still visible call and score.
     * Manual preview, old WebViews, cancelled scores and unselected clips are not facts.
     */
    @Synchronized
    fun recordMotionOutcome(characterId: String, callId: String, phase: String, detail: String): Boolean {
        if (phase != "motion-started" && phase != "motion-failed") return false
        if (visibleStages[characterId] != callId || callId.isBlank()) return false
        val cue = mutableCues.value[characterId]?.takeIf { it.callId == callId } ?: return false
        val event = runCatching { JSONObject(detail) }.getOrNull() ?: return false
        if (event.optString("source") != "character" ||
            event.optLong("sequence") != cue.sequence || cue.sequence <= 0L) return false
        val selected = event.optString("group") to event.optInt("index", -1)
        val segmentId = event.optString("segmentId")
        val authorized = if (segmentId.isNotBlank()) {
            cue.beats.any { it.segmentId == segmentId &&
                (it.motion == selected || it.followUps.any { item -> item.motion == selected }) }
        } else {
            cue.motion == selected || cue.beats.any { it.segmentId == null &&
                (it.motion == selected || it.followUps.any { item -> item.motion == selected }) }
        }
        if (!authorized) return false
        val recent = recentMotionReceipts.getOrPut(characterId) { mutableListOf() }
        recent.removeAll { it.callId == callId && it.sequence == cue.sequence &&
            it.segmentId == segmentId && it.motion == selected }
        recent.add(MotionReceipt(callId, cue.sequence, selected, segmentId,
            phase == "motion-started"))
        if (recent.size > 5) recent.removeAt(0)
        return true
    }

    /** True only while this call actually has a visible, ready character body. */
    @Synchronized
    fun isStageVisible(characterId: String, callId: String): Boolean =
        characterId.isNotBlank() && callId.isNotBlank() &&
            visibleStages[characterId] == callId

    /**
     * Voice generation and the first video frame have independent lifecycles.
     * Permit this character to prepare a Motion3 score as soon as the selected
     * imported model is known, while the renderer is still loading. This does
     * NOT claim that the frame or the motion has actually been displayed.
     * Inactive calls, voice mode and missing models cannot queue video cues.
     */
    internal fun isVideoPlanEligible(
        requested: Boolean, connected: Boolean, sameCharacter: Boolean,
        sameCall: Boolean, hasImportedModel: Boolean,
    ): Boolean = requested && connected && sameCharacter && sameCall && hasImportedModel

    @Synchronized
    fun canPlanVideo(context: Context, characterId: String, callId: String): Boolean {
        if (characterId.isBlank() || callId.isBlank()) return false
        val live = LuluVoiceCallSession.state.value
        return isVideoPlanEligible(
            requested = LuluCallWindowController.videoRequested.value,
            connected = live.connected,
            sameCharacter = live.characterId == characterId,
            sameCall = live.callExperienceId == callId,
            hasImportedModel = Live2DAssets.modelUrl(context, characterId) != null,
        )
    }

    /** A ready stage may play immediately; a live video call may queue while loading. */
    @Synchronized
    private fun canAcceptCue(context: Context, characterId: String, callId: String): Boolean =
        isStageVisible(characterId, callId) || canPlanVideo(context, characterId, callId)

    @Synchronized
    fun stageParameters(characterId: String, callId: String, ids: String) {
        if (visibleStages[characterId] != callId) return
        availableParameters[characterId] = ids.split(',').map(String::trim)
            .filter { it in validParameters }.toSet()
    }

    @Synchronized
    fun capabilityNote(context: Context, characterId: String, callId: String): String {
        if (!canAcceptCue(context, characterId, callId)) return ""
        val controls = Live2DAssets.controls(context, characterId)
        if (controls.expressions.isEmpty() && controls.motions.isEmpty()) return ""
        if (controls.motions.isEmpty()) return "当前视频模型没有 Motion3；正常输出 text，可选 live2d.expression，真实表情：${controls.expressions.joinToString("、")}。不得虚构身体动作。"
        val expressions = controls.expressions.take(20).joinToString("、").ifBlank { "无" }
        val motions = controls.motions.joinToString("、") { (group, index) ->
            Live2DMotionCatalog.describe(group, index) ?: "$group#$index"
        }.ifBlank { "无" }
        return """
            【当前 Live2D 视频通话：同一次回复里由你亲自选择动作】
            这是已导入模型真实的动作资源目录，即使画面仍在加载，你也可以先自主选择每句话的 Motion3；程序按实际音频与视频加载状态执行。选择不等于播放成功。
            你的角色可以自己决定说什么、什么时候做出什么神态或动作；说话时默认要有动作陪着，不要站着干说。
            最重要的是使用真正存在的 Motion3 动作，不要自己编造头部/身体角度或指望文字描述让角色动起来。
            真实动作目录（group#index=动作描述）：
            $motions
            真实表情资源：$expressions
            结合你的性格、当下心情与每句话的语气，自主从真实目录选择最合适的 Motion3，不按关键词机械匹配。视频发言的输出契约：每个实际说话片段都必须对应你选择的真实动作，不能只给回复开头配动作；执行前会检查漏项。日常短句可选轻微动作，神态和力度仍由你自己决定。
            本次可见视频的 reply 使用 speech 数组作为唯一台词来源，取代普通电话的 text；不要重复输出 text 或 bubbles。
            {"action":"reply","speech":[
               {"id":"s1","text":"你回来了！","motion":{"group":"LuluWolf","index":17},"transition":"blend"},
               {"id":"s2","text":"不过我可没一直等你。","motion":{"group":"LuluWolf","index":6},"transition":"blend"}
             ]}
            上面仅说明格式；必须自行从真实目录选动作，示例不是固定搭配。
            speech 每个片段的 id 唯一、text 非空，motion.group/index 必须与目录完全一致（索引从0开始），整轮最多16段。每段直接绑定字幕、语音与动作，不使用原文锚点或估算的 atMs；同一句话重复出现也用不同 id。
            按自然说话意图分段，避免为了动而拆成单字。声音标签直接保留在相应 text 中；整轮保持连贯情绪，不逐句随机变声。
            同一段 text 可能跨好几个逗号，首个短 Motion3 播完并不等于这段已经说完。角色可在一段 speech 内自主选后续动作 followUps（1至5个），不必把自然语音切碎：
            {"id":"s1","text":"我想了想，其实也不是不行，不过先说好，我有个小条件。","motion":{"group":"LuluWolf","index":17},"followUps":[{"motion":{"group":"LuluWolf","index":6}},{"motion":{"group":"LuluWolf","index":16}}]}
            followUps 中每个 motion 都须来自本次真实目录；可选 transition=blend|continue。前一个真实 Motion3 演完、这段 TTS 还在发声时，再执行下一个；实际语音结束就取消未播动作。要符合当下本人想表达的神态，既不按逗号机械计数，也不强制全程不停挥手，不另调用模型。
            新动作 transition=blend，从当前真实姿态衔接；若下一段仍继续同一个动作，填相同 motion 并用 transition=continue，播放器不从头重播尚在进行的动作。
            不要为普通 Motion 使用 head/body/face 数值或 performance.movement；原生 Motion 包含自己完整的身体协调、节奏与回弹，唇形随真实声音独立变化。
            如果你想用这个模型真实提供的表情资源，可单独选择 expression，也可配合一个 motion；没有的资源不能虚构。
            speech 每段可选 expression=真实表情名。表情可以沿用；身体动作、面部与口型独立协调。
            选择 silent 安静陪伴时可以省略 live2d；选择 reply 开口时每个片段须有可执行 Motion3。模型不存在的动作不能编造，程序不会按台词替你猜动作。
            老的 head/body/face/performance 只保留历史兼容，不推荐用于日常视频说话。
            当前画面未准备好时动作不能假称已经成功展示；电话/视频仍是同一角色的表达，不产生第二套人格。
        """.trimIndent() + recentMotionReceipts[characterId]
            ?.filter { it.callId == callId }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "；", prefix = "\n【最近真实身体执行回执】", postfix =
                "。仅表示 Cubism 播放器的启动结果，不证明主人看见全部动作。") { receipt ->
                val title = Live2DMotionCatalog.describe(receipt.motion.first, receipt.motion.second)
                    ?: "${receipt.motion.first}#${receipt.motion.second}"
                val outcome = if (receipt.started) "已启动" else "被播放器拒绝/失败"
                "${title}（${receipt.motion.first}#${receipt.motion.second}）：${outcome}"
            }.orEmpty()
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
        if (chosen == null || !canAcceptCue(context, characterId, callId)) return false
        val paired = Live2DDialoguePlan.parts(chosen)
        val controls = Live2DAssets.controls(context, characterId)
        if (chosen.has("speech") && (paired == null || paired.any { part ->
                part.motion !in controls.motions || part.followUps.any { it.motion !in controls.motions }
            })) return false
        // An explicit original Motion3 is the main performance authority;
        // never inject a second parameter-generated body score on top of it.
        val request = if (paired != null) JSONObject().put("beats", Live2DDialoguePlan.beats(paired))
            else if (chosen.optJSONObject("motion") != null) chosen
            else Live2DConversationPerformance.expand(chosen)
        val expression = request.optString("expression").trim().takeIf(String::isNotBlank)
        // The LLM chooses the intent; if it chose a movement, execute the
        // imported rig's REAL authored clip rather than only a tiny parameter
        // nudge. Explicit group/index always takes precedence.
        val explicitMotion = request.optJSONObject("motion")?.let { item ->
            val group = item.optString("group")
            val index = item.optInt("index", -1)
            (group to index).takeIf { it in controls.motions }
        }
        // The role chooses an exact authored motion. Do not turn its semantic
        // performance description into a different hard-coded clip.
        val motion = explicitMotion
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
                val anchor = item.optString("anchor").trim().takeIf { it.isNotEmpty() && it.length <= 60 }
                if (!item.has("atMs") && anchor == null) continue
                val atMs = item.optInt("atMs", 0)
                if (atMs !in 0..30_000) continue
                var name = item.optString("expression").trim()
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
                var beatFace = beatFaceValues.takeIf { it.isNotEmpty() }?.let(::Face)
                val beatMotion = item.optJSONObject("motion")?.let { selection ->
                    (selection.optString("group") to selection.optInt("index", -1))
                        .takeIf { it in controls.motions }
                }
                // In the existing motion catalog, some complete original clips
                // come with an authored expression/face pairing. A timed clip
                // must behave like its top-level equivalent, unless the model
                // explicitly chose a different valid face.
                val catalogBeat = beatMotion?.takeIf { it.first == "LuluWolf" }
                    ?.let { Live2DMotionCatalog.entries[it.second] }
                if (catalogBeat != null) {
                    if (name == null && catalogBeat.expression != null) {
                        name = resolveExpression(catalogBeat.expression, controls.expressions)
                    }
                    if (beatFace == null && catalogBeat.face.isNotEmpty()) {
                        val catalogValues = mutableMapOf<String, Float>()
                        for ((key, ids) in faceChannels) {
                            val value = catalogBeat.face[key] ?: continue
                            if (ids.all { it in allowed }) catalogValues[key] = value.coerceIn(
                                if (key in positiveFaceChannels) 0f else -1f, 1f)
                        }
                        beatFace = catalogValues.takeIf { it.isNotEmpty() }?.let(::Face)
                    }
                }
                val durationMs = if (item.has("durationMs")) item.optInt("durationMs", -1)
                    .takeIf { it in 1..30_000 } else null
                if (name != null || beatHead != null || beatBody != null ||
                    beatFace != null || beatMotion != null) {
                    // Model-selected Motion3 is a first-class timed beat, not
                    // a parameter approximation. Keep the complete clip intact.
                    val followUps = buildList {
                        val selected = item.optJSONArray("followUps")
                        if (paired != null && selected != null) {
                            for (j in 0 until minOf(selected.length(), 5)) {
                                val extra = selected.optJSONObject(j) ?: continue
                                val entry = extra.optJSONObject("motion") ?: continue
                                val clip = entry.optString("group") to entry.optInt("index", -1)
                                val mode = extra.optString("transition", "blend")
                                if (clip !in controls.motions || mode !in setOf("blend", "continue")) continue
                                add(FollowUpMotion(clip, mode,
                                    Live2DAssets.motionMetadata(context, characterId, clip.first, clip.second)))
                            }
                        }
                    }
                    add(Beat(atMs, name, beatHead, beatBody, beatFace, durationMs,
                        anchor, segmentIndex = if (paired != null) item.optInt("segmentIndex") else 0,
                        motion = beatMotion, segmentId = if (paired != null) item.optString("segmentId") else null,
                        transition = if (paired != null) item.optString("transition", "blend") else "blend",
                        motionMetadata = beatMotion?.let { Live2DAssets.motionMetadata(context, characterId, it.first, it.second) },
                        followUps = followUps))
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
            speechParts = paired.orEmpty(),
        ))
        return true
    }

    private val livenessFloorCounter = java.util.concurrent.atomic.AtomicLong(0)

    /** Small authored everyday clips (nod, listening) eligible for the floor. */
    private val livenessFloorMotions = listOf(0, 3, 30, 34)

    /**
     * Liveness floor for a spoken reply that carried no visual choice at
     * all. The character's own choices always win: this runs only when the
     * reply produced no cue, and it never invents an emotion, an expression
     * or a pose — it replays one small authored everyday clip from the real
     * catalog so a talking character does not stand frozen for a whole
     * reply. Rotation across replies is deterministic, not random. Models
     * without the wolf catalog simply get no floor.
     */
    @Synchronized
    fun publishLivenessFloor(context: Context, characterId: String, callId: String): Boolean {
        if (callId.isBlank() || visibleStages[characterId] != callId) return false
        val controls = Live2DAssets.controls(context, characterId)
        val candidates = livenessFloorMotions.filter { index ->
            ("LuluWolf" to index) in controls.motions &&
                Live2DMotionCatalog.describe("LuluWolf", index) != null
        }
        if (candidates.isEmpty()) return false
        val index = candidates[Math.floorMod(
            livenessFloorCounter.getAndIncrement(), candidates.size.toLong()).toInt()]
        return publish(context, characterId, callId, JSONObject()
            .put("motion", JSONObject().put("group", "LuluWolf").put("index", index)))
    }

    /**
     * A video reply whose motion selection failed still owns a NEW audible
     * speech lease. Retire the former turn's score so its version fence cannot
     * reject every later real TTS start as "stale". This marker carries NO
     * Motion3 or emotion; it must never be reported as action success.
     */
    @Synchronized
    fun publishUnplannedSpeech(context: Context, characterId: String, callId: String): Long {
        if (callId.isBlank() || characterId.isBlank() ||
            !canAcceptCue(context, characterId, callId)) return 0L
        sequence++
        mutableCues.value = mutableCues.value +
            (characterId to Cue(callId, characterId, sequence = sequence))
        return sequence
    }

    /** Bind only exact phrase anchors to actual queued TTS starts, fenced by
     * the issuing visual decision. Never infer timestamps for missing quotes. */
    @Synchronized
    fun bindSpeechAnchors(characterId: String, callId: String, expectedSequence: Long,
        segmentForAnchor: Map<String, Int>): Long {
        val cue = mutableCues.value[characterId] ?: return 0L
        if (visibleStages[characterId] != callId || cue.callId != callId ||
            cue.sequence != expectedSequence) return 0L
        if (cue.beats.none { it.anchor != null }) return expectedSequence
        val aligned = cue.beats.mapNotNull { beat ->
            val index = beat.anchor?.let(segmentForAnchor::get)
            if (beat.anchor != null && index == null) null
            else beat.copy(segmentIndex = index ?: 0)
        }
        sequence++
        mutableCues.value = mutableCues.value +
            (characterId to cue.copy(beats = aligned, sequence = sequence))
        return sequence
    }
}
