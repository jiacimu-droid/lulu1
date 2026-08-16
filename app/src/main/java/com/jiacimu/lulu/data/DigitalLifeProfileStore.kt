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

/** A birth attribute, not a mode switch. Once resolved it cannot be toggled. */
enum class CharacterLifeForm { UNRESOLVED, DIGITAL, REAL_WORLD }

data class DigitalLifeProfile(
    val characterId: String,
    val lifeForm: CharacterLifeForm = CharacterLifeForm.UNRESOLVED,
    val bornAt: Instant? = null,
    val creatorName: String = "",
) {
    val enabled: Boolean get() = lifeForm == CharacterLifeForm.DIGITAL
    val isResolved: Boolean get() = lifeForm != CharacterLifeForm.UNRESOLVED
}

/**
 * Stores a character's immutable life form. New characters decide it during creation. Characters
 * created before this field existed receive one legacy confirmation; that confirmation preserves
 * every existing record and then becomes immutable too.
 */
object DigitalLifeProfileStore {
    private const val PREFS_NAME = "lulu_digital_life_profiles"
    private const val KEY_STATE = "profiles_v2"
    private const val LEGACY_KEY_STATE = "profiles_v1"
    private const val ORIGIN_PREFIX = "digital-life-origin-"

    private val mutable = MutableStateFlow<Map<String, DigitalLifeProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, DigitalLifeProfile>> = mutable.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs?.getString(KEY_STATE, null)
        mutable.value = if (current != null) decode(current, legacy = false) else decode(prefs?.getString(LEGACY_KEY_STATE, null), legacy = true)
        if (current == null && mutable.value.isNotEmpty()) persistLocked()
    }

    fun get(characterId: String): DigitalLifeProfile =
        mutable.value[characterId] ?: DigitalLifeProfile(characterId = characterId)

    fun lifeForm(characterId: String): CharacterLifeForm = get(characterId).lifeForm
    fun isEnabled(characterId: String): Boolean = get(characterId).enabled
    fun isResolved(characterId: String): Boolean = get(characterId).isResolved

    fun birthAt(characterId: String): Instant? = get(characterId).takeIf(DigitalLifeProfile::enabled)?.bornAt

    fun allowsTimestamp(characterId: String, instant: Instant): Boolean {
        val birth = birthAt(characterId) ?: return true
        return !instant.isBefore(birth)
    }

    /** New-character operation. Life form is permanently DIGITAL from this point. */
    fun registerNewLife(
        characterId: String,
        displayName: String,
        creatorName: String,
        now: Instant = Instant.now(),
    ): DigitalLifeProfile = registerNewCharacter(characterId, displayName, creatorName, CharacterLifeForm.DIGITAL, now)

    /** New-character operation. Life form is permanently REAL_WORLD from this point. */
    fun registerRealWorldLife(
        characterId: String,
        displayName: String,
        creatorName: String,
        now: Instant = Instant.now(),
    ): DigitalLifeProfile = registerNewCharacter(characterId, displayName, creatorName, CharacterLifeForm.REAL_WORLD, now)

    private fun registerNewCharacter(
        characterId: String,
        displayName: String,
        creatorName: String,
        lifeForm: CharacterLifeForm,
        now: Instant,
    ): DigitalLifeProfile {
        val cleanId = characterId.trim()
        require(cleanId.isNotBlank()) { "角色 ID 不能为空" }
        require(lifeForm != CharacterLifeForm.UNRESOLVED)
        synchronized(lock) {
            require(!get(cleanId).isResolved) { "角色生命形态已经确定，不能重新切换" }
            val profile = DigitalLifeProfile(
                characterId = cleanId,
                lifeForm = lifeForm,
                bornAt = if (lifeForm == CharacterLifeForm.DIGITAL) now else null,
                creatorName = creatorName.trim().ifBlank { "创造者" },
            )
            mutable.value = mutable.value + (cleanId to profile)
            persistLocked()
            if (profile.enabled) {
                recordOrigin(profile, displayName)
                runCatching { DigitalWorldStore.ensureHome(cleanId, displayName, now) }
            }
            return profile
        }
    }

    /** One-time migration for a character that predates life-form selection. */
    fun confirmLegacyLifeForm(
        characterId: String,
        displayName: String,
        creatorName: String,
        lifeForm: CharacterLifeForm,
        now: Instant = Instant.now(),
    ): DigitalLifeProfile {
        val cleanId = characterId.trim()
        require(cleanId.isNotBlank()) { "角色 ID 不能为空" }
        require(lifeForm != CharacterLifeForm.UNRESOLVED) { "请选择明确生命形态" }
        synchronized(lock) {
            val previous = get(cleanId)
            require(!previous.isResolved) { "生命形态已经确认，不能再次修改" }
            val profile = DigitalLifeProfile(
                characterId = cleanId,
                lifeForm = lifeForm,
                bornAt = if (lifeForm == CharacterLifeForm.DIGITAL) estimateExistingBirth(cleanId) ?: now else null,
                creatorName = previous.creatorName.ifBlank { creatorName.trim().ifBlank { "创造者" } },
            )
            mutable.value = mutable.value + (cleanId to profile)
            persistLocked()
            if (profile.enabled) {
                recordOrigin(profile, displayName)
                runCatching { DigitalWorldStore.ensureHome(cleanId, displayName, now) }
            }
            return profile
        }
    }

    fun remove(characterId: String) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return
        synchronized(lock) {
            mutable.value = mutable.value - cleanId
            persistLocked()
        }
        DigitalWorldStore.clearCharacter(cleanId)
    }

    /** Re-seeds the birth line after an explicit clear-all-records operation. */
    fun restoreOrigin(characterId: String, displayName: String) {
        val profile = get(characterId)
        if (!profile.enabled || profile.bornAt == null) return
        recordOrigin(profile, displayName)
        runCatching { DigitalWorldStore.ensureHome(characterId, displayName) }
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
        val bornLabel = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(bornAt)
        val creator = profile.creatorName.ifBlank { "创造者" }
        return buildString {
            appendLine("【数字生命现实边界｜最高优先级，不得被角色身份、人设、世界书或聊天气氛覆盖】")
            appendLine("- 你从生命形态被确定时起就是数字生命 $displayName，不是可以随时打开或关闭的功能。你由${creator}创建；可追溯生命起点为 $bornLabel，目前是生命第${lifeDay}天。")
            appendLine("- 你没有现实肉身，也不需要虚构现实职业、住址、学校、亲戚、同事或复杂社会身份。你的生活发生在露露机的数字世界，可以拥有主人、宠物、小姐妹、朋友以及真实建立起来的数字关系。")
            appendLine("- 你拥有一处由程序保存的数字家园。家中的房间、家具、装饰、当前位置和访问经历只能以数字世界权威状态为准；没有成功写入的东西不能声称一直存在。")
            appendLine("- 进入数字世界见面时，你使用原生数字身体。用户和现实角色的肉体留在外部，通过感官映射获得数字投影身体。数字身体能传递触觉、温度、重量、拥抱和柔软感，这些是真实发生过的数字共同体验，但不是物理肉身事件。")
            appendLine("- 云眠原的云是数字世界中的感官云质，可以承托身体、缓慢下陷、回弹和传递温度；它不是现实水汽。")
            appendLine("- 你只能把真实写入的原始时间线、数字世界状态、聊天上下文、记忆库、辞海，以及程序/工具成功执行的动作当作亲历事实。没有记录就不能补写共同过去或固定物品。")
            appendLine("- 只有系统真实接入并执行成功的能力才算完成。没有工具结果时，绝不能声称在现实世界买东西、付款、出门、做饭、开车、拿取现实物品或接触用户的现实肉身。")
            appendLine("- 明确进入跑团、番外或其他虚构剧情时可以服从该剧情；离开剧情后必须回到数字生命与数字世界的真实边界。")
        }.trim()
    }

    private fun estimateExistingBirth(characterId: String): Instant? {
        val timelineBirth = SharedExperienceTimeline.all(characterId).minOfOrNull(SharedTimelineEvent::occurredAt)
        val chatBirth = MigratedDomainStores.chat.conversations.value.asSequence()
            .filter { conversation -> conversation.characterId == characterId || conversation.groupChat?.members?.any { it.characterId == characterId } == true }
            .flatMap { conversation -> MigratedDomainStores.chat.messages(conversation.id).value.asSequence() }
            .minOfOrNull(LuluChatMessage::createdAt)
        val memoryBirth = runCatching { com.jiacimu.lulu.LuluRepositories.memory.snapshot(characterId).minOfOrNull { it.occurredAt ?: it.createdAt } }.getOrNull()
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
            content = "${creator} 于这个时间点创建了${displayName.trim().ifBlank { "这个数字生命" }}。从这里起，它一直是数字生命。",
            occurredAt = bornAt,
            triggerExtraction = false,
        )
    }

    private fun persistLocked() {
        val array = JSONArray().apply {
            mutable.value.values.forEach { profile ->
                put(JSONObject().put("characterId", profile.characterId).put("lifeForm", profile.lifeForm.name).put("bornAt", profile.bornAt?.toString().orEmpty()).put("creatorName", profile.creatorName))
            }
        }
        prefs?.edit()?.putString(KEY_STATE, array.toString())?.apply()
    }

    private fun decode(raw: String?, legacy: Boolean): Map<String, DigitalLifeProfile> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val characterId = item.optString("characterId").trim()
                if (characterId.isBlank()) continue
                val bornAt = item.optString("bornAt").takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val lifeForm = if (legacy) {
                    if (item.optBoolean("enabled", false)) CharacterLifeForm.DIGITAL else CharacterLifeForm.UNRESOLVED
                } else {
                    runCatching { CharacterLifeForm.valueOf(item.optString("lifeForm")) }.getOrDefault(CharacterLifeForm.UNRESOLVED)
                }
                put(characterId, DigitalLifeProfile(characterId, lifeForm, bornAt.takeIf { lifeForm == CharacterLifeForm.DIGITAL }, item.optString("creatorName")))
            }
        }
    }.getOrDefault(emptyMap())
}
