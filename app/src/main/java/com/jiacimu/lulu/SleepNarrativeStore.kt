package com.jiacimu.lulu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bedtime fiction and guidance progress belong to this call, not to real-life character memories. */
internal object SleepNarrativeStore {
    private var prefs: android.content.SharedPreferences? = null
    @Synchronized fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("lulu_bedtime_narrative_v1", Context.MODE_PRIVATE)
    }
    private fun read(callId: String): JSONObject = runCatching {
        JSONObject(prefs?.getString(callId, "{}") ?: "{}")
    }.getOrDefault(JSONObject())
    private fun save(callId: String, root: JSONObject) {
        check(prefs?.edit()?.putString(callId, root.toString())?.commit() == true)
    }
    @Synchronized fun recordPlayed(callId: String, messageId: String, words: String) {
        if (callId.isBlank() || words.isBlank() || prefs == null) return
        val root = read(callId)
        val old = root.optJSONArray("played") ?: JSONArray()
        if ((0 until old.length()).any { old.optJSONObject(it)?.optString("id") == messageId }) return
        root.put("played", JSONArray().apply {
            for (i in maxOf(0, old.length() - 11) until old.length()) put(old.opt(i))
            put(JSONObject().put("id", messageId).put("words", words.takeLast(3_000)))
        })
        if (root.optString("opening").isBlank()) root.put("opening", words.take(900))
        save(callId, root)
    }
    @Synchronized fun accept(callId: String, turnId: String, raw: String?) {
        val proposal = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        if (callId.isBlank() || turnId.isBlank() || prefs == null) return
        val root = read(callId)
        if (root.optString("lastTurn") == turnId) return
        root.put("lastTurn", turnId)
        when (proposal.optString("kind")) {
            "story" -> {
                root.put("story", JSONObject().apply {
                    listOf("title", "cast", "summary", "lastScene", "openThread").forEach { key ->
                        put(key, proposal.optString(key).ifBlank { root.optJSONObject("story")?.optString(key).orEmpty() }
                            .take(if (key == "summary") 1_800 else 500))
                    }
                })
            }
            "body" -> root.put("bodyStage", proposal.optInt("nextBodyStage", root.optInt("bodyStage"))
                .coerceIn(0, minOf(root.optInt("bodyStage") + 1, BodyRelaxationPath.stages.size)))
        }
        save(callId, root)
    }
    @Synchronized fun context(callId: String, focus: SleepGuidanceFocus): String {
        val root = read(callId)
        return buildString {
            appendLine("【本次哄睡实际播送的连续内容｜故事是虚构，不能算共同经历】")
            // The opening is already present in the played excerpt; avoid
            // repeatedly priming the model to re-say its first sentence.
            if ((root.optJSONArray("played")?.length() ?: 0) == 0)
                root.optString("opening").takeIf(String::isNotBlank)?.let { appendLine("本次最初的铺陈：$it") }
            root.optJSONObject("story")?.let { story ->
                appendLine("已讲故事的延续摘要：${story}。人物、场景和未完成的小线索要承接；不要另起开头。")
            }
            val played = root.optJSONArray("played") ?: JSONArray()
            val words = (0 until played.length()).mapNotNull { played.optJSONObject(it)?.optString("words") }.joinToString("\n")
            if (words.isNotBlank()) appendLine("最近真正播完的正文（含被打断的上一轮已播部分）：${words.takeLast(5_000)}")
            if (focus == SleepGuidanceFocus.Body) appendLine(BodyRelaxationPath.context(root.optInt("bodyStage")))
            appendLine("睡眠进度只表示已经讲过哪些引导，不能证明用户做了、放松了或睡着了。没有新发言也可能一直在认真听。")
            appendLine("可以在 text 后选填 sleepContinuation={kind:story,title:故事名,cast:已出现人物,summary:本次已经说出的剧情摘要,lastScene:刚讲到的场景,openThread:仍未完的小线索}。只摘要本轮确实说出的事，不能预支下一段剧情。身体引导则填 sleepContinuation={kind:body,nextBodyStage:本轮讲完后下一阶段的序号}。程序只有在整段语音实际播放完后才更新摘要/阶段；被打断时只保留确实播完的正文。")
        }.trim()
    }
    @Synchronized fun clear(callId: String) { prefs?.edit()?.remove(callId)?.commit() }
}

internal object BodyRelaxationPath {
    val stages = listOf(
        "找到舒服姿势，感受床或座椅托住身体；允许睁眼，不要求跟做。",
        "额头与眼周：轻轻觉察是否皱紧眉头，让眉间与眼皮松一点；不努力用力闭眼。",
        "下颌、舌头与面颊：牙齿不必咬紧，嘴唇和舌头找到舒服的位置；用缓慢留白代替反复命令。",
        "肩颈与手臂：感觉肩膀的重量，允许肩膀自然落下；手肘、手腕、每根手指都可以暂时不使劲。",
        "胸背与腹部：让身体得到支撑，呼吸保持自然；不屏息、不强迫深呼吸，不要求用力收腹。",
        "臀部、大腿和膝盖：觉察接触面的支撑感，允许腿部停下紧绷；不扭拉、不要求维持姿势。",
        "小腿、脚踝与脚趾：感觉脚被放稳，脚趾不必抓紧；不适的部位可以跳过，注意力回到舒服处。",
        "把注意力铺到全身的重量与支撑感，让声音慢慢陪着；可以转入一段连贯的轻声故事，不再从额头重新点名。",
    )
    fun context(stage: Int): String {
        val next = stage.coerceIn(0, stages.size)
        return if (next == stages.size) "全身引导已讲完，可以少些指令、保持柔和陪伴或转入故事；不要重新扫描全身。"
        else "【身体放松的连续引导】当前第${next + 1}/${stages.size}阶段：${stages[next]} 本段围绕这一处缓慢铺陈并留停顿，不一口气念完整张部位清单。讲完后 nextBodyStage=${next + 1}；并不表示听者真的执行了。"
    }
}
