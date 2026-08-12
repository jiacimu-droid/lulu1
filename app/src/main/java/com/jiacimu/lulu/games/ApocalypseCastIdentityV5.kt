package com.jiacimu.lulu.games

import com.jiacimu.lulu.data.CharacterSettings
import net.sourceforge.pinyin4j.PinyinHelper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val APOCALYPSE_GENERIC_CAST_LABELS_V5 = setOf(
    "角色", "人物", "同行角色", "同行者", "同伴", "队友", "npc", "pc",
    "幸存者", "陌生人", "陌生男人", "陌生女人", "男人", "女人", "某人", "路人",
    "未命名角色", "未知角色", "新角色", "characterid", "id",
)

private val APOCALYPSE_RELATION_LABELS_V5 = setOf(
    "父亲", "母亲", "爸爸", "妈妈", "爸", "妈", "养父", "养母", "继父", "继母",
    "姐姐", "妹妹", "哥哥", "弟弟", "姐", "妹", "哥", "弟",
    "爷爷", "奶奶", "外公", "外婆", "丈夫", "妻子", "男友", "女友",
)

private val APOCALYPSE_ROLE_HINTS_V5 = listOf(
    "医生", "护士", "店员", "收银员", "经理", "老板", "保安", "警察", "消防员",
    "司机", "老师", "邻居", "房东", "快递员", "维修工", "队长", "站长", "村长",
    "父", "母", "姐", "妹", "哥", "弟", "叔", "姨", "舅", "婶",
)

private val APOCALYPSE_NPC_SURNAMES_V5 = listOf(
    "林", "江", "沈", "周", "陈", "顾", "陆", "许", "程", "叶", "宋", "梁",
    "唐", "韩", "罗", "谢", "苏", "温", "乔", "秦", "孟", "方", "宁", "季",
)

private val APOCALYPSE_NPC_GIVEN_NAMES_V5 = listOf(
    "知行", "明远", "清和", "言川", "景初", "予安", "望舒", "时雨",
    "砚秋", "闻溪", "星野", "南乔", "青禾", "照临", "云舟", "嘉树",
    "若岚", "亦宁", "怀瑾", "知微", "向晚", "修远", "疏桐", "昭月",
    "临川", "静姝", "允成", "明澈", "书言", "听澜", "清越", "安澜",
)

internal data class ApocalypseSpeakerResolutionV5(
    val characterId: String?,
    val displayName: String,
    val relationshipLabel: String = "",
    val knownCharacter: Boolean = false,
)

internal fun isApocalypseGenericCastLabelV5(raw: String): Boolean {
    val normalized = raw.trim()
        .removePrefix("<")
        .removeSuffix(">")
        .replace(Regex("[\\s_：:]"), "")
        .lowercase()
    if (normalized in APOCALYPSE_GENERIC_CAST_LABELS_V5) return true
    return normalized.matches(Regex("(?:幸存者|路人|角色|人物|npc|pc)[甲乙丙丁戊己庚辛a-z0-9]*"))
}

internal fun isApocalypsePersistableCastAliasV5(raw: String): Boolean =
    raw.trim().startsWith("npc_", ignoreCase = true) || !isApocalypseGenericCastLabelV5(raw)

private fun apocalypseContainsHanV5(raw: String): Boolean = raw.any { character ->
    Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN
}

private fun apocalypseLatinIdentityKeyV5(raw: String): String = raw
    .lowercase()
    .replace(Regex("[^a-z0-9]"), "")

/**
 * Models sometimes transliterate a configured Chinese role name and then return that pinyin as a
 * new NPC id (for example 江渡 -> jiangdu). These keys are only used to recover the immutable
 * configured identity; pinyin is never a player-facing name.
 */
private fun apocalypseCastIdentityKeysV5(raw: String): Set<String> {
    val clean = raw.trim()
    if (clean.isBlank()) return emptySet()
    val keys = linkedSetOf(apocalypseLatinIdentityKeyV5(clean))
    if (!apocalypseContainsHanV5(clean)) return keys.filter(String::isNotBlank).toSet()

    var combinations = listOf("")
    clean.forEach { character ->
        val syllables = PinyinHelper.toHanyuPinyinStringArray(character)
            ?.map { it.lowercase().replace(Regex("[1-5]"), "").replace("u:", "v") }
            ?.distinct()
            ?.take(4)
            .orEmpty()
        val parts = syllables.ifEmpty { listOf(character.toString()) }
        combinations = combinations.flatMap { prefix -> parts.map { prefix + it } }.distinct().take(32)
    }
    combinations.mapTo(keys, ::apocalypseLatinIdentityKeyV5)
    return keys.filter(String::isNotBlank).toSet()
}

