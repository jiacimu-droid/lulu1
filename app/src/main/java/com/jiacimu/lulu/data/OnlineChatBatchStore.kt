package com.jiacimu.lulu.data

import android.content.Context

/** Durable chat turns wait for the last user bubble; an unsent older reply must yield to newer input. */
internal object OnlineChatBatchStore {
    // One quiet window per text bubble. Eight seconds gives slower typing time
    // to finish a follow-up bubble; calls use a separate speech turn path.
    // Never grow the wait with message count or trigger the paid API mid-burst.
    // After a sent bubble, observe locally for a short new-draft grace.
    // A genuinely focused, nonempty next draft takes priority over the clock.
    const val QUIET_MILLIS = 8_000L // process-death / no composer-state fallback
    const val POST_SEND_OBSERVE_MILLIS = 4_000L
    const val DRAFT_IDLE_FALLBACK_MILLIS = 60_000L
    data class Batch(val revision: Long, val dueAtMillis: Long)
    private val reading = mutableSetOf<Pair<String, Long>>()
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("lulu_online_chat_batches", Context.MODE_PRIVATE)

    @Synchronized fun next(context: Context, characterId: String, collectMessages: Boolean, now: Long = System.currentTimeMillis()): Batch {
        val p = prefs(context)
        // The last user bubble determines when a queued turn can start.
        // Waking or autonomous ticks must NOT shorten/reset a user's deadline.
        // A message during a claimed batch belongs to a NEW reply revision.
        val pendingRevision = p.getLong("revision:$characterId", 0)
        if (p.contains("due:$characterId") && (characterId to pendingRevision) in reading) {
            // An unrelated online-life/manual wake must NEVER supersede an
            // already-paid message generation. Only an actual new user bubble
            // may open the next revision (onUserBubble owns that transition).
            return Batch(pendingRevision, p.getLong("due:$characterId", now))
        }
        if (p.contains("due:$characterId") && (characterId to pendingRevision) !in reading) {
            val hasUserBubbles = p.getBoolean("messageWindow:$characterId", false)
            if (collectMessages && !hasUserBubbles) {
                // An autonomous background tick may have left an immediate
                // non-message batch pending. The FIRST chat bubble MUST turn
                // that into a real quiet waiting window; never reuse the 0ms due.
                val due = now + QUIET_MILLIS
                check(p.edit().putLong("due:$characterId", due)
                    .putBoolean("messageWindow:$characterId", true).commit()) { "在线首条消息等待期保存失败" }
                return Batch(pendingRevision, due)
            }
            return Batch(pendingRevision, p.getLong("due:$characterId", now))
        }
        val revision = pendingRevision + 1
        val due = if (collectMessages) now + QUIET_MILLIS else now
        check(p.edit().putLong("revision:$characterId", revision).putLong("due:$characterId", due)
            .putBoolean("messageWindow:$characterId", collectMessages).commit()) { "在线消息批次保存失败" }
        return Batch(revision, due)
    }

    /** Extend a waiting turn from each genuine incoming bubble, without starting another model. */
    @Synchronized fun onUserBubble(
        context: Context, characterId: String, atMillis: Long = System.currentTimeMillis(),
    ): Batch {
        val p = prefs(context)
        val revision = p.getLong("revision:$characterId", 0L)
        if (p.contains("due:$characterId") && (characterId to revision) !in reading) {
            // Another bubble means the user may still be typing. The quiet
            // period restarts from this latest persisted bubble, always 8s.
            val due = maxOf(p.getLong("due:$characterId", atMillis), atMillis + QUIET_MILLIS)
            check(p.edit().putLong("due:$characterId", due)
                .putBoolean("messageWindow:$characterId", true).commit()) {
                "延长本轮消息静默等待失败"
            }
            return Batch(revision, due)
        }
        // If a model was already reading, newer user input supersedes its
        // unsent decision. Every new bubble gets the same fixed quiet time.
        if (p.contains("due:$characterId") && (characterId to revision) in reading) {
            val nextRevision = revision + 1
            val due = atMillis + QUIET_MILLIS
            check(p.edit().putLong("revision:$characterId", nextRevision)
                .putLong("due:$characterId", due)
                .putBoolean("messageWindow:$characterId", true).commit()) {
                "生成中的连续消息保存失败"
            }
            return Batch(nextRevision, due)
        }
        return next(context, characterId, collectMessages = true, now = atMillis)
    }

