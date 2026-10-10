package com.jiacimu.lulu.data

/**
 * A spoken form of address is a durable, per-relationship preference, NOT a
 * contact-list remark, an identity trait or a model-invented conversational flourish.
 * Import only explicit first-person user preferences from their own real messages.
 */
internal object CharacterAddressPreference {
    private val scanned = mutableSetOf<String>()

    private val patterns = listOf(
        Regex("""我(?:还是|其实)?(?:更|比较|最)?喜欢([\p{IsHan}A-Za-z]{1,8})(?:这个称呼|这种称呼|这个叫法|这种叫法)"""),
        Regex("""我(?:更|比较|最)?喜欢(?:你)?(?:叫|喊|称呼)(?:我)?[“「]?([\p{IsHan}A-Za-z]{1,8})"""),
        Regex("""(?:以后|今后|平时|日常)(?:你)?(?:就|可以)?(?:叫|喊|称呼)我[“「]?([\p{IsHan}A-Za-z]{1,8})"""),
        Regex("""(?:你)?(?:就|请|可以)?(?:叫|喊)我[“「]?([\p{IsHan}A-Za-z]{1,8})(?:[”」]?就好|[”」]?吧|[”」]?呀|[”」]?哦|[”」]?。|[”」]?$)"""),
    )
    private val invalid = setOf("什么", "这个", "那个", "名字", "昵称", "称呼", "可以", "怎么", "不要", "不叫")
    fun extractExplicitAddress(message: String): String? {
        val text = message.trim().take(300)
        if (text.isBlank() || listOf("比如", "例如", "假设", "不是说", "开个玩笑", "引号里").any(text::contains)) return null
        if (text.contains("不喜欢") || text.contains("别叫我") || text.contains("不要叫我") ||
            text.contains("不想被叫") || text.contains("讨厌被叫")) return null
        val match = patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1) }
            ?.trim()?.trimEnd('吗','呢','啊','了','吧','呀','哦') ?: return null
        return match.takeIf { it.length in 1..8 && it !in invalid && !it.endsWith("什么") }
    }

    fun observeUserMessage(characterId: String, message: String, eventId: String) {
        val address = extractExplicitAddress(message) ?: return
        CharacterLifeStore.observePreferredAddress(characterId, address, eventId)
    }

    /**
     * One-time backfill for preferences spoken before this feature existed.
     * A deleted original cannot remain an unquestioned preference.
     */
    fun refresh(characterId: String) {
        synchronized(scanned) {
            val root = CharacterLifeStore.state(characterId).optJSONObject("socialNames")
            if (root?.optBoolean("preferredAddressManual", false) == true) return
            val sourceId = root?.optString("preferredAddressSourceId").orEmpty()
            if (characterId in scanned && sourceId.isBlank()) return
            if (sourceId.isNotBlank()) {
                val original = SharedExperienceTimeline.eventsByIds(characterId, setOf(sourceId))
                if (original.any { it.id == sourceId &&
                        extractExplicitAddress(it.evidenceContent) == root.optString("preferredAddress") }) return
            }
            val history = SharedExperienceTimeline.all(characterId)
            // Do not lock an empty history as checked before the timeline is initialized.
            if (history.isNotEmpty()) scanned.add(characterId)
            val last = history.asReversed().firstNotNullOfOrNull { event ->
                if ((event.channel != "私聊" && !event.channel.contains("电话")) ||
                    !event.isUserMemoryStatement()) null
                else extractExplicitAddress(event.evidenceContent)?.let { it to event.id }
            }
            if (last != null) CharacterLifeStore.observePreferredAddress(characterId, last.first, last.second)
            else if (sourceId.isNotBlank()) CharacterLifeStore.clearObservedPreferredAddress(characterId)
        }
    }

    fun promptSection(characterId: String): String {
        refresh(characterId)
        val names = CharacterLifeStore.state(characterId).optJSONObject("socialNames")
        val preferred = names?.optString("preferredAddress").orEmpty()
        val remark = names?.optString("userRemark").orEmpty()
        return buildString {
            if (preferred.isNotBlank()) appendLine("用户明确偏好的日常称呼：$preferred。此偏好比临时玩笑外号优先；自然叫，不是每条消息强制叫一次。")
            else appendLine("尚无确认的日常称呼；自然用“你”或已有真实称呼，不按当前话题编新头衔。")
            if (remark.isNotBlank()) appendLine("角色联系人里给用户的私人备注：$remark。备注是私有记录，不自动等于口头称呼。")
            append("亲密称呼有连续性，偶尔可以真心换个特别叫法，但不能随口把对方当下的行为包装成一个新外号、替代双方偏好的常用称呼。")
        }.trim()
    }
}
