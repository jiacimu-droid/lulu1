package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryTier
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject

/**
 * A read-only bridge from evidence-backed memory to the existing autonomous decision.
 * Does not create a second motive store, guess personality, or trigger another model call.
 * The role, not this selector, decides which association matters and whether to act.
 */
internal object CharacterAssociativeRecall {
    internal enum class MemoryLane(val label: String) {
        CORE("核心长期事实"),
        STABLE("可修订的稳定事实"),
        EPISODE("过去的具体经历"),
        PAST_FEELING("过去的情绪印象"),
    }

    fun memoryLane(memory: MemoryEntry): MemoryLane = when {
        memory.kind == MemoryKind.Emotion -> MemoryLane.PAST_FEELING
        memory.kind == MemoryKind.Timeline -> MemoryLane.EPISODE
        memory.tier == MemoryTier.Core -> MemoryLane.CORE
        memory.tier == MemoryTier.Stable -> MemoryLane.STABLE
        else -> MemoryLane.EPISODE
    }

    /**
     * Select a varied working set; only the caller may supply memories whose
     * sources and revisions have been checked. No permanent storage is truncated.
     */
    internal fun selectMemories(
        characterId: String,
        memories: List<MemoryEntry>,
        query: String,
        now: Instant,
        limit: Int = 7,
    ): List<MemoryEntry> {
        if (characterId.isBlank() || limit <= 0) return emptyList()
        val valid = memories.filter {
            it.characterId == characterId && it.canRecallProactively &&
                !(it.occurredAt ?: it.createdAt).isAfter(now)
        }.distinctBy { it.id }
        if (valid.isEmpty()) return emptyList()
        val related = if (query.isBlank()) emptyList()
            else MemoryAssociationPolicy.localRelevant(valid, query, limit = limit, now = now)
        val byImportance = valid.sortedWith(
            compareByDescending<MemoryEntry> { it.pinned }
                .thenByDescending { it.strength }
                .thenByDescending { it.occurredAt ?: it.createdAt }
        )
        val selected = linkedMapOf<String, MemoryEntry>()
        // First protect material variety, then favor actual associations.
        for (lane in listOf(MemoryLane.STABLE, MemoryLane.EPISODE, MemoryLane.PAST_FEELING, MemoryLane.CORE)) {
            (related + byImportance).firstOrNull { memoryLane(it) == lane }
                ?.let { selected[it.id] = it }
        }
        for (memory in related + byImportance) {
            if (selected.size >= limit) break
            selected.putIfAbsent(memory.id, memory)
        }
        return selected.values.take(limit)
    }

