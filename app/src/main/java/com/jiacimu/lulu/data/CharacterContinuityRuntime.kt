package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant
import org.json.JSONObject

/**
 * Program-owned continuity facts shared by chat/phone/proactive surfaces.
 *
 * This layer never invents a feeling. It only carries durable facts across turns: elapsed time,
 * the last witnessed exchange, stable social names and currently active motives. The model decides
 * what these facts mean for this particular person.
 */
internal object CharacterContinuityRuntime {
    internal data class Snapshot(
        val previousInteractionAt: Instant?,
        val minutesSinceInteraction: Long?,
        val lastUserStatement: SharedTimelineEvent?,
        val lastCharacterStatement: SharedTimelineEvent?,
        val preferredAddress: String,
        val addressIsManual: Boolean,
        val activeMotiveAims: List<String>,
    )

    private fun normalized(value: String): String =
        value.lowercase().replace(Regex("[\\s，。！？!?、；;：:“”‘’…~～—_\\\"'（）()]+"), "")

    private fun isCurrentInputEcho(event: SharedTimelineEvent, currentUserText: String, now: Instant): Boolean {
        if (currentUserText.isBlank() || event.evidenceKind != EventEvidenceKind.UserStatement) return false
        val ageSeconds = Duration.between(event.occurredAt, now).seconds
        return ageSeconds in 0..180 && normalized(event.content) == normalized(currentUserText)
    }

    fun snapshot(
        characterId: String,
        currentUserText: String = "",
        now: Instant = Instant.now(),
        excludedEventIds: Set<String> = emptySet(),
    ): Snapshot {
        // The first 160 statement rows can all be group witnesses of other
        // characters. Filter ownership before choosing the last real exchange;
        // otherwise a busy group silently erases the role's actual prior words.
        val firstPage = SharedExperienceTimeline.recentStatementEvents(characterId, now, 160)
        val newestFirst = firstPage.asReversed()
        var lastUser: SharedTimelineEvent? = null
        var lastOwn: SharedTimelineEvent? = null
        var previous: SharedTimelineEvent? = null
        // Only the latest matching, genuinely authored user message may be the
        // current-turn echo. Several identical short messages are distinct
        // events and must not all vanish from the previous-dialogue anchor.
        // Carry this marker across pagination so older pages are not re-filtered.
        var skippedCurrentEcho = false
        fun absorb(page: List<SharedTimelineEvent>) {
            for (event in SharedExperienceTimeline.withoutRecalledGroupSpeech(page)) {
                if (event.id in excludedEventIds) continue
                if (event.evidenceKind == EventEvidenceKind.UserStatement &&
                    TimelineStatementOwnership.involvesUser(event)) {
                    if (!skippedCurrentEcho && isCurrentInputEcho(event, currentUserText, now)) {
                        skippedCurrentEcho = true
                        continue
                    }
                    // Group witnesses are only user words when the canonical
                    // sent message still establishes a real user author.
                    if (lastUser == null) lastUser = event
                    if (previous == null) previous = event
                } else if (TimelineStatementOwnership.isOwnDialogueStatement(event)) {
                    if (lastOwn == null) lastOwn = event
                    if (previous == null && TimelineStatementOwnership.involvesUser(event))
                        previous = event
                }
            }
        }
        absorb(newestFirst)
        var cursor = newestFirst.lastOrNull()
        // Bounded read-only fallback, only for an unusually busy shared group.
        // No extra model call or duplicate timeline store.
        repeat(12) {
            if (cursor == null || (lastUser != null && lastOwn != null && previous != null))
                return@repeat
            val page = SharedExperienceTimeline.statementPageBefore(
                characterId, now, cursor!!.occurredAt, cursor!!.id, 160)
            if (page.isEmpty()) {
                cursor = null
                return@repeat
            }
            absorb(page)
            cursor = if (page.size < 160) null else page.last()
        }
        val minutes = previous?.occurredAt?.let {
            Duration.between(it, now).toMinutes().coerceAtLeast(0L)
        }
        val social = CharacterLifeStore.state(characterId).optJSONObject("socialNames") ?: JSONObject()
        val motives = CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives")
        val aims = CharacterMotiveState.active(motives)
            .sortedByDescending { it.optInt("priority", 2) }
            .map { it.optString("aim").trim() }
            .distinct().take(3)
        return Snapshot(
            previousInteractionAt = previous?.occurredAt,
            minutesSinceInteraction = minutes,
            lastUserStatement = lastUser,
            lastCharacterStatement = lastOwn,
            preferredAddress = CharacterAddressPreference.currentAddress(characterId),
            addressIsManual = social.optBoolean("preferredAddressManual", false),
            activeMotiveAims = aims,
        )
    }

