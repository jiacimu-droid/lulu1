package com.jiacimu.lulu.data

import android.content.Context

/** Durable revisions coalesce queued message events; they never cancel an already-running reply. */
internal object OnlineChatBatchStore {
    const val QUIET_MILLIS = 3_000L
    data class Batch(val revision: Long, val dueAtMillis: Long)
    private val reading = mutableSetOf<Pair<String, Long>>()
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("lulu_online_chat_batches", Context.MODE_PRIVATE)

    @Synchronized fun next(context: Context, characterId: String, collectMessages: Boolean, now: Long = System.currentTimeMillis()): Batch {
        val p = prefs(context)
        // First-bubble window, NOT a debounce: the first message starts the
        // 3s reading buffer. Further bubbles join it without restarting it.
        // A wake/reply button only brings the role online; it must not skip
        // this pending window or trigger the typing indicator itself.
        val pendingRevision = p.getLong("revision:$characterId", 0)
        if (p.contains("due:$characterId") && (characterId to pendingRevision) !in reading) {
            val hasUserBubbles = p.getBoolean("messageWindow:$characterId", false)
            if (collectMessages && !hasUserBubbles) {
                // An autonomous background tick may have left an immediate
                // non-message batch pending. The FIRST chat bubble MUST turn
                // that into a real 3s waiting window; never reuse the 0ms due.
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
                .remove("messageWindow:$characterId").commit()) { "在线消息读取状态保存失败" }
        }
    }

    fun isCurrent(context: Context, characterId: String, revision: Long) =
        prefs(context).getLong("revision:$characterId", 0) == revision

    /** Independent self-reflection must wait for an unfinished first-bubble chat batch. */
    @Synchronized fun pendingDueAt(context: Context, characterId: String): Long? =
        prefs(context).getLong("due:$characterId", 0L).takeIf { it > 0L }

    fun dueAt(context: Context, characterId: String, revision: Long): Long? =
        if (isCurrent(context, characterId, revision)) prefs(context).getLong("due:$characterId", 0L).takeIf { it > 0L }
        else null

    @Synchronized fun cancel(context: Context, characterId: String) {
        val p = prefs(context)
        reading.removeAll { it.first == characterId }
        check(p.edit().putLong("revision:$characterId", p.getLong("revision:$characterId", 0) + 1)
            .remove("due:$characterId").remove("messageWindow:$characterId").commit()) { "取消在线消息失败" }
    }
}