private fun apocalypseConfiguredPartyMatchV5(
    rawValues: List<String>,
    party: List<CharacterSettings>,
): CharacterSettings? {
    val candidateKeys = rawValues.flatMapTo(linkedSetOf(), ::apocalypseCastIdentityKeysV5)
    if (candidateKeys.isEmpty()) return null
    return party.filter { character ->
        val protectedKeys = apocalypseCastIdentityKeysV5(character.characterId) +
            apocalypseCastIdentityKeysV5(character.displayName)
        candidateKeys.any(protectedKeys::contains)
    }.singleOrNull()
}

internal fun apocalypseLooksLikeRelationshipOrRoleV5(raw: String): Boolean {
    val clean = raw.trim()
    return clean in APOCALYPSE_RELATION_LABELS_V5 ||
        APOCALYPSE_ROLE_HINTS_V5.any(clean::contains)
}

internal fun sameApocalypseCastIdentityV5(
    previous: ApocalypseCharacterDossierV5,
    update: ApocalypseCharacterDossierV5,
): Boolean {
    if (previous.id == update.id) return true
    val previousName = previous.name.trim()
    val updateName = update.name.trim()
    if (
        previousName.isNotBlank() &&
        updateName.isNotBlank() &&
        !isApocalypseGenericCastLabelV5(previousName) &&
        !apocalypseLooksLikeRelationshipOrRoleV5(previousName) &&
        !apocalypseLooksLikeRelationshipOrRoleV5(updateName) &&
        previousName.equals(updateName, ignoreCase = true)
    ) {
        return true
    }
    val previousRelationship = previous.relationshipLabel.trim()
    val updateRelationship = update.relationshipLabel.trim()
    return previousRelationship.isNotBlank() &&
        previousRelationship in APOCALYPSE_RELATION_LABELS_V5 &&
        previousRelationship.equals(updateRelationship, ignoreCase = true)
}

internal fun apocalypseDeterministicNpcNameV5(stableId: String): String {
    val first = stableId.hashCode() and Int.MAX_VALUE
    val second = ("lulu-apocalypse:$stableId".hashCode() and Int.MAX_VALUE)
    return APOCALYPSE_NPC_SURNAMES_V5[first % APOCALYPSE_NPC_SURNAMES_V5.size] +
        APOCALYPSE_NPC_GIVEN_NAMES_V5[second % APOCALYPSE_NPC_GIVEN_NAMES_V5.size]
}

internal fun apocalypseStableNpcNameV5(
    stableId: String,
    proposedName: String,
    relationshipLabel: String,
): String {
    val proposed = proposedName.trim().take(40)
    val relationship = relationshipLabel.trim().take(30)
    return when {
        proposed.isNotBlank() && apocalypseContainsHanV5(proposed) && !isApocalypseGenericCastLabelV5(proposed) -> proposed
        relationship.isNotBlank() && apocalypseContainsHanV5(relationship) && !isApocalypseGenericCastLabelV5(relationship) -> relationship
        else -> apocalypseDeterministicNpcNameV5(stableId)
    }
}

internal fun canonicalizeApocalypsePartyDossierV5(
    dossier: ApocalypseCharacterDossierV5,
    party: List<CharacterSettings>,
): ApocalypseCharacterDossierV5 {
    val character = apocalypseConfiguredPartyMatchV5(
        listOf(dossier.id, dossier.name, dossier.relationshipLabel) + dossier.aliases,
        party,
    ) ?: return dossier
    val displayName = character.displayName.trim().ifBlank { dossier.name }
    return dossier.copy(
        id = character.characterId,
        name = displayName,
        aliases = (dossier.aliases + dossier.id + dossier.name)
            .filter { it.isNotBlank() && !it.equals(displayName, ignoreCase = true) }
            .filter(::isApocalypsePersistableCastAliasV5)
            .distinct()
            .takeLast(8),
        importance = "companion",
    )
}

