package com.jiacimu.lulu.health

import android.content.Context
import com.jiacimu.lulu.data.SharedExperienceTimeline
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class HealthSleepObservation(
    val date: LocalDate,
    val sleepStart: Instant?,
    val wakeTime: Instant?,
    val sleepMinutes: Int?,
    val deepSleepMinutes: Int?,
    val lightSleepMinutes: Int?,
    val remSleepMinutes: Int?,
    val awakeMinutes: Int?,
    val sleepScore: Int?,
    val importedAt: Instant?,
) {
    fun clock(value: Instant?): String = value
        ?.atZone(ZoneId.systemDefault())
        ?.format(DateTimeFormatter.ofPattern("HH:mm"))
        ?: "—"

    fun durationLabel(): String = sleepMinutes?.let { minutes ->
        val hours = minutes / 60
        val remainder = minutes % 60
        when {
            hours <= 0 -> "${remainder}分钟"
            remainder == 0 -> "${hours}小时"
            else -> "${hours}小时${remainder}分钟"
        }
    } ?: "—"

    fun rawFact(): String = buildString {
        append("日期=$date；实际入睡=${clock(sleepStart)}；实际起床=${clock(wakeTime)}")
        append("；实际睡眠=${durationLabel()}")
        val stages = buildList {
            deepSleepMinutes?.let { add("深睡${it}分钟") }
            lightSleepMinutes?.let { add("浅睡${it}分钟") }
            remSleepMinutes?.let { add("REM${it}分钟") }
            awakeMinutes?.let { add("清醒${it}分钟") }
        }
        if (stages.isNotEmpty()) append("；睡眠结构=${stages.joinToString("、")}")
        sleepScore?.let { append("；睡眠评分=$it") }
        importedAt?.let {
            val syncTime = it.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("M-d HH:mm"))
            append("；健康数据同步于$syncTime")
        }
    }
}

/** A single role-readable source for Health app facts across chat, perception, and Study. */
internal object HealthRolePerception {
    fun initialize(context: Context) = GadgetbridgeHealthStore.initialize(context.applicationContext)

    fun recentSleeps(limit: Int = 7): List<HealthSleepObservation> {
        val state = GadgetbridgeHealthStore.state.value
        return state.days
            .asSequence()
            .filter { value ->
                value.sleepMinutes != null || value.sleepStartEpochSeconds != null ||
                    value.sleepEndEpochSeconds != null || value.deepSleepMinutes != null ||
                    value.lightSleepMinutes != null || value.remSleepMinutes != null
            }
            .sortedBy(GadgetbridgeDaySummary::date)
            .toList()
            .takeLast(limit.coerceIn(1, 30))
            .map { day -> day.toSleepObservation(state.lastImportedAt) }
    }

    fun latestSleep(): HealthSleepObservation? = recentSleeps(limit = 1).lastOrNull()

    private fun GadgetbridgeDaySummary.toSleepObservation(importedAt: Instant?): HealthSleepObservation {
        val start = sleepStartEpochSeconds?.let(Instant::ofEpochSecond)
        val end = sleepEndEpochSeconds?.let(Instant::ofEpochSecond)
        val windowMinutes = if (start != null && end != null && end.isAfter(start)) {
            Duration.between(start, end).toMinutes().toInt().takeIf { it in 1..1_200 }
        } else null
        return HealthSleepObservation(
            date = date,
            sleepStart = start,
            wakeTime = end,
            sleepMinutes = sleepMinutes ?: windowMinutes,
            deepSleepMinutes = deepSleepMinutes,
            lightSleepMinutes = lightSleepMinutes,
            remSleepMinutes = remSleepMinutes,
            awakeMinutes = awakeSleepMinutes,
            sleepScore = sleepScore,
            importedAt = importedAt,
        )
    }

