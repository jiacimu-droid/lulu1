package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconSection

/**
 * Bounded, real, per-role affordances for three underused social actions.
 * No random quota, invented nickname or automatically selected favorite:
 * the role still decides whether its actual experiences matter enough.
 */
internal object CharacterSocialActionOpportunities {
    fun prompt(characterId: String): String {
        if (characterId.isBlank()) return ""
        val names = CharacterLifeStore.state(characterId).optJSONObject("socialNames")
        val favoriteIds = LuluRepositories.lexicon.snapshot(characterId)
            .filter { it.section == LexiconSection.Favorite }
            .mapTo(hashSetOf()) { it.id }
        val candidates = MigratedDomainStores.chat.conversations.value.asSequence()
            .filter { convo ->
                convo.groupChat?.members?.any { it.characterId == characterId } == true ||
                    (convo.groupChat == null && convo.characterId == characterId &&
                        !convo.id.endsWith("-study-focus"))
            }
            .flatMap { convo ->
                MigratedDomainStores.chat.messages(convo.id).value.asSequence()
                    .filter { it.sender == LuluChatMessage.Sender.User &&
                        it.status == LuluChatMessage.Status.Sent &&
                        it.content.isNotBlank() &&
                        "message-${it.id}" !in favoriteIds &&
                        SharedExperienceTimeline.eventsByIds(characterId,
                            listOf(it.id, "${it.id}:group:$characterId")).any { e ->
                            e.evidenceKind == EventEvidenceKind.UserStatement
                        }
                    }
                    .toList().takeLast(5).asSequence()
                    .map { message -> Triple(message.createdAt, message.id, message.content) }
            }
            .sortedByDescending { it.first }
            .take(3)
            .toList()
        return buildString {
            appendLine("【本人可以自主改变的社交细节｜只是可用机会，不是必须完成的任务】")
            appendLine("给用户的私人联系人备注：${names?.optString("userRemark").orEmpty().ifBlank { "尚未设置" }}。自己在聊天中使用的网名：${names?.optString("selfNickname").orEmpty().ifBlank { "仍用初始名字" }}。")
            appendLine("若某次真实相处令自己想到一个贴切称呼，可以 action=user_remark + nickname；若当前审美与自我表达真的改变，可以 action=self_nickname + nickname。两者均须写当下理由；没想改就继续使用旧名。不要把给用户的备注误写成口头称呼，也不要重复提交相同昵称。")
            if (candidates.isNotEmpty()) {
                appendLine("以下是自己有权查看、尚未收藏的用户真实原话：")
                candidates.forEach { (_, id, text) ->
                    appendLine("- messageId=$id；原话=${text.replace(Regex("\\s+"), " ").take(135)}")
                }
                appendLine("某句话真的触动、逗笑或值得日后回看时，可选择 action=tool,tool=favorite_user_message,args={messageId:准确ID,reason:当时自己的具体想法}，或者直接 action=favorite_user_message 加相同字段。执行器会校验原话是否还存在，绝不能编造ID；不需要向用户发一篇解释。")
            } else appendLine("当前没有通过原始记录校验、尚未收藏的用户消息候选；不可凭记忆虚构 messageId 收藏。")
        }.trim()
    }
}
