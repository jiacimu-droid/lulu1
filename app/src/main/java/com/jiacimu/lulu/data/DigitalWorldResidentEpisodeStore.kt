package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Program-owned, continuing NPC encounters. Dialogue is the event; a synopsis is not proof. */
internal object DigitalWorldResidentEpisodeStore {
    val starts = setOf("share_text", "co_write", "exchange_views")
    val interactions = starts + "continue_episode"
    private var prefs: android.content.SharedPreferences? = null

    internal data class Step(val id: String, val characterId: String, val residentId: String,
        val kind: String, val contribution: String, val prior: JSONObject?, val preparedAt: Instant)

    @Synchronized fun initialize(context: Context) {
        if (prefs == null) prefs = context.applicationContext
            .getSharedPreferences("lulu_resident_episodes_v1", Context.MODE_PRIVATE)
    }

    private fun records(characterId: String): List<JSONObject> = runCatching {
        val array = JSONArray(prefs?.getString(characterId, "[]") ?: "[]")
        (0 until array.length()).mapNotNull(array::optJSONObject)
    }.getOrDefault(emptyList())

    private fun save(characterId: String, items: List<JSONObject>) {
        val open = items.filter { it.optString("status") in setOf("waiting", "revision") }
        val closed = items.filterNot { it in open }.takeLast(20)
        check(prefs?.edit()?.putString(characterId, JSONArray(open + closed).toString())?.commit() == true) {
            "居民事件未能保存"
        }
    }

    @Synchronized fun prepare(characterId: String, residentId: String, action: String,
        args: JSONObject, now: Instant): Step {
        check(prefs != null) { "居民事件尚未初始化" }
        require(action in interactions) { "无此居民事件动作" }
        val text = args.optString("text").trim()
        require(text.length <= 500) { "本次交给居民的文字须在500字以内" }
        val all = records(characterId)
        if (action in starts) {
            require(text.isNotBlank()) { "请提供自己真正想交给居民的短文、上句或具体观点" }
            require(all.count { it.optString("status") in setOf("waiting", "revision") } < 3) { "已有三件居民小事未完，可以先继续或放下" }
            require(all.none { it.optString("residentId") == residentId &&
                it.optString("status") in setOf("waiting", "revision") }) { "和这位居民已有未完的事，请沿用原事件ID" }
            return Step(UUID.randomUUID().toString(), characterId, residentId, action, text, null, now)
        }
        val previous = all.firstOrNull { it.optString("id") == args.optString("incidentId") }
            ?: error("找不到属于本人的这段居民经历")
        require(previous.optString("residentId") == residentId) { "不能让别的居民代答这件事" }
        require(previous.optString("status") in setOf("waiting", "revision")) { "这件事已结束，不应再次生成结果" }
        require(!now.isBefore(Instant.parse(previous.getString("readyAt")))) { "还没到双方约好再谈的时间" }
        require(previous.optInt("rounds") < 4) { "这段交流已经结束，可以另起新话题" }
        if (previous.optString("status") == "revision") require(text.isNotBlank()) { "继续修改需要真正提供修改后的文字或观点" }
        return Step(previous.getString("id"), characterId, residentId, previous.getString("kind"),
            text.ifBlank { previous.getString("contribution") }, previous, now)
    }

