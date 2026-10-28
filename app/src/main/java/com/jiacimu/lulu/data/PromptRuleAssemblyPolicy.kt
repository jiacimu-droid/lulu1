package com.jiacimu.lulu.data

/**
 * One authoritative static rule per stable ID in a model request.
 *
 * Only authored rule modules belong here. Never deduplicate user utterances,
 * world-book placement, memories, source evidence or action receipts by text:
 * repetition there may be a real later correction or a distinct event.
 */
internal object PromptRuleAssemblyPolicy {
    data class Rule(val id: String, val text: String)

    fun assemble(rules: List<Rule>): String {
        val seen = linkedMapOf<String, String>()
        for (rule in rules) {
            require(rule.id.isNotBlank()) { "A static prompt rule must have an ID" }
            val body = rule.text.trim()
            if (body.isEmpty()) continue
            val existing = seen[rule.id]
            if (existing == null) {
                seen[rule.id] = body
            } else {
                // Conflicting versions are a developer error, not a reason to
                // silently discard one potentially important instruction.
                require(existing == body) { "Conflicting prompt rule ID: ${rule.id}" }
            }
        }
        return seen.values.joinToString("\n\n")
    }
}
