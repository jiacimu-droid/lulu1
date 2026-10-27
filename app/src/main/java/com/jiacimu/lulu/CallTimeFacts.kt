package com.jiacimu.lulu

import com.jiacimu.lulu.data.CommitmentTaskStore
import com.jiacimu.lulu.data.MigratedDomainStores
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Wake-time cognition, final piece (batch 19): the call opening turn gets
 * the time facts the model needs to notice that a night has passed, instead
 * of carrying the night's state into the morning unchanged.
 *
 * Only facts, never conclusions: the model itself works out what seven
 * quiet hours overnight mean. The routine clocks come from the user's own
 * daily recurring call tasks (repeat + deliveryAction status fields, never
 * title text), so no keyword guessing is involved. Nothing here makes a
 * model call of its own; it rides the existing opening generation.
 */
internal object CallTimeFacts {
    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 H:mm")
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

    /** Clock mapping only: which part of the day a Shanghai hour falls in. */
    internal fun dayPart(hour: Int): String = when (hour) {
        in 0..4 -> "深夜"
        in 5..8 -> "早上"
        in 9..11 -> "上午"
        in 12..13 -> "中午"
        in 14..17 -> "下午"
        in 18..22 -> "晚上"
        else -> "深夜"
    }

    internal fun formatDayTime(at: Instant): String = DAY_TIME.format(at.atZone(ZONE))
    internal fun formatClock(at: Instant): String = CLOCK.format(at.atZone(ZONE))

    internal fun gapText(from: Instant, to: Instant): String {
        val minutes = Duration.between(from, to).toMinutes().coerceAtLeast(0L)
        return if (minutes < 60) "约${minutes}分钟"
        else "约${minutes / 60}小时" + if (minutes % 60 == 0L) "" else "${minutes % 60}分钟"
    }

    /** Pure render. Any unknown fact is omitted, never guessed. */
    internal fun render(
        now: Instant,
        lastUserAt: Instant?,
        scheduledDueAt: Instant?,
        dailyCallClocks: List<Instant>,
    ): String {
        val lines = mutableListOf<String>()
        lines += "现在是上海时间${formatDayTime(now)}（${dayPart(now.atZone(ZONE).hour)}）。"
        if (lastUserAt != null) {
            lines += "用户上次说话是${formatDayTime(lastUserAt)}，距今${gapText(lastUserAt, now)}。"
        }
        if (scheduledDueAt != null) {
            lines += "这次来电约定的时间是${formatDayTime(scheduledDueAt)}。"
        }
        val clocks = dailyCallClocks
            .map { it.atZone(ZONE).toLocalTime() }
            .distinct().sorted()
            .map { CLOCK.format(it) + "（" + dayPart(it.hour) + "）" }
        if (clocks.isNotEmpty()) {
            lines += "用户每天固定有角色来电约定：${clocks.joinToString("、")}。"
        }
        return lines.joinToString("\n")
    }

    /** Store lookups for one call opening; any failure yields no facts, not wrong facts. */
    internal fun forCall(
        characterId: String,
        conversationId: String,
        commitmentTaskId: String?,
        now: Instant,
    ): String {
        val tasks = runCatching { CommitmentTaskStore.snapshot(characterId) }.getOrDefault(emptyList())
        val scheduledDueAt = commitmentTaskId?.let { id -> tasks.find { it.id == id }?.dueAt }
        val dailyClocks = tasks
            .filter { it.repeat == "daily" && it.deliveryAction == "start_call" && it.status.isActive() }
            .mapNotNull { it.dailyBaseDueAt ?: it.dueAt }
        val lastUserAt = runCatching {
            MigratedDomainStores.chat.messages(conversationId).value
                .lastOrNull { it.sender == LuluChatMessage.Sender.User }?.createdAt
        }.getOrNull()
        return render(now, lastUserAt, scheduledDueAt, dailyClocks)
    }
}