    private fun elapsedLabel(minutes: Long): String = when {
        minutes < 5 -> "几分钟内"
        minutes < 60 -> "约${minutes}分钟"
        minutes < 24 * 60 -> "约${(minutes / 60).coerceAtLeast(1)}小时"
        minutes < 7 * 24 * 60 -> "约${(minutes / (24 * 60)).coerceAtLeast(1)}天"
        else -> "约${(minutes / (7 * 24 * 60)).coerceAtLeast(1)}周"
    }

    fun context(
        characterId: String,
        currentUserText: String = "",
        now: Instant = Instant.now(),
        includeMotives: Boolean = true,
        excludedEventIds: Set<String> = emptySet(),
    ): String = render(snapshot(characterId, currentUserText, now,
        excludedEventIds), includeMotives)

    internal fun render(state: Snapshot, includeMotives: Boolean = true): String {
        if (state.previousInteractionAt == null && state.lastCharacterStatement == null &&
            state.lastUserStatement == null && state.preferredAddress.isBlank() &&
            (!includeMotives || state.activeMotiveAims.isEmpty())) return ""
        return buildString {
            appendLine("【人物连续性锚点｜程序提供的真实历史，不是要求你表演连续性】")
            state.minutesSinceInteraction?.let { minutes ->
                appendLine("距上一条真实双方互动：${elapsedLabel(minutes)}。")
                when {
                    minutes < 6 * 60 ->
                        appendLine("这是同一段近期关系流，不要把它当久别重逢，也不用主动报时。")
                    minutes < 36 * 60 ->
                        appendLine("时间确实过去了一段；可以自然承接，但不要机械说“好久不见”。")
                    minutes < 7 * 24 * 60 ->
                        appendLine("已经跨日。角色应知道时间过去了；是否提起由当前话题、关系和本人性格决定。")
                    else ->
                        appendLine("这是明显的长期空档。不能像刚刚才聊过，也不能擅自断言用户故意冷落、生气或离开；先把“隔了很久”作为事实，再自行理解。")
                }
            }
            state.lastUserStatement?.let {
                appendLine("用户上一次真实说过：${it.content.replace(Regex("\\s+"), " ")}")
            }
            state.lastCharacterStatement?.let {
                appendLine("角色自己上一次真实说过：${it.content.replace(Regex("\\s+"), " ")}（场景：${it.channel}）")
                appendLine("自己已经说出口的话属于自己的历史，要承接并负责，不能下一轮当成用户说的或完全失忆。")
                appendLine("发言场景以原记录为准；在其他群里说过，不代表已经向当前交谈对象说过。")
            }
            if (state.preferredAddress.isNotBlank()) {
                val source = if (state.addressIsManual) "用户/设置明确固定" else "由真实聊天观察得出"
                appendLine("稳定日常称呼基线：${state.preferredAddress}（$source）。")
                appendLine("称呼可以省略，也可在特殊情境偶尔变化；但不要无缘无故每轮随机换一个，更不能用初始人设候选覆盖这个更具体的偏好。")
            }
            if (includeMotives && state.activeMotiveAims.isNotEmpty()) {
                appendLine("角色当前仍未放下的本人动机：${state.activeMotiveAims.joinToString("；")}。")
                appendLine("这些是持续愿望，不是必须每轮提及的台词；只有当前情境真的相关时才影响选择。")
            }
        }.trim()
    }

    fun proactiveContext(
        characterId: String,
        now: Instant = Instant.now(),
    ): String {
        val state = snapshot(characterId, now = now)
        val minutes = state.minutesSinceInteraction ?: return ""
        return buildString {
            appendLine("【联系节律与久别感知】")
            appendLine("距最近一次真实双方互动：${elapsedLabel(minutes)}。")
            appendLine("时间间隔本身只是机会信号，不是“必须联系”的命令；结合挂心、未完承诺、角色动机、最近主动次数和安静时段决定。")
            if (minutes >= 24 * 60) appendLine("跨日后可以出现真正由角色本人发起的联系，但不要每天签到式问候，也不要把用户没出现解释成生气或抛弃。")
            if (minutes >= 7 * 24 * 60) appendLine("长期未互动时，若角色确实在意，可以选择一次低压力联系、分享自己的生活或继续自己的事；不要反复追问“为什么不理我”。")
            if (state.preferredAddress.isNotBlank()) {
                appendLine("若主动联系时需要称呼，优先沿用稳定称呼“${state.preferredAddress}”，而不是随机生成新昵称。")
            }
        }.trim()
    }
}
