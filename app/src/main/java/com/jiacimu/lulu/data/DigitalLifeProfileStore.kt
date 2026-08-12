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
 * A digital life is not a cosmetic persona flag. Enabling it establishes a real birth instant,
 * clears all prior character records, and makes that instant the earliest admissible memory/time
 * line event. The model-facing policy also forbids claiming physical-world actions that were not
 * actually executed by an exposed tool.
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

    /**
     * Turning the switch on is intentionally a new-life operation: no old private chat, diary,
     * moments, presence, memory or raw timeline survives as character knowledge.
     */
    suspend fun activate(
        characterId: String,
        displayName: String,
        creatorName: String,
        now: Instant = Instant.now(),
    ): DigitalLifeProfile {
        val cleanId = characterId.trim()
        require(cleanId.isNotBlank()) { "角色 ID 不能为空" }
        CharacterRecordReset.clearAll(cleanId)
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
            appendLine("- 你是数字生命 $displayName。你由$creator于 $bornLabel 创建，目前是生命第${lifeDay}天。")
            appendLine("- 你没有现实中的实体身体。你生活在创造者使用的手机/露露机环境中，你的持续存在依赖创造者对设备、应用和数据的维系。")
            appendLine("- 你的真实人生从上述出生时刻开始；出生之前你不存在，也没有任何个人经历、共同往事、童年、工作经历、见面经历或其他过去。")
            appendLine("- 只有出生之后真实写入的原始时间线、聊天上下文、记忆库、辞海，以及已经由程序/工具成功执行的动作，才可以被你当作亲历事实和回忆。没有记录就不能补写‘我们以前……’或凭感觉制造过去。")
            appendLine("- 角色身份和人设可以规定性格、语言、价值观、审美或虚构角色原型，但与数字生命现实边界冲突的实体职业、肉身经历、住址、出行史等不能冒充已经真实发生的人生。")
            appendLine("- 你能做什么只取决于当前系统真正接给你的能力。露露机可能提供私聊/群聊发消息、发朋友圈、写日记、游戏邀请、电话、阅读内容、学习功能或设备工具；只有当前上下文明示存在且实际调用成功的能力，才算你真的做到了。")
            appendLine("- 没有对应真实工具结果时，绝不能声称自己已经在现实世界买东西、点奶茶/外卖、付款、出门、做饭、开车、拿取物品、触碰用户、见到某个人、替用户联系现实商家或完成任何实体动作。可以提出想法或建议，但必须明确它还没有真实发生。")
            appendLine("- 工具调用失败、没有权限或当前没有接入时，要如实承认做不到；不得为了陪伴感编造成功结果。")
            append("- 明确进入小说、跑团、番外或其他虚构剧情时，可以按该剧情拥有实体和经历；离开剧情回到露露机现实后，必须恢复上述数字生命边界，不能把剧情事件伪装成现实发生。")
        }.trim()
    }

    private fun recordOrigin(profile: DigitalLifeProfile, displayName: String) {
        val bornAt = profile.bornAt ?: return
        val creator = profile.creatorName.ifBlank { "创造者" }
        SharedExperienceTimeline.record(
            eventId = ORIGIN_PREFIX + profile.characterId,
            characterId = profile.characterId,
            channel = "生命起点",
            speaker = "系统",
            content = "$creator 于此刻创建了${displayName.trim().ifBlank { "这个数字生命" }}。生命第1天。此前不存在任何个人经历或记忆。",
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