    fun sleepJudgmentContext(observation: HealthSleepObservation): String = buildString {
        appendLine("本次真实记录：${observation.rawFact()}")
        val history = recentSleeps().filter { it.date < observation.date }.takeLast(6)
        if (history.isNotEmpty()) {
            appendLine("此前睡眠趋势（用于判断真实进步，不是固定达标线）：")
            history.forEach { item ->
                val sleepAt = item.clock(item.sleepStart)
                val wakeAt = item.clock(item.wakeTime)
                appendLine("- ${item.date}：入睡$sleepAt，起床$wakeAt，睡眠${item.durationLabel()}")
            }
        }
    }.trim()

    fun context(now: Instant = Instant.now()): String {
        val state = GadgetbridgeHealthStore.state.value
        if (!state.connected) return ""
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val day = state.days.firstOrNull { it.date == today } ?: state.latest
        val sleep = latestSleep()
        if (day == null && sleep == null) return "健康 App 已连接，但暂时没有可读数据"
        val facts = buildList {
            sleep?.let { add("最近一次睡眠：${it.rawFact()}") }
            val trend = recentSleeps().dropLast(1).takeLast(3)
            if (trend.isNotEmpty()) {
                add(
                    "此前睡眠=" + trend.joinToString("；") { item ->
                        val sleepAt = item.clock(item.sleepStart)
                        val wakeAt = item.clock(item.wakeTime)
                        "${item.date} 入睡$sleepAt 起床$wakeAt ${item.durationLabel()}"
                    },
                )
            }
            day?.steps?.takeIf { it > 0 }?.let { add("步数=$it") }
            day?.calories?.let { add("活动热量=${it}千卡") }
            day?.distanceMeters?.let { meters ->
                add(
                    if (meters >= 1_000) {
                        "活动距离=${"%.2f".format(Locale.getDefault(), meters / 1_000f)}公里"
                    } else {
                        "活动距离=${meters}米"
                    },
                )
            }
            day?.activeMinutes?.let { add("活跃=${it}分钟") }
            day?.floorsClimbed?.let { add("爬楼=$it层") }
            day?.minimumHeartRate?.let { min -> add("心率范围=$min—${day.maximumHeartRate ?: min}次/分") }
            day?.averageHeartRate?.let { add("平均心率=$it次/分") }
            day?.restingHeartRate?.let { add("静息心率=$it次/分") }
            day?.spo2?.let { add("血氧=$it%") }
            day?.stress?.let { add("压力=$it") }
            day?.hrvMillis?.let { add("HRV=${it}毫秒") }
            day?.respiratoryRate?.let { add("呼吸率=$it次/分") }
            day?.skinTemperatureCelsius?.let { add("皮肤温度=$it℃") }
            day?.bodyEnergy?.let { add("身体能量=$it") }
            if (day?.systolicBloodPressure != null || day?.diastolicBloodPressure != null) {
                add("血压=${day?.systolicBloodPressure ?: "—"}/${day?.diastolicBloodPressure ?: "—"}mmHg")
            }
        }
        val sync = state.lastImportedAt?.atZone(ZoneId.systemDefault())
            ?.format(DateTimeFormatter.ofPattern("M-d HH:mm"))
            ?: "未知"
        return facts.joinToString("；") +
            "；健康 App最后同步=$sync。手环再次导出前，数据可能保持不变。"
    }

    fun recordLatestSleep(characterId: String): HealthSleepObservation? {
        val observations = recentSleeps()
        observations.forEach { observation ->
            val identity = listOf(
                observation.date.toString(),
                observation.sleepStart?.epochSecond?.toString().orEmpty(),
                observation.wakeTime?.epochSecond?.toString().orEmpty(),
            ).joinToString("-")
            SharedExperienceTimeline.record(
                eventId = "health-sleep-${characterId.hashCode()}-$identity",
                characterId = characterId,
                channel = "健康感知",
                speaker = "健康 App",
                content = observation.rawFact(),
                occurredAt = observation.wakeTime ?: observation.importedAt ?: Instant.now(),
                triggerExtraction = false,
            )
        }
        return observations.lastOrNull()
    }
}
