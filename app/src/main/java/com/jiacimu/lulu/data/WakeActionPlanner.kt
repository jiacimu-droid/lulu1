package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant

/** Character-led wake strategy with a reliable Android fallback. */
internal object WakeActionPlanner {
    enum class Cue { RING, SPEAK, MUSIC }
    data class Choice(val cue: Cue, val reason: String = "", val volumePercent: Int? = null, val openingLine: String = "")

    fun musicAllowed(context: Context, characterId: String): Boolean {
        val prefs = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        return prefs.getBoolean("$characterId:media", false) &&
            prefs.getBoolean("$characterId:wake_music_enabled", false)
    }

    suspend fun choose(
        context: Context,
        task: CommitmentTask,
        inCall: Boolean,
        now: Instant = Instant.now(),
    ): Choice {
        // The model must never be a single point of failure for a wake alarm.
        val fallback = Choice(Cue.RING)
        val canMusic = musicAllowed(context, task.characterId)
        val raw = withTimeoutOrNull(8_000L) {
            LuluAiServices.gateway.generate(
                characterId = task.characterId,
                facts = buildString {
                    appendLine("真实到点时刻：$now")
                    appendLine("已经接受的叫醒责任：${task.goal}")
                    appendLine("叫醒形式约定：${task.deliveryAction}；已尝试次数：${task.attemptCount}")
                    appendLine("是否还在同一通电话中：$inCall")
                    appendLine("手机音乐控制是否已授权：$canMusic")
                    appendLine("不能假装她已经醒了。")
                },
                instruction = """
                    你是正在履行这项真实责任的原角色。结合自己的性格、关系、
                    已知的习惯与当前约定，自由决定这一刻怎么叫醒她。
                    只返回 JSON：{"cue":"ring|speak|music","reason":"为什么想这么做","volumePercent":35,"openingLine":"如果要打电话，你接通后自然说的第一句"}
                    ring=本机响铃，通话已接通时随后自然开口；
                    speak=已接通的电话里直接以平常声线叫醒，未接通仍需电话铃声；
                    music=让现有授权播放器真实播放音乐，不凭空指定歌曲。
                    答应打电话的任务仍必须实际来电，不能擅自换成纯文字或音乐。
                    music 只在已授权时可选；音量只控制音乐且受安全限幅限制。
                    可以温柔，可以觉得需要更响一点；无需遵循固定顺序。
                    模型超时、网络不通时，已有安卓叫醒响铃负责兜底。
                """.trimIndent(),
                source = "到点叫醒决策",
                title = "角色自主叫醒",
                maxTokens = 320,
                usage = ModelUsage.Chat,
            ).getOrNull()?.text
        } ?: return fallback
        val json = runCatching {
            JSONObject(raw.trim().removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim())
        }.getOrNull() ?: return fallback
        val cue = when (json.optString("cue").trim().lowercase()) {
            "ring" -> Cue.RING
            "speak" -> if (inCall) Cue.SPEAK else Cue.RING
            "music" -> if (canMusic) Cue.MUSIC else Cue.RING
            else -> Cue.RING
        }
        return Choice(cue, json.optString("reason").trim().take(180),
            json.optInt("volumePercent", -1).takeIf { it in 0..65 },
            json.optString("openingLine").trim().take(180))
    }
}
