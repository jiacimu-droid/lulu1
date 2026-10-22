package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class UserInteractionPresence(
    val appVisible: Boolean? = null,
    val visibilityAt: Instant? = null,
    val conversationId: String? = null,
    val lastTouchAt: Instant? = null,
)

/** Only actual lifecycle/touch signals: an open page cannot prove attention or message reading. */
object UserInteractionPresenceStore {
    private var prefs: android.content.SharedPreferences? = null
    private val mutableState = MutableStateFlow(UserInteractionPresence())
    val state = mutableState.asStateFlow()

    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("lulu_user_interaction_presence_v1", Context.MODE_PRIVATE)
        val touchedAt = prefs?.getLong("lastTouchAt", 0L)?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
        // A previous process's visible page is not evidence about the current foreground app.
        mutableState.value = UserInteractionPresence(lastTouchAt = touchedAt)
    }

    @Synchronized fun setAppVisible(visible: Boolean, now: Instant = Instant.now()) {
        mutableState.value = mutableState.value.copy(appVisible = visible, visibilityAt = now)
    }

    @Synchronized fun setConversation(conversationId: String?) {
        mutableState.value = mutableState.value.copy(conversationId = conversationId)
    }

    @Synchronized fun touch(now: Instant = Instant.now()) {
        mutableState.value = mutableState.value.copy(lastTouchAt = now)
        prefs?.edit()?.putLong("lastTouchAt", now.toEpochMilli())?.apply()
    }

    fun context(characterId: String, now: Instant = Instant.now()): String {
        val conversations = MigratedDomainStores.chat.conversations.value.filter {
            it.characterId == characterId || it.groupChat?.members?.any { member -> member.characterId == characterId } == true
        }
        val messages = conversations.flatMap { conversation ->
            MigratedDomainStores.chat.messages(conversation.id).value.filter { message ->
                message.sender != LuluChatMessage.Sender.Character ||
                    message.authorCharacterId == characterId ||
                    (message.authorCharacterId == null && conversation.groupChat == null)
            }
        }
            .filter { it.status == LuluChatMessage.Status.Sent && it.createdAt <= now }
        val lastUser = messages.filter { it.sender == LuluChatMessage.Sender.User }.maxByOrNull { it.createdAt }
        val lastRole = messages.filter { it.sender == LuluChatMessage.Sender.Character &&
            (it.authorCharacterId == characterId || it.authorCharacterId == null) }.maxByOrNull { it.createdAt }
        val visiblePage = state.value.conversationId?.let { id -> conversations.any { it.id == id } }
        return describe(state.value, visiblePage, lastUser?.createdAt, lastRole?.createdAt, now)
    }

    /**
     * Read-only, per-turn attention evidence. It is not a bedtime alarm, a
     * character emotion, a newly inferred user fact or a persistent goal.
     * The same cue can reach private chat, calls and autonomous perception
     * through UserDevicePerception without a second model request.
     */
    internal fun salientTimeCue(
        presence: UserInteractionPresence,
        relevantPage: Boolean?,
        lastUserAt: Instant?,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val recentSpeechMinutes = lastUserAt?.takeUnless { it.isAfter(now) }
            ?.let { Duration.between(it, now).toMinutes() }
            ?.takeIf { it in 0L..90L }
        val recentTouchMinutes = presence.lastTouchAt?.takeUnless { it.isAfter(now) }
            ?.let { Duration.between(it, now).toMinutes() }
            ?.takeIf { it in 0L..15L }
        val relevantTouch = presence.appVisible == true && relevantPage == true &&
            recentTouchMinutes != null
        val hour = now.atZone(zone).hour
        if (hour !in 0..5 || (recentSpeechMinutes == null && !relevantTouch)) return ""

        val observations = buildList {
            recentSpeechMinutes?.let { add("约 ${it} 分钟前确实收到用户发言") }
            if (relevantTouch) add("约 ${recentTouchMinutes} 分钟前确实观察到当前相关页面有触摸操作")
        }.joinToString("；")
        return """
            【可自主留意的时间线索｜客观证据，并非行动指令】
            用户设备本地时间处于凌晨；$observations。
            这些事实可以与本人的人格、亲疏关系、过去的真实交流及仍在意的事情相关，也可能并不需要特别处理。
            目前尚不确定用户有没有睡过、是否疲惫、是否愿意谈作息；屏幕和触摸也不能证明一直清醒。
            若确实在意，可以自己形成关心、好奇、犹豫或想陪伴的念头，再自行决定适时表达、换一种方式陪伴或不提；不是必须劝睡，更不应反复催促。已表达的关心和用户回应应作为下一轮真实历史承接。
        """.trimIndent()
    }

    internal fun describe(
        presence: UserInteractionPresence, relevantPage: Boolean?,
        lastUserAt: Instant?, lastRoleAt: Instant?, now: Instant,
    ): String = buildString {
        salientTimeCue(presence, relevantPage, lastUserAt, now)
            .takeIf(String::isNotBlank)?.let { appendLine(it) }
        appendLine("【用户回应与沉默｜实际观察，不等同于已读或真实情绪】")
        appendLine(when (presence.appVisible) {
            true -> "露露机当前处于前台可交互状态" + when (relevantPage) {
                true -> "；当前打开的是你参与的聊天页面"
                false -> "；当前未打开你参与的聊天页面"
                null -> "；当前聊天页面未知"
            }
            false -> "露露机当前未处于前台可交互状态；可能切换应用、锁屏或被其他窗口遮挡，具体去向未知"
            null -> "露露机是否在前台未知"
        })
        presence.visibilityAt?.let { appendLine("上述前台状态实际观测于：$it") }
        presence.lastTouchAt?.takeIf { it <= now }?.let {
            appendLine("距离上次在露露机触摸操作约 ${Duration.between(it, now).seconds} 秒；操作不证明读过消息")
        }
        lastUserAt?.let { appendLine("最近用户真实发言：$it；距今约 ${Duration.between(it, now).seconds} 秒") }
        if (lastRoleAt != null && (lastUserAt == null || lastRoleAt > lastUserAt)) {
            appendLine("你最近发言后尚未收到新的用户回应；已等待约 ${Duration.between(lastRoleAt, now).seconds} 秒")
        }
        appendLine("结合之前的真实对话、关系、已有情绪和获准读取的学习/设备状态，理解这段安静。可能在忙、阅读、思考、不想接话，也可能仍有不开心；前台与等待时长都不能证明已读、生气、离开或同意。")
        appendLine("角色可以形成自己的担心、猜测和克制，再选择等待、继续自己的事或合适地询问；推测保持为推测，不责怪用户、不要求持续回应，不把沉默当新冲突反复放大。")
    }.trim()
}