    fun render(characterId: String, now: Instant = Instant.now()): String {
        if (characterId.isBlank()) return ""
        val inner = CharacterInnerLifeStore.snapshot(characterId)
        val motives = CharacterMotiveState.active(inner.optJSONArray("motives"))
            .sortedByDescending { it.optInt("priority", 2) }.take(4)
        val history = inner.optJSONArray("thoughts")
        val unfinishedThoughts = (0 until (history?.length() ?: 0))
            .mapNotNull { history?.optJSONObject(it) }
            .filter {
                val at = runCatching { Instant.parse(it.optString("at")) }.getOrNull()
                at != null && !at.isAfter(now) && Duration.between(at, now) <= Duration.ofDays(3)
            }.takeLast(3)
        val query = (motives.map { it.optString("aim") + " " + it.optString("nextStep") } +
            unfinishedThoughts.map { it.optString("thought") }).joinToString(" ").take(1_000)
        val validMemories = LuluRepositories.memory.snapshot(characterId).filter {
            it.tier != MemoryTier.Core && MemoryValidityStore.isActive(it.id) &&
                memoryHasLiveSources(it, characterId) &&
                DigitalLifeProfileStore.allowsTimestamp(characterId, it.occurredAt ?: it.createdAt)
        }
        val memories = selectMemories(characterId, validMemories, query, now)
        val learned = CharacterDevelopmentStore.active(characterId)
        val ownerKnowledge = CharacterDevelopmentRecall.select(
            learned.filter { it.kind in setOf(DevelopmentKind.UserPreference, DevelopmentKind.UserHabit) },
            query, 4, now)
        val selfKnowledge = CharacterDevelopmentRecall.select(
            learned.filter { it.kind in setOf(DevelopmentKind.Interest, DevelopmentKind.Preference, DevelopmentKind.Habit) },
            query, 3, now)
        val knownMethods = CharacterDevelopmentRecall.select(
            learned.filter { it.kind == DevelopmentKind.VerifiedMethod },
            query, 2, now)
        val insights = CharacterLearningLedger.listInsights(characterId).take(2)
        val events = SharedExperienceTimeline.recentEvents(characterId, 80)
            .filter { !it.occurredAt.isAfter(now) && DigitalLifeProfileStore.allowsTimestamp(characterId, it.occurredAt) }
        val recentUser = events.lastOrNull { it.evidenceKind == EventEvidenceKind.UserStatement }
        val localHour = now.atZone(ZoneId.systemDefault()).hour
        val nightWithRecentSpeech = localHour in 1..5 && recentUser?.let {
            Duration.between(it.occurredAt, now).toMinutes() in 0..15
        } == true
        val olderEpisodes = events.filter {
            it.occurredAt.isBefore(now.minus(Duration.ofMinutes(20))) &&
                (it.evidenceKind == EventEvidenceKind.UserStatement ||
                    it.channel.startsWith("数字世界") || it.channel.startsWith("独自阅读"))
        }.takeLast(2)
        if (memories.isEmpty() && ownerKnowledge.isEmpty() && selfKnowledge.isEmpty() &&
            knownMethods.isEmpty() && motives.isEmpty() && unfinishedThoughts.isEmpty() &&
            olderEpisodes.isEmpty() && !nightWithRecentSpeech) return ""

        return buildString {
            appendLine("【自主联想素材｜从自己的生活想起事情，不是新指令】")
            appendLine("这些是不同性质的旧材料。你可以联系当下时间、人物关系、自己在意的事与经验产生新的联想；也可没有新念头。联想不是事实，不能自动当作待办或必须主动联系用户。")
            if (nightWithRecentSpeech) {
                appendLine("- 时间与当下证据：本地正值深夜，过去15分钟内确实收到过用户发言。可依你自己的身份和关系想到关心、好奇或克制；不能由此断言用户整夜没睡、明天的安排，或强迫她睡觉。")
            }
            if (ownerKnowledge.isNotEmpty()) {
                appendLine("主人明确表达或有跨日证据的喜好、习惯（不是你自己的爱好或待执行命令）：")
                ownerKnowledge.forEach {
                    appendLine("- [" + it.kind.label + "；证据=" + it.evidence.keys.firstOrNull().orEmpty() +
                        "] " + it.content.take(170))
                }
            }
            if (selfKnowledge.isNotEmpty()) {
                appendLine("你自己因真实经历形成的兴趣与倾向（不代表主人同样喜欢）：")
                selfKnowledge.forEach { appendLine("- [" + it.kind.label + "] " + it.content.take(150)) }
            }
            if (memories.isNotEmpty()) {
                appendLine("从原记忆筛选的联想线索（情绪属于当时，不是现在）：")
                memories.forEach {
                    appendLine("- [" + memoryLane(it).label + "；memoryId=" + it.id +
                        "] " + it.content.replace("\n", " ").take(200))
                }
            }
            if (olderEpisodes.isNotEmpty()) {
                appendLine("过去的真实经历（不是刚收到的新消息）：")
                olderEpisodes.forEach {
                    appendLine("- [eventId=" + it.id + "；" + it.occurredAt + "] " +
                        it.channel + "：" + it.evidenceContent.replace("\n", " ").take(160))
                }
            }
            if (unfinishedThoughts.isNotEmpty()) {
                appendLine("曾留下的私人念头（并非必须完成，可放下或重新理解）：")
                unfinishedThoughts.forEach { appendLine("- " + it.optString("thought").take(130)) }
            }
            if (motives.isNotEmpty()) {
                appendLine("尚未放下的本人愿望（沿用原 motiveId 修订；等待、暂停、放下也正常）：")
                motives.forEach {
                    appendLine("- motiveId=" + it.optString("id") + "；想做=" +
                        it.optString("aim").take(120) + "；拟议下一步=" +
                        it.optString("nextStep").take(100) + "；等待=" +
                        it.optString("waitingFor").take(80))
                }
            }
            if (knownMethods.isNotEmpty() || insights.isNotEmpty()) {
                appendLine("学习与反馈不是一回事：下面的资料理解仍可被修订；验证过的方法也只对当时情境有依据。")
                knownMethods.forEach { appendLine("- [曾有真实正面反馈] " + it.content.take(160)) }
                insights.forEach {
                    appendLine("- [已读材料的可修订理解；source=" + it.sourceEvidenceId +
                        "] " + it.topic.take(70) + "：" + it.understanding.take(140))
                }
            }
            append("联想后自己决定：一闪而过的想法只放在本轮 privateIdeas；值得跨轮想起的放 innerLife.thoughts；真心想长期实现且有具体方向的才在同一 innerLife.motives 中 start/revise。行动可以暂时为零。若真实执行或主人之后反馈，下一轮应比较“尝试了什么、实际结果、主人是否明确认可”，再调整旧愿望或相处方法；工具成功不等于主人满意。")
        }.trim()
    }
}
