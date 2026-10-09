package com.jiacimu.lulu.data

import android.content.Context
import java.time.Duration
import java.time.Instant

/**
 * A single opportunistic follow-through, not an automatic public apology or forced diary.
 *
 * A real sent reply showing strong remorse after a witnessed disagreement can merit one more
 * personal decision while the five-minute online window is still open. The model may journal,
 * try a verifiable repair, ask for space, or do nothing. No fake action receipts.
 */
internal object EmotionFollowThroughCoordinator {
    private const val PREFS = "lulu_emotional_followthrough_v1"
    private val remorse = listOf("很后悔", "后悔", "愧疚", "懊悔", "我错了", "是我不对", "对不起",
        "抱歉", "我没做到", "我不该", "是我失约", "让我想想怎么补救")
    private val friction = listOf("吵架", "生气", "伤心", "难过", "委屈", "不高兴", "不舒服",
        "你没", "你没有", "你答应", "失约", "我不想", "你为什么", "我很烦")

    fun qualifies(roleText: String, recentUserText: String): Boolean =
        remorse.any(roleText::contains) && friction.any(recentUserText::contains)

    fun onActualReply(
        context: Context,
        conversation: LuluConversation,
        messages: List<LuluChatMessage>,
        reply: LuluChatMessage,
        now: Instant = Instant.now(),
    ) {
        if (conversation.groupChat != null || reply.sender != LuluChatMessage.Sender.Character ||
            reply.status != LuluChatMessage.Status.Sent) return
        val id = reply.authorCharacterId?.takeIf(String::isNotBlank) ?: conversation.characterId
        if (id.isBlank() || !CompanionOnlineStore.isOnline(id, now)) return
        if (!ProactivePerceptionPolicyStore.get(id).enabled) return
        val lastUser = messages.asReversed().asSequence()
            .filter { it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent }
            .take(6).joinToString(" ") { it.content.take(150) }
        if (!qualifies(reply.content, lastUser)) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "scheduled:$id"
        val previous = prefs.getLong(key, 0L)
        if (previous > 0 && Duration.between(Instant.ofEpochMilli(previous), now).toMinutes() < 30) return
        if (!prefs.edit().putLong(key, now.toEpochMilli()).commit()) return
        ProactivePerceptionScheduler.scheduleOnline(
            context.applicationContext, id,
            "刚经历真实关系冲突并表达歉意后的后续生活：先看自己的真实情绪、承诺回执、对方边界和能力；选择真正想做的一件事或安静反思，不为表演悔恨而机械发动态/日记。",
            delayMillis = 90_000L,
        )
    }
}
