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

    fun advance(before: EmbodiedBodySnapshot, now: Instant): EmbodiedBodySnapshot {
        if (!now.isAfter(before.lastAt)) return before
        val duration = Duration.between(before.lastAt, now)
        val hours = duration.seconds.toDouble() / 3600.0 + duration.nano.toDouble() / 3_600_000_000_000.0
        val end = before.recoveryUntil
        val offlineSleep = before.recoveryMode == "offline_sleep"
        val recoveryHours = if (offlineSleep) hours else if (before.recoveryMode.isNotBlank() && end != null && end.isAfter(before.lastAt)) {
            val boundedEnd = if (end.isBefore(now)) end else now
            if (boundedEnd.isAfter(before.lastAt))
                Duration.between(before.lastAt, boundedEnd).toMillis().toDouble() / 3_600_000.0 else 0.0
        } else 0.0
        val awakeHours = (hours - recoveryHours).coerceAtLeast(0.0)
        val mode = before.recoveryMode
        val energyGain = when (mode) {
            "sleep", "offline_sleep" -> 12.5
            "nap" -> 17.0
            "rest" -> 5.0
            else -> 0.0
        }
        val sleepRelief = when (mode) {
            "sleep", "offline_sleep" -> 15.0
            "nap" -> 19.0
            "rest" -> 5.0
            else -> 0.0
        }
        // A person generally does not get as hungry or thirsty while asleep as
        // while actively awake, but neither need is magically reset by rest.
        val sleepHungerRate = when (mode) {
            "sleep", "offline_sleep" -> 1.1
            "nap" -> 2.0
            "rest" -> 4.0
            else -> 0.0
        }
        val sleepThirstRate = when (mode) {
            "sleep", "offline_sleep" -> 2.0
            "nap" -> 2.8
            "rest" -> 5.0
            else -> 0.0
        }
        fun bounded(n: Double) = n.coerceIn(0.0, 100.0)
        // Rest is not entertainment. Retain existing curiosity and allow only
        // very slow saturation toward 100 during real sleep/offline sleep.
        // The exponential form is stable whether the app checks once after
        // eight hours or eight times during the same interval.
        val restingNovelty = before.boredom + (100.0 - before.boredom) *
            (1.0 - kotlin.math.exp(-recoveryHours *
                (if (offlineSleep) 0.55 else 0.35) / 100.0))
        val finished = !offlineSleep && (end == null || !end.isAfter(now))
        return before.copy(
            lastAt = now,
            energy = bounded(before.energy - awakeHours * AWAKE_ENERGY_DRAIN_PER_HOUR +
                recoveryHours * energyGain),
            sleepPressure = bounded(before.sleepPressure + awakeHours * AWAKE_SLEEP_PRESSURE_PER_HOUR -
                recoveryHours * sleepRelief),
            hunger = bounded(before.hunger + awakeHours * AWAKE_HUNGER_PER_HOUR +
                recoveryHours * sleepHungerRate),
            thirst = bounded(before.thirst + awakeHours * AWAKE_THIRST_PER_HOUR +
                recoveryHours * sleepThirstRate),
            comfort = bounded(before.comfort - awakeHours * AWAKE_COMFORT_DRAIN_PER_HOUR +
                recoveryHours * 3.0),
            boredom = bounded(restingNovelty + awakeHours * AWAKE_MONOTONY_PER_HOUR),
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
                appendLine("当前离线休眠中；模拟身体正在按真实经过的时间恢复。")
            else if (snapshot.recoveryMode.isNotBlank() && snapshot.recoveryUntil != null)
                appendLine("有真实开始、已存档的${when (snapshot.recoveryMode) { "sleep" -> "睡眠"; "nap" -> "小睡"; else -> "休息" }}活动，预计结束于${snapshot.recoveryUntil}；未到时间不冒充完成。")
            appendLine("饥饿和口渴可影响想吃喝的意愿；清醒几小时不进食、饮水或休息时感觉会逐渐明显，睡眠中则变慢。睡眠压力高、精力低可以使本人倾向休息。单调感表示清醒时一段时间缺少有变化的体验：普通整理、查看时间和吃喝不会自动消除；真正的散步、观察新风景有少量缓解，实际游戏、阅读、探索回执能明显缓解。离线休眠恢复体力但不清空好奇心。角色可以因兴趣、独处、好奇或无聊想到查知识、看外界、观察风景、找熟人，也可克制或什么都不做。这些只是决策线索，绝非必须搜索、强制发消息或改变性格的阈值，不越过已有权限和费用限制；点单不代表已食用。")
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
