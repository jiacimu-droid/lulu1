package com.jiacimu.lulu.data

import java.time.Instant

/** Read-only social continuity drawn from real messages and meetings.
 * Never invent a friendship, a reaction to a letter or an activity. */
internal object CharacterEverydaySocialContext {
    /**
     * Only actually delivered, role-witnessed group bubbles can inspire a
     * new social action. A mutable lastMessage preview is not an ownership
     * record: it can refer to a message from before someone joined, or to
     * a later withdrawn observation.
     */
    internal fun witnessedGroupDialogue(
        viewerId: String, conversation: LuluConversation,
    ): List<String> {
        val group = conversation.groupChat ?: return emptyList()
        if (group.members.none { it.characterId == viewerId }) return emptyList()
        return MigratedDomainStores.chat.messages(conversation.id).value.asReversed()
            .asSequence().take(24) // Bound the SQLite evidence checks for very long group histories.
            .filter { it.status == LuluChatMessage.Status.Sent &&
                it.sender != LuluChatMessage.Sender.System && it.content.isNotBlank() }
            .filter { message ->
                SharedExperienceTimeline.eventsByIds(
                    viewerId, listOf("${message.id}:group:$viewerId"),
                ).any { event ->
                    event.sessionId == conversation.id && event.revision == 1L &&
                        event.source in setOf("message", "owner-on-behalf")
                }
            }
            .take(3).toList().asReversed().map { message ->
                val speaker = when (message.sender) {
                    LuluChatMessage.Sender.User -> group.userGroupNickname
                    LuluChatMessage.Sender.Character -> {
                        val id = message.authorCharacterId.orEmpty()
                        group.members.firstOrNull { it.characterId == id }
                            ?.groupNickname?.takeIf(String::isNotBlank)
                            ?: MigratedDomainStores.characters.get(id).displayName
                    }
                    LuluChatMessage.Sender.System -> "系统"
                }
                val byOwner = if (message.onBehalfByOwner) "（主人代发）" else ""
                "${message.createdAt} ${speaker}$byOwner：${message.content.replace(Regex("\\s+"), " ").take(200)}"
            }
    }

