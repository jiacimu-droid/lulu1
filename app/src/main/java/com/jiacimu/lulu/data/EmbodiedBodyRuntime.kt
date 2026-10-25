package com.jiacimu.lulu.data

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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
    /** Simulated desire for novelty; not an emotional diagnosis or a command. */
    val boredom: Double = 14.0,
    val recoveryMode: String = "",
    val recoveryUntil: Instant? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", 2)
        .put("lastAt", lastAt.toString())
        .put("energy", energy)
        .put("sleepPressure", sleepPressure)
        .put("hunger", hunger)
        .put("thirst", thirst)
        .put("comfort", comfort)
        .put("boredom", boredom)
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
                boredom = stat("boredom", 14.0),
                recoveryMode = value.optString("recoveryMode").takeIf { it in setOf("sleep", "nap", "rest", "offline_sleep") }.orEmpty(),
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
    // Game-like 0–100 sensations, not medical measures. One hour is meaningful:
    // a few hours without novelty, food or water can change a person's attention.
    // Pure elapsed-time projection means refresh frequency never changes the rate.
    private const val AWAKE_ENERGY_DRAIN_PER_HOUR = 5.2
    private const val AWAKE_SLEEP_PRESSURE_PER_HOUR = 6.5
    private const val AWAKE_HUNGER_PER_HOUR = 7.0
    private const val AWAKE_THIRST_PER_HOUR = 8.5
    private const val AWAKE_COMFORT_DRAIN_PER_HOUR = 2.0
    private const val AWAKE_MONOTONY_PER_HOUR = 9.5

    fun fresh(at: Instant): EmbodiedBodySnapshot = EmbodiedBodySnapshot(lastAt = at)

    /** Split at real local-hour and activity boundaries, never at UI refresh intervals.
     * Offline digital standby is distinct from an explicitly chosen sleep activity. */
    fun advance(before: EmbodiedBodySnapshot, now: Instant,
        zone: ZoneId = ZoneId.systemDefault()): EmbodiedBodySnapshot {
        if (!now.isAfter(before.lastAt)) return before
        var state = before
        while (state.lastAt.isBefore(now)) {
            val at = state.lastAt
            val local = at.atZone(zone)
            val nextHour = local.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant()
            val recoveryEnd = state.recoveryUntil?.takeIf {
                state.recoveryMode.isNotBlank() && state.recoveryMode != "offline_sleep" && it.isAfter(at)
            }
            val end = listOfNotNull(now, nextHour, recoveryEnd).minOrNull()!!
            val elapsed = Duration.between(at, end)
            val hours = elapsed.seconds / 3600.0 + elapsed.nano / 3_600_000_000_000.0
            val mode = state.recoveryMode.takeIf { it == "offline_sleep" ||
                state.recoveryUntil?.isAfter(at) == true }.orEmpty()
            val standby = mode == "offline_sleep"
            val sleeping = mode in setOf("sleep", "nap")
            val resting = mode == "rest"
            val night = local.hour < 7 || local.hour >= 23
            fun bounded(n: Double) = n.coerceIn(0.0, 100.0)
            fun approach(value: Double, target: Double, rate: Double) =
                target + (value - target) * kotlin.math.exp(-rate * hours)
            // A sparse online lease is a resource policy, not 22 hours of restorative sleep.
            // Daytime standby approaches a circadian level instead of refilling to 100.
            val energyTarget = when (local.hour) {
                in 0..6, 23 -> 94.0
                in 7..10 -> 82.0
                in 11..16 -> 70.0
                in 17..21 -> 58.0
                else -> 66.0
            }
            val pressureTarget = when (local.hour) {
                in 0..6, 23 -> 8.0
                in 7..10 -> 20.0
                in 11..16 -> 34.0
                in 17..21 -> 50.0
                else -> 40.0
            }
            val energy = when {
                standby -> approach(state.energy, energyTarget, if (night) 0.65 else 0.35)
                mode == "sleep" -> state.energy + 12.5 * hours
                mode == "nap" -> state.energy + 17.0 * hours
                resting -> state.energy + 5.0 * hours
                else -> state.energy - AWAKE_ENERGY_DRAIN_PER_HOUR * hours
            }
            val pressure = when {
                standby -> approach(state.sleepPressure, pressureTarget, if (night) 0.65 else 0.35)
                mode == "sleep" -> state.sleepPressure - 15.0 * hours
                mode == "nap" -> state.sleepPressure - 19.0 * hours
                resting -> state.sleepPressure - 5.0 * hours
                else -> state.sleepPressure + AWAKE_SLEEP_PRESSURE_PER_HOUR * hours
            }
            // Daytime offline time still belongs to the person's day. Around 6 points/h
            // and a real sandwich's 34-point relief support breakfast/lunch/dinner spacing.
            val hungerRate = when {
                standby -> if (night) 1.1 else 6.0
                mode == "sleep" -> 1.1
                mode == "nap" -> 2.0
                resting -> 4.0
                else -> AWAKE_HUNGER_PER_HOUR
            }
            val thirstRate = when {
                standby -> if (night) 2.0 else 8.0
                mode == "sleep" -> 2.0
                mode == "nap" -> 2.8
                resting -> 5.0
                else -> AWAKE_THIRST_PER_HOUR
            }
            val boredom = when {
                standby -> approach(state.boredom, 100.0, if (night) 0.0055 else 0.075)
                sleeping -> approach(state.boredom, 100.0, 0.0035)
                resting -> approach(state.boredom, 100.0, 0.035)
                else -> state.boredom + AWAKE_MONOTONY_PER_HOUR * hours
            }
            val comfort = when {
                standby -> approach(state.comfort, if (night) 84.0 else 72.0, 0.25)
                sleeping || resting -> state.comfort + 3.0 * hours
                else -> state.comfort - AWAKE_COMFORT_DRAIN_PER_HOUR * hours
            }
            val finished = state.recoveryMode != "offline_sleep" &&
                (state.recoveryUntil == null || !state.recoveryUntil!!.isAfter(end))
            state = state.copy(lastAt = end, energy = bounded(energy), sleepPressure = bounded(pressure),
                hunger = bounded(state.hunger + hours * hungerRate),
                thirst = bounded(state.thirst + hours * thirstRate), comfort = bounded(comfort),
                boredom = bounded(boredom), recoveryMode = if (finished) "" else state.recoveryMode,
                recoveryUntil = if (finished) null else state.recoveryUntil)
        }
        return state
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
        // Ordinary motions (looking at a clock, opening a lamp, making a bed,
        // ordering food) are NOT novel experiences. They used to each reduce
        // boredom by 10, wiping out hours of elapsed-time growth. Only a
        // recorded, actually experienced change of scene can ease monotony.
        val noveltyRelief = when (activityId) {
            "cloud_walk", "cloud_edge", "courtyard_walk", "courtyard_linger",
            "discovered_walk", "discovered_observe", "browse_games",
            "choose_arcade" -> 8.0
            "cloud_feel", "watch_sky", "water_edge", "window_cafe",
            "admire_art", "play_with_decor", "people_watch", "garden_pause",
            "cafe_linger", "arcade_linger", "discovered_pause" -> 4.0
            else -> 0.0
        }
        val experienced = if (noveltyRelief > 0.0)
            current.copy(boredom = bounded(current.boredom - noveltyRelief)) else current
        DigitalCafeMenu.byConsume(activityId)?.let { item ->
            return experienced.copy(
                thirst = bounded(current.thirst - item.thirstRelief),
                hunger = bounded(current.hunger - item.hungerRelief),
                comfort = bounded(current.comfort + item.comfortGain),
            )
        }
        return when (activityId) {
            "slow_drink" -> experienced.copy(thirst = bounded(current.thirst - 26.0),
                comfort = bounded(current.comfort + 3.0))
            "have_snack" -> experienced.copy(hunger = bounded(current.hunger - 25.0),
                comfort = bounded(current.comfort + 2.0))
            else -> experienced
        }
    }

    /** Only a confirmed, not pending, search/read/game receipt changes novelty. */
    fun afterExploration(before: EmbodiedBodySnapshot, at: Instant): EmbodiedBodySnapshot =
        if (at.isBefore(before.lastAt)) before else advance(before, at).let {
            it.copy(boredom = (it.boredom - 20.0).coerceAtLeast(0.0))
        }

    fun endWorldActivity(before: EmbodiedBodySnapshot, at: Instant): EmbodiedBodySnapshot =
        if (at.isBefore(before.lastAt)) before
        else advance(before, at).copy(recoveryMode = "", recoveryUntil = null)

    fun prompt(snapshot: EmbodiedBodySnapshot): String {
        fun number(n: Double) = n.toInt().coerceIn(0, 100)
        return buildString {
            appendLine("【数字身体模拟｜持续的身体线索，不是情绪、人格或真实传感器数据】")
            appendLine("精力=${number(snapshot.energy)}；睡眠压力=${number(snapshot.sleepPressure)}；饥饿感=${number(snapshot.hunger)}；口渴感=${number(snapshot.thirst)}；舒适度=${number(snapshot.comfort)}；单调感=${number(snapshot.boredom)}（0—100，均为数字世界内模拟值）。")
            if (snapshot.recoveryMode == "offline_sleep")
                appendLine("当前离线休眠中；白天是浅休眠，仍有日间精力节律与吃喝需求；夜间恢复更充分，不等于全天一直熟睡。")
            else if (snapshot.recoveryMode.isNotBlank() && snapshot.recoveryUntil != null)
                appendLine("有真实开始、已存档的${when (snapshot.recoveryMode) { "sleep" -> "睡眠"; "nap" -> "小睡"; else -> "休息" }}活动，预计结束于${snapshot.recoveryUntil}；未到时间不冒充完成。")
            appendLine("饥饿和口渴可影响想吃喝的意愿；白天即使多数时间离线，也会按经过的生活时间积累，早餐、午餐、晚餐和间隔饮水可自然成为本人选择；正餐可间隔约4—6小时，不必等到很饿才准备；可提前准备或通过原日程安排，不能仅因休眠就跳过一整天的吃喝。真正睡眠及夜间休眠积累较慢。睡眠压力高、精力低可以使本人倾向休息。单调感表示一段生活时间缺少有变化的体验，白天离线也会明显积累，夜间较慢：普通整理、查看时间和吃喝不会自动消除；真正的散步、观察新风景有少量缓解，实际游戏、阅读、探索回执能明显缓解。白天休眠不会反复补满精力或清空睡眠压力；夜间与实际睡眠活动恢复更充分，好奇和未解决心事不会被清空。角色可以因兴趣、独处、好奇或无聊想到查知识、看外界、观察风景、找熟人，也可克制或什么都不做。这些只是决策线索，绝非必须搜索、强制发消息或改变性格的阈值，不越过已有权限和费用限制；点单不代表已食用。")
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
        return EmbodiedBodyPhysics.advance(alignedToPresence(characterId, saved, now), now)
    }

    /** Physical sleep follows the one authoritative online lease, not a second timer. */
    private fun alignedToPresence(characterId: String, before: EmbodiedBodySnapshot, now: Instant): EmbodiedBodySnapshot {
        // Native citizens can be offline in chat while actively living their
        // verified duties. A phone-chat lease is not physical sleep for them.
        if (ResidentCharacterBridge.nativeResident(characterId) != null) return before
        val presence = CompanionOnlineStore.states.value[characterId] ?: return before
        if (presence.isOnline(now) || before.recoveryMode == "offline_sleep") return before
        val sleepAt = maxOf(presence.onlineUntil, before.lastAt)
        return EmbodiedBodyPhysics.advance(before, sleepAt).copy(
            recoveryMode = "offline_sleep", recoveryUntil = null)
    }

    @Synchronized
    fun onPresenceSleep(characterId: String, at: Instant) {
        if (ResidentCharacterBridge.nativeResident(characterId) != null) return
        if (!DigitalLifeProfileStore.isEnabled(characterId) || !CharacterLifeStore.bodyStorageReady()) return
        val before = CharacterLifeStore.bodyState(characterId)?.let(EmbodiedBodySnapshot::fromJson)
            ?: EmbodiedBodyPhysics.fresh(at)
        if (before.recoveryMode == "offline_sleep") return
        val sleepAt = maxOf(at, before.lastAt)
        val after = EmbodiedBodyPhysics.advance(before, sleepAt).copy(
            recoveryMode = "offline_sleep", recoveryUntil = null)
        CharacterLifeStore.saveBodyState(characterId, after.toJson(), cause = "角色下线休眠")
    }

    @Synchronized
    fun onPresenceWake(characterId: String, previous: CompanionOnlineState?, at: Instant) {
        if (ResidentCharacterBridge.nativeResident(characterId) != null) return
        if (!DigitalLifeProfileStore.isEnabled(characterId) || !CharacterLifeStore.bodyStorageReady()) return
        val before = CharacterLifeStore.bodyState(characterId)?.let(EmbodiedBodySnapshot::fromJson)
            ?: EmbodiedBodyPhysics.fresh(at)
        if (previous?.isOnline(at) == true && before.recoveryMode != "offline_sleep") return
        val sleeping = if (previous != null && !previous.isOnline(at)) {
            val started = maxOf(previous.onlineUntil, before.lastAt)
            if (before.recoveryMode == "offline_sleep") before
            else EmbodiedBodyPhysics.advance(before, started).copy(
                recoveryMode = "offline_sleep", recoveryUntil = null)
        } else before
        val awake = EmbodiedBodyPhysics.advance(sleeping, at).copy(
            recoveryMode = "", recoveryUntil = null)
        if (awake != before) CharacterLifeStore.saveBodyState(characterId, awake.toJson(), cause = "角色醒来")
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
        val next = EmbodiedBodyPhysics.advance(alignedToPresence(characterId, previous, now), now)
        if (saved == null || next != previous) CharacterLifeStore.saveBodyState(characterId, next.toJson())
        val digitalTemperature = DigitalWorldEnvironment.snapshot(now).temperatureCelsius
        val location = DigitalWorldStore.locationOf(characterId)
        return EmbodiedBodyPhysics.prompt(next) + "\n" +
            environmentalSensation(location, digitalTemperature)
    }

    /** Successful external exploration is a real activity, not a plan or a thought. */
    @Synchronized fun onExplorationReceipt(characterId: String, at: Instant = Instant.now()) {
        if (!DigitalLifeProfileStore.isEnabled(characterId) || !CharacterLifeStore.bodyStorageReady()) return
        val before = CharacterLifeStore.bodyState(characterId)?.let(EmbodiedBodySnapshot::fromJson)
            ?: EmbodiedBodyPhysics.fresh(at)
        val next = EmbodiedBodyPhysics.afterExploration(before, at)
        if (next != before) CharacterLifeStore.saveBodyState(characterId, next.toJson(),
            cause = "已核实探索活动")
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
