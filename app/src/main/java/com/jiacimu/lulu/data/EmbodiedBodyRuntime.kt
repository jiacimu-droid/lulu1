package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant
import org.json.JSONObject

/**
 * A simulated, digital body — NOT biological measurements, a second personality,
 * or a sensor readout. This snapshot lives inside CharacterLifeStore's existing
 * per-character JSON and never manufactures an interaction or a world action.
 */
internal data class EmbodiedBodySnapshot(
    val lastAt: Instant,
    val energy: Double = 78.0,
    val sleepPressure: Double = 22.0,
    val hunger: Double = 18.0,
    val thirst: Double = 16.0,
    val comfort: Double = 78.0,
    val recoveryMode: String = "",
    val recoveryUntil: Instant? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("lastAt", lastAt.toString())
        .put("energy", energy)
        .put("sleepPressure", sleepPressure)
        .put("hunger", hunger)
        .put("thirst", thirst)
        .put("comfort", comfort)
        .put("recoveryMode", recoveryMode)
        .put("recoveryUntil", recoveryUntil?.toString() ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): EmbodiedBodySnapshot? {
            val last = runCatching { Instant.parse(value.optString("lastAt")) }.getOrNull()
                ?: return null
            fun stat(key: String, fallback: Double): Double =
                value.optDouble(key, fallback).takeIf(Double::isFinite)?.coerceIn(0.0, 100.0) ?: fallback
            return EmbodiedBodySnapshot(
                lastAt = last,
                energy = stat("energy", 78.0),
                sleepPressure = stat("sleepPressure", 22.0),
                hunger = stat("hunger", 18.0),
                thirst = stat("thirst", 16.0),
                comfort = stat("comfort", 78.0),
                recoveryMode = value.optString("recoveryMode").takeIf { it in setOf("sleep", "nap", "rest") }.orEmpty(),
                recoveryUntil = runCatching { Instant.parse(value.optString("recoveryUntil")) }.getOrNull(),
            )
        }
    }
}

/**
 * Pure, deterministic clock-based projection. No polling, network, random events
 * or forced speech/actions. World activities are applied only AFTER their owner
 * has successfully persisted the activity receipt.
 */
internal object EmbodiedBodyPhysics {
    fun fresh(at: Instant): EmbodiedBodySnapshot = EmbodiedBodySnapshot(lastAt = at)

    fun advance(before: EmbodiedBodySnapshot, now: Instant): EmbodiedBodySnapshot {
        if (!now.isAfter(before.lastAt)) return before
        val duration = Duration.between(before.lastAt, now)
        val hours = duration.seconds.toDouble() / 3600.0 + duration.nano.toDouble() / 3_600_000_000_000.0
        val end = before.recoveryUntil
        val recoveryHours = if (before.recoveryMode.isNotBlank() && end != null && end.isAfter(before.lastAt)) {
            val boundedEnd = if (end.isBefore(now)) end else now
            if (boundedEnd.isAfter(before.lastAt))
                Duration.between(before.lastAt, boundedEnd).toMillis().toDouble() / 3_600_000.0 else 0.0
        } else 0.0
        val awakeHours = (hours - recoveryHours).coerceAtLeast(0.0)
        val mode = before.recoveryMode
        val energyGain = when (mode) {
            "sleep" -> 19.0
            "nap" -> 15.0
            "rest" -> 6.0
            else -> 0.0
        }
        val sleepRelief = when (mode) {
            "sleep" -> 23.0
            "nap" -> 20.0
            "rest" -> 5.0
            else -> 0.0
        }
        fun bounded(n: Double) = n.coerceIn(0.0, 100.0)
        val finished = end == null || !end.isAfter(now)
        return before.copy(
            lastAt = now,
            energy = bounded(before.energy - awakeHours * 1.4 + recoveryHours * energyGain),
            sleepPressure = bounded(before.sleepPressure + awakeHours * 3.1 - recoveryHours * sleepRelief),
            hunger = bounded(before.hunger + hours * 1.7),
            thirst = bounded(before.thirst + hours * 2.6),
            comfort = bounded(before.comfort - awakeHours * 0.65 + recoveryHours * 4.0),
            recoveryMode = if (finished) "" else mode,
            recoveryUntil = if (finished) null else end,
        )
    }

    fun applyWorldActivity(
        before: EmbodiedBodySnapshot, activityId: String, at: Instant, plannedEndAt: Instant?,
    ): EmbodiedBodySnapshot {
        // Do not replay out-of-order action timestamps into the current state.
        if (at.isBefore(before.lastAt)) return before
        val current = advance(before, at).copy(recoveryMode = "", recoveryUntil = null)
        fun bounded(n: Double) = n.coerceIn(0.0, 100.0)
        val recovery = when (activityId) {
            "sleep" -> "sleep"
            "nap" -> "nap"
            "rest", "cloud_rest", "reading_rest", "home_quiet_rest",
            "lie_down", "lie_on_rug", "curl_up" -> "rest"
            else -> ""
        }
        if (recovery.isNotBlank() && plannedEndAt != null && plannedEndAt.isAfter(at)) {
            return current.copy(recoveryMode = recovery, recoveryUntil = plannedEndAt)
        }
        return when (activityId) {
            "slow_drink" -> current.copy(thirst = bounded(current.thirst - 26.0),
                comfort = bounded(current.comfort + 3.0))
            "have_snack" -> current.copy(hunger = bounded(current.hunger - 25.0),
                comfort = bounded(current.comfort + 2.0))
            else -> current
        }
    }

