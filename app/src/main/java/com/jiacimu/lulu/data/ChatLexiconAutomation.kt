package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
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
    private const val KEY_PROCESSED_USER_MESSAGE_IDS = "processed_user_message_ids_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conversationJobs = mutableMapOf<String, Job>()
    private val characterLocks = mutableMapOf<String, Mutex>()
    private var prefs: android.content.SharedPreferences? = null
    private var started = false

    @Synchronized
    fun initialize(context: Context) {
        if (started) return
        started = true
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
                                val characterReply = messages.lastOrNull() ?: return@collect
                                if (
                                    characterReply.sender != LuluChatMessage.Sender.Character ||
                                    characterReply.status != LuluChatMessage.Status.Sent
                                ) {
                                    return@collect
                                }
                                val replyIndex = messages.lastIndex
                                val userMessage = messages
                                    .subList(0, replyIndex)
                                    .lastOrNull { message ->
                                        message.sender == LuluChatMessage.Sender.User &&
                                            message.status == LuluChatMessage.Status.Sent
                                    }
                                    ?: return@collect
                                if (!looksLikeCommitmentRequest(userMessage.content)) return@collect
                                if (isProcessed(userMessage.id)) return@collect

                                val characterId = conversation.characterId
                                val lock = synchronized(characterLocks) {
                                    characterLocks.getOrPut(characterId) { Mutex() }
                                }
                                lock.withLock {
                                    if (isProcessed(userMessage.id)) return@withLock
                                    extractAndSave(
                                        characterId = characterId,
                                        userMessage = userMessage,
                                        characterReply = characterReply,
                                    )
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
    ) {
        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = "用户：${userMessage.content}\n角色：${characterReply.content}",
            instruction = """
                判断这轮对话是否要求当前角色承担可持续记录的约定，并拆成互相独立的条目。
                只返回 JSON 数组，不要代码块。没有应记录内容时返回 []。
                每项格式：
                {"kind":"promise|responsibility|reminder|long_term_supervision","title":"简短标题","content":"角色以后具体要做什么"}
                规则：
                1. 用户一句话提出多项要求时必须拆成多项，例如监督起床、监督睡觉、监督学习是三项。
                2. promise 是明确答应的约定；responsibility 是角色承担的职责；reminder 是一次或有明确触发点的提醒；long_term_supervision 是长期反复监督。
                3. 用户要求角色以后做某事，即使角色回复较含蓄，也应按真实要求记录；普通问答、临时翻译和一次性聊天不要记录。
                4. 不编造时间、频率和条件；原对话没说就不要自行补充。
                5. title 不超过 12 个汉字，content 要能脱离原聊天独立理解。
            """.trimIndent(),
            source = "辞海",
            title = "约定自动拆分",
            temperature = 0.1,
            maxTokens = 1_200,
        )
        if (result.isFailure) return

        val parsed = runCatching { parseCommitmentEntries(result.getOrThrow().text, characterId) }
            .getOrElse { return }
        val existingKeys = LuluRepositories.lexicon.snapshot(characterId)
            .mapTo(mutableSetOf(), LexiconEntry::commitmentDedupeKey)
        parsed
            .filter { entry -> existingKeys.add(entry.commitmentDedupeKey()) }
            .forEach { entry -> LuluRepositories.lexicon.save(entry) }
        markProcessed(userMessage.id)
    }

    private fun isProcessed(messageId: String): Boolean =
        messageId in prefs?.getStringSet(KEY_PROCESSED_USER_MESSAGE_IDS, emptySet()).orEmpty()

    private fun markProcessed(messageId: String) {
        val current = prefs?.getStringSet(KEY_PROCESSED_USER_MESSAGE_IDS, emptySet()).orEmpty()
        prefs?.edit()
            ?.putStringSet(KEY_PROCESSED_USER_MESSAGE_IDS, current + messageId)
            ?.commit()
    }
}

internal fun looksLikeCommitmentRequest(text: String): Boolean {
    val clean = text.trim()
    if (clean.isBlank()) return false
    return CommitmentSignals.any { signal -> signal in clean }
}

internal fun parseCommitmentEntries(raw: String, characterId: String): List<LexiconEntry> {
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
            val kind = item.optString("kind").toPromiseKindOrNull() ?: continue
            val content = item.optString("content").trim()
            if (content.isBlank()) continue
            val title = item.optString("title").trim().ifBlank { kind.defaultTitle() }.take(24)
            add(
                LexiconEntry(
                    id = UUID.randomUUID().toString(),
                    characterId = characterId,
                    section = LexiconSection.Promise,
                    title = title,
                    content = content,
                    promiseKind = kind,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }
    check(array.length() == 0 || parsed.isNotEmpty()) {
        "模型返回了非空约定数组，但没有任何可保存条目"
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

private fun LexiconEntry.commitmentDedupeKey(): String =
    "${promiseKind?.name.orEmpty()}|${content.normalizedCommitmentText()}"

private fun String.normalizedCommitmentText(): String = lowercase()
    .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

private val CommitmentSignals = listOf(
    "监督", "督促", "提醒", "记得叫", "记得喊", "叫我", "喊我", "催我",
    "答应我", "承诺", "负责", "以后要", "以后帮我", "每天帮我", "每周帮我",
    "别让我忘", "要你帮我", "你要帮我", "监督我起床", "监督我睡觉", "监督我学习",
)
