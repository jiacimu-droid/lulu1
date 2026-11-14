package com.jiacimu.lulu.data

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject

/** Time-aware, locally executed commitments of native-world residents.
 *
 * This is an environment clock, not a new personality model. Work is an
 * existing duty; preferences, ordinary chats, relationships and optional
 * moments remain on the same role-owned decision/execution architecture.
 * Each slot is idempotent; no off-screen simulation runs chargeable AI calls.
 */
internal object ResidentDailyRoutine {
    internal data class Plan(val slot: String, val location: String, val activity: String,
        val intendedActivity: String = "")

    /** Only an explicitly saved role decision may authorize a cafe order.
     * Legacy plans without the new optional fields cause no purchase. */
    internal fun chosenCafeOrder(daily: JSONObject?, slot: String): String? {
        val field = when (slot) {
            "lunch" -> "cafeOrderLunch"
            "evening" -> "cafeOrderEvening"
            else -> return null
        }
        val selectedId = daily?.optString(field).orEmpty()
        return DigitalCafeMenu.offerings.firstOrNull { it.id == selectedId }
            ?.consumeActivity
    }

    private fun dutyPlace(person: DigitalWorldResident): String = when {
        person.templateName == "苏弦" && person.identity == "活动与游戏协助员" ->
            DigitalWorldPublicPlaces.GAME_HALL
        person.templateName == "青禾" && person.identity == "游戏馆机台管理员" ->
            DigitalWorldPublicPlaces.GAME_HALL
        person.templateName in setOf("闻澜", "弥砂", "椿铃", "南汐", "迟墨", "洛芽",
            "栖迟", "言溪", "岚序", "照野") -> DigitalWorldPublicPlaces.COURTYARD
        else -> person.neighborhoodCode
    }

    /** A job identifies work that can be observed, not a fictional completed repair. */
    private fun shiftActivity(person: DigitalWorldResident): String = when {
        person.templateName == "青禾" && person.identity == "游戏馆机台管理员" ->
            "照看游戏馆机台、核对设备状态与玩法说明；未实际开局就没有成绩"
        person.templateName == "苏弦" && person.identity == "活动与游戏协助员" ->
            "组织游戏馆活动、向到场玩家介绍游戏；不虚构玩家和比赛结果"
        person.templateName == "闻澜" && person.identity == "公共区域维护员" ->
            "检查庭院座椅与照明状况；实际维修须另有执行证据"
        person.templateName == "洛芽" && person.identity == "社区活动布置师" ->
            "整理庭院活动场地和展示区域；没有真实活动不能编造来宾"
        person.templateName == "南汐" && person.identity == "社区迎新接待员" ->
            "在庭院接待真正到访的新人、整理公开生活信息"
        else -> "履行${person.identity}的日常职责"
    }

    fun plan(person: DigitalWorldResident, at: Instant,
        zone: ZoneId = ZoneId.systemDefault(), context: Context? = null): Plan {
        // A cold-start worker still reads the *persisted* decision rather than
        // replacing the resident's chosen location with a script fallback.
        val daily = context?.let { ResidentDailyDecision.planFor(it, person.id, at) }
            ?: ResidentDailyDecision.planForApp(person.id, at)
        return planWithDaily(person, at, zone, daily)
    }

    /** Keep an already visited home overnight; it is a location fact, not proof of
     * the host agreeing to a sleepover or the resident actually falling asleep. */
    private fun defaultNightLocation(person: DigitalWorldResident): String =
        person.currentLocationCode.takeIf {
            it.startsWith("home:") || it.startsWith("resident-home:")
        } ?: person.homeLocationCode

    /** Pure planner used by the world clock and tests. Duty is a world fact,
     * leisure belongs to the resident. An absent decision cannot invent
     * a wish to visit the cafe or create an encounter. */
    internal fun planWithDaily(person: DigitalWorldResident, at: Instant,
        zone: ZoneId, daily: JSONObject?): Plan {
        val local = at.atZone(zone)
        val minute = local.hour * 60 + local.minute
        val lunchPlace = daily?.optString("lunch")?.let(ResidentDailyDecision::allowedLeisureLocation)
            ?.let { ResidentDailyDecision.publicPlace(it, person.homeLocationCode) }
            ?: person.homeLocationCode
        val eveningPlace = daily?.optString("evening")?.let(ResidentDailyDecision::allowedLeisureLocation)
            ?.let { ResidentDailyDecision.publicPlace(it, person.homeLocationCode) }
            ?: person.homeLocationCode
        val eveningActivity = daily?.optString("eveningActivity").orEmpty().trim()
        val offset = Math.floorMod(person.id.hashCode(), 35)
        if (person.isCafeBarista) {
            // A barista still has real working hours. Her own saved decisions
            // may now select a lunch break or evening outing instead of being
            // discarded by the all-day-shift early return.
            return when {
                minute < 9 * 60 -> Plan("home", defaultNightLocation(person), "下班休息")
                minute < 12 * 60 + offset -> Plan("morning", DigitalWorldPublicPlaces.CAFE,
                    "经营咖啡角、接待真实在场的顾客")
                minute < 13 * 60 + offset -> Plan("lunch", lunchPlace, "按当天自己的计划过午休")
                minute < 18 * 60 -> Plan("afternoon", DigitalWorldPublicPlaces.CAFE,
                    "继续经营咖啡角")
                minute < 19 * 60 + offset -> Plan("evening", eveningPlace,
                    "进入下班后的自由活动时段", intendedActivity = eveningActivity)
                minute < 21 * 60 -> Plan("late_shift", DigitalWorldPublicPlaces.CAFE,
                    "回到咖啡角继续工作")
                else -> Plan("home", defaultNightLocation(person), "下班休息")
            }
        }
        // Stable duty hours coexist with self-chosen leisure; different
        // resident breaks remain staggered without simulating extra dialogue.
        return when {
            minute < 8 * 60 -> Plan("night", defaultNightLocation(person), "在真实所在住宅休息")
            minute < 12 * 60 + offset -> Plan("morning", dutyPlace(person), shiftActivity(person))
            minute < 13 * 60 + offset -> Plan("lunch", lunchPlace, "按当天自己的计划过午休")
            minute < 18 * 60 -> Plan("afternoon", dutyPlace(person), shiftActivity(person))
            minute < 19 * 60 + offset -> Plan("evening", eveningPlace,
                "进入下班后的自由活动时段", intendedActivity = eveningActivity)
            else -> Plan("home", defaultNightLocation(person),
                "结束当日工作；在真实所在住宅休息，不能把休眠等同于瞬移")
        }
    }

