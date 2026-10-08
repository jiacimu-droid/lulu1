package com.jiacimu.lulu.study

import org.json.JSONObject

/** Recover complete chapter objects from an interrupted JSON array without accepting a truncated object. */
internal fun theaterChapterJsonFragments(raw: String): List<JSONObject> {
    val results = mutableListOf<JSONObject>()
    val stack = mutableListOf<Int>()
    var inString = false
    var escaped = false
    raw.forEachIndexed { index, char ->
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
        } else when (char) {
            '"' -> inString = true
            '{' -> stack += index
            '}' -> {
                val opening = if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else -1
                if (opening >= 0) {
                    val fragment = runCatching { JSONObject(raw.substring(opening, index + 1)) }.getOrNull()
                    if (fragment != null && listOf(
                            "outline", "规划", "剧情", "内容", "summary", "details",
                            "sceneBeats", "spotlight", "events", "keyEvents", "具体事件",
                        ).any { fragment.has(it) && !fragment.isNull(it) }) {
                        results += fragment
                    }
                }
            }
        }
    }
    return results
}

/** Providers sometimes write readable chapter headings instead of JSON. Keep complete sections. */
internal fun theaterChapterMarkdownSections(raw: String): List<Pair<Int, String>> {
    val heading = Regex("""(?m)^[ \t]*(?:#{1,5}[ \t]*)?(?:【[ \t]*)?第[ \t]*([0-9一二三四五六七八九十]+)[ \t]*章(?:[ \t]*】)?[ \t]*[:：、.．—-]?[ \t]*([^\r\n]*)""")
    val matches = heading.findAll(raw).toList()
    fun chapterNumber(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        val digits = "一二三四五六七八九"
        if (token == "十") return 10
        val ten = token.indexOf('十')
        if (ten >= 0) {
            val tens = if (ten == 0) 1 else digits.indexOf(token.first()).takeIf { it >= 0 }?.plus(1) ?: return null
            val units = token.getOrNull(ten + 1)?.let { digits.indexOf(it).takeIf { index -> index >= 0 }?.plus(1) } ?: 0
            return tens * 10 + units
        }
        return digits.indexOf(token.singleOrNull()).takeIf { it >= 0 }?.plus(1)
    }
    return matches.mapIndexedNotNull { index, match ->
        val number = chapterNumber(match.groupValues[1]) ?: return@mapIndexedNotNull null
        val body = raw.substring(match.range.last + 1, matches.getOrNull(index + 1)?.range?.first ?: raw.length).trim()
        if (body.length < 12) return@mapIndexedNotNull null
        number to (match.groupValues[2].trim().takeIf(String::isNotBlank)?.let { "$it\n$body" } ?: body)
    }
}
