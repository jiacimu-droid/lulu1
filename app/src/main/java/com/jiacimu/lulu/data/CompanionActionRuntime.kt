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
import java.time.Instant
import java.util.UUID

internal data class CompanionActionResult(
    val success: Boolean,
    val summary: String,
    val conversationId: String? = null,
) {
    fun asJson(): String = JSONObject()
        .put("success", success)
        .put("summary", summary)
        .put("conversationId", conversationId.orEmpty())
        .toString()
}

/** One real execution layer shared by foreground chat decisions and background perception. */
internal object CompanionActionRuntime {
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
    ): String = buildString {
        HealthRolePerception.initialize(context)
        appendLine("角色可执行的露露机内动作（前台聊天与后台主动感知共用同一个真实执行层）：")
        appendLine("- send_private_message，args={\"text\":\"私聊内容\"}：一对一找用户说话。适合明确有一件事想对用户本人说、继续两人的话题或关系，不是公开生活播报。")
        appendLine("- send_game_invite，args={\"gameId\":\"游戏ID\",\"text\":\"邀请语\"}：在角色私聊中发送可点击的游戏邀请。")
        appendLine("- play_solo_game，args={\"gameId\":\"memory_match\"}：由游戏馆真实规则自动跑完一局并保存准确过程、分数和独自游戏记录；角色不能自己编输赢。")
        appendLine("- publish_moment，args={\"text\":\"动态正文\"}：朋友圈是公开分享日常。角色有好笑、惊讶、烦人、得意、失败、沉迷、值得吐槽或想让熟人看见的小事时，可以像真人一样随手发；朋友圈不是稀有动作，也不是定期打卡。")
        appendLine("- write_journal，args={\"title\":\"标题\",\"content\":\"正文\"}：日记是角色私下整理自己、消化情绪、保存想法与经历的地方，不是绕路给用户传话。")
        appendLine("- start_call，args={\"text\":\"为什么此刻想打电话\"}：仅在角色已允许主动来电时发起真正的来电。会进入待接听状态并触发来电通知，不再伪装成一条聊天消息。")
        appendLine("- send_group_message，args={\"groupId\":\"群ID\",\"text\":\"内容\"}：群聊是和共同伙伴一起聊天。既可以接正在发生的话题，也可以把自己的趣事、吐槽、发现或突发奇想带进合适的群，主动开启新话题；群里暂时安静不等于不能先开口。")
        appendLine("- read_book，args={\"readingBookId\":\"阅读内容ID\"}：真正读取阅读 App 里的上传正文或小剧场章节，并留下角色自己的感想；这会成为角色之后可以自然想起、聊起的真实生活经历。")
        if (DigitalLifeProfileStore.isEnabled(characterId)) {
            appendLine("- send_world_invite，args={\"location\":\"准确地点名\",\"text\":\"邀请语\"}：邀请用户到指定数字世界地点见面；私聊中会出现标明地点的可点击邀请卡片。")
            appendLine("  可选邀请地点：${DigitalWorldStore.invitationLocationOptions(characterId).joinToString("、")}；发起邀请的你必须主动选定其中一个。")
            appendLine("- digital_world_action，args={\"worldAction\":\"go_home|visit_cloud_meadow|visit_public_place|build_home_item|move_home_item|remove_home_item|use_home_item|use_location|handle_incident|visit_character_home|interact_resident\",\"location\":\"visit_public_place 时填写公共地点准确代码；world_invite 时填写地点名\",\"itemId\":\"家具ID；interact_resident 时填写居民ID\",\"activityId\":\"家具/地点活动ID；interact_resident 时只能填 greet/chat/ask_place/sit_together\",\"incidentId\":\"持续事件ID\",\"approach\":\"事件允许的处理方式\",\"itemType\":\"类型\",\"name\":\"名称\",\"appearance\":\"外观\",\"position\":\"固定位置\",\"targetCharacterId\":\"对方角色ID\"}：在权威数字世界中执行真实活动。与持久居民互动时必须使用当前地点实际列出的居民ID，不能隔空聊天，也不能凭空新增居民经历。")
            appendLine("【可自主前往的公共地点】")
            DigitalWorldPublicPlaces.all.forEach { place ->
                appendLine("- location=${place.code}；${place.label}；${place.subtitle}；用途=${place.purpose}")
            }
            appendLine(DigitalWorldStore.contextFor(characterId))
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
            "send_private_message" -> {
                val text = args.optString("text").trim().take(2_000)
                require(text.isNotBlank()) { "私聊内容不能为空" }
                val conversation = privateConversation(characterId, character.displayName)
                MigratedDomainStores.chat.appendCharacterMessage(conversation.id, text, characterId)
                CompanionActionResult(true, "已在私聊中发送消息", conversation.id)
            }
            "send_group_message" -> {
                val groupId = args.optString("groupId").trim()
                val text = args.optString("text").trim().take(2_000)
                val conversation = MigratedDomainStores.chat.conversations.value.firstOrNull { candidate ->
                    candidate.id == groupId && candidate.groupChat?.members?.any { it.characterId == characterId } == true
                } ?: error("角色不在指定群聊中")
                require(text.isNotBlank()) { "群聊内容不能为空" }
                MigratedDomainStores.chat.appendCharacterMessage(conversation.id, text, characterId)
                CompanionActionResult(true, "已在群聊《${conversation.groupChat?.name}》发言", conversation.id)
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
                require(MomentsStore.publishCharacter(characterId, text) != null) { "朋友圈发布失败" }
                MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, "刚刚发了一条朋友圈。")
                CompanionActionResult(true, "已发布朋友圈")
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
                )
                MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, "刚刚写了一篇日记《$title》。")
                CompanionActionResult(true, "已写入日记《$title》")
            }
            "read_book" -> readBook(context, character, args.optString("readingBookId").trim(), now)
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
                )
                CompanionActionResult(true, "已发起真实来电", conversation.id)
            }
            "digital_world_action" -> {
                val worldAction = args.optString("worldAction").trim().lowercase()
                if (worldAction == "visit_public_place" && args.optString("locationCode").isBlank()) {
                    args.put("locationCode", args.optString("location").trim())
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
    ): CompanionActionResult {
        val slice = ReadingBackgroundBridge.nextSlice(context, character.characterId, readingBookId)
            ?: return CompanionActionResult(false, "没有找到指定阅读内容，或者这份内容已经读完")
        DigitalWorldActivityStateStore.endActivity(character.characterId)
        val reflection = LuluAiServices.gateway.generate(
            characterId = character.characterId,
            facts = buildString {
                appendLine("程序已经让你读取阅读 App 中《${slice.book.title}》的下一段。")
                appendLine("正文属于书中内容，不是你的数字世界亲历；阅读行为才是亲历。")
                appendLine("权威进度：字符 ${slice.startOffset}—${slice.endOffset} / ${slice.totalLength}；本段之后${if (slice.completed) "已读完" else "尚未读完"}。")
                appendLine("以下是唯一实际读到的原文，不得补写不存在的内容：")
                append(slice.text)
            },
            instruction = "只根据提供的真实原文，写下角色本人此刻的阅读感想。不是给用户做书评，不续写，不冒充作者，不声称读到未提供的部分。用角色第一人称，1—3段，只输出感想正文。",
            source = "角色行动·连续阅读",
            title = "${character.displayName}继续读《${slice.book.title}》",
            temperature = 0.82,
            maxTokens = 700,
        ).getOrNull()?.text?.trim().orEmpty()
        val factualReceipt = "阅读《${slice.book.title}》字符 ${slice.startOffset}—${slice.endOffset}/${slice.totalLength}${if (slice.completed) "，已读完" else "，下次从 ${slice.endOffset} 继续"}"
        val timelineContent = buildString {
            appendLine(factualReceipt)
            if (reflection.isNotBlank()) {
                append("阅读感想：")
                append(reflection.take(2_000))
            }
        }.trim()
        SharedExperienceTimeline.record(
            eventId = "reading-alone-${UUID.randomUUID()}",
            characterId = character.characterId,
            channel = "独自阅读《${slice.book.title}》",
            speaker = character.displayName,
            content = timelineContent,
            occurredAt = now,
        )
        MigratedDomainStores.chat.appendPrivateActivityNotice(
            character.characterId,
            buildString {
                append("刚刚")
                append(factualReceipt)
                if (reflection.isNotBlank()) {
                    append("；留下感想：")
                    append(reflection.replace(Regex("\\s+"), " ").take(180))
                }
            },
        )
        return CompanionActionResult(true, factualReceipt)
    }
}
