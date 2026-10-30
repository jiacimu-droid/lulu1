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
 * A public-room speaking-order proposal, NOT a shared brain or dialogue author.
 *
 * The model only sees a short excerpt from this group and its roster. It never
 * receives a member's persona, private memories, thoughts or contact graph.
 * Each selected member still makes its own independent reply/silence decision.
 */

/** A private, disposable room-direction suggestion, never a character decision. */
internal data class GroupChatSceneBeat(
    val speakerId: String,
    val targetId: String? = null,
    val focus: String = "",
    val direction: String = "",
)

internal data class GroupChatScenePlan(
    val beats: List<GroupChatSceneBeat>,
    val sceneFocus: String = "",
    val ending: String = "",
) {
    /** Expose only this person's own direction, never another actor's beat. */
    fun hintFor(speakerId: String, names: Map<String, String>): String {
        val beat = beats.firstOrNull { it.speakerId == speakerId } ?: return ""
        val target = when (val id = beat.targetId) {
            null -> ""
            "user" -> "与用户互动"
            else -> "与" + (names[id] ?: "当前群友") + "互动"
        }
        return listOf(
            sceneFocus.takeIf(String::isNotBlank)?.let { "当前公开话题：" + it },
            beat.focus.takeIf(String::isNotBlank)?.let { "这一拍可关注：" + it },
            target.takeIf(String::isNotBlank),
            beat.direction.takeIf(String::isNotBlank)?.let { "可参考的接话方向：" + it },
            ending.takeIf { beats.lastOrNull()?.speakerId == speakerId && it.isNotBlank() }
                ?.let { "自然收束的可能方式：" + it },
        ).filterNotNull().joinToString("；")
    }
}

internal object GroupChatTurnPlanner {

    /** Legacy speakerIds is supported; a real empty beats array means a pause. */
    fun decode(raw: String, allowedIds: List<String>, limit: Int): List<String>? =
        decodePlan(raw, allowedIds, limit)?.beats?.map { it.speakerId }

