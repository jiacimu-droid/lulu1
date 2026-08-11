package com.jiacimu.lulu.games

import com.jiacimu.lulu.data.CharacterSettings

internal enum class ApocalypseStorySpeakerKind { Narrator, Player, Character }

internal data class ApocalypseStoryPage(
    val speakerKind: ApocalypseStorySpeakerKind,
    val characterId: String? = null,
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
    maxChars: Int = 72,
): List<ApocalypseStoryPage> {
    val normalized = text.replace("\r\n", "\n").trim()
    if (normalized.isBlank()) {
        return listOf(ApocalypseStoryPage(ApocalypseStorySpeakerKind.Narrator, text = "……"))
    }

    val partyByName = party
        .filter { it.displayName.isNotBlank() }
        .associateBy { it.displayName.trim() }
    val blocks = normalized.split(Regex("\\n\\s*\\n+"))
        .map(String::trim)
        .filter(String::isNotBlank)
    val pages = mutableListOf<ApocalypseStoryPage>()

    blocks.forEach { rawBlock ->
        val tagged = parseTaggedSpeaker(rawBlock)
        val inferred = tagged ?: inferLegacySpeaker(rawBlock, partyByName)
        val cleanText = inferred.text.trim().ifBlank { return@forEach }
        splitVisualNovelTextPreservingCharacters(cleanText, maxChars).forEach { piece ->
            pages += ApocalypseStoryPage(
                speakerKind = inferred.kind,
                characterId = inferred.characterId,
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

private fun parseTaggedSpeaker(block: String): ParsedSpeaker? {
    val narrator = "【旁白】"
    val player = "【玩家】"
    if (block.startsWith(narrator)) {
        return ParsedSpeaker(ApocalypseStorySpeakerKind.Narrator, text = block.removePrefix(narrator))
    }
    if (block.startsWith(player)) {
        return ParsedSpeaker(ApocalypseStorySpeakerKind.Player, text = block.removePrefix(player))
    }
    val match = Regex("^【角色:([^】]+)】").find(block) ?: return null
    val id = match.groupValues[1].trim().takeIf(String::isNotBlank) ?: return null
    return ParsedSpeaker(
        kind = ApocalypseStorySpeakerKind.Character,
        characterId = id,
        text = block.substring(match.range.last + 1),
    )
}

private fun inferLegacySpeaker(
    block: String,
    partyByName: Map<String, CharacterSettings>,
): ParsedSpeaker {
    // Older saves were plain prose. Only infer a character when the paragraph clearly starts with
    // "名字：" / "名字：“"; otherwise keeping it as narration is safer than showing the wrong portrait.
    partyByName.forEach { (name, character) ->
        val prefixes = listOf("${name}：", "${name}:", "${name}说：", "${name}说:")
        val prefix = prefixes.firstOrNull(block::startsWith)
        if (prefix != null) {
            return ParsedSpeaker(
                kind = ApocalypseStorySpeakerKind.Character,
                characterId = character.characterId,
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
