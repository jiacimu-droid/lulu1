package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.PromiseKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.time.Instant
import java.util.UUID

/**
 * Extracts promises, responsibilities, reminders and long-term supervision from completed
 * user/character chat turns. One sentence may create several independent lexicon entries.
 *
 * A turn is checkpointed only after a valid JSON result has been parsed and saved. Model or
 * format failures therefore never permanently skip the user's request.
 */
object ChatLexiconAutomation {
    private const val PREFS_NAME = "lulu_chat_lexicon_automation"
    private const val KEY_PROCESSED_TURN_KEYS = "processed_turn_keys_v2"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conversationJobs = mutableMapOf<String, Job>()
    private val characterLocks = mutableMapOf<String, Mutex>()
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null
    private var started = false

    @Synchronized
    fun initialize(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        scope.launch {
            MigratedDomainStores.chat.conversations.collect { conversations ->
                val liveIds = conversations.mapTo(mutableSetOf()) { conversation -> conversation.id }
                conversationJobs.keys
                    .filterNot { conversationId -> conversationId in liveIds }
                    .forEach { conversationId -> conversationJobs.remove(conversationId)?.cancel() }

                conversations.forEach { conversation ->
                    if (conversation.id in conversationJobs) return@forEach
                    conversationJobs[conversation.id] = scope.launch {
                        MigratedDomainStores.chat.messages(conversation.id)
                            .drop(1)
                            .collect { messages ->
                                messages.withIndex()
                                    .filter { (_, reply) ->
                                        reply.sender == LuluChatMessage.Sender.Character &&
                                            reply.status == LuluChatMessage.Status.Sent
                                    }
                                    .forEach { (replyIndex, characterReply) ->
                                        val userMessage = messages
                                            .subList(0, replyIndex)
                                            .lastOrNull { message ->
                                                message.sender == LuluChatMessage.Sender.User &&
                                                    message.status == LuluChatMessage.Status.Sent
                                            }
                                            ?: return@forEach
                                        if (!looksLikeLexiconCandidate(userMessage.content)) return@forEach
                                        val characterId = characterReply.authorCharacterId ?: conversation.characterId
                                        if (isProcessed(userMessage.id, characterId)) return@forEach
                                        val lock = synchronized(characterLocks) {
                                            characterLocks.getOrPut(characterId) { Mutex() }
                                        }
                                        lock.withLock {
                                            if (isProcessed(userMessage.id, characterId)) return@withLock
                                            extractAndSave(
                                                characterId = characterId,
                                                userMessage = userMessage,
                                                characterReply = characterReply,
                                                groupName = conversation.groupChat?.name,
                                            )
                                        }
                                    }
                            }
                    }
                }
            }
        }
    }

    private suspend fun extractAndSave(
        characterId: String,
        userMessage: LuluChatMessage,
        characterReply: LuluChatMessage,
        groupName: String?,
    ) {
        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                groupName?.let { appendLine("场景：群聊《$it》，当前判断对象是正在回复的这个角色。") }
                appendLine("用户：${userMessage.content}")
                append("角色：${characterReply.content}")
            },
            instruction = """
                判断这轮对话是否产生了值得写入当前角色辞海的生活、挂心或约定条目，并拆成互相独立的条目。
                只返回 JSON 数组，不要代码块。没有应记录内容时返回 []。
                每项格式：
                {"section":"life|concern|promise","kind":"promise|responsibility|reminder|long_term_supervision或空字符串","title":"简短标题","content":"可脱离聊天理解的内容"}
                规则：
                1. life 保存会影响日常陪伴的当前生活安排、作息、学习任务或现实处境；长期稳定身份与偏好由记忆系统保存，不在这里重复。
                2. concern 保存尚未解决、以后值得角色主动关心或回访的事情；已经结束的小情绪不要长期挂心。
                3. promise 保存双方说好、角色答应或用户要求角色以后履行的事。用户一句话提出多项要求时必须拆开。
                4. promise 的 kind：明确约定用 promise；持续职责用 responsibility；一次或有触发点的提醒用 reminder；长期反复监督用 long_term_supervision。
                5. 群聊中只判断这项内容是否属于当前回复角色；不要替其他角色承担约定。
                6. 不编造时间、频率和条件；普通问答、临时翻译、已经解决的闲聊返回 []。
                7. title 不超过 12 个汉字，content 要准确说明谁需要关注或以后做什么。
            """.trimIndent(),
            source = "辞海",
            title = "辞海自动整理",
            temperature = 0.1,
            maxTokens = 1_200,
            usage = ModelUsage.Chat,
        )
        if (result.isFailure) return

        val parsed = runCatching { parseLexiconEntries(result.getOrThrow().text, characterId) }
            .getOrElse { return }
        val existingKeys = LuluRepositories.lexicon.snapshot(characterId)
            .mapTo(mutableSetOf(), LexiconEntry::automationDedupeKey)
        val saved = parsed.filter { entry -> existingKeys.add(entry.automationDedupeKey()) }
        saved.forEach { entry -> LuluRepositories.lexicon.save(entry) }
        if (saved.any { it.section == LexiconSection.Concern || it.section == LexiconSection.Promise }) {
            appContext?.let { ProactivePerceptionScheduler.scheduleConcernPromise(it, characterId) }
        }
        markProcessed(userMessage.id, characterId)
    }

    private fun turnKey(messageId: String, characterId: String): String = "$characterId:$messageId"

    private fun isProcessed(messageId: String, characterId: String): Boolean =
        turnKey(messageId, characterId) in prefs?.getStringSet(KEY_PROCESSED_TURN_KEYS, emptySet()).orEmpty()

    private fun markProcessed(messageId: String, characterId: String) {
        val current = prefs?.getStringSet(KEY_PROCESSED_TURN_KEYS, emptySet()).orEmpty()
        prefs?.edit()
            ?.putStringSet(KEY_PROCESSED_TURN_KEYS, current + turnKey(messageId, characterId))
            ?.commit()
    }
}

