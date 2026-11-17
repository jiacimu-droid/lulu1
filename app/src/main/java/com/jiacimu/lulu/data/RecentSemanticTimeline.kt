package com.jiacimu.lulu.data

import java.time.Instant

/**
 * Read-only projection of the ONLY authoritative raw timeline.
 *
 * One turn means one actual speaker turn or one coherent activity episode,
 * never one API/perception tick. Raw messages, tools and game steps remain
 * individually persisted, addressable and revocable in SharedExperienceTimeline.
 */
object RecentSemanticTimeline {
    enum class Kind { SPEECH, EXPERIENCE, SINGLE }

    data class Turn(val key: String, val kind: Kind,
                             val events: List<SharedTimelineEvent>) {
        val lastAt: Instant get() = events.last().occurredAt
    }

    /** No text/name interpretation: only producer-assigned IDs and evidence kinds. */
    private fun experienceKey(event: SharedTimelineEvent, relicRuns: Set<String>): String? {
        if (event.source == "game-team-action" && event.sessionId.startsWith("game-session-")) {
            val session = event.sessionId.removePrefix("game-session-")
            // All relic witnesses (actor actions, the legacy ledger and replay)
            // share one physical-run key. The original session uses
            // "relic_escape-<runId>", while ledger IDs use "relic:<runId>:...".
            // Keep island visit grouping separate below.
            if (session.startsWith("relic_escape-")) {
                val run = session.removePrefix("relic_escape-")
                if (run.isNotBlank()) return "game:relic_escape:$run"
            }
            return "game:" + session
        }
        if (event.source == "game-relic" && event.id.startsWith("relic:")) {
            val run = event.id.removePrefix("relic:").substringBefore(':')
            if (run.isNotBlank()) return "game:relic_escape:$run"
        }
        if (event.id.startsWith("game-raw-") && event.channel.contains("迷雾摸金")) {
            // The final replay row contains the true serialized run ID; it is not
            // a second match when the actual run actions are already recorded.
            val run = relicRuns.firstOrNull { id -> event.content.contains(id) }
            if (run != null) return "game:relic_escape:$run"
        }
        if ((event.id.startsWith("game-raw-") || event.id.startsWith("game-solo-")) &&
            event.channel.contains("游戏")) return "game-record:" + event.id
        if (event.source == "meeting" && event.sessionId.isNotBlank())
            return "meeting:" + event.sessionId
        if (event.source == "tool-router" && event.taskId.isNotBlank()) {
            // Background-phone continuation step IDs are derived from the initial
            // task ID. A failed retry belongs to that task, not a new "memory turn".
            val task = event.taskId.replace(Regex("-phone-[0-9]+$"), "")
            return "tool:" + task
        }
        return null
    }

    private fun isConversation(event: SharedTimelineEvent): Boolean {
        if (event.evidenceKind !in setOf(EventEvidenceKind.UserStatement,
                EventEvidenceKind.CharacterStatement)) return false
        return (event.source == "message" && event.sessionId.isNotBlank()) ||
            (event.source == "legacy" && event.sessionId.isNotBlank() && event.channel == "私聊") ||
            event.source.startsWith("game-team-owner-") ||
            event.source == "game-team-companion" ||
            (event.source == "meeting" && event.sessionId.isNotBlank()) ||
            event.source == "watch-together"
    }

    /**
     * A genuine persisted replyBatchId groups character bubbles. Consecutive
     * user messages in the SAME real conversation before a role answer are one
     * incoming turn, even if unrelated tool/game events interleave.
     *
     * The callback is intentionally optional for legacy rows and tests: absent
     * an authenticated batch, separate role utterances are NOT guessed to be
     * one answer based on a timestamp or on identical-looking words.
     */
    fun select(events: List<SharedTimelineEvent>, maxTurns: Int,
               replyBatchId: (SharedTimelineEvent) -> String? = { null }): List<Turn> {
        if (maxTurns <= 0 || events.isEmpty()) return emptyList()
        val ordered = events.sortedWith(compareBy<SharedTimelineEvent> { it.occurredAt }.thenBy { it.id })
        val runIds = ordered.asSequence().filter { it.source == "game-relic" && it.id.startsWith("relic:") }
            .map { it.id.removePrefix("relic:").substringBefore(':') }
            .filter(String::isNotBlank).toSet()
        val groups = linkedMapOf<String, MutableList<SharedTimelineEvent>>()
        val kinds = mutableMapOf<String, Kind>()
        val pendingUser = mutableMapOf<String, String>()
        val idToTurn = mutableMapOf<String, String>()
        val unlinkedPresence = mutableListOf<SharedTimelineEvent>()
        // Persistent island worlds reuse one seed across days. Only continuous
        // play belongs to one visit; an actual long inactivity gap starts the
        // next visit without changing or deleting any original game state.
        val islandLastAction = mutableMapOf<String, Instant>()
        val islandVisit = mutableMapOf<String, Int>()

        fun accept(key: String, kind: Kind, event: SharedTimelineEvent) {
            groups.getOrPut(key) { mutableListOf() }.add(event)
            kinds[key] = kind
            idToTurn[event.id] = key
        }
        for (event in ordered) {
            if (event.source == "presence" && event.evidenceKind == EventEvidenceKind.Inference) {
                unlinkedPresence.add(event)
                continue
            }
            if (isConversation(event)) {
                val session = event.sessionId.ifBlank { "legacy:" + event.channel }
                val key = if (event.evidenceKind == EventEvidenceKind.UserStatement) {
                    pendingUser.getOrPut(session) { "speech:$session:user:${event.id}" }
                } else {
                    pendingUser.remove(session)
                    val batch = replyBatchId(event)?.takeIf(String::isNotBlank)
                    if (batch == null) "speech:$session:role:${event.id}"
                    else "speech:$session:role-batch:$batch"
                }
                accept(key, Kind.SPEECH, event)
                continue
            }
            var key = experienceKey(event, runIds)
            if (key != null && event.source == "game-team-action" &&
                event.sessionId.startsWith("game-session-block_island-")) {
                val last = islandLastAction[event.sessionId]
                if (last != null && java.time.Duration.between(last, event.occurredAt).toMinutes() > 120)
                    islandVisit[event.sessionId] = (islandVisit[event.sessionId] ?: 0) + 1
                islandLastAction[event.sessionId] = event.occurredAt
                key += ":visit:" + (islandVisit[event.sessionId] ?: 0)
            }
            if (key != null) accept(key, Kind.EXPERIENCE, event)
            else accept("single:${event.id}", Kind.SINGLE, event)
        }
        // Subjective snapshots are part of the reply/experience that caused
        // them when their real provenance ID resolves. Never make every mood
        // update another counted turn; otherwise busy autonomous perception
        // evicts actual conversation. Unlinked snapshots remain in raw recall
        // and the separate current-presence context.
        unlinkedPresence.forEach { event ->
            val prefix = "presence-"
            val suffix = "-${event.characterId}"
            val sourceId = if (event.id.startsWith(prefix) && event.id.endsWith(suffix))
                event.id.removePrefix(prefix).removeSuffix(suffix) else ""
            val parent = idToTurn[sourceId]
            if (parent != null) groups[parent]?.add(event)
        }
        return groups.map { (key, items) ->
            Turn(key, kinds.getValue(key), items.sortedWith(
                compareBy<SharedTimelineEvent> { it.occurredAt }.thenBy { it.id }))
        }.sortedWith(compareBy<Turn> { it.lastAt }.thenBy { it.key })
            .takeLast(maxTurns)
    }

