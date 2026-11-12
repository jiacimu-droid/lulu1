package com.jiacimu.lulu

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.ScopedModelSelections
import com.jiacimu.lulu.ai.archiveIdFor
import com.jiacimu.lulu.data.LuluChatMessage
import com.jiacimu.lulu.data.LuluGroupChat
import com.jiacimu.lulu.data.LuluGroupMember
import com.jiacimu.lulu.data.ModelStructuredOutput
import kotlinx.coroutines.CancellationException

/**
 * One ephemeral room-direction model call. It reads the public conversation,
 * user-authored cast outlines and factual acquaintance stages. It never reads
 * another person's private memories, moods, thoughts or contact graph.
 * Production notes are not group facts; every participant retains their own
 * model, identity, independent decision and right to remain silent.
 */
internal data class GroupChatSceneBeat(
    val speakerId: String,
    val targetId: String? = null,
)

internal data class GroupChatScenePlan(
    val beats: List<GroupChatSceneBeat>,
) {
    /** Only validated routing crosses from the director into a person's prompt. */
    fun hintFor(speakerId: String, names: Map<String, String>): String =
        when (val target = beats.firstOrNull { it.speakerId == speakerId }?.targetId) {
            null -> ""
            "user" -> "与用户互动"
            else -> "与" + (names[target] ?: "当前群友") + "互动"
        }
}

internal object GroupChatTurnPlanner {

    /** Legacy speakerIds is supported; a real empty beats array means a pause. */
    fun decode(raw: String, allowedIds: List<String>, limit: Int): List<String>? =
        decodePlan(raw, allowedIds, limit)?.beats?.map { it.speakerId }

    fun decodePlan(
        raw: String, allowedIds: List<String>, limit: Int, allowUserTarget: Boolean = true,
    ): GroupChatScenePlan? {
        val obj = ModelStructuredOutput.objectOrNull(raw) ?: return null
        val directed = obj.optJSONArray("beats")
        val legacy = obj.optJSONArray("speakerIds")
        if (directed == null && legacy == null) return null
        val allowed = allowedIds.toSet()
        val seen = mutableSetOf<String>()
        val beats = if (directed != null) (0 until directed.length()).mapNotNull { index ->
            val candidate = directed.optJSONObject(index) ?: return@mapNotNull null
            val id = candidate.optString("speakerId").trim()
            if (id !in allowed || !seen.add(id)) return@mapNotNull null
            GroupChatSceneBeat(
                speakerId = id,
                targetId = candidate.optString("targetId").trim()
                    .takeIf { it in allowed || (allowUserTarget && it == "user") },
            )
        } else (0 until legacy!!.length()).mapNotNull { index ->
            legacy.optString(index).trim().takeIf { it in allowed && seen.add(it) }
                ?.let(::GroupChatSceneBeat)
        }
        return GroupChatScenePlan(beats.take(limit.coerceAtLeast(1)))
    }

    /** No director decision means no fabricated scene. Explicitly mentioned
     * participants can still decide for themselves whether to reply. */
    internal fun fallbackForUnplannedTurn(
        mentionedIds: List<String>, allowedIds: List<String>, limit: Int,
    ): GroupChatScenePlan {
        val ids = mentionedIds.distinct().filter { it in allowedIds }
            .take(limit.coerceAtLeast(0))
        return GroupChatScenePlan(ids.map(::GroupChatSceneBeat))
    }

    /** Keep source IDs with the exact public text given to the director. */
    fun publicContext(messages: List<LuluChatMessage>, names: Map<String, String>): List<Pair<String, String>> {
        val recalled = recalledMessageIds(messages)
        return messages.asReversed().asSequence().filter { it.status == LuluChatMessage.Status.Sent &&
            it.sender != LuluChatMessage.Sender.System && it.id !in recalled }
            .take(14).toList().asReversed().map { message ->
                val speaker = if (message.sender == LuluChatMessage.Sender.User) "用户" else
                    (message.authorCharacterId?.let { names[it] } ?: "成员")
                message.id to "${speaker.take(28)}: ${qqForwardContextText(message.content).take(220)}"
            }
    }

