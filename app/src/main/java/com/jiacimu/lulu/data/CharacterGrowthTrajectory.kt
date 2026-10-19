package com.jiacimu.lulu.data

import java.time.Instant

/** One item in the user's read-only audit of the character's actual growth trail. */
internal data class CharacterGrowthTrace(
    val at: Instant,
    val label: String,
    val detail: String,
    val evidenceId: String,
    val category: String,
)

/**
 * The user's significant relationship feedback is a fact of what they SAID,
 * not proof that the companion agreed, learned, apologized, or changed.
 *
 * It is read from the original, deletable timeline; no additional shadow
 * memory survives message deletion. A single important complaint should be
 * visible and recalled immediately without manufacturing a mature habit.
 */
internal object CharacterGrowthTrajectory {
    fun feedbackTopic(text: String): String? {
        val s = text.trim()
        if (s.isBlank() || s.length > 1600) return null
        val addressed = listOf("你", "你们", "别", "不要", "能不能", "希望你", "请你").any(s::contains) ||
            // An explicitly expressed preference can be about the shared
            // conversation even when the user does not repeat the word "你".
            listOf("我喜欢这样聊天", "我不喜欢这种说话", "我不喜欢这样聊天").any(s::contains)
        if (!addressed) return null
        return when {
            listOf("弱化我", "弱化了我", "把我弱化", "看低我", "贬低我", "小看我",
                "瞧不起我", "把我当小孩", "把我当孩子", "否定我", "否定我的",
                "我不被尊重", "不尊重我", "轻视我").any(s::contains) -> "被轻视或弱化"
            listOf("没有安全感", "没安全感", "缺乏安全感", "让我不安",
                "让我不放心", "让我没有安全感", "不敢信任你", "不信任你",
                "不敢相信你", "不被珍惜", "不被重视").any(s::contains) -> "关系中的安全感和信任"
            listOf("别总是道歉", "不要总是道歉", "别像客服", "别说教", "聊天像客服",
                "说话太官方", "说话太正式", "太人机", "太机械", "文绉绉",
                "说话自然点", "聊天自然点", "少点套话", "别老反问",
                "别总问我", "不要一直问我", "我喜欢这样聊天", "我不喜欢这种说话",
                "多主动一点", "没有分享欲", "不主动分享").any(s::contains) -> "交流方式与表达反馈"
            listOf("别控制我", "不要控制我", "尊重我的决定", "尊重我的选择",
                "别替我决定", "不要替我决定", "忽视我的感受", "不听我说",
                "没认真听我", "边界感", "越界了").any(s::contains) -> "尊重与相处边界"
            else -> null
        }
    }

    fun relevantFeedback(characterId: String, now: Instant = Instant.now()): List<SharedTimelineEvent> {
        val grounded = SharedExperienceTimeline.all(characterId).asSequence()
            .filter { it.evidenceKind == EventEvidenceKind.UserStatement &&
                (it.channel == "私聊" || it.channel.contains("电话") || it.channel.contains("见面")) }
            .filter { !it.occurredAt.isAfter(now) && feedbackTopic(it.content) != null }
            .toList()
        // A directly expressed boundary or preferred communication style does
        // not expire because the user hasn't complained again in six months.
        // Only the latest statement per category is carried forward, and the
        // original erasable message remains the sole authority.
        return grounded.groupBy { feedbackTopic(it.content).orEmpty() }.values
            .mapNotNull { items -> items.maxByOrNull(SharedTimelineEvent::occurredAt) }
            .sortedBy(SharedTimelineEvent::occurredAt)
    }

