package com.jiacimu.lulu.study

/** Reflow wrapped Chinese prose; explicit paragraphs and structured rows stay separate. */
internal fun theaterPlanningTypography(raw: String): String {
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val result = StringBuilder()
    val row = Regex("^(?:[-*+•·]\\s*|#{1,6}\\s*|[0-9一二三四五六七八九十]+[、.．)）]\\s*|【|[^。！？.!?：:\\n]{1,24}[：:])")
    fun han(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
    lines.forEachIndexed { index, original ->
        val line = original.trim()
        if (index > 0) {
            val previous = lines[index - 1].trim()
            val last = previous.lastOrNull()
            val first = line.firstOrNull()
            val danglingPunctuation = first != null && first in "，。！？；：、,.!?;）)]”’"
            val continuesChinese = last != null && first != null &&
                (han(last) || last in "，、（(“‘") && (han(first) || danglingPunctuation)
            val reflow = previous.isNotEmpty() && line.isNotEmpty() &&
                !row.containsMatchIn(previous) && !row.containsMatchIn(line) &&
                (continuesChinese || danglingPunctuation)
            if (!reflow) result.append('\n')
        }
        result.append(line)
    }
    return result.toString().trim()
}
