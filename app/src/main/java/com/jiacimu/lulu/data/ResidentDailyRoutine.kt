package com.jiacimu.lulu.data

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/** Time-aware, locally executed commitments of native-world residents.
 *
 * This is an environment clock, not a new personality model. Work is an
 * existing duty; preferences, ordinary chats, relationships and optional
 * moments remain on the same role-owned decision/execution architecture.
 * Each slot is idempotent; no off-screen simulation runs chargeable AI calls.
 */
internal object ResidentDailyRoutine {
    internal data class Plan(val slot: String, val location: String, val activity: String)

    private fun dutyPlace(person: DigitalWorldResident): String = when (person.templateName) {
        "苏弦" -> DigitalWorldPublicPlaces.GAME_HALL
        "闻澜", "弥砂", "椿铃", "南汐", "迟墨", "洛芽",
        "栖迟", "青禾", "言溪", "岚序", "照野" -> DigitalWorldPublicPlaces.COURTYARD
        else -> person.neighborhoodCode
    }

    fun plan(person: DigitalWorldResident, at: Instant, zone: ZoneId = ZoneId.systemDefault()): Plan {
        val local = at.atZone(zone)
        val minute = local.hour * 60 + local.minute
        if (person.isCafeBarista) return if (minute in 9 * 60 until 21 * 60)
            Plan("shift", DigitalWorldPublicPlaces.CAFE, "经营咖啡角、接待真实在场的顾客")
        else Plan("home", person.homeLocationCode, "下班休息")
        // Different residents stagger the breaks, allowing simultaneous
        // visitors instead of rerolling a crowd per encounter.
        val offset = Math.floorMod(person.id.hashCode(), 35)
        return when {
            minute < 8 * 60 -> Plan("night", person.homeLocationCode, "在家休息")
            minute < 12 * 60 + offset -> Plan("morning", dutyPlace(person), "履行${person.identity}的日常职责")
            minute < 13 * 60 + offset -> Plan("lunch", DigitalWorldPublicPlaces.CAFE, "午间休息、看看咖啡角")
            minute < 18 * 60 -> Plan("afternoon", dutyPlace(person), "继续完成${person.identity}的工作")
            minute < 19 * 60 + offset -> Plan("evening", DigitalWorldPublicPlaces.CAFE, "下班后到咖啡角坐坐")
            else -> Plan("home", person.homeLocationCode, "回家休息")
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
            val plan = plan(resident, at)
            val key = at.atZone(ZoneId.systemDefault()).toLocalDate().toString() + ":" + plan.slot
            if (resident.dutyKey == key) continue
            val canonical = DigitalWorldExpansionStore.commitResidentDuty(
                resident.id, plan.location, key, plan.activity, at) ?: continue
            val characterId = ResidentCharacterBridge.characterId(resident.id)
            check(DigitalWorldStore.syncNativeResidentLocation(characterId, canonical.currentLocationCode, at))
            val before = CharacterLifeStore.bodyState(characterId)
                ?.let(EmbodiedBodySnapshot::fromJson) ?: EmbodiedBodyPhysics.fresh(at)
            var body = EmbodiedBodyPhysics.advance(before, at)
            var activity = plan.activity
            if (plan.slot in setOf("morning", "afternoon", "shift")) {
                body = body.copy(energy = (body.energy - 3.0).coerceAtLeast(0.0),
                    comfort = (body.comfort - 1.5).coerceAtLeast(0.0))
            }
            if (plan.slot in setOf("lunch", "evening") &&
                residents.any { it.isCafeBarista && plan(it, at).location == DigitalWorldPublicPlaces.CAFE }) {
                val choice = when {
                    body.hunger >= 23.0 -> DigitalCafeMenu.byConsume("cafe_consume_sandwich")
                    body.thirst >= 23.0 -> DigitalCafeMenu.byConsume("cafe_consume_tea")
                    else -> null
                }
                if (choice != null) {
                    body = EmbodiedBodyPhysics.applyWorldActivity(body, choice.consumeActivity, at, null)
                    activity = "到咖啡角购买并${choice.verb}${choice.name}"
                }
            }
            CharacterLifeStore.saveBodyState(characterId, body.toJson(),
                cause = "原住民真实日程：" + activity)
            SharedExperienceTimeline.record(
                eventId = "native-routine-${resident.id}-$key",
                characterId = characterId,
                channel = "数字世界·原住民生活", speaker = resident.name,
                content = "时间=${at}；地点=${canonical.currentLocationCode}；活动=$activity；原住民本人已完成这一日程切换。",
                source = "resident-duty", occurredAt = at, triggerExtraction = false,
                evidenceKind = EventEvidenceKind.Observation,
            )
            transitions++
        }
        return transitions
    }
}
