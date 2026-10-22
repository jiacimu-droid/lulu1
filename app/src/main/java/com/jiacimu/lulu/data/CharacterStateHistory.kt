package com.jiacimu.lulu.data

import org.json.JSONObject
import java.time.Instant

internal data class CharacterStateHistoryRow(
    val key: String, val at: Instant, val title: String, val detail: String,
    val moment: CompanionPresenceState? = null,
    val decisions: List<CharacterRecordProjection.DecisionMoment> = emptyList(),
    val evidenceIds: List<String> = emptyList(),
    val deletionKind: String = "", val deletionKey: String = "",
    val extraDeletions: List<Pair<String, String>> = emptyList(),
    val mergedMomentTimes: List<Instant> = emptyList(),
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
                // A/B labels are intentionally allowed to echo a full voice:
                // concise choices and their narrative are different functions.
                moment.momentReflection.takeIf(String::isNotBlank)?.let { text -> text.lineSequence().filter(String::isNotBlank)
                    .joinToString("\n") { "想法：$it" } },
                entry.status.takeIf(String::isNotBlank)?.let { "状态：$it" },
                entry.gesture.takeIf(String::isNotBlank)?.let { "动作：$it" },
            ).joinToString("\n")
            rows += CharacterStateHistoryRow("moment:${moment.updatedAt}", moment.updatedAt,
                entry.mood.ifBlank { "当时的心声与状态" }, detail, moment, linked,
                (linked.map { it.evidenceId } + moment.provenanceId)
                    .filter(String::isNotBlank).distinct(), "moment")
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
            val concurrentIdeas = change.optJSONObject("appraisal")?.optJSONArray("privateIdeas")
            val ideaLines = (0 until (concurrentIdeas?.length() ?: 0))
                .mapNotNull { i -> concurrentIdeas?.optString(i)?.trim()?.takeIf(String::isNotBlank) }
                .distinct().map { "想法：$it" }
            val parts = listOfNotNull(
                change.optString("innerThought").takeIf(String::isNotBlank),
                delta?.optString("selfCorrection")?.takeIf(String::isNotBlank)?.let { "想调整：$it" },
                delta?.optJSONObject("social")?.optString("interpretation")?.takeIf(String::isNotBlank)
                    ?.let { "当时怎样理解这段关系：$it" },
                change.optJSONObject("appraisal")?.optString("tension")?.takeIf(String::isNotBlank)
                    ?.let { "仍在权衡：$it" },
            )
            val authoredParts = parts + ideaLines
            if (authoredParts.isEmpty()) continue
            // The reflection writer and the presence writer can persist
            // the SAME causal event a few seconds apart. Evidence identity,
            // not identical wall-clock timestamps, determines ownership.
            val existing = rows.indexOfFirst { row ->
                row.at == at || (evidenceId.isNotBlank() &&
                    (evidenceId in row.evidenceIds || row.moment?.provenanceId == evidenceId) &&
                    kotlin.math.abs(java.time.Duration.between(row.at, at).toMillis()) <= 60_000)
            }
            if (existing >= 0) {
                val row = rows[existing]
                val voice = row.moment?.innerThought.orEmpty()
                val added = authoredParts.filterNot { part ->
                    row.detail.contains(part) ||
                        (!part.startsWith("想法：") && voice.isNotBlank() &&
                            CharacterHeartVoicePolicy.sameMeaning(part, voice))
                }
                rows[existing] = row.copy(detail = (listOf(row.detail) + added)
                    .filter(String::isNotBlank).joinToString("\n"), evidenceIds = (row.evidenceIds + evidenceId).distinct(),
                    extraDeletions = (row.extraDeletions + ("transition" to "$evidenceId|$at")).distinct())
            } else rows += CharacterStateHistoryRow("transition:$at:$index", at, "当时的感想与取舍",
                authoredParts.joinToString("\n"), evidenceIds = listOf(evidenceId),
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
                val existing = rows.indexOfFirst { row ->
                    row.at == at || (evidence.isNotBlank() && evidence in row.evidenceIds &&
                        kotlin.math.abs(java.time.Duration.between(row.at, at).toMillis()) <= 60_000)
                }
                if (existing >= 0) {
                    val row = rows[existing]
                    val alreadyExpressed = row.detail.contains(detail) ||
                        CharacterHeartVoicePolicy.sameMeaning(detail, row.moment?.innerThought.orEmpty()) ||
                        (kind == "emotion" && (title == row.title ||
                            CharacterHeartVoicePolicy.sameMeaning(title, row.title)))
                    rows[existing] = row.copy(
                        detail = if (alreadyExpressed) row.detail
                            else listOf(row.detail, "$title：$detail").filter(String::isNotBlank).joinToString("\n"),
                        evidenceIds = (row.evidenceIds + evidence).filter(String::isNotBlank).distinct(),
                        extraDeletions = (row.extraDeletions + (kind to if (kind == "emotion")
                            "$at|${item.optString(keyField)}" else item.optString(keyField))).distinct())
                    continue
                }
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
        return compactRepeatedMoments(rows.distinctBy { it.key })
            .sortedWith(compareByDescending<CharacterStateHistoryRow> { it.at }.thenBy { it.key })
    }


    /**
     * A single authored heart voice may have several projections: a presence
     * snapshot, a causal transition and a decision record. Older versions also
     * saved near-identical snapshots seconds apart. Collapse those *read-only*
     * projections, not their underlying evidence, and never collapse distinct
     * changes merely because they occurred in the same minute.
     */
    internal fun compactRepeatedMoments(input: List<CharacterStateHistoryRow>): List<CharacterStateHistoryRow> {
        val output = mutableListOf<CharacterStateHistoryRow>()
        fun voiceOf(row: CharacterStateHistoryRow): String =
            row.moment?.innerThought?.trim().orEmpty().ifBlank {
                row.detail.lineSequence().map(String::trim).firstOrNull {
                    it.length >= 12 && !it.startsWith("状态：") &&
                        !it.startsWith("动作：") && !it.startsWith("想法：") &&
                        !it.startsWith("当时的念头：") && !it.startsWith("缘由：") &&
                        !it.startsWith("结果：") && !it.startsWith("仍在权衡：") &&
                        !it.startsWith("想调整：") && !it.startsWith("当时怎样理解")
                }.orEmpty()
            }
        fun equivalent(a: String, b: String) =
            a.isNotBlank() && b.isNotBlank() && CharacterHeartVoicePolicy.sameMeaning(a, b)
        fun supportingDeletion(row: CharacterStateHistoryRow): Pair<String, String>? =
            when (row.deletionKind) {
                "transition" -> "transition" to "${row.deletionKey}|${row.at}"
                "emotion" -> "emotion" to "${row.at}|${row.deletionKey}"
                "thought", "concern", "closure" -> row.deletionKind to row.deletionKey
                else -> null
            }
        // A "moment" is one authored psychological/decision event, not one
        // JSON record. Build ONE read-only card from its projections.
        for (row in input.sortedBy { it.at }) {
            val same = output.indexOfLast { old ->
                val elapsedMs = kotlin.math.abs(java.time.Duration.between(old.at, row.at).toMillis())
                val nearby = elapsedMs <= 120_000
                // A later independent decision can reference the SAME old
                // evidence. Do not swallow it just because the IDs match.
                val sameExecutionWindow = elapsedMs <= 20_000
                val overlap = old.evidenceIds.any { it.isNotBlank() && it in row.evidenceIds }
                val oldVoice = voiceOf(old)
                val nextVoice = voiceOf(row)
                val sameVoice = equivalent(oldVoice, nextVoice)
                val fingerprint = old.moment?.innerThoughtFingerprint.orEmpty()
                val sameFingerprint = fingerprint.isNotBlank() &&
                    fingerprint == row.moment?.innerThoughtFingerprint
                // Two different inner voices about the same source are genuine
                // changes, not duplicates. Do not merge merely by clock minute.
                nearby && (sameVoice || sameFingerprint ||
                    (sameExecutionWindow && overlap &&
                        // A decision's structured thoughts are supporting fields
                        // of its presence moment, not another independently
                        // generated "present". Two *actual* presence changes
                        // with different voices remain separate.
                        (old.moment == null || row.moment == null))))
            }
            if (same < 0) {
                output += row
                continue
            }
            val old = output[same]
            val preferred = when {
                row.moment != null && old.moment == null -> row
                row.moment == null && old.moment != null -> old
                voiceOf(row).length > voiceOf(old).length -> row
                else -> old
            }
            val other = if (preferred === row) old else row
            val mergedText = preferred.detail.lines().filter(String::isNotBlank).toMutableList()
            for (part in other.detail.lines().filter(String::isNotBlank)) {
                val partValue = part.substringAfter('：', part).trim()
                val echo = mergedText.any { existing ->
                    existing == part || existing.contains(part) || part.contains(existing) ||
                        equivalent(existing.substringAfter('：', existing).trim(), partValue)
                }
                if (!echo) mergedText += part
            }
            val allMomentTimes = (listOfNotNull(old.moment?.updatedAt, row.moment?.updatedAt) +
                old.mergedMomentTimes + row.mergedMomentTimes).distinct()
            output[same] = preferred.copy(
                detail = mergedText.joinToString("\n"),
                decisions = (old.decisions + row.decisions).distinctBy { it.id },
                evidenceIds = (old.evidenceIds + row.evidenceIds).distinct(),
                extraDeletions = (old.extraDeletions + row.extraDeletions +
                    listOfNotNull(supportingDeletion(other))).distinct(),
                mergedMomentTimes = allMomentTimes.filter { it != preferred.moment?.updatedAt },
            )
        }
        return output
    }

    /** A single read model for the "此刻" card; persisted data remains owned by
     * its original stores. Every section needs distinct information, not a
     * renamed copy of the same inner monologue. */
    internal data class MomentDisplay(
        val voice: String,
        val mood: String,
        val status: String,
        val gesture: String,
        val otherThoughts: List<Pair<String, String>>,
    )

    internal fun display(row: CharacterStateHistoryRow): MomentDisplay {
        val lines = row.detail.lines().map(String::trim).filter(String::isNotBlank)
        val labels = setOf("状态", "动作", "想法", "当时的念头",
            "仍在权衡", "想调整", "当时怎样理解这段关系", "当时挂心的事")
        val voice = row.moment?.innerThought?.trim().orEmpty().ifBlank {
            lines.firstOrNull { it.substringBefore('：') !in labels }.orEmpty()
        }
        val mood = row.moment?.mood.orEmpty()
            .split(Regex("\\s*[·•；;]+\\s*")).map(String::trim)
            .filter(String::isNotBlank).distinct().joinToString(" · ")
        val status = lines.firstOrNull { it.startsWith("状态：") }
            ?.removePrefix("状态：").orEmpty()
        val gesture = lines.firstOrNull { it.startsWith("动作：") }
            ?.removePrefix("动作：").orEmpty()
        // These are the A/B impulses the character entertained. Analysis,
        // execution reasons, uncertainties and audit results remain available
        // in their original causal/evidence records; they are not extra voices.
        val extras = mutableListOf<Pair<String, String>>()
        var readingLegacyIdea = false
        for (line in lines) {
            val label = line.substringBefore('：')
            if (label in setOf("想法", "当时的念头")) {
                readingLegacyIdea = true
            } else if (label in labels) {
                readingLegacyIdea = false
                continue
            } else if (voice.lines().any { it.trim() == line }) {
                readingLegacyIdea = false
                continue
            } else if (!line.contains('：') && row.moment != null) {
                // Old cards appended a second private thought as plain prose.
                // Preserve it as an idea, not as an invented second voice.
                readingLegacyIdea = true
            } else {
                readingLegacyIdea = false
                continue
            }
            if (!readingLegacyIdea) continue
            val content = if (label in setOf("想法", "当时的念头"))
                line.substringAfter('：').trim() else line
            if (content.isBlank() || extras.any {
                    CharacterHeartVoicePolicy.sameMeaning(it.second, content)
                }) continue
            extras += "想法" to content
        }
        return MomentDisplay(voice, mood, status, gesture, extras)
    }

    fun delete(characterId: String, row: CharacterStateHistoryRow) {
        row.mergedMomentTimes.forEach { CompanionPresenceStore.deleteMoment(characterId, it) }
        row.decisions.forEach { CharacterInnerLifeStore.forgetDecision(characterId, it.id) }
        fun remove(kind: String, key: String) { when (kind) {
            "moment" -> row.moment?.let { CompanionPresenceStore.deleteMoment(characterId, it.updatedAt) }
            "thought" -> CharacterInnerLifeStore.forgetExactThought(characterId, key)
            "emotion" -> {
                val stamped = key.substringBefore('|', "")
                val exactAt = runCatching { Instant.parse(stamped) }.getOrNull()
                CharacterInnerLifeStore.forgetPrivateEmotion(characterId,
                    (exactAt ?: row.at).toString(), if (exactAt == null) key else key.substringAfter('|'))
            }
            "concern" -> CharacterInnerLifeStore.deletePrivateConcern(characterId, key)
            "closure" -> CharacterInnerLifeStore.forgetClosedConcern(characterId, key)
            "transition" -> {
                val exactAt = runCatching { Instant.parse(key.substringAfterLast('|')) }.getOrNull()
                CharacterInnerLifeStore.forgetTransition(characterId,
                    if (exactAt == null) key else key.substringBeforeLast('|'), exactAt ?: row.at)
            }
        } }
        row.extraDeletions.forEach { (kind, key) -> remove(kind, key) }
        remove(row.deletionKind, row.deletionKey)
    }
}