    /**
     * A bounded MODEL VIEW, not a new stored fact or a model-authored summary.
     * Speech preserves original words (including multi-bubble replies).
     * An activity gives its opening, most recent steps and verified ending;
     * all omitted action receipts remain retrievable by their original IDs.
     */
    fun render(turn: Turn, budget: Int = 1_600, focus: String = ""): String {
        if (budget < 90 || turn.events.isEmpty()) return ""
        val evidence = turn.events.filter { it.source != "presence" }
        if (evidence.isEmpty()) return ""
        // A short old-episode preview should contain its ACTUAL outcome, not
        // five half-rendered movement receipts with no result.
        val maxMoments = when {
            budget < 260 -> 1
            budget < 500 -> 2
            else -> 5
        }
        val moments = if (turn.kind == Kind.EXPERIENCE && evidence.size > maxMoments) {
            if (maxMoments == 1) evidence.takeLast(1)
            else (evidence.take(1) + evidence.takeLast(maxMoments - 1)).distinctBy { it.id }
        } else evidence
        val heading = when (turn.kind) {
            Kind.SPEECH -> "【完整交流｜${evidence.size}条发言】"
            Kind.EXPERIENCE -> "【同一经历｜${evidence.size}条原始事件；以下为真实片段】"
            Kind.SINGLE -> "【原始事实】"
        }
        val b = StringBuilder(heading)
        val room = budget - heading.length
        if (room <= 50) return ""
        val perEvent = (room / moments.size.coerceAtLeast(1))
            .coerceAtMost(if (turn.kind == Kind.SPEECH) 2_100 else 650).coerceAtLeast(50)
        var written = 0
        for (event in moments) {
            // Very old previews are intentionally compact. The unabridged
            // event ID and revision remain in the original ledger and recall;
            // larger previews retain explicit IDs to aid inspection.
            val prefix = if (budget < 300)
                "\n- [${event.occurredAt}] ${event.speaker}："
            else "\n- [${event.occurredAt}] [${event.channel}] [${event.evidenceKind}]" +
                " eventId=${event.id}@${event.revision} ${event.speaker}："
            val left = budget - b.length - prefix.length
            if (left < 12) break
            val raw = event.evidenceContent.replace("\n", " ")
            val limit = minOf(perEvent, left, raw.length)
            if (limit <= 0) continue
            val span = if (raw.length > limit && focus.isNotBlank())
                OriginalEvidenceSpans.ranked(raw, focus, maxSpans = 6)
                    .maxByOrNull { it.relevance }
                    ?.takeIf { it.relevance > 0 } else null
            val excerpt = span?.text ?: raw
            val offsetNote = if (span != null && span.start > 0 && budget >= 300)
                "【原文字符${span.start + 1}起的片段】" else ""
            val available = (limit - offsetNote.length).coerceAtLeast(0)
            if (available == 0) continue
            b.append(prefix).append(offsetNote).append(excerpt.take(available))
            written++
            if (available < excerpt.length && b.length < budget) b.append("…")
        }
        // A source-linked presence snapshot belongs to the same character
        // reply, not another turn. Label it explicitly as private *past*
        // subjective state; it is not another spoken bubble, verified action,
        // or the character's current mood.
        if (written > 0 && turn.kind == Kind.SPEECH &&
            evidence.any { it.evidenceKind == EventEvidenceKind.CharacterStatement }) {
            val snapshots = turn.events.filter { it.source == "presence" &&
                it.evidenceKind == EventEvidenceKind.Inference }
            val snapshot = snapshots.lastOrNull()
            if (snapshot != null) {
                val prefix = "\n- 【本人当时未公开的主观状态/心声，非对外发言或当前事实】" +
                    " eventId=${snapshot.id}@${snapshot.revision}："
                val left = budget - b.length - prefix.length
                if (left >= 32) {
                    b.append(prefix).append(snapshot.evidenceContent.take(left))
                }
            }
        }
        return if (written == 0) "" else b.toString()
    }
}
