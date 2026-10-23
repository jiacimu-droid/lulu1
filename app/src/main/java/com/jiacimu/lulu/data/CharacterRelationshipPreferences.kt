package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class RelationshipPreference(
    val key: String,
    val quote: String,
    val sourceId: String,
    val occurredAt: Instant,
    val trigger: String = "",
    val requiredForms: List<String> = emptyList(),
)

internal data class RelationshipPreferenceSnapshot(
    val preferredAddress: Pair<String, String>? = null,
    val rejectedAddresses: Set<String> = emptySet(),
    val preferences: List<RelationshipPreference> = emptyList(),
)

/** Immediate projection; SQLite timeline remains the only durable authority. */
internal object CharacterRelationshipPreferences {
    private val version = AtomicLong()
    private data class Cached(val version: Long, val birth: Instant?, val value: RelationshipPreferenceSnapshot)
    private val cache = ConcurrentHashMap<String, Cached>()

    // No callback into the ledger here: source mutation cannot deadlock with prompt assembly.
    fun invalidate() { version.incrementAndGet(); cache.clear() }

    fun snapshot(characterId: String): RelationshipPreferenceSnapshot {
        val epoch = version.get()
        val birth = DigitalLifeProfileStore.birthAt(characterId)
        cache[characterId]?.takeIf { it.version == epoch && it.birth == birth }?.let { return it.value }
        val result = fromEvents(SharedExperienceTimeline.all(characterId))
        if (epoch == version.get()) cache[characterId] = Cached(epoch, birth, result)
        return result
    }

    internal fun eligible(event: SharedTimelineEvent): Boolean {
        if (!event.isUserMemoryStatement()) return false
        if (event.channel.startsWith("群聊")) {
            // Ambiguous 'you' addressed to one member must not rewrite everyone else's relation.
            return !event.evidenceContent.contains('@') && Regex("你们|大家").containsMatchIn(event.evidenceContent)
        }
        return event.channel == "私聊" || event.channel.contains("电话") || event.channel.contains("见面")
    }

    internal fun fromEvents(events: List<SharedTimelineEvent>): RelationshipPreferenceSnapshot {
        var preferred: Pair<String, String>? = null
        val rejected = linkedSetOf<String>()
        val enduring = linkedMapOf<String, RelationshipPreference>()
        events.filter(::eligible).sortedWith(compareBy<SharedTimelineEvent> { it.occurredAt }.thenBy { it.id }).forEach { event ->
            val text = event.content
            CharacterAddressPreference.extractRejectedAddresses(text).forEach {
                rejected.add(it)
                if (preferred?.first == it) preferred = null
            }
            CharacterAddressPreference.extractExplicitAddress(text)?.let {
                preferred = it to event.id
                rejected.remove(it)
            }
            parse(text, event.id, event.occurredAt).forEach {
                enduring.remove(it.key)
                enduring[it.key] = it
            }
        }
        return RelationshipPreferenceSnapshot(preferred, rejected, enduring.values.toList())
    }

