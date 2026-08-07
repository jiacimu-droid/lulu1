package com.jiacimu.lulu.system

/**
 * Lightweight local guard against a character re-sending the same explanation from recent turns.
 * It does not try to understand personality or rewrite prose. It only detects strong textual overlap
 * so the model can be asked to continue from the already-established state instead of restarting it.
 */
internal object ChatContinuityGuard {
    data class Report(
        val repetitive: Boolean,
        val repeatedSamples: List<String> = emptyList(),
        val score: Double = 0.0,
    )

    fun inspect(history: String, candidate: String): Report {
        val cleanCandidate = visibleText(candidate)
        if (cleanCandidate.length < 18 || history.isBlank()) return Report(false)

        val recentBodies = recentBodies(history)
        if (recentBodies.isEmpty()) return Report(false)

        val candidateParts = splitParts(cleanCandidate)
        var highest = 0.0
        val repeated = mutableListOf<String>()
        candidateParts.forEach { part ->
            if (part.length < 14) return@forEach
            val best = recentBodies.maxOfOrNull { old -> similarity(part, old) } ?: 0.0
            highest = maxOf(highest, best)
            if (best >= 0.62) repeated += part.take(90)
        }

        val wholeScore = recentBodies
            .takeLast(8)
            .maxOfOrNull { old -> similarity(cleanCandidate, old) }
            ?: 0.0
        highest = maxOf(highest, wholeScore)

        val repeatedLength = repeated.sumOf(String::length)
        val repeatedShare = repeatedLength.toDouble() / cleanCandidate.length.coerceAtLeast(1)
        val repetitive = highest >= 0.72 ||
            repeated.size >= 2 ||
            (highest >= 0.58 && repeatedShare >= 0.38)
        return Report(repetitive, repeated.distinct().take(4), highest)
    }

    fun repairInstruction(report: Report): String {
        val samples = report.repeatedSamples.joinToString("；")
        return buildString {
            append("连续性校验没有通过：这次草稿和刚才已经说过的话重复过多。不要重述旧结论、旧提醒、旧情绪，也不要重新从头解释。把上一轮当作已经真实说出口并已经发生的状态，只回应这一刻新增的信息，让关系、动作和话题继续向前推进。")
            if (samples.isNotBlank()) append("尤其不要再次展开这些已经表达过的内容：$samples。")
            append("如果当前只需要一句新的回应，就只说这一句；如果有几个自然停顿，再用 ⟪BUBBLE⟫ 分成多个短气泡。")
        }
    }

    /**
     * Last-resort safety net after one repair attempt. Only whole bubbles with very strong overlap
     * are removed; short acknowledgements and genuinely new tool results are intentionally kept.
     */
    fun keepNovelBubbles(history: String, candidate: String): String {
        if (history.isBlank() || candidate.isBlank()) return candidate.trim()
        val recent = recentBodies(history)
        if (recent.isEmpty()) return candidate.trim()

        val actionDirectives = Regex("⟪(?!BUBBLE)[^⟫]+⟫", RegexOption.IGNORE_CASE)
            .findAll(candidate)
            .map { it.value }
            .toList()
        val body = candidate
            .replace(actionDirectives, "")
            .replace("⟪BUBBLE⟫", "\n")
        val kept = body
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter { bubble ->
                bubble.length < 14 || (recent.maxOfOrNull { old -> similarity(bubble, old) } ?: 0.0) < 0.70
            }
            .toList()
        if (kept.isEmpty()) return ""
        return buildString {
            actionDirectives.forEach { directive -> append(directive) }
            append(kept.joinToString("⟪BUBBLE⟫"))
        }.trim()
    }

    private fun recentBodies(history: String): List<String> = history
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { line -> line.substringAfter('：', line).trim() }
        .filter { it.length >= 12 }
        .takeLast(14)
        .toList()

    private fun splitParts(text: String): List<String> = text
        .replace("⟪BUBBLE⟫", "\n")
        .replace(Regex("[。！？!?；;]+"), "\n")
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .toList()

    private fun visibleText(text: String): String = text
        .replace(Regex("⟪[^⟫]+⟫"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun similarity(left: String, right: String): Double {
        val a = normalized(left)
        val b = normalized(right)
        if (a.length < 8 || b.length < 8) return 0.0
        if (a == b) return 1.0
        val gramsA = ngrams(a)
        val gramsB = ngrams(b)
        if (gramsA.isEmpty() || gramsB.isEmpty()) return 0.0
        val common = gramsA.intersect(gramsB).size.toDouble()
        val containment = common / minOf(gramsA.size, gramsB.size).coerceAtLeast(1)
        val union = (gramsA.size + gramsB.size - common).coerceAtLeast(1.0)
        val jaccard = common / union
        return maxOf(containment * 0.72 + jaccard * 0.28, jaccard)
    }

    private fun normalized(value: String): String = value
        .lowercase()
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

    private fun ngrams(value: String, size: Int = 3): Set<String> {
        if (value.length <= size) return setOf(value)
        return buildSet {
            for (index in 0..value.length - size) add(value.substring(index, index + size))
        }
    }
}