internal fun looksLikeCommitmentRequest(text: String): Boolean {
    val clean = text.trim()
    if (clean.isBlank()) return false
    return CommitmentSignals.any { signal -> signal in clean }
}

internal fun looksLikeLexiconCandidate(text: String): Boolean {
    val clean = text.trim()
    if (clean.isBlank()) return false
    return (CommitmentSignals + LifeSignals + ConcernSignals).any { signal -> clean.contains(signal, ignoreCase = true) }
}

internal fun parseCommitmentEntries(raw: String, characterId: String): List<LexiconEntry> {
    return parseLexiconEntries(raw, characterId, defaultSection = LexiconSection.Promise)
}

internal fun parseLexiconEntries(
    raw: String,
    characterId: String,
    defaultSection: LexiconSection? = null,
): List<LexiconEntry> {
    val clean = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val array = JSONArray(clean)
    val now = Instant.now()
    val parsed = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val section = when (item.optString("section").trim().lowercase()) {
                "life", "生活" -> LexiconSection.Life
                "concern", "挂心", "关心" -> LexiconSection.Concern
                "promise", "约定", "承诺" -> LexiconSection.Promise
                else -> defaultSection ?: continue
            }
            val kind = item.optString("kind").toPromiseKindOrNull()
            if (section == LexiconSection.Promise && kind == null) continue
            val content = item.optString("content").trim()
            if (content.isBlank()) continue
            val title = item.optString("title").trim().ifBlank {
                if (section == LexiconSection.Promise) kind?.defaultTitle().orEmpty() else if (section == LexiconSection.Concern) "新的挂心" else "生活记录"
            }.take(24)
            add(
                LexiconEntry(
                    id = UUID.randomUUID().toString(),
                    characterId = characterId,
                    section = section,
                    title = title,
                    content = content,
                    promiseKind = if (section == LexiconSection.Promise) kind else null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }
    check(array.length() == 0 || parsed.isNotEmpty()) {
        "模型返回了非空辞海数组，但没有任何可保存条目"
    }
    return parsed
}

private fun String.toPromiseKindOrNull(): PromiseKind? = when (trim().lowercase()) {
    "promise", "承诺", "约定" -> PromiseKind.Promise
    "responsibility", "责任", "职责" -> PromiseKind.Responsibility
    "reminder", "提醒" -> PromiseKind.Reminder
    "long_term_supervision", "longtermsupervision", "supervision", "长期监督", "监督" ->
        PromiseKind.LongTermSupervision
    else -> null
}

private fun PromiseKind.defaultTitle(): String = when (this) {
    PromiseKind.Promise -> "新的约定"
    PromiseKind.Responsibility -> "新的责任"
    PromiseKind.Reminder -> "新的提醒"
    PromiseKind.LongTermSupervision -> "长期监督"
}

private fun LexiconEntry.automationDedupeKey(): String =
    "${section.name}|${promiseKind?.name.orEmpty()}|${content.normalizedCommitmentText()}"

private fun String.normalizedCommitmentText(): String = lowercase()
    .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

private val CommitmentSignals = listOf(
    "监督", "督促", "提醒", "记得叫", "记得喊", "叫我", "喊我", "催我",
    "答应我", "承诺", "负责", "以后要", "以后帮我", "每天帮我", "每周帮我",
    "别让我忘", "要你帮我", "你要帮我", "监督我起床", "监督我睡觉", "监督我学习",
    "说好了", "说定了", "就这么定", "我们约好", "约定好", "约好", "约定", "你记得", "你可要",
    "记住这个", "别忘了", "以后", "拉钩",
)

private val LifeSignals = listOf(
    "我最近", "我今天", "我明天", "我这周", "我每天", "我的作息", "我要考试", "我要考研",
    "我在备考", "我住在", "我搬到", "我开始上班", "我开始上学", "我的计划", "接下来我要",
)

private val ConcernSignals = listOf(
    "我担心", "我焦虑", "我害怕", "我睡不着", "我失眠", "我不舒服", "我生病", "我难受",
    "压力很大", "快考试", "要出成绩", "等通知", "还没解决", "心情不好", "很紧张",
)
