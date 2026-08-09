package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
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

/**
 * One real execution layer for both foreground chat decisions and background perception.
 * Prompts may differ by scene, but social actions must never have separate fake implementations.
 */
internal object CompanionActionRuntime {
    private val gameTitles = mapOf(
        "roleplay" to "跑团",
        "turtle_soup" to "海龟汤",
        "yacht_dice" to "快艇骰子",
        "gomoku" to "五子棋",
        "memory_match" to "记忆配对",
    )

    fun capabilityContext(context: Context, characterId: String): String = buildString {
        appendLine("角色可执行的露露机内动作（这些动作在前台聊天与后台主动感知中共用同一个真实执行层）：")
        appendLine("- send_private_message，args={\"text\":\"私聊内容\"}：在角色与用户的私聊中真实发送消息。")
        appendLine("- send_game_invite，args={\"gameId\":\"游戏ID\",\"text\":\"邀请语\"}：在角色私聊中发送可点击的游戏邀请。")
        appendLine("- publish_moment，args={\"text\":\"动态正文\"}：以角色身份真实发布朋友圈。")
        appendLine("- write_journal，args={\"title\":\"标题\",\"content\":\"正文\"}：真实写入该角色辞海的日记。")
        appendLine("- start_call，args={\"text\":\"来电缘由\"}：仅在角色已允许主动来电时发起真实来电邀请。")
        appendLine("- send_group_message，args={\"groupId\":\"群ID\",\"text\":\"内容\"}：向角色所在的另一个真实群聊发言。")
        appendLine("- read_book，args={\"readingBookId\":\"阅读内容ID\"}：真正读取已上传正文并留下角色自己的感想。")
        appendLine("可用游戏ID：${gameTitles.entries.joinToString("、") { "${it.key}=${it.value}" }}")
        val groups = MigratedDomainStores.chat.conversations.value.filter { conversation ->
            conversation.groupChat?.members?.any { it.characterId == characterId } == true
        }
        if (groups.isNotEmpty()) {
            appendLine("角色所在群聊：")
            groups.forEach { conversation -> appendLine("- groupId=${conversation.id}；群名=${conversation.groupChat?.name}") }
        }
        val books = ReadingBackgroundBridge.books(context).take(12)
        if (books.isNotEmpty()) {
            appendLine("可真实阅读的内容：")
            books.forEach { book -> appendLine("- readingBookId=${book.id}；《${book.title}》") }
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
                val text = args.optString("text").trim().ifBlank { "忽然有点想听听你的声音。" }.take(100)
                val conversation = privateConversation(characterId, character.displayName)
                MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "[想给你打电话] $text", characterId)
                CompanionActionResult(true, "已发起主动来电邀请", conversation.id)
            }
            else -> error("未知露露机动作：$action")
        }
        if (result.success) {
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
