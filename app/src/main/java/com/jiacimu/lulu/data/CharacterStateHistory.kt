package com.jiacimu.lulu.data

import org.json.JSONObject
import java.time.Instant

internal data class CharacterStateHistoryRow(
    val key: String, val at: Instant, val title: String, val detail: String,
    val moment: CompanionPresenceState? = null,
    val decisions: List<CharacterRecordProjection.DecisionMoment> = emptyList(),
    val evidenceIds: List<String> = emptyList(),
    val deletionKind: String = "", val deletionKey: String = "",
)

/** One chronological view of the original snapshots, thoughts and decisions. No new persistence. */
internal object CharacterStateHistory {
    fun entries(characterId: String, history: List<CompanionPresenceState>, cutoff: Instant,
        mind: JSONObject = CharacterInnerLifeStore.snapshot(characterId),
        decisions: List<CharacterRecordProjection.DecisionMoment> = CharacterRecordProjection.decisions(characterId, cutoff),
    ): List<CharacterStateHistoryRow> {
        val rows = mutableListOf<CharacterStateHistoryRow>()
        val consumed = mutableSetOf<String>()
        PresenceHistoryPresentation.entries(history.filter { !it.updatedAt.isAfter(cutoff) }).forEach { entry ->
            val moment = entry.state
            val linked = decisions.filter { it.id !in consumed && (it.at == moment.updatedAt ||
                (moment.provenanceId.isNotBlank() && it.evidenceId == moment.provenanceId &&
                    kotlin.math.abs(java.time.Duration.between(it.at, moment.updatedAt).toMillis()) <= 2_000)) }
            consumed += linked.map { it.id }
            val detail = listOfNotNull(
                moment.innerThought.takeIf(String::isNotBlank),
                moment.momentReflection.takeIf { it.isNotBlank() &&
                    !CharacterHeartVoicePolicy.sameMeaning(it, moment.innerThought) }?.let { "想法：$it" },
                entry.status.takeIf(String::isNotBlank)?.let { "状态：$it" },
                entry.gesture.takeIf(String::isNotBlank)?.let { "动作：$it" },
            ).joinToString("\n")
            rows += CharacterStateHistoryRow("moment:${moment.updatedAt}", moment.updatedAt,
                entry.mood.ifBlank { "当时的心声与状态" }, detail, moment, linked,
                linked.map { it.evidenceId }.filter(String::isNotBlank), "moment")
        }
        decisions.filterNot { it.id in consumed }.forEach { decision ->
            rows += CharacterStateHistoryRow("decision:${decision.id}", decision.at, decision.action, "",
                decisions = listOf(decision), evidenceIds = listOf(decision.evidenceId).filter(String::isNotBlank),
                deletionKind = "decision", deletionKey = decision.id)
        }
        val transitions = mind.optJSONArray("causalTransitions")
        for (index in 0 until (transitions?.length() ?: 0)) {
            val change = transitions?.optJSONObject(index) ?: continue
            val at = runCatching { Instant.parse(change.optString("at")) }.getOrNull() ?: continue
            if (at.isAfter(cutoff)) continue
            val evidenceId = change.optString("evidenceId")
            if (evidenceId.isBlank() || SharedExperienceTimeline.eventsByIds(characterId,
                    listOf(evidenceId)).isEmpty()) continue
            val delta = change.optJSONObject("stateDelta")
            val parts = listOfNotNull(
                change.optString("innerThought").takeIf(String::isNotBlank),
                delta?.optString("selfCorrection")?.takeIf(String::isNotBlank)?.let { "想调整：$it" },
                delta?.optJSONObject("social")?.optString("interpretation")?.takeIf(String::isNotBlank)
                    ?.let { "当时怎样理解这段关系：$it" },
                change.optJSONObject("appraisal")?.optString("tension")?.takeIf(String::isNotBlank)
                    ?.let { "仍在权衡：$it" },
            )
            if (parts.isEmpty()) continue
            val existing = rows.indexOfFirst { it.at == at }
            if (existing >= 0) {
                val row = rows[existing]
                val added = parts.filterNot { part -> row.detail.contains(part) }
                rows[existing] = row.copy(detail = (listOf(row.detail) + added)
                    .filter(String::isNotBlank).joinToString("\n"), evidenceIds = (row.evidenceIds + evidenceId).distinct())
            } else rows += CharacterStateHistoryRow("transition:$at:$index", at, "当时的感想与取舍",
                parts.joinToString("\n"), evidenceIds = listOf(evidenceId),
                deletionKind = "transition", deletionKey = evidenceId)
        }
        fun collect(array: String, timeField: String, kind: String, keyField: String,
                    describe: (JSONObject) -> Pair<String, String>) {
            val items = mind.optJSONArray(array) ?: return
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val at = runCatching { Instant.parse(item.optString(timeField)) }.getOrNull() ?: continue
                // In particular, never show an old message today's revised concern text.
                if (at.isAfter(cutoff)) continue
                val evidence = item.optString("evidenceId")
                if (evidence.isNotBlank() && SharedExperienceTimeline.eventsByIds(characterId,
                        listOf(evidence)).isEmpty()) continue
                val (title, detail) = describe(item)
                if (detail.isBlank()) continue
                val compact = detail.filterNot(Char::isWhitespace)
                if (rows.any { it.at == at && it.detail.filterNot(Char::isWhitespace).contains(compact) }) continue
                rows += CharacterStateHistoryRow("$kind:$at:$index", at, title, detail,
                    evidenceIds = listOf(evidence).filter(String::isNotBlank),
                    deletionKind = kind, deletionKey = item.optString(keyField))
            }
        }
        collect("thoughts", "at", "thought", "thought") { "当时的念头" to it.optString("thought") }
        collect("emotionHistory", "startedAt", "emotion", "feeling") {
            it.optString("feeling").ifBlank { "当时的感受" } to
                listOf(it.optString("feeling"), it.optString("cause")).filter(String::isNotBlank).joinToString(" · ")
        }
        collect("openConcerns", "updatedAt", "concern", "id") {
            "当时挂心的事" to listOf(it.optString("focus"), it.optString("whyItMatters"))
                .filter(String::isNotBlank).joinToString("\n")
        }
        collect("closedConcerns", "closedAt", "closure", "id") {
            "一段心事的结束" to listOf(it.optString("focus"), it.optString("resolution"))
                .filter(String::isNotBlank).joinToString("\n")
        }
        return rows.distinctBy { it.key }.sortedWith(compareByDescending<CharacterStateHistoryRow> { it.at }.thenBy { it.key })
    }

    fun delete(characterId: String, row: CharacterStateHistoryRow) {
        row.decisions.forEach { CharacterInnerLifeStore.forgetDecision(characterId, it.id) }
        when (row.deletionKind) {
            "moment" -> row.moment?.let { CompanionPresenceStore.deleteMoment(characterId, it.updatedAt) }
            "thought" -> CharacterInnerLifeStore.forgetExactThought(characterId, row.deletionKey)
            "emotion" -> CharacterInnerLifeStore.forgetPrivateEmotion(characterId, row.at.toString(), row.deletionKey)
            "concern" -> CharacterInnerLifeStore.deletePrivateConcern(characterId, row.deletionKey)
            "closure" -> CharacterInnerLifeStore.forgetClosedConcern(characterId, row.deletionKey)
            "transition" -> CharacterInnerLifeStore.forgetTransition(characterId, row.deletionKey, row.at)
        }
    }
}
