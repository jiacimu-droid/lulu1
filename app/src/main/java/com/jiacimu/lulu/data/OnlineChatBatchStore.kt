package com.jiacimu.lulu.data

import android.content.Context

/** Durable revisions coalesce queued message events; they never cancel an already-running reply. */
internal object OnlineChatBatchStore {
    const val QUIET_MILLIS = 3_000L
    data class Batch(val revision: Long, val dueAtMillis: Long)
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("lulu_online_chat_batches", Context.MODE_PRIVATE)

    @Synchronized fun next(context: Context, characterId: String, collectMessages: Boolean, now: Long = System.currentTimeMillis()): Batch {
        val p = prefs(context)
        // The first unread event owns the deadline. Later bubbles do not postpone reading.
        if (p.contains("due:$characterId")) {
            return Batch(p.getLong("revision:$characterId", 0), p.getLong("due:$characterId", now))
        }
        val revision = p.getLong("revision:$characterId", 0) + 1
        val due = now + if (collectMessages) QUIET_MILLIS else 0
        check(p.edit().putLong("revision:$characterId", revision).putLong("due:$characterId", due).commit()) { "在线消息批次保存失败" }
        return Batch(revision, due)
    }

    /** Claim under the perception mutex; pending messages during this reply open the next window. */
    @Synchronized fun claim(context: Context, characterId: String, revision: Long): Boolean {
        val p = prefs(context)
        if (!isCurrent(context, characterId, revision) || !p.contains("due:$characterId")) return false
        check(p.edit().remove("due:$characterId").commit()) { "在线消息读取状态保存失败" }
        return true
    }

    fun isCurrent(context: Context, characterId: String, revision: Long) =
        prefs(context).getLong("revision:$characterId", 0) == revision

    fun dueAt(context: Context, characterId: String, revision: Long): Long? =
        if (isCurrent(context, characterId, revision)) prefs(context).getLong("due:$characterId", 0L).takeIf { it > 0L }
        else null

    @Synchronized fun cancel(context: Context, characterId: String) {
        val p = prefs(context)
        check(p.edit().putLong("revision:$characterId", p.getLong("revision:$characterId", 0) + 1)
            .remove("due:$characterId").commit()) { "取消在线消息失败" }
    }
}
