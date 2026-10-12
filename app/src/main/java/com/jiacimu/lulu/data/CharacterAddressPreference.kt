package com.jiacimu.lulu.data

/** Spoken names are relationship preferences, never contact remarks or task verbs. */
internal object CharacterAddressPreference {
    private val patterns = listOf(
        Regex("""我(?:还是|其实)?(?:更|比较|最)?喜欢[“「\"]?([\p{IsHan}A-Za-z]{1,8}?)[”」\"]?(?:这个称呼|这种称呼|这个叫法|这种叫法)"""),
        Regex("""我(?:还是|其实)?(?:更|比较|最)?喜欢(?:你)?(?:叫|喊|称呼)(?:我)?[“「\"]?([\p{IsHan}A-Za-z]{1,8})"""),
        Regex("""(?:以后|今后|平时|日常)(?:你)?(?:就|可以)?(?:叫|喊|称呼)我[“「\"]?([\p{IsHan}A-Za-z]{1,8})"""),
        Regex("""(?:你)?(?:就|请|可以)?(?:叫|喊)我[“「\"]?([\p{IsHan}A-Za-z]{1,8})(?:[”」\"]?就好|[”」\"]?吧|[”」\"]?呀|[”」\"]?哦|[”」\"]?[。！!]|[”」\"]?$)"""),
    )
    private val refusals = listOf(
        Regex("""(?:别再?|不要再?|不许)(?:叫|喊|称呼)我[“「\"]?([\p{IsHan}A-Za-z]{1,8})"""),
        Regex("""我(?:不喜欢|不想|讨厌)(?:你)?(?:被)?(?:叫|喊|称呼)(?:我)?[“「\"]?([\p{IsHan}A-Za-z]{1,8})"""),
        Regex("""我不喜欢[“「\"]?([\p{IsHan}A-Za-z]{1,8}?)[”」\"]?(?:这个称呼|这种称呼|这个叫法|这种叫法)"""),
    )
    private val invalid = setOf("什么", "这个", "那个", "名字", "昵称", "称呼", "可以", "怎么", "不要", "不叫",
        "起床", "起来", "醒醒", "醒来", "早起", "睡觉", "休息", "吃饭", "喝水", "学习", "复习", "提醒", "叫醒", "加油", "看看")

    internal fun clauses(message: String): List<String> = message.split(Regex("[，,。；;！!？?\\n]+|但是|而是|(?=我(?:更|比较|最)?喜欢)"))
        .map(String::trim).filter(String::isNotBlank)

    internal fun isExample(text: String): Boolean = Regex("比如|例如|假设|开个玩笑|引号里|小说里|故事里|书里|她说|他说|角色说")
        .containsMatchIn(text)

    internal fun isTemporary(text: String): Boolean =
        Regex("今天|这次|现在|暂时|今晚|玩笑").containsMatchIn(text) &&
            !Regex("以后|今后|平时|日常|每次|一直").containsMatchIn(text)

    private fun name(value: String?): String? {
        val clean = value?.trim()?.trimEnd('吗', '呢', '啊', '了', '吧', '呀', '哦') ?: return null
        return clean.takeIf { it.length in 1..8 && it !in invalid &&
            !Regex("一些|奇怪|乱七八糟|这种|那个|这个").containsMatchIn(it) &&
            !it.endsWith("什么") && !Regex("^(起床|起来|叫醒|提醒|开始|以后|去|做|别|不要|记得|快|早点)").containsMatchIn(it) }
    }

    fun extractExplicitAddress(message: String): String? = clauses(message).asReversed().firstNotNullOfOrNull { clause ->
        if (isExample(message) || isExample(clause) || isTemporary(clause) || Regex("不喜欢|别叫我|不要叫我|不想被叫|讨厌被叫").containsMatchIn(clause)) null
        else patterns.firstNotNullOfOrNull { pattern -> name(pattern.find(clause)?.groupValues?.getOrNull(1)) }
    }

    fun extractRejectedAddresses(message: String): Set<String> = clauses(message).flatMap { clause ->
        if (isExample(message) || isExample(clause) || isTemporary(clause)) emptyList() else refusals.mapNotNull { name(it.find(clause)?.groupValues?.getOrNull(1)) }
    }.toSet()

    fun observeUserMessage(characterId: String, message: String, eventId: String) {
        extractExplicitAddress(message)?.let { CharacterLifeStore.observePreferredAddress(characterId, it, eventId) }
    }

    /** Rebuild from live evidence: newer corrections, edits and deletion all take effect. */
    fun refresh(characterId: String) {
        val names = CharacterLifeStore.state(characterId).optJSONObject("socialNames")
        if (names?.optBoolean("preferredAddressManual", false) == true) return
        val preference = CharacterRelationshipPreferences.snapshot(characterId).preferredAddress
        if (preference != null) CharacterLifeStore.observePreferredAddress(characterId, preference.first, preference.second)
        else CharacterLifeStore.clearObservedPreferredAddress(characterId)
    }

    fun currentAddress(characterId: String): String {
        refresh(characterId)
        val address = CharacterLifeStore.state(characterId).optJSONObject("socialNames")?.optString("preferredAddress").orEmpty()
        return address.takeUnless { it in CharacterRelationshipPreferences.snapshot(characterId).rejectedAddresses }.orEmpty()
    }

    fun promptSection(characterId: String): String {
        refresh(characterId)
        val names = CharacterLifeStore.state(characterId).optJSONObject("socialNames")
        val rejected = CharacterRelationshipPreferences.snapshot(characterId).rejectedAddresses
        val preferred = names?.optString("preferredAddress").orEmpty().takeUnless { it in rejected }.orEmpty()
        val remark = names?.optString("userRemark").orEmpty()
        return buildString {
            if (preferred.isNotBlank()) appendLine("用户明确偏好的日常称呼：$preferred。此偏好比旧人设里的举例称呼、模型惯用昵称与临时玩笑外号更具体；自然叫，不是每条消息强制叫一次。")
            else appendLine("尚无确认的日常称呼；自然用“你”或已有真实称呼，不按当前话题编新头衔。")
            if (rejected.isNotEmpty()) appendLine("用户明确拒绝用来称呼她的叫法：${rejected.joinToString("、")}。不要用这些叫她；引用和讨论这些词不等于用来称呼她。")
            if (remark.isNotBlank()) appendLine("角色联系人里给用户的私人备注：$remark。备注是私有记录，不自动等于口头称呼。")
            append("亲密称呼有连续性，偶尔可以真心换个特别叫法，但不能随口把对方当下的行为包装成一个新外号、替代双方偏好的常用称呼。")
        }.trim()
    }
}
