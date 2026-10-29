package com.jiacimu.lulu.data

import java.time.Instant

/** Read-only social continuity drawn from real messages and meetings.
 * Never invent a friendship, a reaction to a letter or an activity. */
internal object CharacterEverydaySocialContext {
    fun render(characterId: String, now: Instant = Instant.now(),
        pending: List<ResidentDirectMessage> = ResidentSocialMailbox.pendingIncoming(characterId)): String {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return ""
        val contacts = ResidentSocialMailbox.visibleContacts(characterId)
        val acquaintances = contacts.mapNotNull { id ->
            val relationship = CharacterSocialRelationship.snapshot(characterId, id)
            if (!relationship.knowsEachOther) null else id to relationship
        }
        val groups = MigratedDomainStores.chat.conversations.value
            .filter { chat -> chat.groupChat?.members?.any { it.characterId == characterId } == true }
            .take(6)
        val recentMessages = ResidentSocialMailbox.inbox(characterId, 8)
        val latestByContact = ResidentSocialMailbox.latestByContact(characterId)
        val residents = DigitalWorldExpansionStore.residents()
        val at = DigitalWorldStore.locationOf(characterId)
        val ownPosts = MomentsStore.posts.value.filter { it.authorCharacterId == characterId }
            .sortedByDescending { it.createdAt }.take(3)
        if (pending.isEmpty() && contacts.isEmpty() && groups.isEmpty() &&
            recentMessages.isEmpty() && residents.isEmpty() && ownPosts.isEmpty()) return ""
        return buildString {
            appendLine("【自己的数字世界来往｜真实事件与可选的下一步】")
            if (pending.isNotEmpty()) {
                appendLine("最近确实收到、尚未由本人决定如何处理的私信：")
                pending.forEach { message ->
                    val sender = MigratedDomainStores.characters.get(message.fromId).displayName
                    appendLine("- mailId=${message.id}；${message.createdAt}；${sender}(${message.fromId})：" +
                        message.content.take(230) +
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
            if (contacts.isNotEmpty()) {
                appendLine("【本人手机里确实可以联系的对象｜模型可自由选择联系或不联系】")
                contacts.take(12).forEach { id ->
                    val name = MigratedDomainStores.characters.get(id).displayName
                    val last = latestByContact[id]
                    appendLine("- targetCharacterId=$id；$name；" +
                        (if (last == null) "当前保留的真实私信里没有这两人的记录"
                        else "最近通信=${last.createdAt}；发送者=${last.fromId}；mailId=${last.id}：${last.content.take(105)}"))
                }
                appendLine("如果此刻想向某个熟人分享见闻、问一句近况、继续旧话题、开个玩笑或提出邀约，可从这些实际可联系对象中自己选 targetCharacterId，用 send_character_message 真正发送；不是必须先收到对方来信，也不是必须给主人发。")
            }
            if (groups.isNotEmpty()) {
                appendLine("【自己已经加入的聊天群】")
                groups.forEach { group ->
                    appendLine("- groupId=${group.id}；${group.groupChat?.name}；最后一条=${group.lastMessage.take(95)}")
                }
                appendLine("想和群友交流可用真实 groupId 选择 send_group_message，不需要先由主人开头，群聊成员身份并不赋予读取其他私聊的权限。")
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
            appendLine("若确实想分享自己亲历的小事、刚读到的材料或一个想法，可用 publish_moment，args={text:本人此刻想发布的内容}；不必给主人汇报，也不用等别人先发。只想私下留着也可以；不要把计划、未执行工具或别人的经历写成自己的亲历。")
            append("一次真实来往可以让本人产生再见、继续话题、避开或暂时不联系的念头；" +
                "只有本人想把这件事留到以后，才用 plan_personal_activity 保存时间、对象与真实可执行的活动。现在时间=$now。")
        }.trim()
    }
}
