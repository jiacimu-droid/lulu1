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
        // A pending batch uses a *trailing-edge* quiet window: every newly
        // scheduled message moves the deadline three seconds after that message.
        // Once a reply is actually being read, new messages open their own batch.
        val pendingRevision = p.getLong("revision:$characterId", 0)
        if (p.contains("due:$characterId") && (characterId to pendingRevision) !in reading) {
            val existingDue = p.getLong("due:$characterId", now)
            val due = if (collectMessages) now + QUIET_MILLIS else existingDue
            if (due != existingDue) {
                check(p.edit().putLong("due:$characterId", due).commit()) { "在线消息静默期保存失败" }
            }
            return Batch(pendingRevision, due)
        }
        val revision = pendingRevision + 1
        val due = if (collectMessages) now + QUIET_MILLIS else now
        check(p.edit().putLong("revision:$characterId", revision).putLong("due:$characterId", due).commit()) { "在线消息批次保存失败" }
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
            check(prefs(context).edit().remove("due:$characterId").commit()) { "在线消息读取状态保存失败" }
        }
    }

    fun isCurrent(context: Context, characterId: String, revision: Long) =
        prefs(context).getLong("revision:$characterId", 0) == revision

    fun dueAt(context: Context, characterId: String, revision: Long): Long? =
        if (isCurrent(context, characterId, revision)) prefs(context).getLong("due:$characterId", 0L).takeIf { it > 0L }
        else null

    @Synchronized fun cancel(context: Context, characterId: String) {
        val p = prefs(context)
        reading.removeAll { it.first == characterId }
        check(p.edit().putLong("revision:$characterId", p.getLong("revision:$characterId", 0) + 1)
            .remove("due:$characterId").commit()) { "取消在线消息失败" }
    }
}