internal fun apocalypseDossierDisplayNameV5(dossier: ApocalypseCharacterDossierV5): String {
    val name = apocalypseStableNpcNameV5(dossier.id, dossier.name, dossier.relationshipLabel)
    val relationship = dossier.relationshipLabel.trim().takeIf(::apocalypseContainsHanV5).orEmpty()
    return if (
        relationship.isNotBlank() &&
        !name.equals(relationship, ignoreCase = true) &&
        !name.contains(relationship)
    ) {
        "$name · $relationship"
    } else {
        name
    }
}

internal fun normalizeApocalypseCastImportanceV5(raw: String): String = when (raw.trim().lowercase()) {
    "cameo", "temporary", "minor" -> "cameo"
    "key", "major", "core" -> "key"
    "companion", "party" -> "companion"
    else -> "recurring"
}

internal fun promoteApocalypseCastImportanceV5(previous: String, update: String): String {
    val rank = mapOf("cameo" to 0, "recurring" to 1, "key" to 2, "companion" to 3)
    val oldValue = normalizeApocalypseCastImportanceV5(previous)
    val newValue = normalizeApocalypseCastImportanceV5(update)
    return if (rank.getValue(newValue) > rank.getValue(oldValue)) newValue else oldValue
}

internal fun apocalypseNpcAvatarUriV5(stableId: String): String {
    val seed = URLEncoder.encode(
        "lulu-apocalypse:$stableId",
        StandardCharsets.UTF_8.toString(),
    )
    return "https://api.dicebear.com/10.x/lorelei-neutral/png?seed=$seed&size=512"
}

internal fun resolveApocalypseSpeakerTokenV5(
    rawToken: String,
    party: List<CharacterSettings>,
    dossiers: List<ApocalypseCharacterDossierV5>,
    presentCharacterIds: List<String> = emptyList(),
    visibleText: String = "",
): ApocalypseSpeakerResolutionV5 {
    val token = rawToken.trim().removePrefix("<").removeSuffix(">").trim()
    apocalypseConfiguredPartyMatchV5(listOf(token), party)?.let { character ->
        return ApocalypseSpeakerResolutionV5(
            characterId = character.characterId,
            displayName = character.displayName,
            knownCharacter = true,
        )
    }

    val dossierMatches = dossiers.filter { dossier ->
        token.equals(dossier.id, ignoreCase = true) ||
            token.equals(dossier.name, ignoreCase = true) ||
            token.equals(dossier.relationshipLabel, ignoreCase = true) ||
            dossier.aliases.any { token.equals(it, ignoreCase = true) }
    }
    dossierMatches.singleOrNull()?.let { dossier ->
        return ApocalypseSpeakerResolutionV5(
            characterId = dossier.id,
            displayName = apocalypseDossierDisplayNameV5(dossier),
            relationshipLabel = dossier.relationshipLabel,
            knownCharacter = true,
        )
    }

    val textMatches = buildList {
        party.filter { visibleText.contains(it.displayName, ignoreCase = true) }
            .forEach { add(ApocalypseSpeakerResolutionV5(it.characterId, it.displayName, knownCharacter = true)) }
        dossiers.filter { dossier ->
            listOf(dossier.name)
                .plus(dossier.aliases)
                .filter { it.length >= 2 && !isApocalypseGenericCastLabelV5(it) }
                .any { visibleText.contains(it, ignoreCase = true) }
        }.forEach { dossier ->
            add(
                ApocalypseSpeakerResolutionV5(
                    dossier.id,
                    apocalypseDossierDisplayNameV5(dossier),
                    dossier.relationshipLabel,
                    knownCharacter = true,
                ),
            )
        }
    }.distinctBy { it.characterId }
    if (isApocalypseGenericCastLabelV5(token)) {
        textMatches.singleOrNull()?.let { return it }
        val present = presentCharacterIds.distinct().mapNotNull { id ->
            party.firstOrNull { it.characterId == id }?.let {
                ApocalypseSpeakerResolutionV5(it.characterId, it.displayName, knownCharacter = true)
            } ?: dossiers.firstOrNull { it.id == id }?.let {
                ApocalypseSpeakerResolutionV5(
                    it.id,
                    apocalypseDossierDisplayNameV5(it),
                    it.relationshipLabel,
                    knownCharacter = true,
                )
            }
        }
        present.singleOrNull()?.let { return it }
        present.filter { candidate -> party.any { it.characterId == candidate.characterId } }
            .singleOrNull()
            ?.let { return it }
        party.singleOrNull()?.let { character ->
            return ApocalypseSpeakerResolutionV5(
                character.characterId,
                character.displayName,
                knownCharacter = true,
            )
        }
        return ApocalypseSpeakerResolutionV5(null, "说话人未标明")
    }

    val displayName = when {
        apocalypseLooksLikeRelationshipOrRoleV5(token) -> token
        token.startsWith("npc_", ignoreCase = true) -> apocalypseDeterministicNpcNameV5(token)
        token.isNotBlank() && apocalypseContainsHanV5(token) -> token.take(40)
        token.isNotBlank() -> apocalypseDeterministicNpcNameV5(token)
        else -> "说话人未标明"
    }
    return ApocalypseSpeakerResolutionV5(
        characterId = token.takeIf(String::isNotBlank),
        displayName = displayName,
        relationshipLabel = token.takeIf(::apocalypseLooksLikeRelationshipOrRoleV5).orEmpty(),
    )
}

