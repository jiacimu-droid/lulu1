package com.jiacimu.lulu.games

/**
 * Lightweight local quality gate for apocalypse prose.
 *
 * The writer emits state JSON before the visible scene. On long/complex turns a provider can reach
 * its output cap after the JSON has already parsed successfully, leaving the visible prose ending in
 * the middle of a sentence. That used to be persisted as canon. This gate deliberately avoids any
 * model call: it only decides whether a generated scene looks complete enough to save.
 */
internal data class ApocalypseNarrativeQualityV5(
    val complete: Boolean,
    val reason: String = "",
)

internal fun inspectApocalypseNarrativeV5(text: String): ApocalypseNarrativeQualityV5 {
    val normalized = text.replace("\r\n", "\n").trim()
    if (normalized.isBlank()) return ApocalypseNarrativeQualityV5(false, "正文为空")

    val plain = normalized
        .replace(Regex("【(?:旁白|玩家|角色\\s*[:：]\\s*[^】\\r\\n]+)】"), "")
        .trim()
    if (plain.length < 420) {
        return ApocalypseNarrativeQualityV5(false, "正文只有${plain.length}字，明显短于一幕应有的完整场景")
    }

    val last = plain.lastOrNull { !it.isWhitespace() }
        ?: return ApocalypseNarrativeQualityV5(false, "正文没有有效内容")
    val obviousDanglingEnds = setOf(
        '，', ',', '、', '：', ':', '；', ';',
        '（', '(', '【', '[', '《', '“', '‘',
        '—', '-', '/', '\\',
    )
    if (last in obviousDanglingEnds) {
        return ApocalypseNarrativeQualityV5(false, "正文停在“$last”上，像是生成被截断")
    }

    // An ellipsis can be a legitimate visual-novel ending. A single Chinese ellipsis character is
    // therefore accepted, while the clearly unfinished punctuation above is not.
    val acceptableEnds = setOf('。', '！', '？', '!', '?', '…', '”', '’', '）', ')', '》', '】')
    if (last !in acceptableEnds) {
        return ApocalypseNarrativeQualityV5(false, "正文没有自然句末，最后一个字符是“$last”")
    }

    val pairs = listOf('“' to '”', '‘' to '’', '（' to '）', '《' to '》')
    pairs.forEach { (open, close) ->
        val opens = plain.count { it == open }
        val closes = plain.count { it == close }
        if (opens != closes) {
            return ApocalypseNarrativeQualityV5(false, "正文中的“$open$close”没有闭合，像是中途截断")
        }
    }

    val danglingSpeakerTag = Regex("【(?:旁白|玩家|角色\\s*[:：]?[^】]*)$").containsMatchIn(normalized)
    if (danglingSpeakerTag) {
        return ApocalypseNarrativeQualityV5(false, "正文停在未完成的说话人标签上")
    }

    return ApocalypseNarrativeQualityV5(true)
}

internal fun apocalypseNarrativeLooksCompleteV5(text: String): Boolean =
    inspectApocalypseNarrativeV5(text).complete
