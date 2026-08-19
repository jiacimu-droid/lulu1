package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.health.HealthRolePerception
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
        appendLine("- publish_moment，args={\"text\":\"动态正文\"}：朋友圈是公开分享日常。只有角色此刻真的有一段生活、心情、见闻或小事觉得值得让朋友们看到时才发；它不是定期状态更新，也不是为了证明自己活跃。")
        appendLine("- write_journal，args={\"title\":\"标题\",\"content\":\"正文\"}：日记是角色私下整理自己、消化情绪、保存想法与经历的地方，不是绕路给用户传话。")
        appendLine("- start_call，args={\"text\":\"为什么此刻想打电话\"}：仅在角色已允许主动来电时发起真正的来电。会进入待接听状态并触发来电通知，不再伪装成一条聊天消息。")
        appendLine("- send_group_message，args={\"groupId\":\"群ID\",\"text\":\"内容\"}：群聊是和共同伙伴一起聊天。只在想参与那个群正在发生的共享话题时发言；在线、看见消息都不等于必须说话。")
        appendLine("- read_book，args={\"readingBookId\":\"阅读内容ID\"}：真正读取阅读 App 里的上传正文或小剧场章节，并留下角色自己的感想；这会成为角色之后可以自然想起、聊起的真实生活经历。")
        if (DigitalLifeProfileStore.isEnabled(characterId)) {
            appendLine("- send_world_invite，args={\"location\":\"准确地点名\",\"text\":\"邀请语\"}：邀请用户到指定数字世界地点见面；私聊中会出现标明地点的可点击邀请卡片。")
            appendLine("  可选邀请地点：${DigitalWorldStore.invitationLocationOptions(characterId).joinToString("、")}；发起邀请的你必须主动选定其中一个。")
            appendLine("- digital_world_action，args={\"worldAction\":\"go_home|visit_cloud_meadow|build_home_item|move_home_item|remove_home_item|visit_character_home\",\"itemId\":\"物品ID\",\"itemType\":\"类型\",\"name\":\"名称\",\"appearance\":\"外观\",\"position\":\"固定位置\",\"targetCharacterId\":\"对方角色ID\"}：在权威数字世界中执行一次真实、持久化的活动。外出或串门抵达后，如果那里已经有别的数字生命，程序会把同地点事实变成真实相遇、唤醒对方并保存共同见面记录。")
            appendLine(DigitalWorldStore.contextFor(characterId))
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
        val books = ReadingBackgroundBridge.books(context).take(24)
        if (books.isNotEmpty()) {
            appendLine("可真实阅读的内容：")
            books.forEach { book -> appendLine("- readingBookId=${book.id}；${book.title}；来源=${book.source}") }
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
                    statusText = "在${location}等待主人赴约",
                    gesture = "留在约定地点，注意着世界通道的动静",
                    innerThought = "",
                    mood = "期待",
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
                val worldAction = args.optString("worldAction").trim()
                val previousLocation = DigitalWorldStore.locationOf(characterId)
                val worldResult = DigitalWorldStore.performAction(characterId, worldAction, args, now)
                if (worldResult.success) {
                    MigratedDomainStores.chat.appendPrivateActivityNotice(characterId, worldResult.summary)
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
        if (result.success && normalizedAction != "digital_world_action") {
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
        val book = ReadingBackgroundBridge.books(context).firstOrNull { it.id == readingBookId }
            ?: return CompanionActionResult(false, "没有找到指定阅读内容")
        val reflection = LuluAiServices.gateway.generate(
            characterId = character.characterId,
            facts = "你刚刚决定独自去阅读 App 里读《${book.title}》。\n正文：\n${book.content.take(12_000)}",
            instruction = "认真读提供的正文，写下角色本人真实的阅读感想。不是给用户做书评，不续写，不冒充作者。用角色第一人称，1—3段，只输出感想正文。",
            source = "角色行动·阅读",
            title = "${character.displayName}阅读《${book.title}》",
            temperature = 0.82,
            maxTokens = 700,
        ).getOrNull()?.text?.trim().orEmpty()
        if (reflection.isBlank()) return CompanionActionResult(false, "阅读感想生成失败")
        SharedExperienceTimeline.record(
            eventId = "reading-alone-${UUID.randomUUID()}",
            characterId = character.characterId,
            channel = "独自阅读《${book.title}》",
            speaker = character.displayName,
            content = reflection.take(2_000),
            occurredAt = now,
        )
        MigratedDomainStores.chat.appendPrivateActivityNotice(
            character.characterId,
            "刚刚读了《${book.title}》，留下了一点感想：${reflection.replace(Regex("\\s+"), " ").take(180)}",
        )
        return CompanionActionResult(true, "已真正阅读《${book.title}》并留下感想")
    }
}
