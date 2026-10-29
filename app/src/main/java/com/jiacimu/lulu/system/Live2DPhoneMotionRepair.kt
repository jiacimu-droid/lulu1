package com.jiacimu.lulu.system

import android.content.Context
import com.jiacimu.lulu.Live2DAssets
import com.jiacimu.lulu.Live2DSpokenMotionContract
import com.jiacimu.lulu.Live2DVisualActionBus
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.data.ModelStructuredOutput
import com.jiacimu.lulu.data.UnifiedMemoryRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Correct only malformed visual metadata; speech and other actions are immutable. */
internal object Live2DPhoneMotionRepair {
    suspend fun publish(context: Context, characterId: String, callId: String,
        text: String, chosen: JSONObject?, history: String, userText: String,
        sceneContext: String, connection: ModelConnection, title: String,
        onAction: ((JSONObject) -> Unit)?, onStatus: ((String) -> Unit)?,
    ) {
        if (onAction == null) return
        val resolution = prepare(context, characterId, callId, text, chosen, history,
            userText, sceneContext, connection, title)
        if (resolution.errors.isNotEmpty()) {
            val status = "视频动作未配齐，已保留语音；本轮动作补正未成功"
            com.jiacimu.lulu.data.CompanionPresenceStore.recordPerceptionAttempt(characterId,
                "$status · ${resolution.errors.joinToString("；")}")
            onStatus?.invoke(status)
        }
        resolution.chosen?.let { onAction(it) }
    }

    suspend fun prepare(context: Context, characterId: String, callId: String,
        text: String, chosen: JSONObject?, history: String, userText: String,
        sceneContext: String, connection: ModelConnection, title: String,
    ): Live2DSpokenMotionContract.Resolution {
        if (!Live2DVisualActionBus.isStageVisible(characterId, callId))
            return Live2DSpokenMotionContract.Resolution(chosen, emptyList(), false)
        val motions = Live2DAssets.controls(context, characterId).motions
        return Live2DSpokenMotionContract.resolve(text, chosen, motions) { errors ->
            val response = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = JSONObject().put("spokenText", text).put("originalVisual", chosen)
                    .put("validationErrors", org.json.JSONArray(errors)).toString(),
                instruction = """
                    这是你刚才同一次视频回复的动作字段补正，不是新对话或新的工具决定。
                    spokenText 已确定，不得改写、重说、执行工具或修改任何心理/日程。
                    结合本人性格与刚才的语气，补齐每个发声片段句首的真实 motion；短句也要配齐。
                    只返回 {"live2d":{"speech":[{"id":"s1","text":"原台词片段","motion":{"group":"真实组名","index":0},"transition":"blend"}]}}。
                    原计划若已有 speech，保持每个 id、text 和顺序不变，只补正动作。否则划分自然发声片段，所有 text 必须精确拼回 spokenText，保留声音标签、标点和空格。
                    不填写原文锚点或估算的 atMs。下一片段仍继续相同动作时用 transition=continue。
                    ${Live2DVisualActionBus.capabilityNote(context, characterId, callId)}
                """.trimIndent(),
                source = "视频动作契约补正",
                title = title,
                maxTokens = 1_800,
                connectionOverride = connection,
                memoryRequest = UnifiedMemoryRequest(currentInput = userText,
                    sceneContext = sceneContext, recentContext = history,
                    taskIntent = "只补正本人已确定发言的 Motion3 元数据，不重新决定台词或工具"),
            )
            currentCoroutineContext().ensureActive()
            response.getOrNull()?.text?.let(ModelStructuredOutput::objectOrNull)
                ?.optJSONObject("live2d")
        }
    }
}
