package com.jiacimu.lulu.data

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

internal data class DigitalEnvironmentSnapshot(
    val observationKey: String,
    val season: String,
    val light: String,
    val temperatureCelsius: Int,
) {
    fun context(): String = "数字世界环境：$season；$light；室外体感约${temperatureCelsius}℃。这是数字世界的共同环境，不是用户现实天气；室内外的感受会有差异。"
}

/** One shared world clock, smooth yearly/daytime cycles; retries never reroll weather. */
internal object DigitalWorldEnvironment {
    private val worldZone = ZoneId.of("Asia/Shanghai")

    fun snapshot(now: Instant): DigitalEnvironmentSnapshot {
        val time = now.atZone(worldZone)
        val season = when (time.monthValue) {
            in 3..5 -> "春季"
            in 6..8 -> "夏季"
            in 9..11 -> "秋季"
            else -> "冬季"
        }
        val phase = when (time.hour) {
            in 6..10 -> "晨光"
            in 11..16 -> "白昼"
            in 17..19 -> "暮色"
            else -> "夜间"
        }
        val annual = 18.0 + 10.0 * cos(2 * PI * (time.dayOfYear - 200) / time.toLocalDate().lengthOfYear())
        val daily = 3.0 * cos(2 * PI * (time.hour - 14) / 24)
        return DigitalEnvironmentSnapshot("${time.toLocalDate()}:$phase", season, phase, (annual + daily).roundToInt())
    }

    fun observe(characterId: String, now: Instant) {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return
        val state = snapshot(now)
        val id = "world-environment-$characterId-${state.observationKey}"
        if (SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).isNotEmpty()) return
        SharedExperienceTimeline.record(id, characterId, "数字世界·环境", "环境感知", state.context(), now,
            triggerExtraction = false, source = "digital-environment", evidenceKind = EventEvidenceKind.Observation)
    }
}
