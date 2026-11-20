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

    /**
     * The actor's CURRENT, evidence-backed contacts and friendship register.
     * Validate against the real resident registry without broadcasting all
     * residents/roles: knowing somebody exists is also knowledge with scope.
     * Include on every Full actor call even if episodic recall is trimmed.
     */
    fun currentSocialAuthority(characterId: String): String {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return ""
        val residentIds = DigitalWorldExpansionStore.residents().mapTo(hashSetOf()) {
            ResidentCharacterBridge.characterId(it.id)
        }
        val peers = MigratedDomainStores.characters.settings.value.values.filter {
            it.characterId != characterId &&
                DigitalLifeProfileStore.isEnabled(it.characterId) &&
                (!it.characterId.startsWith("resident:") || it.characterId in residentIds)
        }
        // A registered stranger is NOT automatically someone this actor knows.
        // Read one actor-owned request ledger, not a fresh disk decode per peer.
        val requestHistory = CharacterFriendRequests.historyFor(characterId)
        val requestPeerIds = requestHistory.mapTo(hashSetOf()) {
            if (it.fromId == characterId) it.toId else it.fromId
        }
        val personalContacts = peers.filter {
            it.characterId in requestPeerIds ||
                CharacterSocialRelationship.knowsEachOther(characterId, it.characterId)
        }
        val native = personalContacts.filter { it.characterId.startsWith("resident:") }
        val other = personalContacts.filterNot { it.characterId.startsWith("resident:") }
        fun labels(people: List<CharacterSettings>) =
            people.joinToString("、") { "${it.displayName}(${it.characterId})" }.ifBlank { "暂无" }
        val friends = personalContacts.filter {
            MomentFriendAccess.areFriends(characterId, it.characterId)
        }
        val requests = requestHistory.filter {
            it.status == "pending" &&
                MomentFriendAccess.isActiveCharacter(it.fromId) &&
                MomentFriendAccess.isActiveCharacter(it.toId)
        }
        val received = requests.filter { it.toId == characterId }
        val sent = requests.filter { it.fromId == characterId }
        val accepted = requestHistory.filter {
            it.status == "accepted" && it.resolvedAt != null &&
                MomentFriendAccess.areFriends(it.fromId, it.toId)
        }.takeLast(6)
        // Browser and mailbox claims must likewise come from delivered/observed
        // originals, not plausible prose in an old conversation or a model plan.
        val visibleReadIds = MomentFriendAccess.visiblePosts(characterId, MomentsStore.posts.value)
            .flatMapTo(hashSetOf()) { post ->
                listOf("moment-${post.id}-$characterId",
                    "moment-own-activity-${post.id}-$characterId")
            }
        val recentRead = SharedExperienceTimeline.recentEvents(characterId, 120)
            .asSequence().filter {
                it.id in visibleReadIds &&
                    it.source == "moment:phone-read" &&
                    it.evidenceKind == EventEvidenceKind.Observation &&
                    it.revision == 1L
            }.sortedByDescending { it.occurredAt }.take(3).toList()
        val recentSent = ResidentSocialMailbox.inbox(characterId, 12)
            .filter { it.fromId == characterId }.takeLast(3)
        return buildString {
            appendLine("【本人有效熟人与QQ好友凭据｜身份和关系均有本人可见来源】")
            appendLine("我有实际认识或联系依据、且目前仍在世界生活的居民：${labels(native)}。注册模板、已删除居民不算现存联系人。")
            appendLine("我有实际认识或联系依据的其他数字角色：${labels(other)}。这是我的已知范围，不是全世界的居民名册；没列出不表示某个人不存在。")
            appendLine("我当前确实确认的数字人物QQ好友：${labels(friends)}。未列出的数字人物不能说成当前好友。")
            appendLine("我真正收到、待本人决定的好友申请：" +
                received.joinToString("、") { "requestId=${it.id}；来自=${it.fromId}" }.ifBlank { "没有" })
            appendLine("我真正发出、待对方决定的好友申请：" +
                sent.joinToString("、") { "requestId=${it.id}；发给=${it.toId}" }.ifBlank { "没有" })
            appendLine("仍有效的申请通过凭据：" +
                accepted.joinToString("、") { "requestId=${it.id}；${it.fromId}→${it.toId}；确认于${it.resolvedAt}" }
                    .ifBlank { "没有；手动建立好友不等于收到或通过了申请" })
            appendLine("本人最近有回执的实际朋友圈浏览：" +
                recentRead.joinToString("；") {
                    "evidenceId=${it.id}，浏览于${it.occurredAt}，${it.speaker}：${it.evidenceContent.take(150)}"
                }.ifBlank { "没有可核验的最近浏览记录" })
            appendLine("本人最近已真实送达的数字人物私信：" +
                recentSent.joinToString("；") {
                    "mailId=${it.id}，于${it.createdAt}发送给${it.toId}：${it.content.take(90)}"
                }.ifBlank { "当前信箱没有可核验的本人发出私信" })
            appendLine("旧记忆、聊天台词或模板里出现名字，不等于这个人目前存在、认识我、加了我、发了消息或发了动态。")
            appendLine("只有真实申请与同意、好友权限、私信送达、帖子及本人实际已读回执，才能称对应社交行为已经发生。")
            appendLine("主人问到自己没有可见来源的人时，承认暂时不了解或需要查证即可，不要把未知说成不存在，更不能编造同意好友申请等结果。")
        }.trim()
    }

    /**
     * Game turns do not need the full resident activity inventory. The gateway
     * already includes currentSocialAuthority(), including actually read
     * Moments, friends, pending applications and delivered mail. Keep only
     * outstanding incoming letters here, without duplicating those sources.
     * A letter is personal evidence, not a requirement to interrupt the game.
     */
    fun gameUpdates(characterId: String): String {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return ""
        val pending = ResidentSocialMailbox.pendingIncoming(characterId).take(3)
        if (pending.isEmpty()) return ""
        return buildString {
            appendLine("【游戏期间仍待本人处理的数字人物新消息｜真实已送达】")
            pending.forEach { message ->
                val name = MigratedDomainStores.characters.get(message.fromId).displayName
                appendLine("- mailId=${message.id}；${message.createdAt}；" +
                    "${name}(${message.fromId})：${message.content}" +
                    if (message.author == "owner-on-behalf") "（主人代发）" else "")
            }
            append("是否回复由本人选择；不能把未读动态、未送达动作或他人私事当作已见事实。")
        }.trim()
    }

    /**
     * Per-friend conversation threads are derived only from delivered,
     * viewer-owned mail. The global inbox tail can otherwise hide quiet friends.
     * A preview is neither an obligation to reply nor proof of a reply.
     */
    internal fun friendThreadLines(
        actorId: String,
        friends: List<Pair<String, String>>,
        witnessedLetters: List<ResidentDirectMessage>,
        now: Instant,
    ): List<String> = friends.map { (peerId, peerName) ->
        val thread = witnessedLetters.asSequence()
            .filter { it.createdAt <= now &&
                ((it.fromId == actorId && it.toId == peerId) ||
                    (it.fromId == peerId && it.toId == actorId)) }
            .sortedBy { it.createdAt }.toList().takeLast(3)
        val detail = if (thread.isEmpty()) "目前没有彼此已送达的私信" else thread.joinToString(" / ") { mail ->
            val speaker = if (mail.fromId == actorId) "我" else peerName
            "${mail.createdAt} $speaker：${mail.content.take(105)}"
        }
        "好友=$peerName；targetCharacterId=$peerId；真实通信片段=$detail"
    }

    fun friendshipThreads(characterId: String, now: Instant = Instant.now()): String {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return ""
        val friends = ResidentSocialMailbox.visibleContacts(characterId)
            .filter { MomentFriendAccess.areFriends(characterId, it) }
        if (friends.isEmpty()) return ""
        val history = ResidentSocialMailbox.historyForInspection(characterId)
            .filter { !it.createdAt.isAfter(now) }
        val lastContact = history.groupBy {
            if (it.fromId == characterId) it.toId else it.fromId
        }.mapValues { (_, letters) -> letters.maxOf { it.createdAt } }
        // Surface quiet ties alongside recent threads; never pick who to call.
        val active = friends.sortedByDescending { lastContact[it] ?: Instant.MIN }.take(5)
        val quieter = friends.filterNot { it in active }
            .sortedBy { lastContact[it] ?: Instant.MIN }.take(3)
        val selected = (active + quieter).distinct().map { peer ->
            peer to (MigratedDomainStores.characters.settings.value[peer]?.displayName ?: peer)
        }
        val lines = friendThreadLines(characterId, selected, history, now)
        return buildString {
            appendLine("【真正的好友关系与可延续的双向话题｜只来自本人信箱】")
            lines.forEach { appendLine("- $it") }
            appendLine("这不是今日发信指标。发过信但没回信，不等于对方已读、拒绝或答应；没有聊过的朋友也不必为了热闹硬聊。")
        }.trim()
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
        val allOwnPosts = MomentsStore.posts.value.filter { it.authorCharacterId == characterId }
        val ownPosts = allOwnPosts.sortedByDescending { it.createdAt }.take(3)
        // Frequency is a fact offered to the role, never an automatic posting ban.
        val ownPostsLastTwoHours = allOwnPosts.count {
            it.createdAt.isAfter(now.minusSeconds(7_200)) && !it.createdAt.isAfter(now)
        }
        val ownPostsLastDay = allOwnPosts.count {
            it.createdAt.isAfter(now.minusSeconds(86_400)) && !it.createdAt.isAfter(now)
        }
        // The same actor-owned witness timeline holds the small moments that a
        // search result can't supply: changing light, a real world action, or
        // something witnessed during a meeting. Never look at other brains.
        val livedMoments = SharedExperienceTimeline.recentEvents(characterId, 160)
            .asSequence().filter { event ->
                !event.occurredAt.isAfter(now) && (
                    (event.evidenceKind == EventEvidenceKind.Observation &&
                        event.source in setOf("digital-world", "digital-environment")) ||
                    // A genuine meeting contains both what the actor spoke
                    // and the replies they personally witnessed. Character
                    // speech is not an invented story when it has a session.
                    (event.source == "meeting" && event.sessionId.isNotBlank() &&
                        event.evidenceKind in setOf(
                            EventEvidenceKind.CharacterStatement, EventEvidenceKind.Observation)) ||
                    // DigitalWorldStore.execute currently saves a verified
                    // world-fact-* raw action with Legacy metadata. Preserve
                    // its canonical receipt without reclassifying it into a
                    // new paid perception stimulus or fabricated thought.
                    (event.evidenceKind == EventEvidenceKind.Legacy &&
                        event.source == "legacy" && event.channel == "数字世界" &&
                        event.id.startsWith("world-fact-"))
                )
            }.sortedByDescending { it.occurredAt }.toList()
        // Allocate a few slots to each kind of life. Otherwise several
        // lighting effects can crowd out the actual joke shared with a friend.
        val recentWorldMoments = (
            livedMoments.filter { it.source == "meeting" }.take(2) +
                livedMoments.filter { it.source == "digital-environment" }.take(1) +
                livedMoments.filter { it.source != "meeting" &&
                    it.source != "digital-environment" }.take(2)
        ).distinctBy { it.id }.sortedByDescending { it.occurredAt }
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
        // An ordinary quiet day still has a time, place, body and possible
        // small observations. Do not drop the entire social-life context just
        // because the character has no unread messages or published posts.
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
            if (DigitalLifeProfileStore.isEnabled(characterId)) {
                appendLine("【此刻真实的数字世界环境｜不是主人现实天气】")
                appendLine(DigitalWorldEnvironment.snapshot(now).context())
            }
            if (recentWorldMoments.isNotEmpty()) {
                appendLine("【自己确实经历过的生活片段｜原始观察，不是发圈素材任务】")
                recentWorldMoments.forEach { event ->
                    appendLine("- evidenceId=${event.id}；${event.occurredAt}；${event.channel}：" +
                        event.evidenceContent.replace(Regex("\\s+"), " ").take(240))
                }
                appendLine("这只是你经历过的事实；当时你觉得有趣、平淡、好笑、舒服还是无感，由你自己判断。可以回味、和熟人聊、继续生活、静静留着，未必值得公开发布；不能把未做过的触摸、未说过的台词、没出现的居民写成亲历。")
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
            appendLine("最近24小时自己实际发布了${ownPostsLastDay}条，最近2小时${ownPostsLastTwoHours}条。这是核实过的发帖次数，不是继续凑数量的目标；你可以自由决定什么时候有真正新的话想说。")
            appendLine("【本人最近真实发出的朋友圈｜不是待发布草稿】")
            ownPosts.forEach { post ->
                appendLine("- postId=${post.id}；${post.createdAt}：${post.content.take(180)}")
            }
            if (ownPosts.isEmpty()) appendLine("当前存档里还没有本人发布的动态。")
            if (ownPosts.isNotEmpty()) {
                appendLine("这些内容已经真正公开过，发动态本身就是一次分享，不是每次醒来都要补交的生活日报。决定再发前先对照发布时间和主题：是否真的出现了新的经历、感受、发现或想说的角度？若刚分享过同一件事，完全可以继续生活、在原帖接评论、私聊、琢磨或先不发；不要把一份材料拆成间隔十几分钟的重复长文。这不是硬性禁发时间，真的有新鲜事或强烈表达愿望仍由本人决定。")
            }
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