    /** Sent unchanged to chat, phone, meeting and autonomous personas.
     * Every old complaint is presented together with later user evidence. */
    fun context(characterId: String, now: Instant = Instant.now()): String {
        val recent = relevantFeedback(characterId, now).takeLast(4)
        if (recent.isEmpty()) return ""
        val realUserTurns = SharedExperienceTimeline.all(characterId).filter {
            it.evidenceKind == EventEvidenceKind.UserStatement &&
                (it.channel == "私聊" || it.channel.contains("电话") || it.channel.contains("见面")) &&
                !it.occurredAt.isAfter(now)
        }
        var unresolved = false
        return buildString {
            appendLine("【关系反馈的真实演进｜过去的不满并不自动代表现在仍在争执】")
            recent.forEach { event ->
                // A reconciliation is tied to its later USER evidence. A model
                // apology or silent time passing can never become a settlement.
                val resolution = realUserTurns.lastOrNull { later ->
                    later.occurredAt.isAfter(event.occurredAt) &&
                        later.occurredAt <= event.occurredAt.plus(java.time.Duration.ofDays(3)) &&
                        RelationshipRepairContinuity.userExplicitlySettled(later.content)
                }
                if (resolution == null) {
                    unresolved = true
                    appendLine("- 待核对后续：时间=${event.occurredAt}；证据ID=${event.id}；主题=${feedbackTopic(event.content)}；用户原话=${event.content.take(220)}。这是当时的不满，不能在未核对后续聊天前断言她现在仍生气。")
                } else {
                    appendLine("- 已有后续转折：${feedbackTopic(event.content)}；当时的具体意见=${event.content.take(180)}；后来用户在${resolution.occurredAt}明确表示=${resolution.content.take(180)}（真实证据ID=${resolution.id}）。这一轮争执已说开，保留反思但不得以旧不满为由重新道歉或索取原谅。")
                }
            }
            appendLine("用户当时的不满需要认真对待；它应转化为将来的相处方式，不是每次上线重新开庭。")
            if (unresolved) appendLine("没有明确后续结论的事项也不能靠模型自行宣布被原谅；结合最新实际对话和未结状态判断，不能凭历史片段假设她此刻仍委屈。")
            appendLine("真正改进要由后续言行和反馈体现，不复述忏悔，不把已经和解的事重新变成待修复任务。")
        }.trim()
    }

    /** Only substantive decisions, not each conversational appraisal, count as
     * a traceable turning point. The proposal is the model's own stated view,
     * not an exposure of any hidden model reasoning.
     */
    internal fun isSignificantInnerChange(entry: org.json.JSONObject): Boolean {
        if (entry.optString("selectedAction") == "emotional_imprint") return true
        val delta = entry.optJSONObject("stateDelta")
        val correction = delta?.optString("selfCorrection").orEmpty()
        val social = delta?.optJSONObject("social")?.optString("interpretation").orEmpty()
        val motives = delta?.optInt("motiveChanges", 0) ?: 0
        val thoughts = delta?.optInt("thoughtChanges", 0) ?: 0
        val appraisal = entry.optJSONObject("appraisal")
        val alternatives = entry.optJSONArray("consideredAlternatives")
        return correction.isNotBlank() || social.isNotBlank() || motives > 0 ||
            thoughts > 0 || appraisal?.optString("tension").orEmpty().isNotBlank() ||
            (alternatives?.length() ?: 0) > 0
    }

