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
    maxChars: Int = 118,
): List<ApocalypseStoryPage> {
    // Older builds appended a client-generated "【旁白】入库清点：..." system audit to the prose.
    // It was never story content, so strip it before pagination. New builds no longer create it.
    val normalized = stripLegacyApocalypseInventoryAuditV5(text)
        .replace("\r\n", "\n")
        .trim()
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
 * Prefer complete sentences over a mechanically even character count. A page may grow beyond the
 * preferred limit when that keeps one natural sentence intact. Only a genuinely oversized sentence
 * falls back to commas/secondary pauses. Every returned piece remains an exact substring of [text].
 */
private fun splitVisualNovelTextPreservingCharacters(text: String, maxChars: Int): List<String> {
    if (text.isEmpty()) return emptyList()
    val preferred = maxChars.coerceAtLeast(48)
    if (text.length <= preferred) return listOf(text)

    val hardSentenceLimit = (preferred * 1.55f).toInt().coerceAtLeast(preferred + 24)
    val sentenceEnds = setOf('。', '！', '？', '!', '?', '；', ';', '…', '\n')
    val closers = setOf('”', '’', '）', ')', '】', ']', '》')

    val sentences = mutableListOf<String>()
    var start = 0
    var index = 0
    while (index < text.length) {
        if (text[index] in sentenceEnds) {
            var end = index + 1
            while (end < text.length && (text[end] in sentenceEnds || text[end] in closers)) end += 1
            sentences += text.substring(start, end)
            start = end
            index = end
        } else {
            index += 1
        }
    }
    if (start < text.length) sentences += text.substring(start)

    val result = mutableListOf<String>()
    val page = StringBuilder()

    fun flushPage() {
        if (page.isNotEmpty()) {
            result += page.toString()
            page.clear()
        }
    }

    sentences.filter(String::isNotEmpty).forEach { sentence ->
        if (sentence.length > hardSentenceLimit) {
            flushPage()
            result += splitOversizedVisualSentence(sentence, preferred, hardSentenceLimit)
            return@forEach
        }

        if (page.isEmpty()) {
            page.append(sentence)
        } else if (page.length + sentence.length <= preferred) {
            page.append(sentence)
        } else {
            flushPage()
            page.append(sentence)
        }
    }
    flushPage()

    return result.ifEmpty { listOf(text) }
}

private fun splitOversizedVisualSentence(
    sentence: String,
    preferred: Int,
    hardLimit: Int,
): List<String> {
    val softStops = setOf('，', ',', '、', '：', ':', '）', ')', ']', '】', '》', '”', '’')
    val pieces = mutableListOf<String>()
    var start = 0
    while (start < sentence.length) {
        if (sentence.length - start <= hardLimit) {
            pieces += sentence.substring(start)
            break
        }

        val target = (start + preferred).coerceAtMost(sentence.length)
        val minCut = (start + (preferred * .68f).toInt()).coerceAtMost(target)
        var cut = -1
        for (index in target - 1 downTo minCut) {
            if (sentence[index] in softStops) {
                cut = index + 1
                break
            }
        }
        if (cut <= start) cut = (start + hardLimit).coerceAtMost(sentence.length)
        pieces += sentence.substring(start, cut)
        start = cut
    }
    return pieces
}
