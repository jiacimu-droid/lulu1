package com.jiacimu.lulu.data

internal data class MemoryPromptCandidate(
    val id: String,
    val section: String,
    val text: String,
    val priority: Int,
    val payload: String = text,
    val sources: Set<String> = emptySet(),
    val originalEvidence: Boolean = false,
    val derivedSummary: Boolean = false,
)

internal data class MemoryWorkingSelection(
    val sections: Map<String, String>,
    val selected: List<String>,
    val omitted: Map<String, String>,
    val usedCharacters: Int,
    val budget: Int,
    val protectedCharacters: Int,
)

/** One budget for the memory family. Core and executable responsibilities are never silently cut. */
internal object MemoryWorkingSetPolicy {
    /** State headers carry authority/time scope. Select their complete blocks,
     * never independent lines that can outlive the qualification above them. */
    fun auxiliaryCandidates(auxiliary: List<Pair<String, String>>): List<MemoryPromptCandidate> = buildList {
        auxiliary.forEachIndexed { group, (label, text) ->
            val block = mutableListOf<String>()
            var index = 0
            fun flush() {
                val body = block.joinToString("\n").trim()
                if (body.isNotBlank()) {
                    add(MemoryPromptCandidate("state:$group:${index++}", label, body, 900 - group * 35))
                }
                block.clear()
            }
            text.lineSequence().forEach { line ->
                // Authored renderers delimit context sections with these
                // headings; this is structure, not interpretation of intent.
                if (line.startsWith("【") && line.contains('】')) flush()
                block += line
            }
            flush()
        }
    }

    fun select(candidates: List<MemoryPromptCandidate>, budget: Int, protectedCharacters: Int = 0,
               sectionLimits: Map<String, Int> = emptyMap()): MemoryWorkingSelection {
        val sections = linkedMapOf<String, MutableList<String>>()
        val omitted = linkedMapOf<String, String>()
        val selected = mutableListOf<String>()
        // Identical words from two different event IDs are two independent facts.
        // Only collapse a payload when its provenance set is also identical.
        val payloadSources = mutableMapOf<String, MutableSet<Set<String>>>()
        val originals = mutableSetOf<String>()
        val sectionCosts = mutableMapOf<String, Int>()
        var used = 0
        val available = (budget - protectedCharacters).coerceAtLeast(0)
        candidates.sortedByDescending { it.priority }.distinctBy { it.id }.forEach { candidate ->
            val key = candidate.payload.trim().replace(Regex("\\s+"), " ")
            val duplicate = (candidate.sources in (payloadSources[key] ?: emptySet())) ||
                (candidate.derivedSummary && candidate.sources.isNotEmpty() &&
                candidate.sources.all(originals::contains))
            val sectionCost = candidate.text.length + if (candidate.section in sections) 1 else candidate.section.length + 1
            val cost = sectionCost + if (candidate.section !in sections && sections.isNotEmpty()) 2 else 0
            val reason = when {
                duplicate -> "同一原文已提供，不重复注入派生摘要"
                used + cost > available -> "统一记忆上下文字数预算"
                (sectionCosts[candidate.section] ?: 0) + sectionCost > (sectionLimits[candidate.section] ?: Int.MAX_VALUE) -> "类别上下文字数预算"
                else -> null
            }
            if (reason != null) omitted[candidate.id] = reason
            else {
                sections.getOrPut(candidate.section) { mutableListOf() }.add(candidate.text)
                used += cost
                sectionCosts[candidate.section] = (sectionCosts[candidate.section] ?: 0) + sectionCost
                payloadSources.getOrPut(key) { mutableSetOf() }.add(candidate.sources.toSet())
                if (candidate.originalEvidence) originals.addAll(candidate.sources)
                selected.add(candidate.id)
            }
        }
        return MemoryWorkingSelection(sections.mapValues { (title, lines) -> title + "\n" + lines.joinToString("\n") },
            selected, omitted, used, budget, protectedCharacters)
    }
}

