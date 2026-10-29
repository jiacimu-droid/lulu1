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
internal object GroupChatTurnPlanner {
    fun decode(raw: String, allowedIds: List<String>, limit: Int): List<String>? {
        val obj = ModelStructuredOutput.objectOrNull(raw) ?: return null
        val order = obj.optJSONArray("speakerIds") ?: return null
        val allowed = allowedIds.toSet()
        val selected = (0 until order.length()).mapNotNull { index ->
            order.optString(index).trim().takeIf { it in allowed }
        }.distinct()
        // [] is a real pause, not a parse error.
        return selected.take(limit.coerceAtLeast(1))
    }

    /** Text-only, owner initiated group rounds; voice has different latency needs. */
    suspend fun choose(
        group: LuluGroupChat,
        members: List<LuluGroupMember>,
        messages: List<LuluChatMessage>,
        names: Map<String, String>,
    ): List<String> {
        val allowed = members.map { it.characterId }.distinct()
        if (allowed.isEmpty()) return emptyList()
        if (allowed.size == 1) return allowed
        val limit = if (group.allowCharacterConversation) group.maxAutoReplies.coerceIn(1, 8)
            else 1
        val fallback = allowed.take(limit.coerceAtMost(2))
        val library = LuluAiServices.connectionStore.library.value
        val archiveId = ScopedModelSelections.residentSocialArchiveId(library)
            ?: library.archiveIdFor(ModelUsage.Chat)
        val connection = runCatching {
            archiveId?.let(LuluAiServices.connectionStore::resolveConnection)
        }.getOrNull() ?: return fallback
        if (connection.model.startsWith("jev-")) return fallback
        val roster = members.joinToString("\n") { member ->
            "${member.characterId}: ${member.groupNickname.ifBlank { names[member.characterId].orEmpty() }.take(35)}"
        }
        val scene = messages.filter { it.status == LuluChatMessage.Status.Sent &&
            it.sender != LuluChatMessage.Sender.System }.takeLast(14).joinToString("\n") { m ->
            val speaker = if (m.sender == LuluChatMessage.Sender.User) "用户" else
                (m.authorCharacterId?.let { names[it] } ?: "成员")
            "${speaker.take(28)}: ${qqForwardContextText(m.content).take(220)}"
        }
        val instruction = """
            你是多人群聊发言机会的协调员，只安排本轮发言机会，不创作台词、不判断任何成员的内心。
            只能依据以下公开群聊文字，不推测私聊、心声、人物性格、隐私和群外关系。
            决定这次是否需要回应，以及哪些成员各获得一次独立决定「回复或沉默」的机会。
            普通问候可以只选一人；明确询问两个人可选择两人；已经自然结束可以留空。
            不是接龙剧本，不要因为上一位说了梗就安排下一位续梗。避免所有人永远显示正在输入。
            角色各自会重新阅读本人真实资料并独立决定具体说什么，绝不能替他们写台词。
            只返回 JSON：{"speakerIds":["实际成员ID"]}，可为 []；不重复 ID、只用名单里的 ID。
            最高发言机会数=$limit。
        """.trimIndent()
        return try {
            val answer = LuluAiServices.gateway.generate(
                characterId = "",
                facts = "群聊《${group.name.take(60)}》\n成员ID与昵称：\n$roster\n当前群内公开消息：\n$scene",
                instruction = instruction,
                source = "群聊协调",
                title = "群聊发言机会",
                maxTokens = 240,
                connectionOverride = connection,
                contextMode = CompanionContextMode.Isolated,
                readTimeoutMillis = 30_000,
            ).getOrThrow().text
            decode(answer, allowed, limit) ?: fallback
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No second paid retry and no published fake coordinator decision.
            fallback
        }
    }
}
