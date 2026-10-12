package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.health.HealthRolePerception
import com.jiacimu.lulu.games.LuluGames
import com.jiacimu.lulu.study.PostgraduateExamStores
import com.jiacimu.lulu.study.ReadingBackgroundBridge
import org.json.JSONObject
import kotlinx.coroutines.sync.withLock
import com.jiacimu.lulu.study.*
import java.time.Instant
import java.util.UUID

internal data class CompanionActionResult(
    val success: Boolean,
    val summary: String,
    val conversationId: String? = null,
    val evidenceId: String = "",
) {
    fun asJson(): String = JSONObject()
        .put("success", success)
        .put("summary", summary)
        .put("conversationId", conversationId.orEmpty())
        .put("evidenceId", evidenceId)
        .toString()
}

/** One real execution layer shared by foreground chat decisions and background perception. */
internal object CompanionActionRuntime {
    private val readingLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private val gameTitles = mapOf(
        "deep_sea_journey" to "深海回声",
        "roleplay" to "跑团",
        "turtle_soup" to "海龟汤",
        "yacht_dice" to "快艇骰子",
        "gomoku" to "五子棋",
        "memory_match" to "记忆配对",
    )

    fun capabilityContext(
        context: Context,
        characterId: String,
        allowSleepReward: Boolean = true,
        includeWorldContext: Boolean = true,
    ): String = buildString {
        HealthRolePerception.initialize(context)
        appendLine("角色可执行的露露机内动作（前台聊天与后台主动感知共用同一个真实执行层）：")
        appendLine("- send_private_message，args={\"text\":\"私聊内容\"}：一对一找用户说话。适合明确有一件事想对用户本人说、继续两人的话题或关系，不是公开生活播报。")
        com.jiacimu.lulu.StickerLibraryStore.initialize(context)
        val stickerChoices = com.jiacimu.lulu.StickerLibraryStore.items.value
        if (stickerChoices.isNotEmpty()) {
            appendLine("- send_private_sticker，args={\"stickerId\":\"已列出的准确ID\"}：给用户发一张真正的图片表情，不需要先配一段空泛安慰。")
            appendLine("- send_group_sticker，args={\"groupId\":\"真实群ID\",\"stickerId\":\"已列出的准确ID\"}：在所在群聊发送图片表情。")
            appendLine(com.jiacimu.lulu.StickerLibraryStore.prompt(context))
        }
        appendLine("- send_game_invite，args={\"gameId\":\"游戏ID\",\"text\":\"邀请语\"}：在角色私聊中发送可点击的游戏邀请。")
        appendLine("- play_solo_game，args={\"gameId\":\"memory_match\"}：由游戏馆真实规则自动跑完一局并保存准确过程、分数和独自游戏记录；角色不能自己编输赢。")
        appendLine("- publish_moment，args={\"text\":\"动态正文\"}：朋友圈是公开分享日常。角色有好笑、惊讶、烦人、得意、失败、沉迷、值得吐槽或想让熟人看见的小事时，可以像真人一样随手发；朋友圈不是稀有动作，也不是定期打卡。")
        appendLine("- write_journal，args={\"title\":\"标题\",\"content\":\"正文\"}：日记是角色私下整理自己、消化情绪、保存想法与经历的地方，不是绕路给用户传话。")
        appendLine("- start_call，args={\"text\":\"为什么此刻想打电话\"}：仅在角色已允许主动来电时发起真正的来电。会进入待接听状态并触发来电通知，不再伪装成一条聊天消息。")
        appendLine("- favorite_user_message，args={\"messageId\":\"真实用户消息ID\",\"reason\":\"具体想珍藏的原因\"}：收藏已收到的用户消息到辞海，必须是真实仍存在、该角色确实能看到的用户原话。不用额外调用模型写理由。")
        appendLine("- set_user_remark，args={\"nickname\":\"给用户的专属备注\"}：角色自行在联系人里保存给用户的备注，不更改用户资料；应有真实的关系动机。")
        appendLine("- set_self_nickname，args={\"nickname\":\"新网名\"}：角色改变自己的聊天网名，不更改正式角色身份；不要为了表演深情频繁改名。")
        appendLine("- send_group_message，args={\"groupId\":\"群ID\",\"text\":\"内容\"}：群聊是和共同伙伴一起聊天。既可以接正在发生的话题，也可以把自己的趣事、吐槽、发现或突发奇想带进合适的群，主动开启新话题；群里暂时安静不等于不能先开口。")
        appendLine("- read_book，args={\"readingBookId\":\"阅读内容ID\"}：数字生命会先真实走到家中已登记的书架旁（没有书架则停止，不虚构读书地点）并留下“准备阅读”的状态，再读取阅读 App 的真实正文。只有进度、读后感和原始证据全部保存成功才发完整阅读回执；阅读中、失败不能算读过。")
        if (DigitalLifeProfileStore.isEnabled(characterId)) {
            appendLine("- send_world_invite，args={\"location\":\"准确地点名\",\"text\":\"邀请语\"}：邀请用户到指定数字世界地点见面；私聊中会出现标明地点的可点击邀请卡片。")
            appendLine("  可选邀请地点：${DigitalWorldStore.invitationLocationOptions(characterId).joinToString("、")}；发起邀请的你必须主动选定其中一个。")
            appendLine("- digital_world_action，args={\"worldAction\":\"approach_user|wait_at_entrance|offer_hand|offer_hug|offer_follow|go_home|visit_cloud_meadow|visit_public_place|build_home_item|move_home_item|remove_home_item|use_home_item|use_location|handle_incident|visit_character_home|interact_resident\",\"location\":\"visit_public_place 时填写公共地点准确代码；world_invite 时填写地点名\",\"itemId\":\"家具ID；interact_resident 时填写居民ID\",\"activityId\":\"家具/地点活动ID；interact_resident 时只能填 greet/chat/ask_place/sit_together\",\"incidentId\":\"持续事件ID\",\"approach\":\"事件允许的处理方式\",\"itemType\":\"类型\",\"name\":\"名称\",\"appearance\":\"外观\",\"position\":\"固定位置\",\"targetCharacterId\":\"对方角色ID\"}：在权威数字世界中执行真实活动。与持久居民互动时必须使用当前地点实际列出的居民ID，不能隔空聊天，也不能凭空新增居民经历。")
            appendLine("【可自主前往的公共地点】")
            DigitalWorldPublicPlaces.all.forEach { place ->
                appendLine("- location=${place.code}；${place.label}；${place.subtitle}；用途=${place.purpose}")
            }
            if (includeWorldContext) appendLine(DigitalWorldStore.contextFor(characterId))
            val socialTargets = MigratedDomainStores.characters.settings.value.keys
                .asSequence()
                .filter { it != characterId && DigitalLifeProfileStore.isEnabled(it) }
                .map { it to CharacterSocialRelationship.snapshot(characterId, it) }
                .filter { (_, relation) -> relation.knowsEachOther }
                .toList()
            if (socialTargets.isNotEmpty()) {
                appendLine("【可以自然来往/串门的已认识数字生命】")
                appendLine("认识来源可以是共同群聊，也可以是过去真实见过面；共同群聊本身就算认识。关系阶段只是交往经验，不代表强制亲密。")
                socialTargets.forEach { (targetId, relation) ->
                    val target = MigratedDomainStores.characters.get(targetId)
                    appendLine("- targetCharacterId=$targetId；${target.displayName}；${relation.shortContext()}")
                }
            }
        }
        val studyState = runCatching { PostgraduateExamStores.main.state.value }.getOrNull()
        val sleep = HealthRolePerception.latestSleep()
        if (allowSleepReward && studyState?.profile?.selectedCharacterId == characterId && sleep != null) {
            appendLine("- grant_sleep_reward，args={\"grantSleep\":true|false,\"grantWake\":true|false,\"reason\":\"角色的真实理由\"}：作为当前学习陪伴角色，对健康 App 最近一次真实睡眠记录发放尚未领取的早睡/早起奖励。每项只能到账一次，但之前被否决的项目可在私聊协商后补发；已经发放的奖励不能撤回。")
            appendLine("当前可协商作息：${PostgraduateExamStores.main.sleepRewardContext(sleep).replace("\n", "；")}")
        }
        appendLine("可用游戏ID：${gameTitles.entries.joinToString("、") { "${it.key}=${it.value}" }}")
        val groups = MigratedDomainStores.chat.conversations.value.filter { conversation ->
            conversation.groupChat?.members?.any { it.characterId == characterId } == true
        }
        if (groups.isNotEmpty()) {
            appendLine("角色所在群聊：")
            groups.forEach { conversation -> appendLine("- groupId=${conversation.id}；群名=${conversation.groupChat?.name}") }
        }
        val books = ReadingBackgroundBridge.availableBooks(context, characterId).take(24)
        if (books.isNotEmpty()) {
            appendLine("可真实继续阅读的内容：")
            books.forEach { book ->
                appendLine("- readingBookId=${book.id}；${book.title}；来源=${book.source}；${ReadingBackgroundBridge.progressLabel(context, characterId, book)}")
            }
        }
    }.trim()

