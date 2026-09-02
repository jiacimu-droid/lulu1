package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

internal data class DigitalWorldLifeTick(
    val incidentId: String,
    val locationCode: String,
    val locationName: String,
    val kind: String,
    val anchorItemId: String,
    val anchorItemName: String,
    val status: String,
    val stage: Int,
    val summary: String,
    val occurredAt: Instant,
)

/**
 * Persistent, program-owned incidents for the digital world.
 *
 * The model may react to these facts, but it cannot create, move, resolve or rewrite them.
 * Every furniture reference is resolved from [DigitalWorldStore] by a stable item ID.
 */
internal object DigitalWorldLifeEventStore {
    private const val PREFS_NAME = "lulu_digital_world_life_events"
    private const val KEY_INCIDENTS = "incidents_v1"
    private const val STATUS_ACTIVE = "active"
    private const val STATUS_RESOLVED = "resolved"
    private const val MAX_INCIDENTS = 240

    private val lock = Any()
    private var prefs: android.content.SharedPreferences? = null
    private var incidents: List<Incident> = emptyList()

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        incidents = decode(prefs?.getString(KEY_INCIDENTS, null))
    }

    fun tick(
        context: Context,
        characterId: String,
        now: Instant = Instant.now(),
        arrivalBoost: Boolean = false,
    ): DigitalWorldLifeTick? {
        initialize(context)
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return null
        val locationCode = DigitalWorldStore.locationOf(characterId)
        val actorName = MigratedDomainStores.characters.get(characterId).displayName
        val result = synchronized(lock) {
            val active = incidents
                .filter { it.status == STATUS_ACTIVE && it.locationCode == locationCode }
                .minByOrNull(Incident::createdAt)
            val pair = if (active != null) {
                advance(active, characterId, actorName, now)
            } else {
                val chance = if (arrivalBoost) 58 else 28
                if (roll("$characterId:$locationCode:${now.epochSecond / 3_600}", 100) >= chance) return@synchronized null
                spawn(locationCode, characterId, actorName, now)
            } ?: return@synchronized null
            val updated = pair.first
            incidents = (incidents.filterNot { it.id == updated.id } + updated)
                .sortedBy(Incident::updatedAt)
                .takeLast(MAX_INCIDENTS)
            persistLocked()
            pair.second
        } ?: return null

        SharedExperienceTimeline.record(
            eventId = "world-incident-${result.incidentId}-$characterId-${now.toEpochMilli()}",
            characterId = characterId,
            channel = "数字世界事件·${result.locationName}",
            speaker = "数字世界",
            content = result.summary,
            occurredAt = now,
        )
        return result
    }

    fun recordWitness(characterId: String, tick: DigitalWorldLifeTick, now: Instant = tick.occurredAt) {
        if (characterId.isBlank()) return
        SharedExperienceTimeline.record(
            eventId = "world-incident-${tick.incidentId}-$characterId-${now.toEpochMilli()}",
            characterId = characterId,
            channel = "数字世界事件·${tick.locationName}",
            speaker = "数字世界",
            content = tick.summary,
            occurredAt = now,
        )
    }

    fun contextFor(characterId: String): String {
        if (characterId.isBlank()) return ""
        val location = DigitalWorldStore.locationOf(characterId)
        val active = synchronized(lock) {
            incidents.filter { it.locationCode == location && it.status == STATUS_ACTIVE }
                .sortedBy(Incident::createdAt)
                .take(6)
        }
        if (active.isEmpty()) return "当前位置没有尚未解决的持续事件。"
        return buildString {
            appendLine("【数字世界尚未解决的持续事件｜程序权威状态】")
            active.forEach { incident ->
                append("- incidentId=${incident.id}；类型=${incident.kind}；阶段=${incident.stage}；")
                if (incident.anchorItemId.isNotBlank()) {
                    append("真实家具 itemId=${incident.anchorItemId}；家具=${incident.anchorItemName}；")
                }
                appendLine("当前状态=${incident.summary}")
            }
        }.trim()
    }

    fun clearCharacter(characterId: String) {
        if (characterId.isBlank()) return
        synchronized(lock) {
            val home = DigitalWorldStore.homeLocation(characterId)
            incidents = incidents.filterNot { incident ->
                incident.locationCode == home
            }
            persistLocked()
        }
    }

    private fun spawn(
        locationCode: String,
        characterId: String,
        actorName: String,
        now: Instant,
    ): Pair<Incident, DigitalWorldLifeTick>? {
        val homeOwnerId = locationCode.takeIf { it.startsWith("home:") }?.removePrefix("home:")
        val realItems = homeOwnerId?.let(DigitalWorldStore::itemsAtHome).orEmpty()
        val anchor = realItems.getOrNull(roll("$locationCode:${now.toEpochMilli()}:anchor", realItems.size.coerceAtLeast(1)))
        val kind = when {
            realItems.isNotEmpty() -> when (roll("$characterId:$locationCode:${now.toEpochMilli()}:kind", 100)) {
                in 0..44 -> "roach"
                in 45..72 -> "glimmer_mote"
                else -> "odd_sound"
            }
            locationCode == DigitalWorldStore.CLOUD_MEADOW -> if (roll("$characterId:$now:cloud", 2) == 0) "cloud_ripple" else "static_cluster"
            locationCode == DigitalWorldStore.ARRIVAL -> "arrival_echo"
            else -> "floating_dust"
        }
        val incident = Incident(
            id = UUID.randomUUID().toString(),
            kind = kind,
            locationCode = locationCode,
            anchorItemId = anchor?.id.orEmpty(),
            anchorItemName = anchor?.name.orEmpty(),
            status = STATUS_ACTIVE,
            stage = 0,
            summary = openingSummary(kind, actorName, anchor?.name.orEmpty()),
            lastActorCharacterId = characterId,
            createdAt = now,
            updatedAt = now,
        )
        return incident to incident.toTick(now)
    }

    private fun advance(
        current: Incident,
        characterId: String,
        actorName: String,
        now: Instant,
    ): Pair<Incident, DigitalWorldLifeTick> {
        val homeOwnerId = current.locationCode.takeIf { it.startsWith("home:") }?.removePrefix("home:")
        val realItems = homeOwnerId?.let(DigitalWorldStore::itemsAtHome).orEmpty()
        val existingAnchor = realItems.firstOrNull { it.id == current.anchorItemId }
        if (current.anchorItemId.isNotBlank() && existingAnchor == null) {
            val summary = "$actorName 检查持续事件时，程序确认原先关联的家具已不在当前权威家园中；为避免引用不存在的物品，这条事件已结束。"
            val updated = current.copy(
                status = STATUS_RESOLVED,
                stage = current.stage + 1,
                summary = summary,
                lastActorCharacterId = characterId,
                updatedAt = now,
            )
            return updated to updated.toTick(now)
        }
        val fallbackAnchor = realItems.getOrNull(
            roll("${current.id}:${current.stage}:fallback", realItems.size.coerceAtLeast(1)),
        )
        val anchor = existingAnchor ?: fallbackAnchor
        val nextStage = current.stage + 1
        val outcomeRoll = roll("${current.id}:$characterId:$nextStage:${now.epochSecond / 60}", 100)
        val resolveThreshold = (18 + nextStage * 17).coerceAtMost(78)
        val resolved = outcomeRoll < resolveThreshold
        val nextAnchor = if (!resolved && realItems.size > 1) {
            realItems.filterNot { it.id == anchor?.id }
                .let { choices -> choices.getOrNull(roll("${current.id}:move:$nextStage", choices.size.coerceAtLeast(1))) }
                ?: anchor
        } else {
            anchor
        }
        val summary = continuationSummary(
            kind = current.kind,
            actorName = actorName,
            previousAnchor = anchor?.name.orEmpty().ifBlank { current.anchorItemName },
            nextAnchor = nextAnchor?.name.orEmpty(),
            resolved = resolved,
            stage = nextStage,
        )
        val updated = current.copy(
            anchorItemId = nextAnchor?.id.orEmpty().ifBlank { current.anchorItemId },
            anchorItemName = nextAnchor?.name.orEmpty().ifBlank { current.anchorItemName },
            status = if (resolved) STATUS_RESOLVED else STATUS_ACTIVE,
            stage = nextStage,
            summary = summary,
            lastActorCharacterId = characterId,
            updatedAt = now,
        )
        return updated to updated.toTick(now)
    }

    private fun openingSummary(kind: String, actorName: String, anchorName: String): String = when (kind) {
        "roach" -> "$actorName 在真实家具“$anchorName”上看见一只蟑螂；它立刻钻进家具边缘的视线死角，目前仍未找到。"
        "glimmer_mote" -> "$actorName 发现一粒不稳定的微光停在真实家具“$anchorName”边缘，靠近时它滑进家具下方，目前仍在附近。"
        "odd_sound" -> "$actorName 听见真实家具“$anchorName”附近传出断断续续的轻响，来源暂时没有确认。"
        "cloud_ripple" -> "$actorName 在云眠原遇见一圈逆着周围流向扩散的云质波纹；波纹没有立刻消失，仍在缓慢移动。"
        "static_cluster" -> "$actorName 在云眠原发现一小团带静电感的感官云质黏在脚边，甩开后它仍在附近聚拢。"
        "arrival_echo" -> "$actorName 在世界入口听见一次延迟很久的回声；入口记录里暂时找不到对应来源，回声仍偶尔重现。"
        else -> "$actorName 注意到当前位置有一团细小漂浮物反复聚散，目前还没有消失。"
    }

    private fun continuationSummary(
        kind: String,
        actorName: String,
        previousAnchor: String,
        nextAnchor: String,
        resolved: Boolean,
        stage: Int,
    ): String = when (kind) {
        "roach" -> if (resolved) {
            "$actorName 再次追查那只蟑螂，终于在真实家具“$previousAnchor”附近找到并将它清理出去；这条持续事件已解决。"
        } else {
            val destination = nextAnchor.ifBlank { previousAnchor }
            "$actorName 又看见上次那只蟑螂从真实家具“$previousAnchor”边缘一闪而过，追过去时它躲到了真实家具“$destination”附近；第$stage 次仍未找到，事件继续存在。"
        }
        "glimmer_mote" -> if (resolved) {
            "$actorName 再次检查真实家具“$previousAnchor”附近，那粒微光短暂亮起后彻底散开；这条持续事件已解决。"
        } else {
            "$actorName 又捕捉到那粒微光，它从真实家具“$previousAnchor”滑向真实家具“${nextAnchor.ifBlank { previousAnchor }}”下方；第$stage 次仍未消失。"
        }
        "odd_sound" -> if (resolved) {
            "$actorName 顺着真实家具“$previousAnchor”附近的轻响反复确认，最终找到并排除了声音来源；这条持续事件已解决。"
        } else {
            "$actorName 再次听见真实家具“$previousAnchor”附近的轻响，但靠近后声音又停了；第$stage 次仍无法确认来源。"
        }
        "cloud_ripple" -> if (resolved) {
            "$actorName 再次遇见那圈异常云质波纹，它在靠近后逐渐恢复正常流向；这条持续事件已解决。"
        } else {
            "$actorName 又在云眠原看见同一圈逆向云质波纹；第$stage 次观察时它仍在缓慢迁移。"
        }
        "static_cluster" -> if (resolved) {
            "$actorName 再次踩到那团带静电感的云质，它这次彻底松散并融回云眠原；事件已解决。"
        } else {
            "$actorName 又被那团带静电感的云质黏住脚边；第$stage 次甩开后，它仍在不远处重新聚拢。"
        }
        "arrival_echo" -> if (resolved) {
            "$actorName 再次听见世界入口的延迟回声，这一次回声完整衰减并停止重现；事件已解决。"
        } else {
            "$actorName 第$stage 次听见同一段延迟回声，入口依旧没有出现与它对应的新来客。"
        }
        else -> if (resolved) {
            "$actorName 再次观察那团漂浮物，它最终散去；事件已解决。"
        } else {
            "$actorName 第$stage 次看见那团漂浮物重新聚拢，它仍留在当前位置。"
        }
    }

    private fun Incident.toTick(now: Instant): DigitalWorldLifeTick = DigitalWorldLifeTick(
        incidentId = id,
        locationCode = locationCode,
        locationName = locationName(locationCode),
        kind = kind,
        anchorItemId = anchorItemId,
        anchorItemName = anchorItemName,
        status = status,
        stage = stage,
        summary = summary,
        occurredAt = now,
    )

    private fun locationName(code: String): String = when (code) {
        DigitalWorldStore.ARRIVAL -> "世界入口"
        DigitalWorldStore.CLOUD_MEADOW -> "云眠原"
        else -> if (code.startsWith("home:")) {
            val ownerId = code.removePrefix("home:")
            DigitalWorldStore.state.value.homes[ownerId]?.name
                ?: "${MigratedDomainStores.characters.get(ownerId).displayName}的家"
        } else code
    }

    private fun roll(seed: String, bound: Int): Int {
        if (bound <= 1) return 0
        val positive = seed.hashCode().toLong() and 0x7fff_ffffL
        return (positive % bound).toInt()
    }

    private fun persistLocked() {
        val array = JSONArray().apply {
            incidents.forEach { incident ->
                put(
                    JSONObject()
                        .put("id", incident.id)
                        .put("kind", incident.kind)
                        .put("locationCode", incident.locationCode)
                        .put("anchorItemId", incident.anchorItemId)
                        .put("anchorItemName", incident.anchorItemName)
                        .put("status", incident.status)
                        .put("stage", incident.stage)
                        .put("summary", incident.summary)
                        .put("lastActorCharacterId", incident.lastActorCharacterId)
                        .put("createdAt", incident.createdAt.toString())
                        .put("updatedAt", incident.updatedAt.toString()),
                )
            }
        }
        prefs?.edit()?.putString(KEY_INCIDENTS, array.toString())?.apply()
    }

    private fun decode(raw: String?): List<Incident> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val location = item.optString("locationCode").trim()
                if (id.isBlank() || location.isBlank()) continue
                add(
                    Incident(
                        id = id,
                        kind = item.optString("kind"),
                        locationCode = location,
                        anchorItemId = item.optString("anchorItemId"),
                        anchorItemName = item.optString("anchorItemName"),
                        status = item.optString("status", STATUS_ACTIVE),
                        stage = item.optInt("stage"),
                        summary = item.optString("summary"),
                        lastActorCharacterId = item.optString("lastActorCharacterId"),
                        createdAt = item.instant("createdAt"),
                        updatedAt = item.instant("updatedAt"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun JSONObject.instant(key: String): Instant =
        runCatching { Instant.parse(optString(key)) }.getOrDefault(Instant.EPOCH)

    private data class Incident(
        val id: String,
        val kind: String,
        val locationCode: String,
        val anchorItemId: String,
        val anchorItemName: String,
        val status: String,
        val stage: Int,
        val summary: String,
        val lastActorCharacterId: String,
        val createdAt: Instant,
        val updatedAt: Instant,
    )
}