    fun prompt(step: Step): String = buildString {
        appendLine("【本次持续居民小事｜只让当前交流推进一个阶段】")
        appendLine("episodeId=${step.id}；类型=${step.kind}；本次角色真正写下/提出的原文=${step.contribution}")
        if (step.prior == null || step.contribution != step.prior.optString("contribution"))
            appendLine("角色须在自己本次台词中原样交出上面的完整原文，让对方实际看见；不能只称‘我写了一段’。")
        step.prior?.let { appendLine("此前已发生且保存的原文与进度：$it") }
        appendLine("share_text=请对方点评本人的短文；co_write=双方各写真正的一小段接力作品；exchange_views=就这个具体观点在约好稍后后再次讨论。不得冒充读过未提供的书、做过研究或完成工具任务。")
        appendLine("本次 JSON 另写 episode={\"response\":\"选择\",\"residentTurnIndex\":从0开始的居民台词序号,\"resultText\":\"居民本次实际写出/说出的具体片段\"}。resultText 必须原样出现于所指居民台词，不能用泛泛摘要代替。")
        if (step.prior == null) {
            appendLine("这是第一次提出。居民按自己的身份和已有观点选择 response=waiting（愿意稍后继续）或 declined（婉拒）。愿意时约十分钟后再当面谈，先接住具体原文，不提前编造未来的完成结果；也不要求他一定愿意。")
        } else {
            val choices = when (step.kind) {
                "co_write" -> "continued/revision/declined"
                "exchange_views" -> "agreed/different_view/undecided/declined"
                else -> "appreciated/revision/different_view/declined"
            }
            appendLine("现在只完成这一次真实回应。response 可选 $choices。接力作品选 continued 时要真正写出下一小段，不能只称赞；revision 必须明确哪一处想改，不保证下次成功。")
        }
        append("居民拥有自己的口味、注意力、理由和界限，沿用此前台词和身份，不知道角色的私人期待，不以取悦角色为目的。可以欣赏具体之处、意见相左、觉得仍需打磨、暂不能判断或礼貌拒绝。不要为制造情绪刻意贬低、突然翻脸或安排灾难。角色如何理解由本人的性格与期待决定，双方说话留白，不写长篇戏剧。")
    }

    internal fun response(step: Step, json: JSONObject, turns: List<MeetingTurn>): JSONObject {
        val result = json.optJSONObject("episode") ?: error("居民事件缺少实际阶段结果")
        val choice = result.optString("response")
        val allowed = if (step.prior == null) setOf("waiting", "declined") else when (step.kind) {
            "co_write" -> setOf("continued", "revision", "declined")
            "exchange_views" -> setOf("agreed", "different_view", "undecided", "declined")
            else -> setOf("appreciated", "revision", "different_view", "declined")
        }
        require(choice in allowed) { "居民事件的阶段结果不合法" }
        val turn = turns.getOrNull(result.optInt("residentTurnIndex", -1))
            ?: error("阶段结果未引用实际台词")
        val text = result.optString("resultText").trim()
        require(turn.speakerId == "resident:${step.residentId}" && text.isNotBlank() &&
            text.length <= 900 && turn.dialogue.contains(text)) { "居民结果必须来自本次居民真正说出的原文" }
        if (step.prior == null || step.contribution != step.prior.optString("contribution"))
            require(turns.any { it.speakerId == step.characterId && it.dialogue.contains(step.contribution) }) {
                "角色没有真正把这次原文交给居民"
            }
        return JSONObject().put("choice", choice).put("residentText", turn.dialogue).put("resultText", text)
    }

    @Synchronized fun commit(step: Step, result: JSONObject, evidenceId: String, now: Instant): JSONObject {
        require(SharedExperienceTimeline.eventsByIds(step.characterId, listOf(evidenceId)).isNotEmpty()) {
            "相处事实尚未保存，不能推进居民事件"
        }
        val all = records(step.characterId)
        val current = all.firstOrNull { it.optString("id") == step.id }
        if (current?.optJSONArray("history")?.let { history ->
            (0 until history.length()).any { history.optJSONObject(it)?.optString("evidenceId") == evidenceId }
        } == true) return current
        require(current?.optString("updatedAt") == step.prior?.optString("updatedAt")) { "居民事件已被另一轮推进，请读取现有结果" }
        val entry = JSONObject((step.prior ?: JSONObject()).toString())
        val history = entry.optJSONArray("history") ?: JSONArray()
        history.put(JSONObject(result.toString()).put("evidenceId", evidenceId)
            .put("characterText", step.contribution).put("at", now.toString()))
        val choice = result.getString("choice")
        val rounds = (step.prior?.optInt("rounds") ?: 0) + 1
        val status = when {
            choice == "declined" -> "declined"
            choice == "waiting" -> "waiting"
            choice == "revision" && rounds < 4 -> "revision"
            choice == "revision" -> "closed_unfinished"
            else -> "finished"
        }
        entry.put("id", step.id).put("residentId", step.residentId).put("kind", step.kind)
            .put("characterId", step.characterId).put("contribution", step.contribution)
            .put("status", status).put("rounds", rounds).put("history", history)
            .put("updatedAt", now.toString()).put("readyAt", now.plusSeconds(600).toString())
        save(step.characterId, all.filterNot { it.optString("id") == step.id } + entry)
        return entry
    }