    fun decodePlan(raw: String, allowedIds: List<String>, limit: Int): GroupChatScenePlan? {
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
                    .takeIf { it in allowed || it == "user" },
                focus = candidate.optString("focus").trim().replace("\n", " ").take(150),
                direction = candidate.optString("direction").trim().replace("\n", " ").take(260),
            )
        } else (0 until legacy!!.length()).mapNotNull { index ->
            legacy.optString(index).trim().takeIf { it in allowed && seen.add(it) }
                ?.let(::GroupChatSceneBeat)
        }
        return GroupChatScenePlan(
            beats = beats.take(limit.coerceAtLeast(1)),
            sceneFocus = obj.optString("sceneFocus").trim().replace("\n", " ").take(180),
            ending = obj.optString("ending").trim().replace("\n", " ").take(140),
        )
    }

    /** Text-only, owner initiated group rounds; voice has different latency needs. */
    suspend fun choose(
        group: LuluGroupChat,
        members: List<LuluGroupMember>,
        messages: List<LuluChatMessage>,
        names: Map<String, String>,
    ): GroupChatScenePlan {
        val allowed = members.map { it.characterId }.distinct()
        if (allowed.isEmpty()) return GroupChatScenePlan(emptyList())
        if (allowed.size == 1) return GroupChatScenePlan(listOf(GroupChatSceneBeat(allowed.first())))
        val limit = if (group.allowCharacterConversation) group.maxAutoReplies.coerceIn(1, 8)
            else 1
        // Failed director requests must not make two unrelated characters perform.
        // Explicit @ targets still receive a chance to decide for themselves.
        val newestUserText = messages.lastOrNull {
            it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent
        }?.content.orEmpty()
        val addressed = members.filter { member ->
            val nickname = member.groupNickname.ifBlank { names[member.characterId].orEmpty() }
            nickname.isNotBlank() && newestUserText.contains("@" + nickname, ignoreCase = true)
        }.map { it.characterId }.distinct()
        val fallback = GroupChatScenePlan(
            (addressed.ifEmpty { allowed.take(1) }).take(limit).map(::GroupChatSceneBeat)
        )
        val library = LuluAiServices.connectionStore.library.value
        val archiveId = ScopedModelSelections.residentSocialArchiveId(library)
            ?: library.archiveIdFor(ModelUsage.Chat)
        val connection = runCatching {
            archiveId?.let(LuluAiServices.connectionStore::resolveConnection)
        }.getOrNull() ?: return fallback
        if (connection.model.startsWith("jev-")) return fallback

        // The director can read user-configured cast descriptions to propose a scene.
        // These private production notes are NEVER injected as peer knowledge.
        val cast = members.joinToString("\n") { member ->
            val id = member.characterId
            val label = member.groupNickname.ifBlank { names[id].orEmpty() }
            val authoredPersona = com.jiacimu.lulu.data.CharacterRuntime
                .personaConstraintSnapshot(id).replace("\n", " ").take(1_000)
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
        val scene = messages.filter { it.status == LuluChatMessage.Status.Sent &&
            it.sender != LuluChatMessage.Sender.System }.takeLast(14).joinToString("\n") { m ->
            val speaker = if (m.sender == LuluChatMessage.Sender.User) "用户" else
                (m.authorCharacterId?.let { names[it] } ?: "成员")
            "${speaker.take(28)}: ${qqForwardContextText(m.content).take(220)}"
        }

        val instruction = """
            你是这一轮多人聊天的临时导演：设计「关系、反应、节奏和留白」，不是名单排队器，也不是代写台词的人。
            根据刚刚真实发生的公开聊天、每个人的稳定人设及可核验关系，构思这一刻最有生活感的互动：
            可以是一人短接、一个人对另一个人说的话产生反应、轻微误会/冲突/调侃，也可以认真、尴尬或自然冷场。
            对同一话头安排不同成员各自有辨识度的表达意图；避免每人分别面向用户写长篇点评、解释笑点或机械复述群名。
            不为了好笑强迫造梗，不要求一定有人抖包袱，不限制谁话多话少：有分量的话可充分说，简单接话一两句就够。
            你有权提议 0 到 $limit 个角色获得独立决定发言或沉默的机会；多数普通消息不需要全员接力。
            有明确 @ 的成员优先考虑，但如果全场已经说到位也可以收住；不要无限续聊抢掉用户的发言空间。
            每个 beats 项写这个人「可以怎样接」的意图、互动对象与内容焦点，不写任何具体台词。
            下一位必须在上一位实际发言后自己重新观察；前一人未按计划说时，后面的预案可自行放弃。
            人设、私下关系说明只供导演理解人物，不意味着其他成员知道这件事。不要泄露其他角色私聊、心理、秘事或编造共同经历。
            本导演没有替人判断心理/替人发言/批准工具/强制说话的权力。每位角色本人仍可以选择完全不同的做法。
            仅返回 JSON：
            {"sceneFocus":"本轮公开话题焦点","beats":[
              {"speakerId":"名单内ID","targetId":"user或另一成员ID或空字符串","focus":"要抓住哪一点","direction":"这个人的可选互动方向，不是台词"}
            ],"ending":"这一轮可能如何留白/收住"}
            speakerId 只从群成员 ID 里选择，不得重复；beats=[] 表示此刻自然停住。无需让所有成员都发言。
        """.trimIndent()
        return try {
            val answer = LuluAiServices.gateway.generate(
                characterId = "",
                facts = "群聊《${group.name.take(60)}》\n成员ID、人物演出参照与认识阶段（不可直接公开为事实）：\n$cast\n当前群内公开消息：\n$scene",
                instruction = instruction,
                source = "群聊协调",
                title = "群聊发言机会",
                maxTokens = 700,
                connectionOverride = connection,
                contextMode = CompanionContextMode.Isolated,
                readTimeoutMillis = 30_000,
            ).getOrThrow().text
            decodePlan(answer, allowed, limit) ?: fallback
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No second paid retry and no published fake coordinator decision.
            fallback
        }
    }
}
