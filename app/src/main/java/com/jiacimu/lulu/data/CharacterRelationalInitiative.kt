package com.jiacimu.lulu.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * A relational wish belongs to the character, not to a task assigned by the user.
 * The planner may choose one; only executor receipts count as delivery.
 * User preferences are sourced to raw first-person statements, never inferred from
 * the role's own monologue, a forwarded article or a fabricated world experience.
 */
internal object CharacterRelationalInitiative {
    private const val PREFS = "lulu_relational_initiative_v1"
    private var prefs: SharedPreferences? = null
    private var boundApplication: Context? = null
    private val preferenceSignal = Regex(
        "(我(很|特别|真的|更|最|就)?(喜欢|不喜欢|讨厌|偏爱|希望|想要|不想|受不了|不接受|害怕)|" +
            "(叫我|别叫我|不要叫我|以后(可以|不要|别)|你(可以|能不能|不要|别)(再|总是)?))"
    )
    private val rejectionSignal = Regex("不喜欢|讨厌|不想|受不了|不接受|害怕|别叫|不要叫|不要|拒绝|不舒服")

    @Synchronized fun initialize(context: Context) {
        val application = context.applicationContext
        // In Android the application is process-wide. Robolectric swaps it between
        // tests without unloading Kotlin objects; keeping an old prefs handle
        // silently sends writes to the previous test's dead application.
        if (prefs != null && boundApplication === application) return
        boundApplication = application
        prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        CharacterInnerLifeStore.initialize(application)
    }

    /** This is evidence selection, NOT a classifier that announces an inferred preference. */
    internal fun explicitStatements(events: List<SharedTimelineEvent>, now: Instant, limit: Int = 5):
        List<SharedTimelineEvent> {
        val candidates = events.asReversed().asSequence()
            .filter { it.evidenceKind == EventEvidenceKind.UserStatement }
            .filter { !it.occurredAt.isAfter(now) && Duration.between(it.occurredAt, now).toDays() <= 180 }
            .filter { preferenceSignal.containsMatchIn(it.content.take(400)) }
            .distinctBy { it.content.trim().lowercase().replace(Regex("\\s+"), "") }
            .take(18).toList()
        // Keep fresh corrections and explicit objections visible even when many
        // positive preferences exist. The model must interpret statements in context.
        val selected = candidates.filter { rejectionSignal.containsMatchIn(it.content) }.take(2) +
            candidates.filterNot { rejectionSignal.containsMatchIn(it.content) }.take(limit)
        return selected.distinctBy { it.id }.take(limit)
    }

    private fun read(characterId: String): JSONObject = runCatching {
        JSONObject(prefs?.getString(characterId, "{}") ?: "{}")
    }.getOrDefault(JSONObject())

