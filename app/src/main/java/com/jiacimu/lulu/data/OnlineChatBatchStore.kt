package com.jiacimu.lulu.data

import android.content.Context

/** Durable revisions coalesce queued message events; they never cancel an already-running reply. */
internal object OnlineChatBatchStore {
    const val QUIET_MILLIS = 6_000L
    data class Batch(val revision: Long, val dueAtMillis: Long)
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("lulu_online_chat_batches", Context.MODE_PRIVATE)

    @Synchronized fun next(context: Context, characterId: String, collectMessages: Boolean, now: Long = System.currentTimeMillis()): Batch {
        val p = prefs(context)
        val revision = p.getLong("revision:$characterId", 0) + 1
        val due = maxOf(p.getLong("due:$characterId", 0), now + if (collectMessages) QUIET_MILLIS else 0)
        check(p.edit().putLong("revision:$characterId", revision).putLong("due:$characterId", due).commit()) { "在线消息批次保存失败" }
        return Batch(revision, due)
    }
    fun isCurrent(context: Context, characterId: String, revision: Long) =
        prefs(context).getLong("revision:$characterId", 0) == revision

    @Synchronized fun cancel(context: Context, characterId: String) {
        val p = prefs(context)
        check(p.edit().putLong("revision:$characterId", p.getLong("revision:$characterId", 0) + 1)
            .remove("due:$characterId").commit()) { "取消在线消息失败" }
    }
}