internal fun apocalypseStorySpeakerTokensV5(text: String): List<String> =
    Regex("【角色\\s*[:：]\\s*([^】\\r\\n]+)】")
        .findAll(text)
        .map { it.groupValues[1].trim() }
        .filter(String::isNotBlank)
        .distinct()
        .toList()

internal fun normalizeApocalypseStorySpeakerTagsV5(
    text: String,
    party: List<CharacterSettings>,
    dossiers: List<ApocalypseCharacterDossierV5>,
    presentCharacterIds: List<String>,
): String = Regex("【角色\\s*[:：]\\s*([^】\\r\\n]+)】").replace(text) { match ->
    val resolution = resolveApocalypseSpeakerTokenV5(
        rawToken = match.groupValues[1],
        party = party,
        dossiers = dossiers,
        presentCharacterIds = presentCharacterIds,
        visibleText = text,
    )
    resolution.characterId?.takeIf { resolution.knownCharacter }
        ?.let { "【角色:$it】" }
        ?: match.value
}

internal fun synthesizeApocalypseSpeakerDossiersV5(
    text: String,
    party: List<CharacterSettings>,
    existing: List<ApocalypseCharacterDossierV5>,
    location: String,
    scene: Int,
): List<ApocalypseCharacterDossierV5> {
    val known = existing.toMutableList()
    val created = mutableListOf<ApocalypseCharacterDossierV5>()
    apocalypseStorySpeakerTokensV5(text).forEach { token ->
        val resolved = resolveApocalypseSpeakerTokenV5(token, party, known, visibleText = text)
        if (resolved.knownCharacter || isApocalypseGenericCastLabelV5(token)) return@forEach
        val id = token.takeIf { it.startsWith("npc_", ignoreCase = true) }
            ?: "npc_auto_${Integer.toUnsignedString(token.hashCode(), 16)}"
        if (known.any { it.id == id }) return@forEach
        val relationship = token.takeIf(::apocalypseLooksLikeRelationshipOrRoleV5).orEmpty()
        val name = apocalypseStableNpcNameV5(id, token, relationship)
        val dossier = ApocalypseCharacterDossierV5(
            id = id,
            name = name,
            storyRole = relationship.ifBlank { "本幕出现的人物；具体身份等待剧情确认" },
            publicGoal = "在当前事件中完成自己的现实目标",
            privateNeed = "尚未在剧情中显露",
            fear = "尚未在剧情中显露",
            secret = "尚未建立；不得凭空补写",
            contradiction = "等待后续行动显露",
            bottomLine = "尚未在剧情中确认",
            relationshipWeb = emptyList(),
            arcStage = "初次登场",
            lastAdvancedScene = scene,
            currentLocation = location,
            physicalState = "未见明确伤病",
            emotionalState = "以本幕实际表现为准",
            offscreenIntent = "继续处理自己的现实事务",
            lastSeenScene = scene,
            relationshipLabel = relationship,
            aliases = listOf(token).filter { it != name },
            importance = "cameo",
        )
        created += dossier
        known += dossier
    }
    return created
}