    /** Execute only observed slots. Never backfill imagined conversations or meals. */
    @Synchronized
    fun refresh(context: Context, at: Instant = Instant.now()): Int {
        val app = context.applicationContext
        DigitalWorldExpansionStore.initialize(app)
        ResidentCharacterBridge.sync(app, at)
        val residents = DigitalWorldExpansionStore.residents()
        // Employees are resolved first so a visiting worker can really order.
        var transitions = 0
        for (resident in residents.sortedByDescending { it.isCafeBarista }) {
            val plan = plan(resident, at, context = app)
            val key = at.atZone(ZoneId.systemDefault()).toLocalDate().toString() + ":" + plan.slot
            if (resident.dutyKey == key) continue
            val canonical = DigitalWorldExpansionStore.commitResidentDuty(
                resident.id, plan.location, key, plan.activity, at) ?: continue
            val characterId = ResidentCharacterBridge.characterId(resident.id)
            check(DigitalWorldStore.syncNativeResidentLocation(characterId, canonical.currentLocationCode, at))
            val before = EmbodiedBodyRuntime.storedOrFresh(characterId, at)
            var body = EmbodiedBodyPhysics.advance(before, at)
                .copy(recoveryMode = "", recoveryUntil = null)
            var activity = plan.activity
            if (!CompanionOnlineStore.residentIsAwake(app, characterId, at)) {
                body = body.copy(recoveryMode = "offline_sleep", recoveryUntil = null)
            } else if (plan.slot in setOf("morning", "afternoon", "shift", "late_shift")) {
                body = body.copy(energy = (body.energy - 3.0).coerceAtLeast(0.0),
                    comfort = (body.comfort - 1.5).coerceAtLeast(0.0))
            }
            CharacterLifeStore.saveBodyState(characterId, body.toJson(),
                cause = "原住民真实日程：" + activity)
            if (CompanionOnlineStore.residentIsAwake(app, characterId, at) &&
                plan.slot in setOf("lunch", "evening") &&
                canonical.currentLocationCode == DigitalWorldPublicPlaces.CAFE &&
                residents.any { it.isCafeBarista && plan(it, at, context = app).location == DigitalWorldPublicPlaces.CAFE }) {
                // The resident already chose; the world clock can only verify
                // present location, a real barista, inventory and action receipts.
                val savedDay = ResidentDailyDecision.planFor(app, resident.id, at)
                val choice = chosenCafeOrder(savedDay, plan.slot)?.let(DigitalCafeMenu::byConsume)
                if (choice != null) {
                    // Use the very same inventory/receipt executor used by the
                    // main companion. A failed order never fabricates consumption.
                    val order = runCatching {
                        DigitalWorldActivityStateStore.apply(characterId, null,
                            choice.orderActivity, "向在岗店员点单", at)
                    }.getOrNull()
                    if (order != null) {
                        val consumed = runCatching {
                            DigitalWorldActivityStateStore.apply(characterId, null,
                                choice.consumeActivity, "实际吃喝", at)
                        }.getOrNull()
                        activity = if (consumed != null)
                            "在咖啡角点单并实际${choice.verb}${choice.name}"
                        else "在咖啡角领取${choice.name}，尚未食用"
                    }
                }
            }
            SharedExperienceTimeline.record(
                eventId = "native-routine-${resident.id}-$key",
                characterId = characterId,
                channel = "数字世界·原住民生活", speaker = resident.name,
                content = "时间=${at}；地点=${canonical.currentLocationCode}；当下职责/状态=$activity；原住民本人已完成这一日程切换。" +
                    if (plan.intendedActivity.isNotBlank())
                        "接下来想做：${plan.intendedActivity}；这项具体活动尚待实际执行，日程切换不证明已经完成。"
                    else "",
                source = "resident-duty", occurredAt = at, triggerExtraction = false,
                evidenceKind = EventEvidenceKind.Observation,
            )
            transitions++
        }
        return transitions
    }
}