    /**
     * ONLY a local, focused composer edit: never calls the model and never
     * changes a queued revision. A slow owner can compose for as long as they
     * keep actually editing, while 60 seconds of inactivity is an intentional
     * escape hatch for abandoned drafts (including after a process death).
     */
    @Synchronized fun onComposerEdit(
        context: Context, characterId: String, atMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val p = prefs(context)
        val revision = p.getLong("revision:$characterId", 0L)
        if (!p.getBoolean("messageWindow:$characterId", false) ||
            !p.contains("due:$characterId") || (characterId to revision) in reading)
            return false
        val existing = p.getLong("due:$characterId", 0L)
        // Refresh at most about every 30 seconds during uninterrupted typing;
        // checking keyboard state is free, writing SharedPreferences for every
        // keypress is unnecessary and would worsen the old keyboard lag.
        if (existing - atMillis > 30_000L) return false
        val next = maxOf(existing, atMillis + DRAFT_IDLE_FALLBACK_MILLIS)
        if (next <= existing) return false
        p.edit().putLong("due:$characterId", next).apply()
        return true
    }

    /** The composer is empty, unfocused or no longer being edited.
     * Shorten a not-yet-claimed chat's wait to a few seconds, rather than
     * forcing an extra eight seconds after a message was already sent.
     * Does NOT cancel or start any paid model request. */
    @Synchronized fun onComposerIdle(
        context: Context, characterId: String, atMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val p = prefs(context)
        val revision = p.getLong("revision:$characterId", 0L)
        if (!p.getBoolean("messageWindow:$characterId", false) ||
            !p.contains("due:$characterId") || (characterId to revision) in reading)
            return false
        val old = p.getLong("due:$characterId", 0L)
        val shortened = minOf(old, atMillis + POST_SEND_OBSERVE_MILLIS)
        if (shortened >= old) return false
        p.edit().putLong("due:$characterId", shortened).apply()
        return true
    }

    /** A passive wake is already represented by the pending user-message
     * observation. Do not buy a second perception before that chat is read.
     * Expired, abandoned windows stop blocking after a bounded recovery span. */
    @Synchronized fun shouldDeferBackgroundWake(
        context: Context, characterId: String, nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val p = prefs(context)
        if (!p.getBoolean("messageWindow:$characterId", false)) return false
        val due = p.getLong("due:$characterId", 0L)
        if (due <= 0L) return false
        val revision = p.getLong("revision:$characterId", 0L)
        if ((characterId to revision) in reading) return true
        return nowMillis <= due + 90_000L
    }

    /**
     * A single burst of bubbles has ONE queued network worker, not one worker
     * per new bubble. The due timestamp can still extend while it is waiting.
     * A new revision after an actual claim may enqueue its own work.
     */
    @Synchronized fun markEnqueued(context: Context, characterId: String, revision: Long): Boolean {
        val p = prefs(context)
        if (!isCurrent(context, characterId, revision)) return false
        if (!p.contains("due:$characterId")) return false
        if (p.getLong("enqueued:$characterId", -1L) == revision) return false
        return p.edit().putLong("enqueued:$characterId", revision).commit()
    }

    @Synchronized fun unmarkEnqueued(context: Context, characterId: String, revision: Long) {
        val p = prefs(context)
        if (p.getLong("enqueued:$characterId", -1L) == revision) {
            p.edit().remove("enqueued:$characterId").commit()
        }
    }

    /** Claim under the perception mutex; pending messages during this reply open the next window. */
    @Synchronized fun claim(
        context: Context,
        characterId: String,
        revision: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val p = prefs(context)
        if (!isCurrent(context, characterId, revision) || !p.contains("due:$characterId")) return false
        // Closing the race between a sleeping worker and another incoming
        // bubble: even a worker with the correct revision cannot read early.
        if (now < p.getLong("due:$characterId", Long.MAX_VALUE)) return false
        return reading.add(characterId to revision)
    }

    /** A cancelled worker retains its durable deadline so WorkManager can resume the unread batch. */
    @Synchronized fun finish(context: Context, characterId: String, revision: Long, completed: Boolean = true) {
        reading.remove(characterId to revision)
        if (completed && isCurrent(context, characterId, revision)) {
            check(prefs(context).edit().remove("due:$characterId")
                .remove("messageWindow:$characterId")
                .remove("enqueued:$characterId").commit()) { "在线消息读取状态保存失败" }
        }
    }

    fun isCurrent(context: Context, characterId: String, revision: Long) =
        prefs(context).getLong("revision:$characterId", 0) == revision

    /** Independent self-reflection waits for an unfinished user-message turn. */
    @Synchronized fun pendingDueAt(context: Context, characterId: String): Long? =
        prefs(context).getLong("due:$characterId", 0L).takeIf { it > 0L }

    fun dueAt(context: Context, characterId: String, revision: Long): Long? =
        if (isCurrent(context, characterId, revision)) prefs(context).getLong("due:$characterId", 0L).takeIf { it > 0L }
        else null

    @Synchronized fun cancel(context: Context, characterId: String) {
        val p = prefs(context)
        reading.removeAll { it.first == characterId }
        check(p.edit().putLong("revision:$characterId", p.getLong("revision:$characterId", 0) + 1)
            .remove("due:$characterId").remove("messageWindow:$characterId")
            .remove("enqueued:$characterId").commit()) { "取消在线消息失败" }
    }
}