    fun render(characterId: String, now: Instant = Instant.now(),
        pending: List<ResidentDirectMessage> = ResidentSocialMailbox.pendingIncoming(characterId)): String {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return ""
        val contacts = ResidentSocialMailbox.visibleContacts(characterId)
        val acquaintances = contacts.mapNotNull { id ->
            val relationship = CharacterSocialRelationship.snapshot(characterId, id)
            if (!relationship.knowsEachOther) null else id to relationship
        }
        // A confirmed request is a relationship change worth bringing into
        // the person's next normal decision, not an automatic obligation to chat.
        val newlyConfirmedFriends = CharacterFriendRequests.historyFor(characterId)
            .filter { request -> request.status == "accepted" &&
                request.resolvedAt != null &&
                MomentFriendAccess.areFriends(request.fromId, request.toId) }
            .sortedByDescending { it.resolvedAt }
            .take(4)
        val groups = MigratedDomainStores.chat.conversations.value
            .filter { chat -> chat.groupChat?.members?.any { it.characterId == characterId } == true }
            .sortedByDescending(LuluConversation::updatedAt)
            .take(6)
        val recentMessages = ResidentSocialMailbox.inbox(characterId, 12)
        val latestByContact = ResidentSocialMailbox.latestByContact(characterId)
        // Sender-owned, already committed mailbox originals only: an intended
        // message or a spoken "I sent it" never appears in this ledger.
        val deliveredOutbox = recentMessages.asSequence()
            .filter { it.fromId == characterId }
            .filter { message ->
                SharedExperienceTimeline.eventsByIds(characterId,
                    listOf(ResidentSocialMailbox.evidenceId(message, characterId))).any {
                    it.source == "character-message" && it.revision == 1L
                }
            }.toList().takeLast(3)
        val residents = DigitalWorldExpansionStore.residents()
        val at = DigitalWorldStore.locationOf(characterId)
        val ownPosts = MomentsStore.posts.value.filter { it.authorCharacterId == characterId }
            .sortedByDescending { it.createdAt }.take(3)
        // The unread cursor is not the end of a conversation. A post already
        // seen yesterday can receive a reply today; give the same character a
        // small, live, ACL-filtered view of those threads in their next normal
        // decision, not a second social model request or a fabricated memory.
        val accessibleThreads = MomentFriendAccess.visiblePosts(characterId, MomentsStore.posts.value)
            .asSequence().filter { it.comments.isNotEmpty() }
            .sortedByDescending { it.lastActivityAt }
            .take(24).toList() // Read-only prompt candidates; never truncates stored posts or action rights.
        // ACL visibility is not firsthand knowledge. Keep earlier conversations
        // only if the viewer actually read the post, owns it, or personally
        // authored a still-valid comment. Batch the original timeline IDs so
        // ordinary chat/social context cannot silently reveal every friend's
        // unread comment thread (or pay for a second perception).
        val latestOwnComments = accessibleThreads.associate { post ->
            post.id to post.comments.lastOrNull { it.characterId == characterId }?.id
        }
        val originalEvidence = SharedExperienceTimeline.eventsByIds(characterId,
            accessibleThreads.flatMap { post ->
                listOfNotNull(
                    "moment-${post.id}-$characterId",
                    latestOwnComments[post.id]?.let { "moment-public-comment-$it-$characterId" },
                )
            }).associateBy { it.id }
        val visibleThreads = accessibleThreads.asSequence().filter { post ->
            post.authorCharacterId == characterId ||
                originalEvidence["moment-${post.id}-$characterId"]?.let {
                    it.source == "moment:phone-read" &&
                        it.evidenceKind == EventEvidenceKind.Observation
                } == true ||
                latestOwnComments[post.id]?.let { commentId ->
                    originalEvidence.containsKey("moment-public-comment-$commentId-$characterId")
                } == true
        }.take(3).toList()
        if (pending.isEmpty() && contacts.isEmpty() && groups.isEmpty() &&
            recentMessages.isEmpty() && residents.isEmpty() && ownPosts.isEmpty() &&
            visibleThreads.isEmpty()) return ""
        return buildString {
            appendLine("【自己的数字世界来往｜真实事件与可选的下一步】")
            if (pending.isNotEmpty()) {
                appendLine("最近确实收到、尚未由本人决定如何处理的私信：")
                pending.forEach { message ->
                    val sender = MigratedDomainStores.characters.get(message.fromId).displayName
                    appendLine("- mailId=${message.id}；${message.createdAt}；${sender}(${message.fromId})：" +
                        message.content +
                        if (message.author == "owner-on-behalf") "；这条由主人代发，并非角色自己作出的选择" else "")
                }
                appendLine("收到消息不等于必须回。若愿意回，可一次行动调用 send_character_message 并用真实 targetCharacterId；成功回执之前不能自称已回复。")
            }
            if (acquaintances.isNotEmpty()) {
                appendLine("自己确实认识的数字人物（认识不等于好友，也不代表对方想见我）：")
                acquaintances.take(8).forEach { (id, relation) ->
                    val name = MigratedDomainStores.characters.get(id).displayName
                    val view = CharacterSocialRelationship.perspective(characterId, id)?.impression.orEmpty()
                    appendLine("- ${name}(${id})；${relation.shortContext()}" +
                        if (view.isNotBlank()) "；自己曾想：${view.take(110)}" else "")
                }
            } else if (contacts.isEmpty() && groups.isEmpty()) {
                appendLine("目前没有经真实相遇/共同群聊确认的熟人。可以出门认识人，也可以独处；不能把登记的陌生居民直接写成朋友。")
            }
            if (acquaintances.isNotEmpty()) {
                val requests = CharacterFriendRequests.historyFor(characterId)
                val candidates = acquaintances.filterNot { (id, _) ->
                    MomentFriendAccess.areFriends(characterId, id)
                }
                if (candidates.isNotEmpty()) {
                    appendLine("【真实认识但还不是QQ好友的人｜关系并非自动建立】")
                    candidates.take(8).forEach { (id, _) ->
                        val peer = MigratedDomainStores.characters.get(id).displayName
                        val latest = requests.filter { setOf(it.fromId, it.toId) ==
                            setOf(characterId, id) }.maxByOrNull { it.createdAt }
                        val status = when (latest?.status) {
                            "pending" -> if (latest.fromId == characterId)
                                "已经发出申请，正在等对方独立决定"
                                else "对方向我提出了申请，需我独立决定"
                            "declined" -> "之前有申请被拒绝，尊重对方界限"
                            else -> "没有待处理申请，可由本人考虑是否发送"
                        }
                        appendLine("- targetCharacterId=$id；$peer；$status")
                    }
                    appendLine("若本人确实想进一步结识，且没有重复申请，可用 send_friend_request(targetCharacterId,greeting)；申请送达≠已经成为好友。是否发、是否同意，由双方各自决定。")
                }
            }
            if (newlyConfirmedFriends.isNotEmpty()) {
                appendLine("【最近确认的好友关系｜已同意才算好友】")
                newlyConfirmedFriends.forEach { request ->
                    val peerId = if (request.fromId == characterId) request.toId else request.fromId
                    val peer = MigratedDomainStores.characters.settings.value[peerId]?.displayName
                        ?: peerId
                    appendLine("- ${request.resolvedAt}；$peer($peerId) 与我正式成为QQ好友；此前申请已被对方接受，不是剧情里的口头约定。")
                }
                appendLine("关系确认后，可以自己决定找新朋友聊聊、分享真正想说的动态、提议一起活动，或者先不联系；申请已通过不等于双方已实际聊天或产生了好感。")
            }
            if (contacts.isNotEmpty()) {
                appendLine("【本人手机里确实可以联系的对象｜模型可自由选择联系或不联系】")
                contacts.take(12).forEach { id ->
                    val name = MigratedDomainStores.characters.get(id).displayName
                    val last = latestByContact[id]
                    appendLine("- targetCharacterId=$id；$name；" +
                        (if (last == null) "当前保留的真实私信里没有这两人的记录"
                        else "最近通信=${last.createdAt}；发送者=${last.fromId}；mailId=${last.id}：${last.content.take(105)}" +
                            if (last.fromId == characterId) "；此后没有可见的回信，是否已读与对方近况未知" else ""))
                }
                appendLine("若一段真实的共同话题、错过的会面或未获回复的联系让你想起某个人，可以沿用本人已有的挂心或印象，选择关心、继续话题、改期或先等等。只能用亲历事实，近况不明就保留未知。若想分享见闻或打个招呼，从实际可联系对象中自己选 targetCharacterId，用 send_character_message 真正发送。")
            }
            if (groups.isNotEmpty()) {
                appendLine("【自己已经加入的聊天群｜只看本人确实收到的群消息】")
                groups.forEach { group ->
                    val members = group.groupChat?.members.orEmpty()
                    appendLine("- groupId=${group.id}；${group.groupChat?.name}；当前成员=" +
                        members.take(8).joinToString("、") { member ->
                            "${member.characterId}=${member.groupNickname.ifBlank {
                                MigratedDomainStores.characters.get(member.characterId).displayName
                            }}"
                        })
                    witnessedGroupDialogue(characterId, group).forEach { appendLine("  ${it}") }
                }
                appendLine("想和群友交流可用真实 groupId 选择 send_group_message，不需要先由主人开头，群聊成员身份并不赋予读取其他私聊的权限。群里显示的历史仅证明消息已经入群；如果自己只在私聊里说‘我发了’，却没有群消息/发送工具回执，就不能当作真的发给群友。")
            }
            appendLine("【本人已实际送达的数字人物私信｜可核验的发送原件，不是计划或心声】")
            if (deliveredOutbox.isEmpty()) appendLine("最近信箱没有可核验的本人发出私信；不能据口头声称推断已经发送。")
            else deliveredOutbox.forEach { sent ->
                appendLine("- 送达=${sent.createdAt}；收件人=${sent.toId}；mailId=${sent.id}；正文=${sent.content.take(160)}")
            }
            if (recentMessages.isNotEmpty()) {
                appendLine("本人真实通信的最近片段（旧消息不是新消息）：")
                recentMessages.takeLast(5).forEach { msg ->
                    val speaker = MigratedDomainStores.characters.get(msg.fromId).displayName
                    appendLine("- ${msg.createdAt} ${speaker}：${msg.content.take(145)}")
                }
            }
            // Presence is a world fact, but the observer is not another person
            // available for interaction. Native IDs and role IDs use different
            // namespaces; compare through the existing identity bridge.
            val nearby = residents.filter {
                it.currentLocationCode == at &&
                    ResidentCharacterBridge.characterId(it.id) != characterId
            }
            if (nearby.isNotEmpty()) {
                appendLine("此处确实在场的居民：")
                nearby.take(5).forEach { appendLine("- itemId=${it.id}；${it.name}；${it.identity}") }
            }
            // Do not turn the global world registry into an all-seeing map.
            // Another resident's remote location is not this role's observation.
            // A real encounter, public bulletin, or received message can still
            // enter the viewer's own timeline and be recalled normally.
            appendLine("【本人最近真实发出的朋友圈｜不是待发布草稿】")
            ownPosts.forEach { post ->
                appendLine("- postId=${post.id}；${post.createdAt}：${post.content.take(180)}")
            }
            if (ownPosts.isEmpty()) appendLine("当前存档里还没有本人发布的动态。")
            if (visibleThreads.isNotEmpty()) {
                appendLine("【此刻仍可查看的朋友圈讨论｜已读也能自然接话】")
                visibleThreads.forEach { post ->
                    val authorId = post.authorCharacterId
                    val authorName = authorId?.let {
                        MigratedDomainStores.characters.get(it).displayName
                    } ?: UserProfileContext.displayLabel()
                    appendLine("- postId=${post.id}；作者=${authorName}；最近活动=${post.lastActivityAt}；正文=${post.content.take(140)}")
                    post.comments.takeLast(4).forEach { comment ->
                        val speaker = if (comment.characterId == "__user__")
                            UserProfileContext.displayLabel()
                        else MigratedDomainStores.characters.get(comment.characterId).displayName
                        appendLine("  commentId=${comment.id}；作者Id=${comment.characterId}；${speaker}：${comment.content.take(125)}" +
                            comment.replyToCommentId?.let { "；回复 commentId=$it" }.orEmpty())
                    }
                }
                appendLine("如果本人想接某句评论，可选择 comment_moment 的 postId/text/replyToCommentId；回复只指向该帖子真实存在的 commentId，不填就是评论原帖。可以在同一次决定里做其他即时动作，也可以不回应；这些不是要占用未来行程的任务。")
            }
            appendLine("若确实想分享自己亲历的小事、刚读到的材料或一个想法，可用 publish_moment，args={text:本人此刻想发布的内容}；不必给主人汇报，也不用等别人先发。只想私下留着也可以；不要把计划、未执行工具或别人的经历写成自己的亲历。")
            append("一次真实来往可以让本人产生再见、继续话题、避开或暂时不联系的念头；" +
                "只有本人想把这件事留到以后，才用 plan_personal_activity 保存时间、对象与真实可执行的活动。现在时间=$now。")
        }.trim()
    }
}
