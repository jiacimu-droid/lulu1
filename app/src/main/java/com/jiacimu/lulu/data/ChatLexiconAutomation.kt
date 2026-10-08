package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.core.LexiconStatus
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.PromiseKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.time.Instant
import java.util.UUID

/**
 * Extracts non-task lexicon state from a completed user/character turn.
 *
 * Promise execution is owned by CommitmentTurnAutomation/CommitmentTaskStore. This automation
 * owns Life and Concern entries so there is only one execution source of truth for commitments.
 * A quiet window lets all bubbles from one character reply arrive before the turn is examined.
 */
object ChatLexiconAutomation {
    private const val PREFS_NAME = "lulu_chat_lexicon_automation"
    private const val KEY_PROCESSED_SIGNATURES = "processed_signatures_v3"
    private const val QUIET_WINDOW_MS = 2_500L

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
            MigratedDomainStores.chat.conversations.collectLatest { conversations ->
                val liveIds = conversations.mapTo(mutableSetOf()) { it.id }
                conversationJobs.keys
                    .filterNot(liveIds::contains)
                    .forEach { conversationId -> conversationJobs.remove(conversationId)?.cancel() }

                conversations.forEach { conversation ->
                    if (conversation.id in conversationJobs) return@forEach
                    conversationJobs[conversation.id] = scope.launch {
                        MigratedDomainStores.chat.messages(conversation.id).collectLatest {
                            inspectImmediateUpdate(conversation)
                            delay(QUIET_WINDOW_MS)
                            inspectLatestTurn(conversation)
                        }
                    }
                }
            }
        }
    }

    private suspend fun inspectLatestTurn(conversation: LuluConversation) {
        val messages = MigratedDomainStores.chat.messages(conversation.id).value
        val userIndex = messages.indexOfLast {
            it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent
        }
        if (userIndex < 0) return
        val userMessage = messages[userIndex]
        val replies = messages.drop(userIndex + 1).filter {
            it.sender == LuluChatMessage.Sender.Character && it.status == LuluChatMessage.Status.Sent
        }
        if (replies.isEmpty()) return

        replies.groupBy { reply -> reply.authorCharacterId ?: conversation.characterId }
            .forEach { (characterId, roleReplies) ->
                if (characterId.isBlank()) return@forEach
                val characterText = roleReplies.joinToString("\n", transform = LuluChatMessage::content).trim()
                val fullTurn = "${userMessage.content}\n$characterText"
                if (!looksLikeLexiconCandidate(fullTurn) && LuluRepositories.lexicon.snapshot(characterId).none {
                    it.status == LexiconStatus.Active && it.section in setOf(LexiconSection.Life, LexiconSection.Concern)
                }) return@forEach

                val sourceTurnId = "${conversation.id}:${userMessage.id}:$characterId"
                val signature = "$sourceTurnId:${roleReplies.joinToString("|") { it.id }.hashCode()}:$characterText"
                    .hashCode().toString()
                if (isProcessed(signature)) return@forEach
                val lock = synchronized(characterLocks) { characterLocks.getOrPut(characterId) { Mutex() } }
                lock.withLock {
                    if (isProcessed(signature)) return@withLock
                    val saved = extractAndSave(
                        characterId = characterId,
                        userText = userMessage.content,
                        characterText = characterText,
                        groupName = conversation.groupChat?.name,
                        sourceEventIds = (listOf(userMessage.id) + roleReplies.map { it.id }).map {
                            if (conversation.groupChat == null) it else "$it:group:$characterId"
                        },
                    )
                    if (saved) markProcessed(signature)
                }
            }
    }

    private suspend fun inspectImmediateUpdate(conversation: LuluConversation) {
        val message = MigratedDomainStores.chat.messages(conversation.id).value.lastOrNull()?.takeIf {
            it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent
        } ?: return
        if (!listOf("好了", "解决", "结束", "不用", "取消", "不再", "已经").any(message.content::contains)) return
        val ids = conversation.groupChat?.members?.map { it.characterId } ?: listOf(conversation.characterId)
        ids.distinct().forEach { characterId ->
            if (LuluRepositories.lexicon.snapshot(characterId).none { it.status == LexiconStatus.Active && it.section in setOf(LexiconSection.Life, LexiconSection.Concern) }) return@forEach
            val signature = "lexicon-update:${message.id}:$characterId"
            if (isProcessed(signature)) return@forEach
            val lock = synchronized(characterLocks) { characterLocks.getOrPut(characterId) { Mutex() } }
            lock.withLock {
                val sourceId = if (conversation.groupChat == null) message.id else "${message.id}:group:$characterId"
                if (extractAndSave(characterId, message.content, "", conversation.groupChat?.name, listOf(sourceId))) markProcessed(signature)
            }
        }
    }

    private suspend fun extractAndSave(
        characterId: String,
        userText: String,
        characterText: String,
        groupName: String?,
        sourceEventIds: List<String>,
    ): Boolean {
        val active = LuluRepositories.lexicon.snapshot(characterId).filter {
            it.status == LexiconStatus.Active && it.section in setOf(LexiconSection.Life, LexiconSection.Concern)
        }
        val terms = memorySearchTerms(userText + " " + characterText)
        val relevant = active.filter { terms.intersect(memorySearchTerms(it.title + it.content)).isNotEmpty() }
        val existing = relevant + active.filterNot { it in relevant }.take(16)
        val context = SharedExperienceTimeline.eventsByIds(characterId, sourceEventIds)
        if (context.isEmpty()) return false
        val result = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                groupName?.let { appendLine("场景：群聊《$it》，当前判断对象是这个角色。") }
                appendLine("用户：$userText")
                appendLine("已有生活与挂心（只能更新这些真实ID）：")
                existing.forEach { appendLine("entryId=${it.id}；${it.section}；${it.title}：${it.content}；上次回访=${it.lastFollowUpAt}") }
                appendLine("角色完整回复（可能由多个气泡组成）：")
                append(characterText)
            },
            instruction = """
                检查这一整轮对话（用户输入 + 当前角色全部回复气泡），提取值得进入当前角色辞海的 life 或 concern，并更新已有条目的状态。
                约定、提醒、监督、责任由独立 CommitmentTask 系统处理，这里不要再生成 promise，避免两套正文和重复执行。
                只返回 JSON 数组，不要代码块；没有内容时返回 []。
                每项格式：
                {"section":"life|concern","kind":"","title":"简短标题","content":"可脱离聊天理解的内容","existingId":"更新时填已有entryId，否则空","status":"Active|Resolved","followedUp":false}

                规则：
                1. life 保存会影响日常陪伴的当前生活安排、作息、学习任务或现实处境；长期稳定身份与偏好交给记忆系统。
                2. concern 保存尚未解决、以后值得当前角色主动关心或自然回访的问题；角色回复中主动表达“我有点挂心/之后想问问”也可以成为 concern。
                3. 必须看完整轮次，不能只根据用户消息判断；第二、第三个回复气泡里的新信息同样有效。
                4. 群聊只记录属于当前角色主观关注的内容，不替其他角色承担挂心。
                5. 不编造时间、频率、病情、结果或用户未提供的状态；已经明确解决的小情绪不要长期挂心。
                6. 用户明确说问题解决、安排结束或取消时，更新对应 existingId 为 Resolved；未回复、角色安慰、推测好转不代表解决。临时变化与长期事实区分。
                7. 同一问题有新进展时更新已有条目，不重复创建。角色实际在本轮询问对应问题时 followedUp=true；知道刚问过就避免短时间重复回访。
                8. 角色完整回复为空时，只允许更新或解决已有条目，不新增；归属不明确就不改。
                9. title 不超过 12 个汉字，content 说明关注对象、当前未解决点和已知回访条件（没有条件就不要猜）。
            """.trimIndent(),
            source = "辞海",
            title = "辞海自动整理",
            maxTokens = 1_000,
            usage = ModelUsage.Chat,
            contextMode = CompanionContextMode.Isolated,
            connectionOverride = MemoryModelRuntime.extractionConnection(),
        )
        if (result.isFailure) return false

        val rows = runCatching { decodeMemoryResponseArray(result.getOrThrow().text) }.getOrElse { return false }
        val allowed = existing.associateBy(LexiconEntry::id)
        val existingKeys = LuluRepositories.lexicon.snapshot(characterId).mapTo(mutableSetOf(), LexiconEntry::automationDedupeKey)
        val liveSources = SharedExperienceTimeline.eventsByIds(characterId, sourceEventIds)
        if (liveSources.size != sourceEventIds.distinct().size) return false
        var hasConcern = false
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: return false
            val existingId = item.optString("existingId").trim()
            val old = allowed[existingId]
            if (existingId.isNotBlank() && old == null) continue
            val entry = if (old != null) {
                // A delete/edit racing this request must not restore the old snapshot.
                val current = LuluRepositories.lexicon.snapshot(characterId).firstOrNull { it.id == old.id }
                if (current != old) continue
                old.copy(
                    content = item.optString("content").trim().ifBlank { old.content },
                    status = if (item.optString("status").equals("Resolved", true)) LexiconStatus.Resolved else LexiconStatus.Active,
                    updatedAt = Instant.now(),
                    sourceEventIds = (old.sourceEventIds + sourceEventIds).distinct(),
                    lastFollowUpAt = if (item.optBoolean("followedUp")) Instant.now() else old.lastFollowUpAt,
                )
            } else {
                if (characterText.isBlank()) continue
                val parsed = runCatching { parseLexiconEntries(JSONArray().put(item).toString(), characterId) }.getOrElse { return false }
                val new = parsed.singleOrNull()?.takeIf { it.section != LexiconSection.Promise } ?: continue
                if (!existingKeys.add(new.automationDedupeKey())) continue
                new.copy(sourceEventIds = sourceEventIds)
            }
            LuluRepositories.lexicon.save(entry)
            if (entry.section == LexiconSection.Concern && entry.status == LexiconStatus.Active) hasConcern = true
        }
        if (hasConcern) appContext?.let { ProactivePerceptionScheduler.scheduleConcernPromise(it, characterId) }
        return true
    }

    private fun isProcessed(signature: String): Boolean =
        signature in prefs?.getStringSet(KEY_PROCESSED_SIGNATURES, emptySet()).orEmpty()

    private fun markProcessed(signature: String) {
        val current = prefs?.getStringSet(KEY_PROCESSED_SIGNATURES, emptySet()).orEmpty().toMutableList()
        current += signature
        prefs?.edit()?.putStringSet(KEY_PROCESSED_SIGNATURES, current.takeLast(400).toSet())?.apply()
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
                if (section == LexiconSection.Promise) kind?.defaultTitle().orEmpty()
                else if (section == LexiconSection.Concern) "新的挂心"
                else "生活记录"
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
    "记住这个", "别忘了", "以后", "拉钩", "我会", "我来", "交给我",
)

private val LifeSignals = listOf(
    "我最近", "我今天", "我明天", "我这周", "我每天", "我的作息", "我要考试", "我要考研",
    "我在备考", "我住在", "我搬到", "我开始上班", "我开始上学", "我的计划", "接下来我要",
    "今天要", "明天要", "最近在", "接下来", "准备去", "准备开始",
)

private val ConcernSignals = listOf(
    "我担心", "我焦虑", "我害怕", "我睡不着", "我失眠", "我不舒服", "我生病", "我难受",
    "压力很大", "快考试", "要出成绩", "等通知", "还没解决", "心情不好", "很紧张",
    "挂心", "放心不下", "之后问", "回头问", "我会关心", "我想知道后来",
)
