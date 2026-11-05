package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.decodeQqChatImage
import com.jiacimu.lulu.core.LexiconSection

/**
 * Bounded, real, per-role affordances for three underused social actions.
 * No random quota, invented nickname or automatically selected favorite:
 * the role still decides whether its actual experiences matter enough.
 */
internal object CharacterSocialActionOpportunities {
    private data class FavoriteCandidate(
        val at: java.time.Instant, val messageId: String, val preview: String, val isImage: Boolean,
    )
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
                        CharacterMessageFavorites.entryId(characterId, it.id) !in favoriteIds &&
                        SharedExperienceTimeline.eventsByIds(characterId,
                            listOf(it.id, "${it.id}:group:$characterId")).any { e ->
                            e.evidenceKind == EventEvidenceKind.UserStatement
                        }
                    }
                    .toList().takeLast(5).asSequence()
                    .map { message ->
                        val image = decodeQqChatImage(message.content)
                        val preview = if (image == null) message.content else
                            buildString {
                                append(if (image.sticker) "聊天表情包" else "聊天图片")
                                image.caption.ifBlank { image.imageDescription }
                                    .takeIf(String::isNotBlank)?.let { append("；内容：${it.take(100)}") }
                            }
                        FavoriteCandidate(message.createdAt, message.id, preview, image != null)
                    }
            }
            .sortedByDescending { it.at }
            .take(3)
            .toList()
        return buildString {
            appendLine("【本人可以自主改变的社交细节｜只是可用机会，不是必须完成的任务】")
            appendLine("给用户的私人联系人备注：${names?.optString("userRemark").orEmpty().ifBlank { "尚未设置" }}。自己在聊天中使用的网名：${names?.optString("selfNickname").orEmpty().ifBlank { "仍用初始名字" }}。")
            appendLine("若某次真实相处令自己想到一个贴切称呼，可以 action=user_remark + nickname；若当前审美与自我表达真的改变，可以 action=self_nickname + nickname。两者均须写当下理由；没想改就继续使用旧名。不要把给用户的备注误写成口头称呼，也不要重复提交相同昵称。")
            if (candidates.isNotEmpty()) {
                appendLine("以下是自己实际收到、可核实ID的用户聊天内容（图片不是文字原话）：")
                candidates.forEach { candidate ->
                    if (candidate.isImage) {
                        appendLine("- messageId=${candidate.messageId}；${candidate.preview}；想把图片文件留在本人相册，可选择 tool=save_photo,args={messageId:准确ID}；只有保存成功才算入相册。")
                    } else {
                        appendLine("- messageId=${candidate.messageId}；原话=${candidate.preview.replace(Regex("\\s+"), " ").take(135)}")
                    }
                }
                appendLine("想珍藏一句真实原话，可自主选择 tool=favorite_user_message,args={messageId:准确ID,reason:当时自己的具体想法}，也可用同名直接 action。想保存聊天图片本身用 save_photo；若确实还想珍藏这条图片聊天记录，收藏消息与保存相册是两种独立选择，可分别执行，不自动互相替换。不得编造ID，不需要额外生成一篇解释。")
            } else appendLine("当前没有通过原始记录校验、尚未收藏的用户消息候选；不可凭记忆虚构 messageId 收藏。")
        }.trim()
    }
}