    @Synchronized fun contextFor(characterId: String, now: Instant = Instant.now()): String {
        val selected = records(characterId).filter { entry ->
            entry.optString("status") in setOf("waiting", "revision") ||
                runCatching { Duration.between(Instant.parse(entry.getString("updatedAt")), now).toDays() in 0..3 }.getOrDefault(false)
        }.takeLast(5)
        if (selected.isEmpty()) return ""
        return buildString {
            appendLine("【与居民还记得的具体小事｜世界持久化进度】")
            selected.forEach { entry ->
                val resident = DigitalWorldExpansionStore.residents().firstOrNull { it.id == entry.optString("residentId") }
                appendLine("- incidentId=${entry.optString("id")}；居民 itemId=${entry.optString("residentId")} ${resident?.name.orEmpty()}；当前位置=${resident?.currentLocationCode.orEmpty()}；类型=${entry.optString("kind")}；状态=${entry.optString("status")}；可再谈时间=${entry.optString("readyAt")}")
                appendLine("  本人原文=${entry.optString("contribution")}")
                val history = entry.optJSONArray("history")
                history?.optJSONObject(history.length() - 1)?.let { last ->
                    appendLine("  最近实际回应=${last.optString("residentText")}；evidenceId=${last.optString("evidenceId")}")
                }
            }
            append("若仍想继续，实际到同一居民所在地点后 interact_resident + activityId=continue_episode + incidentId=原ID；revision 还需 text=本人真正改后的原文。可以暂缓；决定放下用 worldAction=leave_resident_episode + incidentId=原ID，只结束自己的打算，不冒充已通知对方。约定再谈不证明对方已经在背后完成作品/研究。")
        }.trim()
    }

    @Synchronized fun invalidateEvidence(evidenceId: String) {
        prefs?.all?.keys?.toList()?.forEach { role ->
            val all = records(role)
            // A partial edited/deleted conversation cannot continue to assert a derived plot.
            val kept = all.filterNot { entry -> entry.optJSONArray("history")?.let { history ->
                (0 until history.length()).any { history.optJSONObject(it)?.optString("evidenceId") == evidenceId }
            } == true }
            if (kept.size != all.size) save(role, kept)
        }
    }

    @Synchronized fun clearCharacter(characterId: String) { prefs?.edit()?.remove(characterId)?.commit() }

    @Synchronized fun isReady(characterId: String, episodeId: String, now: Instant): Boolean =
        records(characterId).any { it.optString("id") == episodeId &&
            it.optString("status") in setOf("waiting", "revision") &&
            runCatching { !now.isBefore(Instant.parse(it.getString("readyAt"))) }.getOrDefault(false) }

    @Synchronized fun leave(characterId: String, episodeId: String, now: Instant): String {
        val all = records(characterId)
        val entry = all.firstOrNull { it.optString("id") == episodeId } ?: error("没有属于本人的这件居民小事")
        require(entry.optString("status") in setOf("waiting", "revision")) { "这件事已经结束" }
        entry.put("status", "abandoned").put("updatedAt", now.toString())
        save(characterId, all)
        return "本人放下了居民小事 $episodeId，保留已发生的原文与交流；没有通知对方，也没有宣称作品完成。"
    }
}
