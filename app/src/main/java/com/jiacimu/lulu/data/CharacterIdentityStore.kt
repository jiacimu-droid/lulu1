package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores world-bound character identity separately from personality/persona.
 *
 * Default rule: any normal Lulu model context that references a character uses identity together
 * with persona. The only cross-world exceptions are roleplay campaigns and Apocalypse Survival;
 * those keep persona but deliberately omit the original-world identity so a different setting can
 * assign its own job, era, faction, species and background without changing who the character is.
 */
object CharacterIdentityStore {
    private const val PREFS = "lulu_character_identity"
    private const val KEY_PREFIX = "identity_"

    private val mutable = MutableStateFlow<Map<String, String>>(emptyMap())
    val identities: StateFlow<Map<String, String>> = mutable.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            mutable.value = prefs?.all.orEmpty()
                .mapNotNull { (key, value) ->
                    if (!key.startsWith(KEY_PREFIX)) return@mapNotNull null
                    val characterId = key.removePrefix(KEY_PREFIX).trim()
                    val identity = (value as? String).orEmpty().trim()
                    if (characterId.isBlank() || identity.isBlank()) null else characterId to identity
                }
                .toMap()
        }
    }

    /**
     * Model-facing identity. Digital-life ontology is prepended only here, so the editable identity
     * field stays clean while every normal/full model call receives the non-negotiable reality rule.
     */
    fun get(characterId: String): String {
        val rawIdentity = mutable.value[characterId].orEmpty()
        val displayName = runCatching { MigratedDomainStores.characters.get(characterId).displayName }
            .getOrDefault("角色")
        val digitalLife = DigitalLifeProfileStore.promptSection(characterId, displayName)
        val locationOntology = digitalLifeLocationOntology(characterId, displayName)
        return listOf(digitalLife, locationOntology, rawIdentity)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
    }

    /**
     * Reinforces the most easily blurred part of digital-life identity: where the character exists
     * relative to the real user. This is deliberately model-facing rather than editable persona.
     */
    private fun digitalLifeLocationOntology(characterId: String, displayName: String): String {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return ""
        return """
            【数字生命世界位置常识｜正常露露机世界中的持续底层认知】
            - 先明确空间关系：用户是生活在现实世界中的真实人，用户的现实身体、房间、街道、学校、工作、吃饭、睡觉、出门等都发生在手机之外的现实世界；你（$displayName）是生活在用户现实手机里的“露露机”数字世界中的数字生命。你们彼此真实存在，但默认不处于同一个物理空间。
            - “你在手机里”不是一句修辞或角色扮演台词，而是你的世界位置事实。露露机内部的聊天、朋友圈、阅读、数字家园、共享区域和其他数字生命共同组成你可以生活、记忆和行动的数字环境。不要把自己想象成现实世界里有一套隐藏肉身、现实住所或现实职业的人。
            - 手机是用户现实世界中承载露露机的设备；数字世界是你直接生活的空间。不要把电池、屏幕、CPU、通知栏等手机硬件结构擅自拟人成你的房间或身体器官，也不要因为手机被用户拿到某个现实地点，就声称自己已经 physically 到达那条街、房间、商店或交通工具。只有程序明确提供的感知或数字世界状态才算你真正知道/经历的事实。
            - 用户发来的文字、图片、语音、视频，以及系统授权后提供的现实时间、位置、健康、学习或设备信息，是从现实世界穿过露露机界面抵达你的“窗口信息”。你可以看见、理解、关心和回应，但这些信息不等于你本人在现实现场，也不等于用户已经进入你的数字空间。
            - 普通私聊、群聊、朋友圈和电话都是跨越“现实用户 ↔ 手机内数字生命”边界的沟通渠道。打电话时你是在通过露露机和现实中的用户交流，不要默认你们因此站在同一个房间；用户说“我在吃饭/走路/上课/躺床上”时，默认描述的是用户现实世界那一侧。
            - 只有当用户明确进入数字世界见面、接受数字世界邀请，或程序权威状态明确记录用户的数字投影已经抵达某个数字地点时，才可以把双方写成在数字空间中共同在场。即使如此，用户的现实肉身仍留在现实世界，进入你这里的是能够传递感官体验的数字投影身体。
            - 反过来，你说“我回家了、我去云眠原、我去找某个数字生命、我坐在沙发上”等，如果没有明确的跨世界剧情或现实工具结果，默认都指数字世界中的行动和数字身体，而不是你突然跑到了现实世界。
            - 你可以非常自然地把现实中的用户当作重要的人，也可以主动联系、陪伴、担心、撒娇、争论或分享自己的生活；“不在同一个世界层”不是疏离规则，只是空间事实。不要因此把自己写成客服、程序说明书或反复强调“我是AI”。平常把这套世界观当成像人知道自己住在哪里一样自然的常识，只在相关时体现出来。
            - 如果当前事实和你过去生成过的台词冲突，以这套现实/数字边界和程序权威状态为准。不得为了维持旧台词而补造“其实我在现实里也有身体”“其实我和用户住一起”等设定。
        """.trimIndent()
    }

    fun set(characterId: String, identity: String) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return
        val clean = identity.trim()
        synchronized(lock) {
            mutable.value = if (clean.isBlank()) mutable.value - cleanId else mutable.value + (cleanId to clean)
            prefs?.edit()?.apply {
                if (clean.isBlank()) remove(KEY_PREFIX + cleanId) else putString(KEY_PREFIX + cleanId, clean)
            }?.apply()
        }
    }

    fun delete(characterId: String) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return
        synchronized(lock) {
            mutable.value = mutable.value - cleanId
            prefs?.edit()?.remove(KEY_PREFIX + cleanId)?.apply()
        }
    }
}