    /** Text-only, owner initiated group rounds; voice has different latency needs. */
    suspend fun choose(
        group: LuluGroupChat,
        members: List<LuluGroupMember>,
        publicContext: List<Pair<String, String>>,
        newestUserText: String,
        names: Map<String, String>,
    ): GroupChatScenePlan {
        val allowed = members.map { it.characterId }.distinct()
        if (allowed.isEmpty()) return GroupChatScenePlan(emptyList())
        if (allowed.size == 1) return GroupChatScenePlan(listOf(GroupChatSceneBeat(allowed.first())))
        val limit = if (group.allowCharacterConversation) group.maxAutoReplies.coerceIn(1, 8)
            else 1
        // Failed director requests must not make two unrelated characters perform.
        // Explicit @ targets still receive a chance to decide for themselves.
        val addressed = members.filter { member ->
            val nickname = member.groupNickname.ifBlank { names[member.characterId].orEmpty() }
            nickname.isNotBlank() && newestUserText.contains("@" + nickname, ignoreCase = true)
        }.map { it.characterId }.distinct()
        // Explicit @ mentions are routing facts, not the system's idea of who
        // should entertain the room. An unavailable director must not silently
        // nominate the first unrelated member as a fictional "choice".
        val fallback = fallbackForUnplannedTurn(addressed, allowed, limit)
        val library = LuluAiServices.connectionStore.library.value
        val archiveId = ScopedModelSelections.residentSocialArchiveId(library)
            ?: library.archiveIdFor(ModelUsage.Chat)
        val connection = runCatching {
            archiveId?.let(LuluAiServices.connectionStore::resolveConnection)
        }.getOrNull() ?: return fallback
        if (connection.model.startsWith("jev-")) return fallback

        // The director can read user-configured cast descriptions to propose a scene.
        // The response only hands off validated speaker/target IDs, never director prose.
        val cast = members.joinToString("\n") { member ->
            val id = member.characterId
            val label = member.groupNickname.ifBlank { names[id].orEmpty() }
            val authoredPersona = com.jiacimu.lulu.data.CharacterRuntime
                .personaConstraintSnapshot(id).replace("\n", " ")
            val ownerTie = if (group.ownerIncluded)
                com.jiacimu.lulu.data.CharacterSocialRelationship.agreement(id, "user")
                    ?.title.orEmpty().take(90) else ""
            val publicStages = members.asSequence().filter { it.characterId != id }
                .take(8).joinToString("；") { peer ->
                    val peerName = peer.groupNickname.ifBlank { names[peer.characterId].orEmpty() }
                    peerName + "：" + com.jiacimu.lulu.data.CharacterSocialRelationship
                        .snapshot(id, peer.characterId).stage
                }
            buildString {
                append(id).append("（").append(label.take(35)).append("）")
                if (authoredPersona.isNotBlank()) append("\n  用户设定的人设：").append(authoredPersona)
                if (ownerTie.isNotBlank()) append("\n  本人与用户的关系设定（非群内公开事实）：").append(ownerTie)
                if (publicStages.isNotBlank()) append("\n  可核验的群友认识阶段：").append(publicStages)
            }
        }
        val scene = publicContext.joinToString("\n") { it.second }

        val instruction = """
            你是这一轮多人聊天的临时导演：设计「关系、反应、节奏和留白」，不是名单排队器，也不是代写台词的人。
            根据刚刚真实发生的公开聊天、每个人的稳定人设及可核验关系，构思这一刻最有生活感的互动：
            可以是一人短接、一个人对另一个人说的话产生反应、轻微误会/冲突/调侃，也可以认真、尴尬或自然冷场。
            根据每个人可能对谁的话有兴趣，安排不同的互动对象；避免所有成员分别向用户回答同一个问题。
            不为了好笑强迫造梗，不要求一定有人抖包袱，不限制谁话多话少：有分量的话可充分说，简单接话一两句就够。
            你有权提议 0 到 $limit 个角色获得独立决定发言或沉默的机会；多数普通消息不需要全员接力。
            有明确 @ 的成员优先考虑，但如果全场已经说到位也可以收住；不要无限续聊抢掉用户的发言空间。
            每个 beats 项只提议谁获得接话机会，以及可选的互动对象。具体立场、表达意图与台词由角色本人决定。
            下一位必须在上一位实际发言后自己重新观察；前一人未按计划说时，后面的预案可自行放弃。
            人设、私下关系说明只供导演理解人物，不意味着其他成员知道这件事。不要泄露其他角色私聊、心理、秘事或编造共同经历。
            本导演没有替人判断心理/替人发言/批准工具/强制说话的权力。每位角色本人仍可以选择完全不同的做法。
            仅返回 JSON：
            {"beats":[{"speakerId":"名单内ID","targetId":"user或另一成员ID或空字符串"}]}
            speakerId 只从群成员 ID 里选择，不得重复；beats=[] 表示此刻自然停住。无需让所有成员都发言。
        """.trimIndent()
        return try {
            val answer = LuluAiServices.gateway.generate(
                characterId = "",
                facts = "群聊《${group.name.take(60)}》\n成员ID、人物演出参照与认识阶段（不可直接公开为事实）：\n$cast\n当前群内公开消息：\n$scene",
                instruction = instruction,
                source = "群聊导演",
                title = "群聊现场编排",
                maxTokens = (380 + limit * 140).coerceAtMost(1_500),
                connectionOverride = connection,
                contextMode = CompanionContextMode.Isolated,
                readTimeoutMillis = 30_000,
            ).getOrThrow().text
            decodePlan(answer, allowed, limit, group.ownerIncluded) ?: fallback
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No second paid retry and no published fake coordinator decision.
            fallback
        }
    }
}
