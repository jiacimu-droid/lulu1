package com.jiacimu.lulu.data

import android.content.Context
import java.time.Duration
import java.time.Instant
import org.json.JSONObject

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
        "你没", "你没有", "你答应", "失约", "我不想", "你为什么", "我很烦",
        "骗我", "不信任", "为什么这样", "让我失望", "怎么能", "伤害我", "不在乎", "冷暴力", "辜负")

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
        if (id.isBlank() || !ProactivePerceptionPolicyStore.get(id).enabled) return
        val recentUser = messages.asReversed().asSequence()
            .filter { it.sender == LuluChatMessage.Sender.User && it.status == LuluChatMessage.Status.Sent }
            .take(12).toList()
        val lastUser = recentUser.joinToString(" ") { it.content.take(150) }
        // Never manufacture a conflict from the model's text alone.
        if (!qualifies(reply.content, lastUser)) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "scheduled:$id"
        val previous = prefs.getLong(key, 0L)
        if (previous > 0 && Duration.between(Instant.ofEpochMilli(previous), now).toMinutes() < 30) return
        if (!prefs.edit().putLong(key, now.toEpochMilli()).commit()) return

        // The character has ACTUALLY expressed remorse. Its reply, not an LLM's
        // optional hidden JSON, is the evidence for this modest subjective state.
        // A richer emotion already recorded from the reply takes precedence.
        val current = CharacterInnerLifeStore.snapshot(id).optJSONObject("emotion")
        val started = current?.optString("startedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val emotionallyRecognized = current != null &&
            (current.optInt("strength", 2) >= 3 ||
                listOf("后悔", "愧疚", "自责", "难过", "委屈", "心疼", "懊悔", "歉疚")
                    .any(current.optString("feeling")::contains))
        if (!emotionallyRecognized || started == null || Duration.between(started, now).abs().toMinutes() > 10) {
            val proposal = JSONObject().put("emotion", JSONObject()
                .put("feeling", "对刚才的争执感到歉疚")
                .put("cause", lastUser.takeLast(170).ifBlank { "刚才的争执" })
                .put("strength", 3)
                .put("halfLifeMinutes", 360))
            CharacterInnerLifeStore.observe(id, reply.id,
                "刚才真实发出了道歉：${reply.content.take(180)}", proposal, setOf("user"), now)
        }
        // One extra choice during the online window. Silence remains a valid
        // choice, but it must result from an actual model decision.
        if (CompanionOnlineStore.isOnline(id, now)) {
            ProactivePerceptionScheduler.scheduleOnline(
                context.applicationContext, id,
                "刚经历真实关系冲突并表达歉意：检视持续情绪与真实后果，自主选择修复、日记、动态、继续沟通或暂时独处；不强迫公开道歉。",
                delayMillis = 45_000L,
            )
        }
        // Independent durable work survives process death and can act after
        // logout. It does not falsely renew the five-minute online window.
        ProactivePerceptionScheduler.scheduleEmotionalAftercare(
            context.applicationContext, id, delayMillis = 420_000L,
        )
    }
}
