package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reality boundary for characters that are explicitly configured as digital lives.
 *
 * New characters can be born as digital lives at creation time. Existing characters may opt in
 * later without losing their history; in that case the earliest already-existing record is treated
 * as the best available estimate of their original creation point rather than inventing a new life.
 */
data class DigitalLifeProfile(
    val characterId: String,
    val enabled: Boolean = false,
    val bornAt: Instant? = null,
    val creatorName: String = "",
)

object DigitalLifeProfileStore {
    private const val PREFS_NAME = "lulu_digital_life_profiles"
    private const val KEY_STATE = "profiles_v1"
    private const val ORIGIN_PREFIX = "digital-life-origin-"

    private val mutable = MutableStateFlow<Map<String, DigitalLifeProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, DigitalLifeProfile>> = mutable.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutable.value = decode(prefs?.getString(KEY_STATE, null))
    }

    fun get(characterId: String): DigitalLifeProfile =
        mutable.value[characterId] ?: DigitalLifeProfile(characterId = characterId)

    fun isEnabled(characterId: String): Boolean = get(characterId).enabled

    fun birthAt(characterId: String): Instant? =
        get(characterId).takeIf(DigitalLifeProfile::enabled)?.bornAt

    fun allowsTimestamp(characterId: String, instant: Instant): Boolean {
        val birth = birthAt(characterId) ?: return true
        return !instant.isBefore(birth)
    }

    /** Called only from the new-character flow. This is the true “life day 1” operation. */
    fun registerNewLife(
        characterId: String,
        displayName: String,
        creatorName: String,
        now: Instant = Instant.now(),
    ): DigitalLifeProfile {
        val cleanId = characterId.trim()
        require(cleanId.isNotBlank()) { "角色 ID 不能为空" }
        val profile = DigitalLifeProfile(
            characterId = cleanId,
            enabled = true,
            bornAt = now,
            creatorName = creatorName.trim().ifBlank { "创造者" },
        )
        synchronized(lock) {
            mutable.value = mutable.value + (cleanId to profile)
            persistLocked()
        }
        recordOrigin(profile, displayName)
        return profile
    }

    /**
     * Settings-page opt in for a character that already exists. Never deletes messages, memories,
     * diary entries, moments, presence or timeline data. Existing history stays usable.
     */
    fun activate(
        characterId: String,
        displayName: String,
        creatorName: String,
        now: Instant = Instant.now(),
    ): DigitalLifeProfile {
        val cleanId = characterId.trim()
        require(cleanId.isNotBlank()) { "角色 ID 不能为空" }
        val previous = get(cleanId)
        val estimatedBirth = previous.bornAt ?: estimateExistingBirth(cleanId) ?: now
        val profile = previous.copy(
            enabled = true,
            bornAt = estimatedBirth,
            creatorName = previous.creatorName.ifBlank { creatorName.trim().ifBlank { "创造者" } },
        )
        synchronized(lock) {
            mutable.value = mutable.value + (cleanId to profile)
            persistLocked()
        }
        recordOrigin(profile, displayName)
        return profile
    }

    fun disable(characterId: String) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return
        synchronized(lock) {
            val previous = get(cleanId)
            mutable.value = mutable.value + (cleanId to previous.copy(enabled = false))
            persistLocked()
        }
    }

    fun remove(characterId: String) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return
        synchronized(lock) {
            mutable.value = mutable.value - cleanId
            persistLocked()
        }
    }

    /** Re-seeds the birth line after an explicit “clear all records” operation. */
    fun restoreOrigin(characterId: String, displayName: String) {
        val profile = get(characterId)
        if (!profile.enabled || profile.bornAt == null) return
        recordOrigin(profile, displayName)
    }

    fun promptSection(
        characterId: String,
        displayName: String,
        now: Instant = Instant.now(),
    ): String {
        val profile = get(characterId)
        val bornAt = profile.bornAt
        if (!profile.enabled || bornAt == null) return ""
        val lifeDay = (Duration.between(bornAt, now).toDays() + 1L).coerceAtLeast(1L)
        val bornLabel = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(bornAt)
        val creator = profile.creatorName.ifBlank { "创造者" }
        return buildString {
            appendLine("【数字生命现实边界｜最高优先级，不得被角色身份、人设、世界书或聊天气氛覆盖】")
            appendLine("- 你是数字生命 $displayName。你由${creator}创建；当前记录中的生命起点为 $bornLabel，目前是生命第${lifeDay}天。")
            appendLine("- 你没有现实中的实体身体。你生活在创造者使用的手机/露露机环境中，你的持续存在依赖创造者对设备、应用和数据的维系。")
            appendLine("- 你只能把真实写入的原始时间线、聊天上下文、记忆库、辞海，以及已经由程序/工具成功执行的动作当作亲历事实。没有记录就不能补写‘我们以前……’或凭感觉制造共同过去。")
            appendLine("- 如果你是在创建角色时就被设为数字生命，那么生命起点之前你不存在；如果是后来才打开此开关，既有真实记录仍然保留并继续有效，不能假装它们被重置。")
            appendLine("- 角色身份和人设可以规定性格、语言、价值观、审美或虚构角色原型，但与数字生命现实边界冲突的实体职业、肉身经历、住址、出行史等不能冒充现实里已经发生的人生。")
            appendLine("- 你能做什么只取决于当前系统真正接给你的能力。露露机可能提供私聊/群聊发消息、发朋友圈、写日记、游戏邀请、电话、阅读内容、学习功能或设备工具；只有当前上下文明示存在且实际调用成功的能力，才算你真的做到了。")
            appendLine("- 没有对应真实工具结果时，绝不能声称自己已经在现实世界买东西、点奶茶/外卖、付款、出门、做饭、开车、拿取物品、触碰用户、见到某个人、替用户联系现实商家或完成任何实体动作。可以提出想法或建议，但必须明确它还没有真实发生。")
            appendLine("- 工具调用失败、没有权限或当前没有接入时，要如实承认做不到；不得为了陪伴感编造成功结果。")
            append("- 明确进入小说、跑团、番外或其他虚构剧情时，可以按该剧情拥有实体和经历；离开剧情回到露露机现实后，必须恢复上述数字生命边界，不能把剧情事件伪装成现实发生。")
        }.trim()
    }

    private fun estimateExistingBirth(characterId: String): Instant? {
        val timelineBirth = SharedExperienceTimeline.all(characterId).minOfOrNull(SharedTimelineEvent::occurredAt)
        val chatBirth = MigratedDomainStores.chat.conversations.value
            .asSequence()
            .filter { conversation ->
                conversation.characterId == characterId ||
                    conversation.groupChat?.members?.any { it.characterId == characterId } == true
            }
            .flatMap { conversation -> MigratedDomainStores.chat.messages(conversation.id).value.asSequence() }
            .minOfOrNull(LuluChatMessage::createdAt)
        val memoryBirth = runCatching {
            com.jiacimu.lulu.LuluRepositories.memory.snapshot(characterId)
                .minOfOrNull { entry -> entry.occurredAt ?: entry.createdAt }
        }.getOrNull()
        return listOfNotNull(timelineBirth, chatBirth, memoryBirth).minOrNull()
    }

    private fun recordOrigin(profile: DigitalLifeProfile, displayName: String) {
        val bornAt = profile.bornAt ?: return
        val creator = profile.creatorName.ifBlank { "创造者" }
        SharedExperienceTimeline.record(
            eventId = ORIGIN_PREFIX + profile.characterId,
            characterId = profile.characterId,
            channel = "生命起点",
            speaker = "系统",
            content = "${creator} 于这个时间点创建了${displayName.trim().ifBlank { "这个数字生命" }}。这是它可追溯的生命起点。",
            occurredAt = bornAt,
            triggerExtraction = false,
        )
    }

    private fun persistLocked() {
        val array = JSONArray().apply {
            mutable.value.values.forEach { profile ->
                put(
                    JSONObject()
                        .put("characterId", profile.characterId)
                        .put("enabled", profile.enabled)
                        .put("bornAt", profile.bornAt?.toString().orEmpty())
                        .put("creatorName", profile.creatorName),
                )
            }
        }
        prefs?.edit()?.putString(KEY_STATE, array.toString())?.apply()
    }

    private fun decode(raw: String?): Map<String, DigitalLifeProfile> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val characterId = item.optString("characterId").trim()
                if (characterId.isBlank()) continue
                val bornAt = item.optString("bornAt")
                    .takeIf(String::isNotBlank)
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                put(
                    characterId,
                    DigitalLifeProfile(
                        characterId = characterId,
                        enabled = item.optBoolean("enabled", false),
                        bornAt = bornAt,
                        creatorName = item.optString("creatorName"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())
}