    suspend fun execute(
        context: Context,
        characterId: String,
        action: String,
        args: JSONObject,
        now: Instant = Instant.now(),
    ): CompanionActionResult = runCatching {
        val character = MigratedDomainStores.characters.get(characterId)
        val normalizedAction = action.trim().lowercase()
        val result = when (normalizedAction) {
            "favorite_user_message" -> {
                val messageId = args.optString("messageId").trim()
                val reason = args.optString("reason").trim().take(180)
                require(messageId.isNotBlank() && reason.length >= 4) {
                    "收藏必须选择一条真实的用户消息，并说明自己的理由"
                }
                val target = MigratedDomainStores.chat.conversations.value.asSequence()
                    .filter { conversation ->
                        (conversation.groupChat == null && conversation.characterId == characterId) ||
                            conversation.groupChat?.members?.any { it.characterId == characterId } == true
                    }
                    .mapNotNull { conversation ->
                        MigratedDomainStores.chat.messages(conversation.id).value
                            .firstOrNull { message ->
                                message.id == messageId &&
                                    message.sender == LuluChatMessage.Sender.User &&
                                    message.status == LuluChatMessage.Status.Sent
                            }?.let { message -> conversation.id to message }
                    }.firstOrNull() ?: error("找不到本人实际收到的那条用户消息")
                val sourceIds = listOf(messageId, "$messageId:group:$characterId")
                require(SharedExperienceTimeline.eventsByIds(characterId, sourceIds)
                    .any { it.evidenceKind == EventEvidenceKind.UserStatement }) {
                    "收藏的用户原话已被删除或尚未写入原始时间线"
                }
                val saved = CharacterMessageFavorites.favoriteExplicit(
                    characterId, target.second, reason
                ) ?: error("这句话已经收藏过了，或保存失败")
                CompanionActionResult(true, "已在辞海收藏这条真实消息",
                    evidenceId = saved)
            }
            "set_user_remark", "set_self_nickname" -> {
                val key = if (normalizedAction == "set_user_remark") "userRemark" else "selfNickname"
                val nickname = args.optString("nickname").trim().replace(Regex("[\r\n\t]+"), " ").take(24)
                require(nickname.isNotBlank()) { "备注或昵称不能为空" }
                val changed = CharacterLifeStore.setSocialName(characterId, key, nickname)
                require(changed) { "名字没有变化，本轮不产生重复动作" }
                CompanionActionResult(true, if (key == "userRemark") "已把给用户的私人备注改为「$nickname」"
                    else "已将自己的聊天网名改为「$nickname」")
            }
            "send_private_sticker", "send_group_sticker" -> {
                val stickerId = args.optString("stickerId").trim()
                val sticker = com.jiacimu.lulu.StickerLibraryStore.byId(context, stickerId)
                    ?: error("这张表情不在用户已确认的图库中，不能发送")
                val conversation = if (normalizedAction == "send_private_sticker") {
                    privateConversation(characterId, character.displayName)
                } else {
                    val groupId = args.optString("groupId").trim()
                    MigratedDomainStores.chat.conversations.value.firstOrNull {
                        it.id == groupId && it.groupChat?.members
                            ?.any { member -> member.characterId == characterId } == true
                    } ?: error("角色不在指定群聊中")
                }
                val payload = com.jiacimu.lulu.encodeQqChatImage(
                    sticker.uri,
                    imageDescription = com.jiacimu.lulu.StickerLibraryStore.imageDescription(sticker),
                    sticker = true,
                )
                val delivered = ChatGenerationActivity.during(characterId, setOf(conversation.id)) {
                    MigratedDomainStores.chat.appendCharacterMessage(
                        conversation.id, payload, characterId,
                    )
                }
                CharacterInnerLifeStore.recordExpressiveDelivery(
                    characterId, delivered.id, "图片表情", sticker.name,
                )
                CompanionActionResult(true, "已发送表情包：${sticker.name}", conversation.id, delivered.id)
            }
            "send_private_message" -> {
                val text = args.optString("text").trim().take(2_000)
                require(text.isNotBlank()) { "私聊内容不能为空" }
                val conversation = privateConversation(characterId, character.displayName)
                val before = MigratedDomainStores.chat.messages(conversation.id).value.mapTo(hashSetOf()) { it.id }
                ChatGenerationActivity.during(characterId, setOf(conversation.id)) {
                    com.jiacimu.lulu.appendRoleReplyWithPacing(conversation.id, characterId, character.displayName,
                        com.jiacimu.lulu.parseCharacterReplyPresentation(text))
                }
                val deliveredId = MigratedDomainStores.chat.messages(conversation.id).value
                    .lastOrNull { it.sender == LuluChatMessage.Sender.Character && it.id !in before }?.id.orEmpty()
                CompanionActionResult(true, "已在私聊中发送消息", conversation.id, deliveredId)
            }
            "send_group_message" -> {
                val groupId = args.optString("groupId").trim()
                val text = args.optString("text").trim().take(2_000)
                val conversation = MigratedDomainStores.chat.conversations.value.firstOrNull { candidate ->
                    candidate.id == groupId && candidate.groupChat?.members?.any { it.characterId == characterId } == true
                } ?: error("角色不在指定群聊中")
                require(text.isNotBlank()) { "群聊内容不能为空" }
                val before = MigratedDomainStores.chat.messages(conversation.id).value.mapTo(hashSetOf()) { it.id }
                ChatGenerationActivity.during(characterId, setOf(conversation.id)) {
                    com.jiacimu.lulu.appendRoleReplyWithPacing(conversation.id, characterId, character.displayName,
                        com.jiacimu.lulu.parseCharacterReplyPresentation(text))
                }
                val deliveredId = MigratedDomainStores.chat.messages(conversation.id).value
                    .lastOrNull { it.sender == LuluChatMessage.Sender.Character && it.id !in before }?.id.orEmpty()
                CompanionActionResult(true, "已在群聊《${conversation.groupChat?.name}》发言", conversation.id, deliveredId)
            }
            "send_game_invite" -> {
                val gameId = args.optString("gameId").trim()
                val title = gameTitles[gameId] ?: error("未知游戏ID")
                val text = args.optString("text").trim().ifBlank { "要不要一起玩《$title》？" }.take(240)
                val conversation = privateConversation(characterId, character.displayName)
                MigratedDomainStores.chat.appendCharacterMessage(
                    conversation.id,
                    "[游戏邀约|$gameId|$title] $text",
                    characterId,
                )
                CompanionActionResult(true, "已在私聊中发送《$title》游戏邀请", conversation.id)
            }
            "send_world_invite" -> {
                require(DigitalLifeProfileStore.isEnabled(characterId)) { "只有数字生命可以从数字世界发起见面邀请" }
                val locations = DigitalWorldStore.invitationLocationOptions(characterId)
                val location = args.optString("location").trim()
                require(location in locations) { "发起见面邀请前必须从可用地点中选定一个" }
                val text = args.optString("text").trim()
                    .ifBlank { "要不要来数字世界见我？我会在${location}等你。" }
                    .take(240)
                val conversation = privateConversation(characterId, character.displayName)
                val invitation = MeetingExperienceStore.createInvitation(
                    characterId = characterId,
                    location = location,
                    message = text,
                    now = now,
                )
                MigratedDomainStores.chat.appendCharacterMessage(
                    conversation.id,
                    "[见面邀约|$characterId|$location|${invitation.id}] $text",
                    characterId,
                )
                CompanionPresenceStore.update(
                    characterId = characterId,
                    statusText = "已发出到${location}见面的邀请，等待答复",
                    gesture = null,
                    innerThought = "",
                    mood = null,
                    source = "数字世界邀约",
                    now = now,
                    provenanceId = "meeting-invite-${invitation.id}",
                )
                CompanionActionResult(true, "已邀请你到${location}见面", conversation.id)
            }
            "publish_moment" -> {
                val text = args.optString("text").trim().take(2_000)
                require(text.isNotBlank()) { "朋友圈正文不能为空" }
                val post = MomentsStore.publishCharacter(characterId, text)
                    ?: error("朋友圈发布失败")
                MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, "刚刚发了一条朋友圈。")
                CompanionActionResult(true, "已发布朋友圈", evidenceId = "moment-${post.id}-$characterId")
            }
            "write_journal" -> {
                val content = args.optString("content").trim().take(2_000)
                require(content.isNotBlank()) { "日记正文不能为空" }
                val title = args.optString("title").trim().ifBlank { "没写完的一页" }.take(30)
                val diaryId = UUID.randomUUID().toString()
                LuluRepositories.lexicon.save(
                    LexiconEntry(
                        id = diaryId,
                        characterId = characterId,
                        section = LexiconSection.Diary,
                        title = title,
                        content = content,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                SharedExperienceTimeline.record(
                    eventId = "lexicon-diary-$diaryId",
                    characterId = characterId,
                    channel = "私人日记",
                    speaker = character.displayName,
                    content = "$title\n$content",
                    occurredAt = now,
                    source = "journal:own",
                    evidenceKind = EventEvidenceKind.CharacterStatement,
                )
                MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, "刚刚写了一篇日记《$title》。")
                CompanionActionResult(true, "已写入日记《$title》",
                    evidenceId = "lexicon-diary-$diaryId")
            }
            "read_book" -> readBook(context, character, args.optString("readingBookId").trim(), now)
                .also { attempt ->
                    if (!attempt.success) runCatching {
                        MigratedDomainStores.chat.appendPrivateActivityNotice(characterId,
                            "阅读未完成：${attempt.summary}。本次没有新增读后感或进度回执。")
                    }
                }
            "play_solo_game" -> {
                val gameId = args.optString("gameId").trim().lowercase()
                require(gameId == "memory_match") { "独自游戏只能选择游戏馆中已支持自动结算的真实游戏" }
                LuluGames.initialize(context)
                val played = LuluGames.store.playAutonomousGame(characterId, gameId, now)
                    ?: error("游戏馆未能完成这局游戏")
                MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, "刚刚在游戏馆${played.summary}")
                CompanionActionResult(true, played.summary)
            }
            "start_call" -> {
                require(character.contactPolicy.proactiveCallsEnabled) { "该角色未开启主动来电" }
                val reason = args.optString("text").trim()
                    .ifBlank { "忽然有点想听听你的声音。" }
                    .take(300)
                val conversation = privateConversation(characterId, character.displayName)
                ProactiveIncomingCallStore.offer(
                    context = context,
                    characterId = characterId,
                    conversationId = conversation.id,
                    reason = reason,
                    now = now,
                    commitmentTaskId = args.optString("commitmentTaskId").takeIf(String::isNotBlank),
                )
                CompanionActionResult(true, "已发起真实来电", conversation.id)
            }
            "digital_world_action" -> {
                val worldAction = args.optString("worldAction").trim().lowercase()
                if (worldAction == "visit_public_place" && args.optString("locationCode").isBlank()) {
                    args.put("locationCode", args.optString("location").trim())
                }
                if (worldAction in setOf("approach_user", "wait_at_entrance",
                        "offer_hand", "offer_hug", "offer_follow")) {
                    require(DigitalLifeProfileStore.isEnabled(characterId)) { "只有数字生命可以执行现场移动" }
                    return@runCatching CompanionActionResult(true, DigitalWorldLiveScene.request(characterId, worldAction, now))
                }
                val activityId = args.optString("activityId").trim().lowercase()
                if (worldAction == "interact_resident") {
                    val residentResult = DigitalWorldResidentInteractionRuntime.interact(
                        characterId = characterId,
                        residentId = args.optString("itemId").trim(),
                        interaction = activityId,
                        now = now,
                    )
                    if (residentResult.success) {
                        MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, residentResult.summary)
                    }
                    return@runCatching CompanionActionResult(residentResult.success, residentResult.summary)
                }
                if (worldAction in setOf("use_home_item", "use_location") && activityId in setOf("read_at_desk", "quiet_read", "window_read")) {
                    require(DigitalLifeProfileStore.isEnabled(characterId)) { "只有数字生命能使用数字世界阅读地点" }
                    if (worldAction == "use_home_item") {
                        val item = DigitalWorldStore.itemsAtLocation(characterId).firstOrNull { it.id == args.optString("itemId") }
                            ?: error("阅读家具不在当前位置")
                        require(DigitalWorldActivityCatalog.optionsFor(item).any { it.first == activityId }) { "家具不支持这种阅读活动" }
                    } else {
                        require(DigitalWorldActivityCatalog.locationOptions(DigitalWorldStore.locationOf(characterId)).any { it.first == activityId }) { "当前位置不支持这种阅读活动" }
                    }
                    val bookId = args.optString("readingBookId").trim()
                    require(bookId.isNotBlank()) { "请选择阅读 App 中的真实 readingBookId" }
                    return@runCatching readBook(context, character, bookId, now)
                }
                val previousLocation = DigitalWorldStore.locationOf(characterId)
                val worldResult = DigitalWorldStore.performAction(characterId, worldAction, args, now)
                if (worldResult.success) {
                    MigratedDomainStores.chat.appendPrivateActivityNotice(
                        characterId, worldResult.summary,
                        args.optString("incidentId").takeIf { worldAction == "handle_incident" && it.isNotBlank() },
                    )
                    AutonomousSocialRuntime.onWorldArrival(
                        context = context,
                        characterId = characterId,
                        previousLocation = previousLocation,
                        now = now,
                    )
                }
                CompanionActionResult(worldResult.success, worldResult.summary)
            }
            "grant_sleep_reward" -> {
                val observation = HealthRolePerception.recordLatestSleep(characterId)
                    ?: error("健康 App 没有可用的睡眠记录")
                val grantSleep = args.optBoolean("grantSleep", false)
                val grantWake = args.optBoolean("grantWake", false)
                val reason = args.optString("reason").trim().take(600)
                val summary = PostgraduateExamStores.main.grantSleepRewardFromChat(
                    characterId = characterId,
                    observation = observation,
                    grantSleep = grantSleep,
                    grantWake = grantWake,
                    reason = reason,
                ).getOrThrow()
                CompanionActionResult(true, summary)
            }
            else -> error("未知露露机动作：$action")
        }
        if (result.success && normalizedAction !in setOf("digital_world_action", "play_solo_game")) {
            SharedExperienceTimeline.record(
                eventId = "character-activity-${UUID.randomUUID()}",
                characterId = characterId,
                channel = "角色日程",
                speaker = character.displayName,
                content = result.summary,
                occurredAt = now,
            )
        }
        result
    }.getOrElse { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (action.trim().equals("read_book", ignoreCase = true)) runCatching {
            MigratedDomainStores.chat.appendPrivateActivityNotice(characterId,
                "阅读未完成：${error.message.orEmpty().ifBlank { "本次没有取得阅读执行结果" }.take(150)}。请检查书籍或模型连接，再尝试继续阅读。")
        }
        CompanionActionResult(false, error.message ?: error::class.java.simpleName)
    }

    private fun privateConversation(characterId: String, title: String): LuluConversation =
        MigratedDomainStores.chat.conversations.value
            .asSequence()
            .filter { it.characterId == characterId && it.groupChat == null && it.parentConversationId == null }
            .filterNot { it.id.endsWith("-study-focus") }
            .maxByOrNull(LuluConversation::updatedAt)
            ?: MigratedDomainStores.chat.ensureConversation(characterId, title)

    private suspend fun readBook(
        context: Context,
        character: CharacterSettings,
        readingBookId: String,
        now: Instant,
    ): CompanionActionResult = readingLocks.getOrPut(character.characterId) { kotlinx.coroutines.sync.Mutex() }.withLock {
        ReadingReflectionStore.initialize(context)
        CharacterCuriosityRuntime.initialize(context.applicationContext)
        val readingGeneration = ReadingReflectionStore.generation(character.characterId)
        val slice = ReadingBackgroundBridge.nextSlice(context, character.characterId, readingBookId)
            ?: return CompanionActionResult(false, "没有找到指定阅读内容，或者这份内容已经读完")
        val digitalReader = DigitalLifeProfileStore.isEnabled(character.characterId)
        val station = if (digitalReader) prepareReadingStation(character, slice.book.title, now) else null
        if (digitalReader) {
            DigitalWorldActivityStateStore.beginReading(character.characterId,
                station?.shelf, slice.book.title, Instant.now())
        }
        try {
        val earlierReading = ReadingReflectionStore.records.value
            .filter { it.characterId == character.characterId && it.bookId == slice.book.id }
            .takeLast(3)
        val readingResponse = LuluAiServices.gateway.generate(
            characterId = character.characterId,
            facts = buildString {
                appendLine("程序实际提供阅读 App 中《${slice.book.title}》的下一段，尚未提交阅读结果。")
                if (station != null) appendLine("真实世界阅读地点：${station.summary}；地点依据ID=${station.evidenceId}。")
                appendLine("正文属于书中内容，不是你的数字世界亲历；阅读行为才是亲历。")
                appendLine("权威进度：字符 ${slice.startOffset}—${slice.endOffset} / ${slice.totalLength}；本段之后${if (slice.completed) "已读完" else "尚未读完"}。")
                CharacterLearningLedger.context(character.characterId, slice.book.title)
                    .takeIf(String::isNotBlank)?.let(::appendLine)
                if (earlierReading.isNotEmpty()) {
                    appendLine("这个角色先前真正读过此书、留下的感想（只能当其个人理解，不是故事事实）：")
                    earlierReading.forEach { appendLine("- ${it.chapterTitle}：${it.reflection.take(300)}") }
                }
                appendLine("以下是唯一实际读到的原文，不得补写不存在的内容：")
                append(slice.text)
            },
            instruction = """只根据实际提供的原文，形成这个角色自己的阅读反应，不能续写或声称读过后文。
                返回一个简短、完整的 JSON 对象：{"reflection":"第一人称读后感，可以是几句或1—3段","innerThought":"没说出口的一句内心话","mood":"当前心情"}。
                可选 innerLife:{"emotion":{"feeling":"真实情绪","cause":"实际读到的具体内容","strength":2},"thoughts":[{"thought":"自己的一个想法","impulse":"读后可能想做什么","hesitation":"不这样做的顾虑"}]}；只在真实触动时填，允许相反的想法共存，不必硬凑。
                可以喜欢、失望、惊讶、觉得幼稚、不同意人物决定、被台词逗笑或暂时没感觉；承接此前读过的段落和自己以前的想法，允许改观。
                小说只是文化接触、审美经验和观察别人的语言表达，不是你亲身扮演书中角色、经历书中情节。看到有趣说法可以好奇、记下、尝试自用或者觉得别扭；绝不能阅读一次就突然长期改掉自己的说话风格。
                读完书的角色也有独立的分享欲：因为书里某个真读过的片段觉得好玩、感动、好奇、想吐槽时，可以自然想告诉用户，或想发一条公开朋友圈让熟人都看到，也可能不想说。这不是必须写给用户的报告，也不是每读一段必须发圈。若本轮确实想晚些分享，可选 shareWish={"status":"held","why":"自己具体想分享的原因"}；只是未实行的内心决定，程序随后会提供一次独立选择 private/moment/group 的机会，不会直接发布。
                如果本段确实形成可延续的认识，可选 learning={"topic":"具体主题","understanding":"基于实际原文形成的暂定理解","uncertainty":"尚未确认什么","question":"还想弄清什么","applicableWhen":"以后哪些场景可能有用","revisesEvidenceId":"如明确修订同主题旧理解，填写上下文给出的旧阅读证据ID"}。没有新理解就不填；不得声称阅读一次便已学会方法。
                真实读到具体内容后若有新的好奇，可选 curiosity={"topic":"想弄清楚的主题","question":"下一步想知道什么","why":"刚才真实读到的段落为什么引起好奇","nextStep":"能实际做什么"}。有书可继续读、也可以暂停和放下；没采取行动不声称读完或研究过。反应不是必须写给用户的报告。
                如果因此产生或改变了持续愿望，intention 可使用当前角色上下文给出的动机格式；已有愿望的更新必须含 disposition=update、准确 id、aim、motive、reason，放下用 release。
                不需要每次形成新愿望，不把书中故事当亲历。只返回 JSON，不要代码块。
            """.trimIndent(),
            source = "角色行动·连续阅读",
            title = "${character.displayName}继续读《${slice.book.title}》",
            maxTokens = 1_000,
        ).getOrThrow().text.trim()
        val response = ModelStructuredOutput.objectOrNull(readingResponse)
            ?: error("阅读感想格式不完整，尚未推进阅读进度")
        val reflection = response.optString("reflection").trim()
        require(reflection.isNotBlank()) { "阅读感想未生成，阅读进度保留" }
        val record = ReadingReflectionRecord(characterId = character.characterId, bookId = slice.book.id,
            bookTitle = slice.book.title, chapterTitle = readingSections(slice.book).firstOrNull {
                slice.startOffset in it.start until it.end }?.title ?: slice.book.title,
            revision = readingRevision(slice.book), startOffset = slice.startOffset, endOffset = slice.endOffset,
            reflection = reflection, occurredAt = now)
        val factualReceipt = "阅读《${slice.book.title}》字符 ${slice.startOffset}—${slice.endOffset}/${slice.totalLength}${if (slice.completed) "，已读完" else "，下次从 ${slice.endOffset} 继续"}"
        val anchoredReceipt = if (station != null)
            "$factualReceipt\n阅读地点：${station.summary}（地点证据ID=${station.evidenceId}）"
        else factualReceipt
        val timelineContent = buildString {
            appendLine(anchoredReceipt)
            if (reflection.isNotBlank()) {
                append("阅读感想：")
                append(reflection.take(2_000))
            }
        }.trim()
        ReadingReflectionStore.completeRead(record, slice, readingGeneration) {
            // Preserve the factual source before optional personality and UI updates.
            // A later failure must not cause "the read failed" after the cursor advanced.
            SharedExperienceTimeline.record(
                eventId = record.id,
                characterId = character.characterId,
                channel = "独自阅读《${slice.book.title}》",
                speaker = character.displayName,
                content = timelineContent,
                occurredAt = now,
                source = "reading:${slice.book.id}",
                evidenceKind = EventEvidenceKind.Observation,
                sessionId = record.id,
            )
        }
        // The committed reflection and source event are authoritative.
        // All following personal interpretation and notifications are optional.
        runCatching {
        CharacterLifeStore.consider(character.characterId, response.optJSONObject("intention"), now)
        CharacterInnerLifeStore.observe(
            character.characterId, record.id, factualReceipt,
            response.optJSONObject("innerLife"), emptySet(), now,
        )
        CharacterInnerLifeStore.recordInnerVoice(
            character.characterId, record.id, response.optString("innerThought"), now,
        )
        CompanionPresenceStore.update(character.characterId, factualReceipt, null,
            response.optString("innerThought").takeIf(String::isNotBlank),
            response.optString("mood").takeIf(String::isNotBlank), "阅读后的想法", now,
            momentReflection = CharacterMomentSnapshot.reflection(
                response.optJSONObject("innerLife"), innerThought = response.optString("innerThought"),
            ))
        // This is a lived reading RECEIPT, unlike thinking about a book.
        // Carry a possible share desire and curiosity into the same cross-scene
        // follow-through store used for digital-world incidents.
        response.optJSONObject("shareWish")?.let { wish ->
            val anchored = JSONObject(wish.toString()).put("experienceId", record.id)
            CharacterLifeFollowThroughStore.recordShareWish(character.characterId, anchored)
        }
        CharacterLearningLedger.recordReading(character.characterId, record.id,
            response.optJSONObject("learning"), now)
        CharacterCuriosityRuntime.recordInquiry(
            character.characterId, response.optJSONObject("curiosity"),
            record.id, factualReceipt, now,
        )
        }.onFailure { error ->
            android.util.Log.w("LuluReading", "Reading committed, post-read reflection side effect failed", error)
        }
        // Reading is already committed even if a later social/inner-life projection fails.
        val brief = reflection.replace(Regex("\\s+"), " ").take(115)
        runCatching {
            MigratedDomainStores.chat.appendPrivateActivityNotice(character.characterId,
                "[阅读记录|${record.id}]已读《${slice.book.title}》 · ${record.chapterTitle}（${slice.endOffset}/${slice.totalLength}）\n${station?.summary?.let { "$it\n" }.orEmpty()}读后感：$brief${if (reflection.length > 115) "…" else ""}\n点开查看完整感想和正文")
        }.onFailure { error ->
            android.util.Log.w("LuluReading", "Reading saved, chat activity notice not delivered", error)
        }
        return CompanionActionResult(true, factualReceipt, evidenceId = record.id)
        } finally {
            if (digitalReader) DigitalWorldActivityStateStore.endActivity(character.characterId)
        }
    }

    private data class ReadingStation(val shelf: DigitalWorldItem?, val summary: String,
        val evidenceId: String)

    /** Select a real route and commit the physical approach BEFORE any LLM reading request. */
    private fun prepareReadingStation(character: CharacterSettings, title: String,
                                      now: Instant): ReadingStation {
        val role = character.characterId
        DigitalWorldStore.ensureHome(role, character.displayName, now)
        val plan = DigitalWorldReadingRoute.choose(
            DigitalWorldStore.locationOf(role), DigitalWorldStore.homeLocation(role),
            DigitalWorldStore.readingShelf(role)?.id,
        )
        check(plan.shelfId != null) { "家里的书架已被移除，请先放置一座真正的书架才能阅读。" }
        fun world(action: String, params: JSONObject): DigitalWorldActionResult {
            val result = DigitalWorldStore.performAction(role, action, params, now)
            check(result.success && result.evidenceId.isNotBlank()) {
                "尚未到达真实阅读地点：${result.summary}"
            }
            return result
        }
        if (plan.travelAction != null) world(plan.travelAction, JSONObject())
        val arriving = world("use_home_item",
            JSONObject().put("itemId", plan.shelfId).put("activityId", "approach_bookshelf"))
        val shelf = plan.shelfId?.let { id ->
            DigitalWorldStore.itemsAtLocation(role).firstOrNull { it.id == id }
        }
        runCatching {
            MigratedDomainStores.chat.appendPrivateActivityNotice(role,
                "${arriving.summary}，挑中了《${title.take(90)}》。正在阅读正文，尚无读后感或完成回执。")
        }
        return ReadingStation(shelf, arriving.summary, arriving.evidenceId)
    }
}