    fun entries(characterId: String, now: Instant = Instant.now()): List<CharacterGrowthTrace> {
        val result = mutableListOf<CharacterGrowthTrace>()
        // Project available persisted sources rather than a speculative
        // growth summary. The original timeline is authoritative. Oldest
        // private transitions are bounded by the inner-life retention cap.
        SharedExperienceTimeline.all(characterId).asSequence()
            .filter { it.evidenceKind == EventEvidenceKind.UserStatement &&
                (it.channel == "私聊" || it.channel.contains("电话") || it.channel.contains("见面")) &&
                !it.occurredAt.isAfter(now) && feedbackTopic(it.content) != null }
            .toList().takeLast(24).forEach { event ->
            result += CharacterGrowthTrace(event.occurredAt, "收到重要关系反馈",
                "关于${feedbackTopic(event.content)}：${event.content.take(240)}。这是用户实际表达的感受，还不能证明角色已改正。",
                event.id, "反馈")
        }
        val mind = CharacterInnerLifeStore.snapshot(characterId)
        val transitions = mind.optJSONArray("causalTransitions")
        for (i in 0 until (transitions?.length() ?: 0)) {
            val entry = transitions?.optJSONObject(i) ?: continue
            // Real replies, voice, meetings and autonomous actions can form
            // a meaningful private change in the SAME decision call. A routine
            // greeting's "meaning" or generic reason is not growth.
            val evidence = entry.optString("evidenceId")
            // A composite perception ID is not a raw event. Never show an
            // unverifiable experience as a confirmed personal milestone.
            val verified = SharedExperienceTimeline.eventsByIds(characterId, listOf(evidence))
            if (verified.isEmpty()) continue
            val feedback = verified.any {
                it.evidenceKind == EventEvidenceKind.UserStatement &&
                    feedbackTopic(it.content) != null
            }
            val emotionalChange = entry.optJSONObject("stateDelta")
                ?.optJSONObject("emotion")?.optString("feeling").orEmpty().isNotBlank()
            if (!isSignificantInnerChange(entry) && !(feedback && emotionalChange)) continue
            val time = runCatching { Instant.parse(entry.optString("at")) }.getOrNull() ?: continue
            if (time.isAfter(now)) continue
            val delta = entry.optJSONObject("stateDelta")
            val appraisal = entry.optJSONObject("appraisal")
            val correction = delta?.optString("selfCorrection").orEmpty().take(190)
            val tension = appraisal?.optString("tension").orEmpty().take(180)
            val own = entry.optString("innerThought").take(190)
            val interpretation = delta?.optJSONObject("social")?.optString("interpretation").orEmpty().take(180)
            val emotion = delta?.optJSONObject("emotion")
            val alternatives = entry.optJSONArray("consideredAlternatives")
            val considered = (0 until minOf(alternatives?.length() ?: 0, 2))
                .mapNotNull { alternatives?.optJSONObject(it)?.optString("idea")?.takeIf(String::isNotBlank) }
            val meaning = appraisal?.optString("meaning").orEmpty().take(200)
            val details = listOfNotNull(
                correction.takeIf(String::isNotBlank)?.let { "想调整：$it" },
                tension.takeIf(String::isNotBlank)?.let { "仍在权衡：$it" },
                interpretation.takeIf(String::isNotBlank)?.let { "关系理解：$it" },
                emotion?.optString("feeling").orEmpty()
                    .takeIf(String::isNotBlank)?.let { feeling ->
                        "当时的感受：$feeling；缘由：${emotion?.optString("cause").orEmpty().take(130)}"
                    },
                own.takeIf(String::isNotBlank)?.let { "没说出口：$it" },
                considered.takeIf { it.isNotEmpty() }?.let { "曾考虑：${it.joinToString("／")}" },
                meaning.takeIf { delta != null && it.isNotBlank() }?.let { "如何看待：$it" },
            ).joinToString("\n").take(500)
            if (details.isBlank()) continue
            result += CharacterGrowthTrace(time,
                if (correction.isNotBlank()) "当次决定调整做法"
                else if (entry.optString("selectedAction") == "emotional_imprint") "旧版事后反思"
                else "当次形成的私人想法",
                details, evidence, "反思")
        }
        for (kind in listOf("openConcerns", "closedConcerns")) {
            val arr = mind.optJSONArray(kind)
            for (i in 0 until (arr?.length() ?: 0)) {
                val entry = arr?.optJSONObject(i) ?: continue
                val evidence = if (kind == "closedConcerns") entry.optString("closureEvidenceId") else entry.optString("evidenceId")
                if (SharedExperienceTimeline.eventsByIds(characterId, listOf(evidence)).isEmpty()) continue
                val field = if (kind == "closedConcerns") "closedAt" else "updatedAt"
                val time = runCatching { Instant.parse(entry.optString(field)) }.getOrNull() ?: continue
                if (time.isAfter(now)) continue
                result += CharacterGrowthTrace(time,
                    if (kind == "closedConcerns") "心事已结束" else "仍放在心上的事",
                    entry.optString("focus").take(180) + "\n" +
                        if (kind == "closedConcerns")
                            "后来如何看待：${entry.optString("resolution").take(190)}"
                        else "缘由：${entry.optString("whyItMatters").take(190)}",
                    evidence, "心事")
            }
        }
        CharacterDevelopmentStore.history(characterId).forEach { growth ->
            val evidence = growth.evidence.keys.firstOrNull() ?: return@forEach
            if (SharedExperienceTimeline.eventsByIds(characterId, growth.evidence.keys).size != growth.evidence.size) return@forEach
            if (growth.createdAt.isAfter(now)) return@forEach
            result += CharacterGrowthTrace(growth.createdAt,
                if (growth.active && CharacterDevelopmentStore.active(characterId).any { it.id == growth.id })
                    "形成${growth.kind.label} · ${if (growth.maturity == DevelopmentMaturity.Established) "较稳定" else "形成中"}"
                else "过往${growth.kind.label} · 已撤回或依据失效",
                growth.content.take(270), evidence, "成长")
        }
        result += CharacterLearningLedger.growthTrace(characterId, now)
        SharedExperienceTimeline.all(characterId).asSequence()
            .filter { it.source == "journal:own" }
            .filter { !it.occurredAt.isAfter(now) }
            .toList().takeLast(8).forEach { event ->
                result += CharacterGrowthTrace(event.occurredAt, "确实写下日记",
                    event.content.take(250), event.id, "行动")
            }
        return result.sortedWith(compareByDescending<CharacterGrowthTrace> { it.at }.thenBy { it.label }).take(100)
    }
}
