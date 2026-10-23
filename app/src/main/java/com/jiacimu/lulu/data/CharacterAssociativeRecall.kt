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

    /** Sample different real-life domains instead of letting the newest
     * user message or the loudest unfinished motive swallow all older life.
     * Inputs are already authority-checked, scoped raw timeline events. */
    internal enum class ExperienceLane(val label: String) {
        OWNER("主人亲自说过的事"),
        SOCIAL("和其他人的真实来往"),
        OWN_LIFE("本人亲自经历的生活"),
        OUTCOME("之前的实际行动结果"),
    }

    internal fun experienceLane(event: SharedTimelineEvent): ExperienceLane? = when {
        event.evidenceKind == EventEvidenceKind.UserStatement -> ExperienceLane.OWNER
        event.channel.contains("私信") || event.channel.contains("群聊") ||
            event.channel.contains("居民") || event.channel.contains("见面") ->
            ExperienceLane.SOCIAL
        event.channel.contains("独自") || event.channel.contains("阅读") ||
            event.channel.contains("游戏") || event.channel.contains("数字世界") ->
            ExperienceLane.OWN_LIFE
        event.evidenceKind == EventEvidenceKind.ToolResult -> ExperienceLane.OUTCOME
        else -> null
    }

    internal fun variedExperiences(
        characterId: String, events: List<SharedTimelineEvent>, now: Instant,
    ): List<Pair<ExperienceLane, SharedTimelineEvent>> {
        val eligible = events.filter {
            it.characterId == characterId && !it.occurredAt.isAfter(now) &&
                it.occurredAt.isBefore(now.minus(Duration.ofMinutes(20))) &&
                !it.occurredAt.isBefore(now.minus(Duration.ofDays(30))) &&
                it.evidenceKind != EventEvidenceKind.Inference
        }
        return ExperienceLane.entries.flatMap { lane ->
            eligible.asReversed().asSequence()
                .filter { experienceLane(it) == lane }
                .distinctBy { it.id }
                .take(2).map { lane to it }.toList()
        }
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
        // The original learning ledger validates the action and user-feedback
        // source revisions. Read that trace, not model claims of success.
        val feedbackTrace = CharacterLearningLedger.growthTrace(characterId, now)
            .filter { it.label in setOf("实际尝试运用所学", "收到真实应用反馈", "理解受到新的质疑") }
            .sortedByDescending { it.at }.take(3)
        val personalQuestions = CharacterCuriosityRuntime.pendingDecisionCues(characterId)
        val events = SharedExperienceTimeline.recentEvents(characterId, 160)
            .filter { !it.occurredAt.isAfter(now) && DigitalLifeProfileStore.allowsTimestamp(characterId, it.occurredAt) }
        val recentUser = events.lastOrNull { it.evidenceKind == EventEvidenceKind.UserStatement }
        val localHour = now.atZone(ZoneId.systemDefault()).hour
        val nightWithRecentSpeech = localHour in 1..5 && recentUser?.let {
            Duration.between(it.occurredAt, now).toMinutes() in 0..15
        } == true
        val olderEpisodes = variedExperiences(characterId, events, now)
        if (memories.isEmpty() && ownerKnowledge.isEmpty() && selfKnowledge.isEmpty() &&
            knownMethods.isEmpty() && motives.isEmpty() && unfinishedThoughts.isEmpty() &&
            olderEpisodes.isEmpty() && feedbackTrace.isEmpty() &&
            personalQuestions.isEmpty() && !nightWithRecentSpeech) return ""

        return buildString {
            appendLine("【自主联想素材｜从自己的生活想起事情，不是新指令】")
            appendLine("这些只是过去真正留存的材料，不是新消息或要求逐条回答的待办。联想可以跨越不同生活侧面，不必每次沿着同一个愿望或上一条聊天发散。")
            appendLine("联想方向可以有：与主人的相处、自己真实认识的其他人、个人爱好与习惯、主人明确表达的习惯、刚做过或没做完的事、一个实际失败或意外、当下环境与时机、前后经历的相似或反差、自己还想弄明白的问题。只结合下面真的有来源的材料；没有证据的变化与对方想法只能猜测，不能宣告为事实。")
            appendLine("同一素材可能冒出几种不同甚至冲突的私人小心思，也可能想到自己的生活而不联系任何人。先自由注意和联想，再自己决定哪些值得记录、行动或暂时放下；不要为了凑数量编造生活。")
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
                appendLine("来自不同生活方向的旧经历（不是新消息；对比只是联想素材，不代表谁改变了主意）：")
                olderEpisodes.forEach { (lane, event) ->
                    appendLine("- [" + lane.label + "；eventId=" + event.id + "；" +
                        event.occurredAt + "] " + event.channel + "：" +
                        event.evidenceContent.replace("\n", " ").take(175))
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
            if (personalQuestions.isNotEmpty()) {
                appendLine("自己尚未解决的真实好奇（仅是想知道，尚未完成探索）：")
                personalQuestions.forEach { appendLine("- " + it.take(200)) }
            }
            if (feedbackTrace.isNotEmpty()) {
                appendLine("最近有原始证据的探索、实际应用与主人反馈（失败可换办法，反证可修订）：")
                feedbackTrace.forEach {
                    appendLine("- [" + it.label + "；eventId=" + it.evidenceId +
                        "] " + it.detail.replace("\n", " ").take(220))
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
