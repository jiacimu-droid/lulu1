package com.jiacimu.lulu.data

import kotlin.math.min

/**
* Motive upgrade, phase 1: numeric drive readings only.
*
* This is a reading layer beside the existing text motives book, not a
* second book of motives. It answers "how strongly is this kind of wanting
* felt right now", while motives still answer "what exactly is wanted".
* Nothing here sends a message, creates a motive, changes a schedule, or
* overrides a model decision. Phase 1 only delivers the snapshot alongside
* the existing soft life-rhythm text; behaviour is unchanged.
*
* Every input must come from a real timeline event, a real task state, or
* a real stored count. No drive is invented from a guess. All times are
* Shanghai time, per the user's standing choice.
*/
internal enum class DriveKind(val label: String) {
AFFILIATION("亲近"),
CURIOSITY("好奇"),
CARE("关照"),
PLAY("玩心"),
GROWTH("成长"),
REST("休息"),
AUTONOMY("自主"),
}

/** Inputs to the pure drive computation. Gaps are hours since the last real event. */
internal data class DriveInput(
/** Hours since the most recent private chat, call, or group chat. */
val socialGapHours: Long = 0,
/** Hours since the most recent exploration: reading, world action,
 * reality window, or any real tool use (web search, image search,
 * Xiaohongshu reading, and so on). Tools count as exploration. */
val explorationGapHours: Long = 0,
/** What that most recent exploration was, e.g. "上网查" or "看书". */
val explorationLabel: String = "探索",
/** Hours since the character last learned something new about the user:
 * a user message, or a user moment. Curiosity about the user counts as
 * curiosity too, not only books and going out. */
val userInfoGapHours: Long = 0,
/** Hours since the most recent solo game. */
val gameGapHours: Long = 0,
/** Hours since the most recent journal entry. */
val journalGapHours: Long = 0,
/** Still-open concerns + waiting-feedback motives + unclosed tasks. */
val openItems: Int = 0,
/** Thoughts recorded but not yet spoken out loud. */
val unspokenThoughts: Int = 0,
/** Real interactions (private chat / call) in the last hour. */
val recentInteractions: Int = 0,
/** Current hour in Shanghai time, 0..23. */
val shanghaiHour: Int = 12,
)

internal data class DriveReading(
val kind: DriveKind,
/** 0.0 = fully satisfied, 1.0 = saturated. Always clamped to [0, 1]. */
val value: Double,
/** Human basis for the number, e.g. "上次社交约30小时前". */
val basis: String,
)

internal object DriveState {
/** Conservative reference line: above this a drive is called "strong".
* It is a reading label for the model, not an auto-send switch. */
const val STRONG_THRESHOLD = 0.75

private fun gapValue(gapHours: Long, saturationHours: Long): Double =
(gapHours.toDouble() / saturationHours.toDouble()).coerceIn(0.0, 1.0)

private fun countValue(count: Int, saturationCount: Int): Double =
(count.toDouble() / saturationCount.toDouble()).coerceIn(0.0, 1.0)

private fun restValue(input: DriveInput): Double {
// Night dominates rest, per the design default: late night is rest
// time even when other drives are high. Daytime rest only reflects
// recent interaction load, and never above 0.6 on its own.
return when (input.shanghaiHour) {
23, 0, 1, 2, 3, 4, 5, 6 -> 1.0
22 -> 0.8
7 -> 0.7
else -> min(0.6, input.recentInteractions.toDouble() / 8.0)
}
}

fun snapshot(input: DriveInput): List<DriveReading> = listOf(
DriveReading(
DriveKind.AFFILIATION,
gapValue(input.socialGapHours, 72),
"上次社交约${input.socialGapHours}小时前",
),
DriveReading(
DriveKind.CURIOSITY,
// Curiosity is satisfied by EITHER exploring the world/tools OR
// learning something new about the user, whichever was latest.
gapValue(minOf(input.explorationGapHours, input.userInfoGapHours), 48),
if (input.userInfoGapHours <= input.explorationGapHours)
"上次满足好奇是了解你在干什么，约${input.userInfoGapHours}小时前"
else
"上次满足好奇是${input.explorationLabel}，约${input.explorationGapHours}小时前",
),
DriveReading(
DriveKind.CARE,
countValue(input.openItems, 6),
"仍开着的事共${input.openItems}件",
),
DriveReading(
DriveKind.PLAY,
gapValue(input.gameGapHours, 48),
"上次独自游戏约${input.gameGapHours}小时前",
),
DriveReading(
DriveKind.GROWTH,
gapValue(input.journalGapHours, 96),
"上次写日记约${input.journalGapHours}小时前",
),
DriveReading(DriveKind.REST, restValue(input), "上海时间${input.shanghaiHour}点"),
DriveReading(
DriveKind.AUTONOMY,
countValue(input.unspokenThoughts, 6),
"还没说出口的琢磨事共${input.unspokenThoughts}件",
),
)

fun strongest(readings: List<DriveReading>): DriveReading? =
readings.maxByOrNull { it.value}

/** Short rendering for the prompt. Readings only; no instruction to act. */
fun render(readings: List<DriveReading>): String {
if (readings.isEmpty()) return ""
val lines = readings.joinToString("\n") { reading ->
val percent = (reading.value * 100).toInt()
val mark = if (reading.value >= STRONG_THRESHOLD) "强" else "平"
"- ${reading.kind.label} $percent（$mark；${reading.basis}）"
}
val top = strongest(readings)
val topLine = if (top!= null && top.value >= STRONG_THRESHOLD) {
"\n- 目前最强的驱动是${top.kind.label}，仅作参考；做什么、说不说，仍由你自己决定，沉默也合法。"
} else {
"\n- 目前没有驱动越过参考线；这只是一份读数，不等于要行动。"
}
return "\n$lines$topLine"
}
}