    private fun evidenceStillExists(characterId: String, evidenceId: String): Boolean =
        evidenceId.isBlank() || SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId))
            .any { it.evidenceKind == EventEvidenceKind.UserStatement }

    /** Stable persona is the authority: a changed role is not bound by yesterday's plan. */
    private fun personaSignature(characterId: String): String =
        CharacterRuntime.personaConstraintSnapshot(characterId).hashCode().toString()

    private fun compatiblePending(characterId: String, root: JSONObject): JSONObject? =
        root.optJSONObject("pending")?.takeIf { pending ->
            evidenceStillExists(characterId, pending.optString("evidenceId")) &&
                pending.optString("personaSignature") == personaSignature(characterId)
        }

    /** One-time migration of old private relational plans into canonical motives. */
    private fun migrateLegacy(characterId: String) {
        val p = prefs ?: return
        val marker = "migrated:$characterId"
        if (p.getBoolean(marker, false)) return
        val old = read(characterId)
        compatiblePending(characterId, old)?.let { pending ->
            CharacterInnerLifeStore.integrateRelationalGoal(
                characterId, pending,
                pending.optString("evidenceId").ifBlank { "legacy-relation:$characterId" },
                personaSignature(characterId),
            )
        }
        old.optJSONObject("lastDelivery")?.let { last ->
            val receiptId = last.optString("evidenceId")
            val action = last.optString("action")
            val verified = receiptId.isNotBlank() &&
                CharacterActionEvidence.resolve(characterId, receiptId)?.let {
                    CharacterActionEvidence.supports(it, action)
                } == true
            if (verified && last.optString("aim").isNotBlank()) {
                CharacterInnerLifeStore.integrateRelationalGoal(
                    characterId, JSONObject().put("status", "ready")
                        .put("aim", last.optString("aim"))
                        .put("why", "此前真实选择为对方创造某种体验")
                        .put("deliveryAction", action),
                    receiptId, personaSignature(characterId), action, true,
                    receiptId, last.optString("summary"),
                    runCatching { Instant.parse(last.optString("at")) }.getOrDefault(Instant.now()),
                )
            }
        }
        check(p.edit().putBoolean(marker, true).commit()) { "旧关系意愿迁移未保存" }
    }

    /** Used by private chat, calls, meetings and background decisions through the same personality runtime. */
    fun context(characterId: String, now: Instant = Instant.now(), proactive: Boolean = false): String {
        val timeline = SharedExperienceTimeline.recentEvents(characterId, 240)
        val recent = explicitStatements(timeline, now)
        migrateLegacy(characterId)
        val relations = CharacterGoalConvergence.relationships(
            CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives"))
        val pending = relations.lastOrNull { CharacterMotiveState.isActive(it) ||
            it.optString("status") == "paused" }
            ?.takeIf { it.optString("personaSignature") == personaSignature(characterId) }
        val last = relations.asReversed().mapNotNull { goal ->
            val outcomes = goal.optJSONArray("outcomes") ?: return@mapNotNull null
            outcomes.optJSONObject(outcomes.length() - 1)
        }.firstOrNull()
        val lastAt = last?.optString("at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val followUp = if (lastAt == null) emptyList() else timeline.filter { event ->
            event.evidenceKind == EventEvidenceKind.UserStatement &&
                event.occurredAt.isAfter(lastAt) && !event.occurredAt.isAfter(now) &&
                Duration.between(lastAt, event.occurredAt).toHours() < 12
        }.takeLast(3)
        return buildString {
            appendLine("【关系中的自主意愿｜不是任务、表白或行动配额】")
            appendLine("根据自己的稳定人格、真实感情与当下兴趣，决定是否想为对方创造一点体验：有的角色会主动靠近、逗乐、准备小惊喜；有的更克制、好胜、疏离或只愿表达独立见解。不能强制所有人恋爱、讨好或变热情。")
            appendLine("对用户的已知偏好只能参考真实发言；对方喜欢的感受与角色自己想做的事需要同时成立。对方明确拒绝、表示不舒服或关系未到时，不用‘惊喜’或‘压迫感’作越界借口。")
            if (recent.isNotEmpty()) {
                appendLine("可核对的用户原话（可能只是一次性想法、否定或举例，不自动当成持久偏好；仅用于判断）：")
                recent.forEach { event ->
                    appendLine("- [evidenceId=${event.id}; ${event.occurredAt}] ${event.content.replace(Regex("\\s+"), " ").take(210)}")
                }
            }
            if (pending != null) {
                appendLine("本人上次尚未实行的主意（不等于承诺，更不是已经送出惊喜）：")
                appendLine("- motiveId=${pending.optString("id")}；目标=${pending.optString("aim").take(150)}；自己的缘由=${pending.optString("why").take(180)}；拟议下一步=${pending.optString("nextStep").take(160)}")
                appendLine("- 阶段=${pending.optString("status")}；行动已送达不等于目标实现。可继续观察、调整计划、暂停或放下，不能机械重复。")
            }
            if (last != null) {
                appendLine("最近一次相关真实行动：${last.optString("action")}；回执=${last.optString("summary").take(120)}；执行时间=${last.optString("at")}。")
                if (followUp.isNotEmpty()) {
                    appendLine("动作之后用户真实说过的话（仅是时间上随后，未必在评价这个行动）：")
                    followUp.forEach { event ->
                        appendLine("- [${event.occurredAt}] ${event.content.replace(Regex("\\s+"), " ").take(180)}")
                    }
                }
                appendLine("完成动作不等于用户一定喜欢；要辨别后续回应是否真在评价该动作，不机械重复。")
            }
            if (proactive) {
                appendLine("若本人确实想准备一次惊喜/靠近/挑逗/帮助，不必等用户发起任务：可以先私下想一个能力内的下一步，后续再执行真实动作；若已经适合此刻，可直接选择 action 发消息、邀请、发动态或做数字世界活动。仅说‘以后我要给你惊喜’不是完成。")
            }
        }.trim()
    }

    /**
     * Short handoff for the second, wording-only step of private chat. It does
     * not fetch broad memories, rewrite motives, or invent new user preferences.
     * The primary decision step already chose whether and what to express.
     */
    fun expressionHandoff(characterId: String): String {
        migrateLegacy(characterId)
        val pending = CharacterGoalConvergence.relationships(
            CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives"))
            .lastOrNull { CharacterMotiveState.isActive(it) &&
                it.optString("personaSignature") == personaSignature(characterId) } ?: return ""
        return buildString {
            appendLine("【同一角色仍在推进的私人愿望｜仅用于表达连贯，不是新回复指令】")
            appendLine("自己此前考虑：${pending.optString("aim").take(130)}")
            appendLine("缘由：${pending.optString("why").take(150)}")
            appendLine("这是未执行的想法，不要在回复里伪装已完成，也不要覆盖本轮已确定的言语意图。")
        }.trim()
    }

    /** Old-model producer compatibility; no second relational goal database. */
    @Synchronized fun recordDecision(
        characterId: String, proposal: JSONObject?, action: String, success: Boolean,
        summary: String, now: Instant = Instant.now(),
        actionEvidenceId: String = "",
    ) {
        if (prefs == null || proposal == null || characterId.isBlank()) return
        migrateLegacy(characterId)
        val exactEvidence = proposal.optString("evidenceId").trim()
        if (!evidenceStillExists(characterId, exactEvidence)) return
        val status = proposal.optString("status").lowercase()
        val effective = JSONObject(proposal.toString())
        if (status == "released" && effective.optString("motiveId").isBlank()) {
            val active = CharacterGoalConvergence.relationships(
                CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives"))
                .filter(CharacterMotiveState::isActive)
            // Old release had no id because it only supported a single pending wish.
            if (active.size != 1) return
            effective.put("motiveId", active.single().optString("id"))
        }
        val verified = success && actionEvidenceId.isNotBlank() &&
            CharacterActionEvidence.resolve(characterId, actionEvidenceId)?.let {
                CharacterActionEvidence.supports(it, action)
            } == true
        CharacterInnerLifeStore.integrateRelationalGoal(
            characterId, effective,
            exactEvidence.ifBlank { "relationship-choice:$characterId:${now.toEpochMilli()}" },
            personaSignature(characterId), action, success,
            if (verified) actionEvidenceId else "", summary, now,
        )
    }

    @Synchronized fun invalidateEvidence(eventId: String) {
        if (eventId.isBlank()) return
        prefs?.all?.keys?.forEach { id ->
            val root = read(id)
            var changed = false
            if (root.optJSONObject("pending")?.optString("evidenceId") == eventId) {
                root.remove("pending")
                changed = true
            }
            if (root.optJSONObject("lastDelivery")?.optString("evidenceId") == eventId) {
                root.remove("lastDelivery")
                changed = true
            }
            if (changed) prefs?.edit()?.putString(id, root.toString())?.commit()
        }
    }

    @Synchronized fun clear(characterId: String) {
        prefs?.edit()?.remove(characterId)?.remove("migrated:$characterId")?.commit()
    }
}
