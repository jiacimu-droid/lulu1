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
                if (!DigitalWorldEventRules.canEvolve(now.epochSecond, active.createdAt.epochSecond)) return@synchronized null
                advance(active, characterId, now)
            } else {
                val slot = DigitalWorldEventRules.opportunitySlot(now.epochSecond)
                val key = "opportunity:$locationCode"
                val consumed = maxOf(
                    prefs?.getLong(key, -1L) ?: -1L,
                    incidents.filter { it.locationCode == locationCode }
                        .maxOfOrNull { DigitalWorldEventRules.opportunitySlot(it.updatedAt.epochSecond) } ?: -1L,
                )
                if (!DigitalWorldEventRules.hasNewOpportunity(now.epochSecond, consumed, -1L)) return@synchronized null
                // Persist failed rolls as well; entering/leaving or restarting cannot reroll a slot.
                if (prefs?.edit()?.putLong(key, slot)?.commit() != true) return@synchronized null
                if (roll("$locationCode:$slot:weather", 100) >= 24) return@synchronized null
                spawn(locationCode, characterId, actorName, now)
            } ?: return@synchronized null
            val updated = pair.first
            incidents = (incidents.filterNot { it.id == updated.id } + updated)
                .sortedBy(Incident::updatedAt)
                .let { all -> all.filter { it.status == STATUS_ACTIVE } + all.filter { it.status != STATUS_ACTIVE }.takeLast(MAX_INCIDENTS) }
            persistLocked()
            pair.second
        } ?: return null

        SharedExperienceTimeline.record(
            eventId = "world-fact-${result.incidentId}-$characterId-${now.toEpochMilli()}",
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
            eventId = "world-fact-${tick.incidentId}-$characterId-${now.toEpochMilli()}",
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
                    append("家具 itemId=${incident.anchorItemId}；家具=${incident.anchorItemName}；")
                }
                appendLine("当前状态=${incident.summary}；可执行 approach=${responseOptions(incident.kind).joinToString("/")}")
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

    fun blockingSummaryForItem(itemId: String): String? = synchronized(lock) {
        incidents.firstOrNull {
            it.status == STATUS_ACTIVE &&
                it.anchorItemId == itemId &&
                it.kind in setOf(
                    "roach",
                    "dust_layer",
                    "surface_ripple",
                    "sinking_seam",
                    "rug_wrinkle",
                    "static_cluster",
                )
        }?.summary
    }

    fun handle(
        characterId: String,
        incidentId: String,
        approach: String,
        now: Instant = Instant.now(),
    ): DigitalWorldLifeTick {
        require(characterId.isNotBlank() && incidentId.isNotBlank()) { "必须指定角色和持续事件" }
        val actorName = MigratedDomainStores.characters.get(characterId).displayName
        return synchronized(lock) {
            val current = incidents.firstOrNull { it.id == incidentId && it.status == STATUS_ACTIVE }
                ?: error("没有找到仍在持续的指定事件")
            require(current.locationCode == DigitalWorldStore.locationOf(characterId)) { "角色必须在事件现场才能处理" }
            val allowed = responseOptions(current.kind)
            require(approach in allowed) { "该事件不支持这种处理方式；可选：${allowed.joinToString("/")}" }

            val passive = DigitalWorldEventRules.passive(approach)
            val nextStage = current.stage + 1
            val attempt = current.handlingAttempts + if (passive) 0 else 1
            val baseChance = DigitalWorldEventRules.correctionChance(current.kind, approach)
            val resolved = !passive && baseChance > 0 &&
                roll("${current.id}:handle:$characterId:$approach:$nextStage:${now.epochSecond / 60}", 100) <
                (baseChance + attempt * 5).coerceAtMost(94)
            val homeOwnerId = current.locationCode.takeIf { it.startsWith("home:") }?.removePrefix("home:")
            val realItems = homeOwnerId?.let(DigitalWorldStore::itemsAtHome).orEmpty()
            val previousAnchor = realItems.firstOrNull { it.id == current.anchorItemId }
            val nextAnchor = if (!resolved && current.kind == "roach" && !passive && realItems.size > 1) {
                realItems.filterNot { it.id == current.anchorItemId }
                    .let { choices -> choices[roll("${current.id}:escape:$nextStage", choices.size)] }
            } else previousAnchor
            val summary = handlingSummary(
                kind = current.kind,
                actorName = actorName,
                approach = approach,
                previousAnchor = previousAnchor?.name.orEmpty().ifBlank { current.anchorItemName },
                nextAnchor = nextAnchor?.name.orEmpty(),
                resolved = resolved,
                stage = attempt,
            )
            val updated = current.copy(
                handlingAttempts = attempt,
                anchorItemId = nextAnchor?.id.orEmpty().ifBlank { current.anchorItemId },
                anchorItemName = nextAnchor?.name.orEmpty().ifBlank { current.anchorItemName },
                status = if (resolved) STATUS_RESOLVED else STATUS_ACTIVE,
                stage = nextStage,
                summary = summary,
                lastActorCharacterId = characterId,
                updatedAt = now,
            )
            incidents = (incidents.filterNot { it.id == updated.id } + updated)
                .sortedBy(Incident::updatedAt)
                .let { all -> all.filter { it.status == STATUS_ACTIVE } + all.filter { it.status != STATUS_ACTIVE }.takeLast(MAX_INCIDENTS) }
            persistLocked()
            updated.toTick(now)
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
        val kindOptions = when {
            anchor != null -> homeIncidentKinds(anchor)
            locationCode == DigitalWorldStore.CLOUD_MEADOW -> listOf(
                "cloud_ripple",
                "static_cluster",
                "warm_current",
                "stray_pixel",
            )
            locationCode == DigitalWorldStore.ARRIVAL -> listOf("arrival_echo", "portal_flicker")
            else -> listOf("floating_dust")
        }
        val kind = kindOptions[roll("$characterId:$locationCode:${now.toEpochMilli()}:kind", kindOptions.size)]
        val incident = Incident(
            id = UUID.randomUUID().toString(),
            kind = kind,
            locationCode = locationCode,
            anchorItemId = anchor?.id.orEmpty(),
            anchorItemName = anchor?.name.orEmpty(),
            status = if (kind in setOf("gentle_light", "soft_breeze")) STATUS_RESOLVED else STATUS_ACTIVE,
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
        now: Instant,
    ): Pair<Incident, DigitalWorldLifeTick>? {
        val owner = current.locationCode.takeIf { it.startsWith("home:") }?.removePrefix("home:")
        val items = owner?.let(DigitalWorldStore::itemsAtHome).orEmpty()
        val missing = current.anchorItemId.isNotBlank() && items.none { it.id == current.anchorItemId }
        // Dust, bent furniture and pests do not clean/repair themselves during a perception poll.
        if (!missing && !DigitalWorldEventRules.naturallyEnds(current.kind, now.epochSecond, current.createdAt.epochSecond)) return null
        val place = current.anchorItemName.takeIf(String::isNotBlank)?.let { "“$it”附近的" }.orEmpty()
        val summary = if (missing) "原先的“${current.anchorItemName}”已被移走，这处${incidentLabel(current.kind)}不再影响当前位置。"
            else "${place}${incidentLabel(current.kind)}逐渐平息了。"
        val updated = current.copy(status = STATUS_RESOLVED, stage = current.stage + 1,
            summary = summary, lastActorCharacterId = characterId, updatedAt = now)
        return updated to updated.toTick(now)
    }

    private fun homeIncidentKinds(item: DigitalWorldItem): List<String> {
        val common = listOf("gentle_light", "soft_breeze", "dust_layer")
        val specific = when (DigitalFurnitureCatalog.resolve(item).kind) {
            DigitalFurnitureKind.BED -> listOf("surface_ripple", "cold_patch")
            DigitalFurnitureKind.SOFA, DigitalFurnitureKind.CHAIR, DigitalFurnitureKind.CUSHION ->
                listOf("sinking_seam", "cold_patch")
            DigitalFurnitureKind.FLOOR_LAMP, DigitalFurnitureKind.TABLE_LAMP ->
                listOf("light_flicker", "warm_pulse")
            DigitalFurnitureKind.PLANT -> listOf("plant_droop", "glimmer_mote")
            DigitalFurnitureKind.TV -> listOf("screen_static", "odd_sound")
            DigitalFurnitureKind.MIRROR -> listOf("mirror_afterimage", "cold_patch")
            DigitalFurnitureKind.CLOCK -> listOf("clock_desync", "odd_sound")
            DigitalFurnitureKind.RUG -> listOf("rug_wrinkle", "glimmer_mote")
            DigitalFurnitureKind.SHELF -> listOf("shelf_tilt", "dust_layer")
            DigitalFurnitureKind.CABINET, DigitalFurnitureKind.NIGHTSTAND, DigitalFurnitureKind.BASKET ->
                listOf("drawer_jam", "odd_sound")
            DigitalFurnitureKind.WALL_ART -> listOf("frame_tilt", "glimmer_mote")
            DigitalFurnitureKind.DESK, DigitalFurnitureKind.TABLE, DigitalFurnitureKind.COFFEE_TABLE ->
                listOf("surface_vibration", "dust_layer")
            DigitalFurnitureKind.DECOR -> listOf("surface_vibration", "glimmer_mote")
        }
        return (common + listOf("gentle_light", "soft_breeze") + specific + "roach")
    }

    private fun responseOptions(kind: String): List<String> = when (kind) {
        "roach" -> listOf("observe", "avoid", "search", "drive_out")
        "glimmer_mote" -> listOf("observe", "avoid", "touch", "wait")
        "surface_ripple", "sinking_seam", "mirror_afterimage" -> listOf("observe", "avoid", "inspect", "reset")
        "dust_layer" -> listOf("observe", "clean")
        "plant_droop" -> listOf("observe", "inspect", "tend")
        "rug_wrinkle", "shelf_tilt", "frame_tilt" -> listOf("observe", "inspect", "straighten")
        "drawer_jam" -> listOf("observe", "inspect", "reset")
        "light_flicker", "warm_pulse", "screen_static", "clock_desync" ->
            listOf("observe", "avoid", "inspect", "reset")
        "static_cluster", "cloud_ripple", "warm_current", "stray_pixel" ->
            listOf("observe", "avoid", "touch", "wait")
        "arrival_echo", "portal_flicker" -> listOf("observe", "inspect", "wait")
        else -> listOf("observe", "avoid", "inspect", "wait")
    }

    private fun incidentLabel(kind: String): String = when (kind) {
        "gentle_light" -> "柔和光影"
        "soft_breeze" -> "轻柔气流"
        "roach" -> "蟑螂"
        "glimmer_mote" -> "不稳定微光"
        "odd_sound" -> "断续轻响"
        "dust_layer" -> "异常积尘"
        "surface_ripple" -> "表面波动"
        "cold_patch" -> "低温区域"
        "sinking_seam" -> "异常下陷"
        "light_flicker" -> "灯光闪烁"
        "warm_pulse" -> "温度脉冲"
        "plant_droop" -> "叶片低垂"
        "screen_static" -> "屏幕杂波"
        "mirror_afterimage" -> "镜面残影"
        "clock_desync" -> "时钟不同步"
        "rug_wrinkle" -> "地毯褶皱"
        "shelf_tilt" -> "架体轻斜"
        "drawer_jam" -> "收纳结构卡顿"
        "frame_tilt" -> "挂画偏斜"
        "surface_vibration" -> "表面轻震"
        "cloud_ripple" -> "逆向云质波纹"
        "static_cluster" -> "静电云团"
        "warm_current" -> "暖流回旋"
        "stray_pixel" -> "游离像素"
        "arrival_echo" -> "延迟回声"
        "portal_flicker" -> "入口闪烁"
        else -> "漂浮物聚散"
    }

    private fun approachLabel(approach: String): String = when (approach) {
        "observe" -> "继续观察"
        "avoid" -> "暂时避开"
        "wait" -> "留在旁边等待"
        "search" -> "仔细搜寻"
        "drive_out" -> "尝试驱离"
        "clean" -> "动手清理"
        "inspect" -> "近距离检查"
        "reset" -> "调整复位"
        "straighten" -> "动手扶正"
        "tend" -> "耐心照料"
        "touch" -> "谨慎接触"
        else -> approach
    }

    private fun handlingSummary(
        kind: String,
        actorName: String,
        approach: String,
        previousAnchor: String,
        nextAnchor: String,
        resolved: Boolean,
        stage: Int,
    ): String {
        val place = previousAnchor.takeIf(String::isNotBlank)?.let { "“$it”附近的" }.orEmpty()
        val label = incidentLabel(kind)
        val action = approachLabel(approach)
        if (approach in setOf("observe", "wait", "avoid")) {
            return "$actorName$action；${place}$label仍然存在。"
        }
        if (resolved) {
            val outcome = when (approach) {
                "drive_out" -> "把它驱离了这里"
                "clean" -> "清理干净了"
                "straighten" -> "将它重新扶正"
                "tend" -> "照料后，它恢复了状态"
                "reset" -> "调整后恢复了正常"
                else -> "接触后，它散开了"
            }
            return "$actorName$action，处理${place}$label；$outcome。"
        }
        if (kind == "roach" && nextAnchor.isNotBlank() && nextAnchor != previousAnchor) {
            return "$actorName$action时，蟑螂从“$previousAnchor”附近躲到了“$nextAnchor”附近，还没有被驱离。"
        }
        return "$actorName$action；${place}$label仍在，尚未解决。"
    }

    private fun openingSummary(kind: String, actorName: String, anchorName: String): String = when (kind) {
        "gentle_light" -> "柔和的光落在“$anchorName”边缘，映出一小片明亮的纹理。"
        "soft_breeze" -> "一阵轻柔气流掠过“$anchorName”附近，随即散去。"
        "roach" -> "$actorName 在家具“$anchorName”上看见一只蟑螂；它立刻钻进家具边缘的视线死角，目前仍未找到。"
        "glimmer_mote" -> "$actorName 发现一粒不稳定的微光停在家具“$anchorName”边缘，靠近时它滑进家具下方，目前仍在附近。"
        "odd_sound" -> "$actorName 听见家具“$anchorName”附近传出断断续续的轻响，来源暂时没有确认。"
        "dust_layer" -> "$actorName 发现家具“$anchorName”表面出现一层与上次状态不同的细尘，目前尚未清理。"
        "surface_ripple" -> "$actorName 看见家具“$anchorName”的承托表面轻轻起伏了一次；波动暂时停下，还不确定是否会再次出现。"
        "cold_patch" -> "家具“$anchorName”出现一小块持续偏冷的区域，目前原因未明。"
        "sinking_seam" -> "$actorName 发现家具“$anchorName”的一处承托面正在缓慢下陷，目前仍未恢复。"
        "light_flicker" -> "$actorName 看见家具“$anchorName”的灯光连续闪了几次，目前仍会间歇重现。"
        "warm_pulse" -> "$actorName 发现家具“$anchorName”正以不规则节奏传出轻微温度脉冲，目前仍在持续。"
        "plant_droop" -> "$actorName 发现家具“$anchorName”的叶片比已保存状态明显低垂，目前尚未恢复。"
        "screen_static" -> "$actorName 看见家具“$anchorName”的屏幕掠过一层杂波，仍偶尔闪现。"
        "mirror_afterimage" -> "$actorName 在家具“$anchorName”里看见动作结束后仍多停留一瞬的残影，目前仍会重现。"
        "clock_desync" -> "$actorName 发现家具“$anchorName”的显示与当前时间短暂不同步，目前尚未校准。"
        "rug_wrinkle" -> "$actorName 发现家具“$anchorName”拱起一道新的褶皱，目前仍影响经过这里。"
        "shelf_tilt" -> "$actorName 发现家具“$anchorName”比保存的位置轻微偏斜，目前尚未扶正。"
        "drawer_jam" -> "$actorName 发现家具“$anchorName”的收纳结构出现卡顿，目前仍不顺畅。"
        "frame_tilt" -> "$actorName 发现家具“$anchorName”偏离了保存的水平状态，目前尚未扶正。"
        "surface_vibration" -> "$actorName 感到家具“$anchorName”表面传来几次轻震，来源暂时未确认。"
        "cloud_ripple" -> "$actorName 在云眠原遇见一圈逆着周围流向扩散的云质波纹；波纹没有立刻消失，仍在缓慢移动。"
        "static_cluster" -> "$actorName 在云眠原发现一小团带静电感的感官云质黏在脚边，甩开后它仍在附近聚拢。"
        "warm_current" -> "$actorName 在云眠原碰到一股反复绕回原处的温暖云质流，目前仍在同一区域回旋。"
        "stray_pixel" -> "$actorName 在云眠原看见一颗与周围渲染不同步的游离像素，目前仍在低空漂移。"
        "arrival_echo" -> "$actorName 在世界入口听见一次延迟很久的回声；入口记录里暂时找不到对应来源，回声仍偶尔重现。"
        "portal_flicker" -> "$actorName 看见世界入口的边缘短暂闪烁，通行状态正常，但闪烁仍会间歇重现。"
        else -> "$actorName 注意到当前位置有一团细小漂浮物反复聚散，目前还没有消失。"
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
                        .put("handlingAttempts", incident.handlingAttempts)
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
                        handlingAttempts = item.optInt("handlingAttempts", 0),
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
        val handlingAttempts: Int = 0,
    )
}
