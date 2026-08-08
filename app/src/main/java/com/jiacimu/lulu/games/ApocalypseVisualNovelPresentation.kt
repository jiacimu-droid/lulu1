package com.jiacimu.lulu.games

import com.jiacimu.lulu.data.CharacterSettings

internal enum class ApocalypseStorySpeakerKind { Narrator, Player, Character }

internal data class ApocalypseStoryPage(
    val speakerKind: ApocalypseStorySpeakerKind,
    val characterId: String? = null,
    val text: String,
)

/**
 * Visual-novel tagged format used by the apocalypse writer.
 *
 * New scenes use one of these prefixes at the beginning of every display beat:
 * 【旁白】 / 【玩家】 / 【角色:<characterId>】
 * Legacy saves without tags remain readable and are treated as narration.
 */
internal fun parseApocalypseStoryPages(
    text: String,
    party: List<CharacterSettings>,
    maxChars: Int = 96,
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
        splitVisualNovelText(cleanText, maxChars).forEach { piece ->
            pages += ApocalypseStoryPage(
                speakerKind = inferred.kind,
                characterId = inferred.characterId,
                text = piece,
            )
        }
    }

    return pages.ifEmpty {
        listOf(ApocalypseStoryPage(ApocalypseStorySpeakerKind.Narrator, text = normalized.take(maxChars)))
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
        val prefixes = listOf("$name：", "$name:", "$name说：", "$name说:")
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

private fun splitVisualNovelText(text: String, maxChars: Int): List<String> {
    val pages = mutableListOf<String>()
    val sentences = text.split(Regex("(?<=[。！？!?；;])\\s*"))
        .map(String::trim)
        .filter(String::isNotBlank)
    var current = ""

    fun push(unit: String) {
        if (unit.isBlank()) return
        if (current.isBlank()) {
            current = unit
        } else if (current.length + unit.length <= maxChars) {
            current += unit
        } else {
            pages += current.trim()
            current = unit
        }
    }

    sentences.forEach { sentence ->
        if (sentence.length <= maxChars) {
            push(sentence)
        } else {
            sentence.split(Regex("(?<=[，,、：:])\\s*"))
                .map(String::trim)
                .filter(String::isNotBlank)
                .flatMap { piece -> if (piece.length <= maxChars) listOf(piece) else piece.chunked(maxChars) }
                .forEach(::push)
        }
    }
    if (current.isNotBlank()) pages += current.trim()
    return pages.ifEmpty { listOf(text.take(maxChars)) }
}