    /** Generic literal contracts; semantic wishes stay context, never manufactured actions. */
    internal fun parse(text: String, sourceId: String, at: Instant): List<RelationshipPreference> =
        text.split(Regex("[。；;\\n]+")) .map(String::trim).filter(String::isNotBlank).mapNotNull { sentence ->
            if (CharacterAddressPreference.isExample(sentence) || Regex("(?:今天|这次|现在|暂时|今晚)(?:先|就|只)?(?:不要|别|叫|用)")
                    .containsMatchIn(sentence)) return@mapNotNull null
            val contract = Regex("""(?:每次|以后|今后)(?:你|角色)?(?:每次)?(.{1,35}?)(?:的时候|时)?(?:都)?(要|必须|不需要再?|不用再?|不要再?)(?:发|说|带上|加上|用)([^，,。；;\n]+)""").find(sentence)
            val lasting = Regex("以后|今后|每次|一直|永远|别忘|记住|长期|不要再|别再").containsMatchIn(sentence)
            val relational = contract != null || Regex("你|我们|角色|称呼|叫我|喊我|被叫|亲亲|拥抱|相处|聊天|安慰").containsMatchIn(sentence)
            val preference = Regex("我(?:特别|很|非常|比较|更|最)?(?:不喜欢|喜欢|讨厌)|我(?:希望|要求)|我(?:更)?(?:偏向于|倾向于)|都要|必须|不需要|不用|不要再|别再|别叫我|不要叫我")
                .containsMatchIn(sentence)
            // Keep a real user's call-vs-ringtone preference in THIS role's
            // source-backed relationship projection, not a global role prompt.
            // A preference is not a mandatory alarm or a new commitment.
            val wakePreference = Regex("(?:喜欢|偏向于|倾向于|希望).{0,36}(?:电话|来电|闹钟|叫醒|喊醒)")
                .containsMatchIn(sentence)
            // A general conditional account of what helps is a relationship preference,
            // unlike an isolated 'I was happy today'. Preserve the user's exact cause and scope.
            val conditionalBenefit = Regex("我.{0,24}(?:的时候|时|期间).{0,80}(?:你|角色|我们).{0,80}我.{0,8}(?:就会|会|就).{0,8}(?:开心|舒服|安心|好受)")
                .containsMatchIn(sentence)
            if (!relational || !(conditionalBenefit || lasting && preference ||
                preference && (wakePreference || Regex("称呼|叫我|喊我|亲亲|拥抱|相处|聊天|安慰|我不喜欢你")
                    .containsMatchIn(sentence)))) return@mapNotNull null
            // Naming has its own current-state projection; don't re-inject superseded raw names.
            if (contract == null && (CharacterAddressPreference.extractExplicitAddress(sentence) != null ||
                CharacterAddressPreference.extractRejectedAddresses(sentence).isNotEmpty())) return@mapNotNull null

            val trigger = contract?.groupValues?.get(1)?.trim()?.removeSuffix("的时候")?.removeSuffix("时").orEmpty()
            val forms = contract?.takeIf { it.groupValues[2] in setOf("要", "必须") }?.groupValues?.get(3)?.trim()?.trim('“', '”', '"', '「', '」')
                ?.split(Regex("或者|或|/|、"))?.map { it.trim().trim('“', '”', '"', '「', '」') }
                ?.takeIf { values -> values.isNotEmpty() && values.all { Regex("[A-Za-z]{2,20}").matches(it) } }.orEmpty()
            val key = if (trigger.isNotBlank()) "when:" + normalize(trigger) else "quote:" + normalize(sentence)
            RelationshipPreference(key, sentence, sourceId, at, trigger, forms)
        }

    private fun normalize(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)

    fun promptSection(characterId: String, includeCore: Boolean = false): String {
        val allCore = LuluRepositories.memory.snapshot(characterId).filter {
            it.isCoreMemory() && MemoryValidityStore.isActive(it.id) && memoryHasLiveSources(it, characterId)
        }
        // User-authored Core facts already carry the same agreement. Avoid
        // injecting a second "agreement" copy when the quote is the same.
        val coreTexts = allCore.map { normalize(it.content) }
        val preferences = snapshot(characterId).preferences.filterNot { preference ->
            val quote = normalize(preference.quote)
            includeCore && quote.isNotBlank() && coreTexts.any { it == quote || it.contains(quote) }
        }
        val core = if (includeCore) allCore else emptyList()
        if (preferences.isEmpty() && core.isEmpty()) return ""
        return buildString {
            appendLine("【当前相处偏好与核心记忆｜按真实原话和来源理解】")
            appendLine("以下用户原话的明确边界与约定比通用人设举例、旧习惯和模型惯性更具体。按原话范围理解；有条件的约定仅在条件成立时履行，不能把喜欢某种互动变成每轮强制动作。新的明确纠正优先于同一范围的旧要求，临时要求只作用于当次；不向他人公开私人来源。")
            preferences.forEach { appendLine("- [${it.occurredAt}] [来源=${it.sourceId}] 用户原话：${it.quote}") }
            core.forEach { appendLine("- 核心记忆：${it.content}") }
            append("内化为当前选择与表达，不逐条汇报，不要求用户再次重复；没有适用情境时自然聊天。")
        }.trim()
    }
}