    fun endWorldActivity(before: EmbodiedBodySnapshot, at: Instant): EmbodiedBodySnapshot =
        if (at.isBefore(before.lastAt)) before
        else advance(before, at).copy(recoveryMode = "", recoveryUntil = null)

    fun prompt(snapshot: EmbodiedBodySnapshot): String {
        fun number(n: Double) = n.toInt().coerceIn(0, 100)
        return buildString {
            appendLine("【数字身体模拟｜持续的身体线索，不是情绪、人格或真实传感器数据】")
            appendLine("精力=${number(snapshot.energy)}；睡眠压力=${number(snapshot.sleepPressure)}；饥饿感=${number(snapshot.hunger)}；口渴感=${number(snapshot.thirst)}；舒适度=${number(snapshot.comfort)}（0—100，均为数字世界内模拟值）。")
            if (snapshot.recoveryMode.isNotBlank() && snapshot.recoveryUntil != null)
                appendLine("有真实开始、已存档的${when (snapshot.recoveryMode) { "sleep" -> "睡眠"; "nap" -> "小睡"; else -> "休息" }}活动，预计结束于${snapshot.recoveryUntil}；未到时间不冒充完成。")
            appendLine("这些感受可影响本人的注意、节奏、行动选择及数字身体表现，也完全可以被本人忽略或克制；不强制说困、说饿、求陪伴或使用特定台词。没有真实活动/设备证据不得声称吃过、睡过、触摸过现实人物、测得体温或感受到现实天气。")
        }.trim()
    }
}

/** One adapter into the existing per-character life state and shared character context. */
internal object EmbodiedBodyRuntime {
    /**
     * Read-only physical projection for UI and renderer. Never writes during
     * video frame or Compose updates and never triggers a model call.
     */
    fun sensorySnapshot(characterId: String, now: Instant = Instant.now()): EmbodiedBodySnapshot? {
        if (!DigitalLifeProfileStore.isEnabled(characterId) || !CharacterLifeStore.bodyStorageReady()) return null
        val saved = CharacterLifeStore.bodyState(characterId)?.let(EmbodiedBodySnapshot::fromJson)
            ?: EmbodiedBodyPhysics.fresh(now)
        return EmbodiedBodyPhysics.advance(saved, now)
    }

    /** Only confirmed outdoor digital locations expose this simulated temperature. */
    internal fun environmentalSensation(location: String, temperature: Int): String {
        val outdoors = location == DigitalWorldStore.CLOUD_MEADOW ||
            location == DigitalWorldPublicPlaces.COURTYARD
        if (!outdoors) return "目前没有已确认的户外温度接触；不得把世界室外气温当作当前身体温度。"
        val feel = when {
            temperature <= 8 -> "明显寒冷"
            temperature <= 16 -> "稍有凉意"
            temperature >= 33 -> "明显炎热"
            temperature >= 27 -> "偏暖"
            else -> "较为舒适"
        }
        return "当前处于数字世界户外；模拟气温约${temperature}℃，皮肤温感可能${feel}。这是数字环境模拟，不是现实天气或体温测量；穿着、活动和本人感受仍可能改变具体反应。"
    }

    @Synchronized
    fun context(characterId: String, now: Instant = Instant.now()): String {
        if (!DigitalLifeProfileStore.isEnabled(characterId) || !CharacterLifeStore.bodyStorageReady()) return ""
        val saved = CharacterLifeStore.bodyState(characterId)
        val previous = saved?.let(EmbodiedBodySnapshot::fromJson) ?: EmbodiedBodyPhysics.fresh(now)
        val next = EmbodiedBodyPhysics.advance(previous, now)
        if (saved == null || next != previous) CharacterLifeStore.saveBodyState(characterId, next.toJson())
        val digitalTemperature = DigitalWorldEnvironment.snapshot(now).temperatureCelsius
        val location = DigitalWorldStore.locationOf(characterId)
        return EmbodiedBodyPhysics.prompt(next) + "\n" +
            environmentalSensation(location, digitalTemperature)
    }

    @Synchronized
    fun onWorldActivity(characterId: String, activityId: String, at: Instant, plannedEndAt: Instant?) {
        if (!DigitalLifeProfileStore.isEnabled(characterId) || !CharacterLifeStore.bodyStorageReady()) return
        val before = CharacterLifeStore.bodyState(characterId)?.let(EmbodiedBodySnapshot::fromJson)
            ?: EmbodiedBodyPhysics.fresh(at)
        if (at.isBefore(before.lastAt)) return
        CharacterLifeStore.saveBodyState(characterId,
            EmbodiedBodyPhysics.applyWorldActivity(before, activityId, at, plannedEndAt).toJson(),
            cause = "数字世界动作：" + activityId)
    }

    @Synchronized
    fun onWorldActivityEnded(characterId: String, at: Instant) {
        if (!DigitalLifeProfileStore.isEnabled(characterId) || !CharacterLifeStore.bodyStorageReady()) return
        val before = CharacterLifeStore.bodyState(characterId)?.let(EmbodiedBodySnapshot::fromJson) ?: return
        if (before.recoveryMode.isBlank() || at.isBefore(before.lastAt)) return
        CharacterLifeStore.saveBodyState(characterId, EmbodiedBodyPhysics.endWorldActivity(before, at).toJson(),
            cause = "休息活动结束")
    }
}
