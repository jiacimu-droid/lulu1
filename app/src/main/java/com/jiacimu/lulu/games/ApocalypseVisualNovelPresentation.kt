package com.jiacimu.lulu.games

import com.jiacimu.lulu.data.CharacterSettings

internal enum class ApocalypseStorySpeakerKind { Narrator, Player, Character }

internal data class ApocalypseStoryPage(
    val speakerKind: ApocalypseStorySpeakerKind,
    val characterId: String? = null,
    val speakerLabel: String? = null,
    val text: String,
)

/**
 * Visual-novel tagged format used by the apocalypse writer and stage renderer.
 *
 * New scenes use one of these prefixes at the beginning of every display beat:
 * 【旁白】 / 【玩家】 / 【角色:<characterId>】。V5 的 characterId 既可以来自同行角色，
 * 也可以来自导演持久化的原创 NPC 档案；解析层保留原始 id，由舞台层决定头像与署名。
 * Legacy saves without tags remain readable and are treated as narration.
 */
internal fun parseApocalypseStoryPages(
    text: String,
    party: List<CharacterSettings>,
    dossiers: List<ApocalypseCharacterDossierV5> = emptyList(),
    presentCharacterIds: List<String> = emptyList(),
    maxChars: Int = 72,
): List<ApocalypseStoryPage> {
    val normalized = text.replace("\r\n", "\n").trim()
    if (normalized.isBlank()) {
        return listOf(ApocalypseStoryPage(ApocalypseStorySpeakerKind.Narrator, text = "……"))
    }

    val castByName = buildMap<String, String> {
        party.filter { it.displayName.isNotBlank() }.forEach { put(it.displayName.trim(), it.characterId) }
        dossiers.forEach { dossier ->
            (listOf(dossier.name, dossier.relationshipLabel) + dossier.aliases)
                .filter(String::isNotBlank)
                .forEach { put(it.trim(), dossier.id) }
        }
    }
    val blocks = splitApocalypseSpeakerBlocks(normalized)
    val pages = mutableListOf<ApocalypseStoryPage>()

    blocks.forEach { rawBlock ->
        val tagged = parseTaggedSpeaker(rawBlock)
        val inferred = tagged ?: inferLegacySpeaker(rawBlock, castByName)
        val cleanText = inferred.text.trim().ifBlank { return@forEach }
        val resolution = if (inferred.kind == ApocalypseStorySpeakerKind.Character) {
            resolveApocalypseSpeakerTokenV5(
                rawToken = inferred.characterId.orEmpty(),
                party = party,
                dossiers = dossiers,
                presentCharacterIds = presentCharacterIds,
                visibleText = normalized,
            )
        } else {
            null
        }
        splitVisualNovelTextPreservingCharacters(cleanText, maxChars).forEach { piece ->
            pages += ApocalypseStoryPage(
                speakerKind = inferred.kind,
                characterId = resolution?.characterId ?: inferred.characterId,
                speakerLabel = resolution?.displayName,
                text = piece,
            )
        }
    }

    return pages.ifEmpty {
        listOf(ApocalypseStoryPage(ApocalypseStorySpeakerKind.Narrator, text = normalized))
    }
}

internal fun tagApocalypseNarrationAsNarrator(text: String): String =
    text.replace("\r\n", "\n")
        .trim()
        .split(Regex("\\n\\s*\\n+"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString("\n\n") { "【旁白】$it" }

private data class ParsedSpeaker(
    val kind: ApocalypseStorySpeakerKind,
    val characterId: String? = null,
    val text: String,
)

private val apocalypseSpeakerTag = Regex("【(?:旁白|玩家|角色\\s*[:：]\\s*[^】\\r\\n]+)】")

/**
 * Split on every speaker tag instead of only on blank paragraphs. Models occasionally put the
 * next tagged beat after a single newline (or directly after the previous sentence). Keeping that
 * text in one block would make the later internal `【角色:<id>】` marker visible to the player.
 */
private fun splitApocalypseSpeakerBlocks(text: String): List<String> {
    val matches = apocalypseSpeakerTag.findAll(text).toList()
    if (matches.isEmpty()) {
        return text.split(Regex("\\n\\s*\\n+"))
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    return buildList {
        val legacyPrefix = text.substring(0, matches.first().range.first).trim()
        if (legacyPrefix.isNotBlank()) addAll(splitApocalypseSpeakerBlocks(legacyPrefix))

        matches.forEachIndexed { index, match ->
            val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
            text.substring(match.range.first, end).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

private fun parseTaggedSpeaker(block: String): ParsedSpeaker? {
    val narrator = "【旁白】"
    val player = "【玩家】"
    if (block.startsWith(narrator)) {
        return ParsedSpeaker(ApocalypseStorySpeakerKind.Narrator, text = block.removePrefix(narrator))
    }
    if (block.startsWith(player)) {
        return ParsedSpeaker(ApocalypseStorySpeakerKind.Player, text = block.removePrefix(player))
    }
    val match = Regex("^【角色\\s*[:：]\\s*([^】]+)】").find(block) ?: return null
    val id = match.groupValues[1].trim().takeIf(String::isNotBlank) ?: return null
    return ParsedSpeaker(
        kind = ApocalypseStorySpeakerKind.Character,
        characterId = id,
        text = block.substring(match.range.last + 1),
    )
}

private fun inferLegacySpeaker(
    block: String,
    castByName: Map<String, String>,
): ParsedSpeaker {
    // Older saves were plain prose. Only infer a character when the paragraph clearly starts with
    // "名字：" / "名字：“"; otherwise keeping it as narration is safer than showing the wrong portrait.
    castByName.forEach { (name, characterId) ->
        val prefixes = listOf("${name}：", "${name}:", "${name}说：", "${name}说:")
        val prefix = prefixes.firstOrNull(block::startsWith)
        if (prefix != null) {
            return ParsedSpeaker(
                kind = ApocalypseStorySpeakerKind.Character,
                characterId = characterId,
                text = block.removePrefix(prefix).trim(),
            )
        }
    }
    return ParsedSpeaker(ApocalypseStorySpeakerKind.Narrator, text = block)
}

/**
 * Split only at substring boundaries. No sentence is rewritten, rejoined, shortened, or chunked with
 * dropped punctuation, so concatenating all returned pieces recreates [text] byte-for-byte.
 */
private fun splitVisualNovelTextPreservingCharacters(text: String, maxChars: Int): List<String> {
    if (text.isEmpty()) return emptyList()
    val limit = maxChars.coerceAtLeast(24)
    if (text.length <= limit) return listOf(text)

    val strongStops = setOf('。', '！', '？', '!', '?', '；', ';', '…', '\n')
    val softStops = setOf('，', ',', '、', '：', ':', '）', ')', ']', '】', '》', '”', '’')
    val result = mutableListOf<String>()
    var start = 0

    while (start < text.length) {
        var end = (start + limit).coerceAtMost(text.length)
        if (end < text.length) {
            val preferredStart = (start + (limit * 0.55f).toInt()).coerceAtMost(end - 1)
            fun findCut(stops: Set<Char>): Int {
                for (index in end - 1 downTo preferredStart) {
                    if (text[index] in stops) return index + 1
                }
                return -1
            }
            val strongCut = findCut(strongStops)
            val softCut = if (strongCut < 0) findCut(softStops) else -1
            end = when {
                strongCut > start -> strongCut
                softCut > start -> softCut
                else -> end
            }
        }
        result += text.substring(start, end)
        start = end
    }

    return result
}
