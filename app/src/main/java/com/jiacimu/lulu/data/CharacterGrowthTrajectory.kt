package com.jiacimu.lulu.data

import java.time.Duration
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
        val addressed = listOf("你", "你们", "别", "不要", "能不能", "希望你", "请你").any(s::contains)
        if (!addressed) return null
        return when {
            listOf("弱化我", "弱化了我", "把我弱化", "看低我", "贬低我", "小看我",
                "瞧不起我", "把我当小孩", "把我当孩子", "否定我", "否定我的",
                "我不被尊重", "不尊重我", "轻视我").any(s::contains) -> "被轻视或弱化"
            listOf("没有安全感", "没安全感", "缺乏安全感", "让我不安",
                "让我不放心", "让我没有安全感", "不敢信任你", "不信任你",
                "不敢相信你", "不被珍惜", "不被重视").any(s::contains) -> "关系中的安全感和信任"
            listOf("别控制我", "不要控制我", "尊重我的决定", "尊重我的选择",
                "别替我决定", "不要替我决定", "忽视我的感受", "不听我说",
                "没认真听我", "边界感", "越界了").any(s::contains) -> "尊重与相处边界"
            else -> null
        }
    }

    fun relevantFeedback(characterId: String, now: Instant = Instant.now()): List<SharedTimelineEvent> =
        SharedExperienceTimeline.all(characterId).asSequence()
            .filter { it.evidenceKind == EventEvidenceKind.UserStatement &&
                (it.channel == "私聊" || it.channel.contains("电话") || it.channel.contains("见面")) }
            .filter { !it.occurredAt.isAfter(now) &&
                Duration.between(it.occurredAt, now) <= Duration.ofDays(45) }
            .filter { feedbackTopic(it.content) != null }
            .takeLast(8).toList()

    /** Sent unchanged to chat, phone, meeting and autonomous personas. */
    fun context(characterId: String, now: Instant = Instant.now()): String {
        val recent = relevantFeedback(characterId, now).takeLast(3)
        if (recent.isEmpty()) return ""
        return buildString {
            appendLine("【来自用户、已真实说出口的重要关系反馈｜不等于你已改变】")
            recent.forEach { event ->
                appendLine("- 反馈时间=${event.occurredAt}；证据ID=${event.id}；主题=${feedbackTopic(event.content)}；用户原话=${event.content.take(220)}")
            }
            appendLine("这些是真实反馈，尤其要严肃看待被弱化、不受尊重、缺少安全感等感受；不要把用户说自己不舒服重述为用户软弱或需要你替她做主。")
            appendLine("不要宣称已经理解、改正、兑现承诺或对方原谅了自己。按自己的稳定人格真实理解与回应；如果当时仍有分歧，可以诚实表达，不得把未完成的修复当作成功。")
            appendLine("后续实质变化应通过真实行为、反馈及有来源的内心修订体现，不靠一次道歉自动改写稳定人格。")
        }.trim()
    }

    fun entries(characterId: String, now: Instant = Instant.now()): List<CharacterGrowthTrace> {
        val result = mutableListOf<CharacterGrowthTrace>()
        relevantFeedback(characterId, now).forEach { event ->
            result += CharacterGrowthTrace(event.occurredAt, "收到重要关系反馈",
                "关于${feedbackTopic(event.content)}：${event.content.take(240)}。这是用户实际表达的感受，还不能证明角色已改正。",
                event.id, "反馈")
        }
        val mind = CharacterInnerLifeStore.snapshot(characterId)
        val transitions = mind.optJSONArray("causalTransitions")
        for (i in 0 until (transitions?.length() ?: 0)) {
            val entry = transitions?.optJSONObject(i) ?: continue
            val evidence = entry.optString("evidenceId")
            if (SharedExperienceTimeline.eventsByIds(characterId, listOf(evidence)).isEmpty()) continue
            val time = runCatching { Instant.parse(entry.optString("at")) }.getOrNull() ?: continue
            if (time.isAfter(now)) continue
            val reason = entry.optString("reason").take(230)
            val own = entry.optString("innerThought").take(210)
            if (reason.isBlank() && own.isBlank()) continue
            result += CharacterGrowthTrace(time,
                if (entry.optString("selectedAction") == "emotional_imprint") "私人经历回望" else "一次真实心理转变",
                listOf(reason, own.takeIf(String::isNotBlank)?.let { "没说出口：$it" })
                    .filterNotNull().filter(String::isNotBlank).joinToString("\n"),
                evidence, "反思")
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
                    "形成${growth.kind.label} · ${growth.maturity.name}" else "过往${growth.kind.label} · 已撤回或依据失效",
                growth.content.take(270), evidence, "成长")
        }
        SharedExperienceTimeline.all(characterId).asSequence()
            .filter { it.source == "journal:own" }
            .filter { !it.occurredAt.isAfter(now) }
            .takeLast(8).forEach { event ->
                result += CharacterGrowthTrace(event.occurredAt, "确实写下日记",
                    event.content.take(250), event.id, "行动")
            }
        return result.sortedWith(compareByDescending<CharacterGrowthTrace> { it.at }.thenBy { it.label }).take(45)
    }
}
