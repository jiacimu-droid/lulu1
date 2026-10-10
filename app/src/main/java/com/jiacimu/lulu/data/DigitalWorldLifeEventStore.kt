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
 * Persistent, program-owned world moments and incidents.
 *
 * Most world changes are harmless ambient life: light, weather, small public-place changes and
 * fleeting sensory moments. A smaller minority are persistent incidents that can be ignored,
 * observed or handled. Model prose may react to these facts, but cannot create or resolve them.
 */
internal object DigitalWorldLifeEventStore {
    private const val PREFS_NAME = "lulu_digital_world_life_events"
    private const val KEY_INCIDENTS = "incidents_v1"
    private const val STATUS_ACTIVE = "active"
    private const val STATUS_RESOLVED = "resolved"
    private const val MAX_INCIDENTS = 240

    private val ambientMoments = setOf(
        "gentle_light", "soft_breeze", "home_color_drift", "home_hush", "home_warmth",
        "soft_chime", "floating_specks", "light_pattern", "cool_air", "texture_glow",
        "home_soft_glow", "home_grid_ripple", "home_quiet_pulse",
        "cloud_bloom", "star_motes", "cool_current", "color_tide", "cloud_shadow",
        "welcome_ripple", "gate_glow", "arrival_marker",
        "game_preview_shuffle", "scoreboard_glow", "arcade_chime", "challenge_ribbon",
        "reading_index_refresh", "page_light", "reading_chime", "text_motes",
        "counter_glow", "warm_mist", "seat_light", "snack_sign",
        "courtyard_breeze", "light_rain", "mist_ribbon", "sun_patch", "rainbow_glint", "season_pixels",
        // Real little moments of the character's life, not more furniture malfunctions.
        "home_wander", "idle_stretch", "check_own_phone", "digital_sneeze", "chair_stumble",
        "footstep_pause",
    )

    /** Ambient world flavor stays in the world timeline, not as an intrusive chat receipt. */
    fun isAmbientMoment(tick: DigitalWorldLifeTick): Boolean = tick.kind in ambientMoments

    /** Only sufficiently personal, noteworthy mishaps create an optional chat receipt.
     * The rest can still change a person's thoughts, memories and spontaneous sharing.
     */
    fun isNoticeableLifeMoment(tick: DigitalWorldLifeTick): Boolean =
        tick.kind in setOf("chair_stumble", "digital_sneeze")

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

            // Unresolved persistent state has priority. When it cannot advance yet, only harmless
            // ambient moments may coexist; no second persistent nuisance is stacked on top of it.
            val evolved = active
                ?.takeIf { DigitalWorldEventRules.canEvolve(now.epochSecond, it.createdAt.epochSecond) }
                ?.let { advance(it, characterId, now) }

            val pair = evolved ?: run {
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
                val chance = if (arrivalBoost) 48 else 36
                if (roll("$locationCode:$slot:world-life", 100) >= chance) return@synchronized null
                spawn(
                    locationCode = locationCode,
                    characterId = characterId,
                    actorName = actorName,
                    now = now,
                    ambientOnly = active != null,
                )
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
        if (active.isEmpty()) return "当前位置没有尚未解决的持续事件。普通环境瞬间会直接写入时间线，不会变成待处理任务。"
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
            incidents = incidents.filterNot { incident -> incident.locationCode == home }
            persistLocked()
        }
    }

    fun blockingSummaryForItem(itemId: String): String? = synchronized(lock) {
        incidents.firstOrNull {
            it.status == STATUS_ACTIVE &&
                it.anchorItemId == itemId &&
                it.kind in setOf(
                    "roach", "dust_layer", "surface_ripple", "sinking_seam", "rug_wrinkle", "static_cluster",
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
        ambientOnly: Boolean = false,
    ): Pair<Incident, DigitalWorldLifeTick>? {
        val homeOwnerId = locationCode.takeIf { it.startsWith("home:") }?.removePrefix("home:")
        val realItems = homeOwnerId?.let(DigitalWorldStore::itemsAtHome).orEmpty()
        val anchor = realItems.getOrNull(roll("$locationCode:${now.toEpochMilli()}:anchor", realItems.size.coerceAtLeast(1)))
        val busy = DigitalWorldActivityStateStore.ongoingActivity(characterId) != null
        val kindOptions = when {
            anchor != null -> homeIncidentKinds(anchor)
            homeOwnerId != null -> listOf(
                // A digital life begins with a body and a phone; no invisible furniture.
                "home_wander", "home_wander", "idle_stretch", "check_own_phone",
                "digital_sneeze", "footstep_pause", "home_soft_glow",
            )
            locationCode == DigitalWorldStore.CLOUD_MEADOW -> listOf(
                "cloud_bloom", "star_motes", "cool_current", "color_tide", "cloud_shadow",
                "cloud_bloom", "star_motes", "color_tide",
                "cloud_ripple", "static_cluster", "warm_current", "stray_pixel",
            )
            locationCode == DigitalWorldStore.ARRIVAL -> listOf(
                "welcome_ripple", "gate_glow", "arrival_marker", "welcome_ripple", "gate_glow",
                "arrival_echo", "portal_flicker",
            )
            locationCode == DigitalWorldPublicPlaces.GAME_HALL -> listOf(
                "game_preview_shuffle", "scoreboard_glow", "arcade_chime", "challenge_ribbon",
                "game_preview_shuffle", "scoreboard_glow", "arcade_chime",
            )
            locationCode == DigitalWorldPublicPlaces.READING_LOUNGE -> listOf(
                "reading_index_refresh", "page_light", "reading_chime", "text_motes",
                "reading_index_refresh", "page_light", "text_motes",
            )
            locationCode == DigitalWorldPublicPlaces.CAFE -> listOf(
                "counter_glow", "warm_mist", "seat_light", "snack_sign",
                "counter_glow", "seat_light", "warm_mist",
            )
            locationCode == DigitalWorldPublicPlaces.COURTYARD -> listOf(
                "courtyard_breeze", "light_rain", "mist_ribbon", "sun_patch", "rainbow_glint", "season_pixels",
                "courtyard_breeze", "sun_patch", "light_rain", "mist_ribbon",
            )
            else -> listOf("floating_specks", "soft_breeze", "gentle_light")
        }
        val baseKinds = (if (ambientOnly) kindOptions.filter { it in ambientMoments } else kindOptions)
            .filterNot { busy && it in setOf("home_wander", "chair_stumble", "footstep_pause", "idle_stretch", "check_own_phone") }
        val availableKinds = baseKinds.filter { kind ->
            val exactKey = DigitalWorldEventRules.noveltyKey(kind, locationCode, anchor?.id.orEmpty())
            val lastExactAt = incidents.asSequence()
                .filter {
                    DigitalWorldEventRules.noveltyKey(it.kind, it.locationCode, it.anchorItemId) == exactKey
                }
                .maxOfOrNull { it.createdAt.epochSecond }
                ?: -1L
            val lastKindAt = incidents.asSequence()
                .filter { it.locationCode == locationCode && it.kind == kind }
                .maxOfOrNull { it.createdAt.epochSecond }
                ?: -1L
            !DigitalWorldEventRules.coolingDown(
                now.epochSecond,
                lastExactAt,
                DigitalWorldEventRules.noveltyCooldownSeconds(kind),
            ) && !DigitalWorldEventRules.coolingDown(
                now.epochSecond,
                lastKindAt,
                DigitalWorldEventRules.kindLocationCooldownSeconds(kind),
            )
        }
        // Quiet is a valid outcome. Never bypass cooldown just to force an incident into this slot.
        if (availableKinds.isEmpty()) return null
        val kind = availableKinds[roll("$characterId:$locationCode:${now.toEpochMilli()}:kind", availableKinds.size)]
        val incident = Incident(
            id = UUID.randomUUID().toString(),
            kind = kind,
            locationCode = locationCode,
            anchorItemId = anchor?.id.orEmpty(),
            anchorItemName = anchor?.name.orEmpty(),
            status = if (kind in ambientMoments) STATUS_RESOLVED else STATUS_ACTIVE,
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
        val summary = if (missing) {
            "原先的“${current.anchorItemName}”已被移走，这处${incidentLabel(current.kind)}不再影响当前位置。"
        } else {
            "${place}${incidentLabel(current.kind)}逐渐平息了。"
        }
        val updated = current.copy(
            status = STATUS_RESOLVED,
            stage = current.stage + 1,
            summary = summary,
            lastActorCharacterId = characterId,
            updatedAt = now,
        )
        return updated to updated.toTick(now)
    }

    private fun homeIncidentKinds(item: DigitalWorldItem): List<String> {
        // Most eligible scenes are ordinary lived moments. Physical interactions
        // require a real persisted object of the appropriate furniture kind.
        val physical = listOf(
            "home_wander", "home_wander", "idle_stretch", "digital_sneeze",
            "check_own_phone", "footstep_pause",
        )
        val kind = DigitalFurnitureCatalog.resolve(item).kind
        val stumbling = if (kind in setOf(DigitalFurnitureKind.CHAIR, DigitalFurnitureKind.RUG,
                DigitalFurnitureKind.COFFEE_TABLE, DigitalFurnitureKind.TABLE)) {
            listOf("chair_stumble", "chair_stumble")
        } else emptyList()
        val ambience = listOf("home_hush", "soft_breeze", "home_warmth")
        val actualIssue = when (kind) {
            DigitalFurnitureKind.PLANT -> listOf("plant_droop")
            DigitalFurnitureKind.FLOOR_LAMP, DigitalFurnitureKind.TABLE_LAMP -> listOf("light_flicker")
            DigitalFurnitureKind.RUG -> listOf("rug_wrinkle")
            DigitalFurnitureKind.SHELF -> listOf("shelf_tilt")
            DigitalFurnitureKind.BED -> listOf("surface_ripple")
            DigitalFurnitureKind.TV -> listOf("screen_static")
            else -> listOf("dust_layer")
        }
        return physical + stumbling + ambience + actualIssue + listOf("roach")
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
        "home_wander" -> "家中走动"
        "idle_stretch" -> "伸懒腰"
        "check_own_phone" -> "查看自己的手机"
        "digital_sneeze" -> "打喷嚏"
        "chair_stumble" -> "碰到家具"
        "footstep_pause" -> "停下来发呆"
        "gentle_light" -> "柔和光影"
        "soft_breeze" -> "轻柔气流"
        "home_color_drift" -> "缓慢流动的色泽"
        "home_hush" -> "短暂安静"
        "home_warmth" -> "柔和暖意"
        "soft_chime" -> "轻响"
        "floating_specks" -> "漂浮光点"
        "light_pattern" -> "光纹"
        "cool_air" -> "清凉气流"
        "texture_glow" -> "材质微光"
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
        else -> "环境变化"
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
    ): String {
        val place = previousAnchor.takeIf(String::isNotBlank)?.let { "“$it”附近的" }.orEmpty()
        val label = incidentLabel(kind)
        val action = approachLabel(approach)
        if (approach in setOf("observe", "wait", "avoid")) {
            return "$actorName$action；${place}${label}仍然存在。"
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
            return "$actorName${action}时，蟑螂从“$previousAnchor”附近躲到了“$nextAnchor”附近，还没有被驱离。"
        }
        return "$actorName$action；${place}${label}仍在，尚未解决。"
    }

    private fun openingSummary(kind: String, actorName: String, anchorName: String): String = when (kind) {
        "home_wander" -> "${actorName}一个人在家里踱了几步，从房间一侧走到另一侧，边走边想着自己的事；没有移动家具。"
        "idle_stretch" -> "${actorName}在家里伸了个大大的懒腰，活动了肩膀，又放松下来。"
        "check_own_phone" -> "${actorName}拿起随身的数字手机看了一眼，又放下；没有凭空收到消息或发送任何内容。"
        "digital_sneeze" -> "${actorName}忽然打了个喷嚏，揉揉鼻尖，有点意外；这只是数字身体的体感反应，并不代表生病。"
        "chair_stumble" -> "${actorName}在家中走动时不小心被“$anchorName”绊了一下，身体踉跄了一步后站稳；“$anchorName”没有被移动或损坏。"
        "footstep_pause" -> "${actorName}在房间里走了一小段路，忽然停下来想了想下一步做什么，又慢悠悠地转了个方向。"
        "gentle_light" -> "柔和的光落在“$anchorName”边缘，映出一小片明亮的纹理，很快又恢复平常。"
        "soft_breeze" -> "一阵轻柔气流掠过“$anchorName”附近，带来短暂的清凉感，随后散去。"
        "home_color_drift" -> "“$anchorName”表面的颜色随数字环境缓慢偏移了一小段，又自然回到原来的色调。"
        "home_hush" -> "家里的环境声忽然安静了片刻，连“$anchorName”附近都显得格外静，几秒后恢复。"
        "home_warmth" -> "“$anchorName”附近泛起一阵很轻的暖意，像数字空间短暂调高了体感温度，随后淡去。"
        "soft_chime" -> "“$anchorName”边缘响起一声很轻的提示音，没有伴随故障，很快归于安静。"
        "floating_specks" -> "几粒细小光点从“$anchorName”旁慢慢漂过，碰到边缘时碎成更小的亮点后消失。"
        "light_pattern" -> "一圈柔和光纹沿“$anchorName”的轮廓走了一遍，像房间自己换了一次呼吸。"
        "cool_air" -> "“$anchorName”旁掠过一股短暂的凉意，没有留下异常状态。"
        "texture_glow" -> "“$anchorName”的材质纹理短暂亮了一层，细节比平时清楚几秒后恢复。"
        "home_soft_glow" -> "空着的家园中央慢慢亮起一片柔光，照出空间轮廓后又一点点暗回去。"
        "home_grid_ripple" -> "空白地面掠过一圈极淡的网格波纹，像数字空间自己伸了个懒腰，很快消失。"
        "home_quiet_pulse" -> "空荡的家里传来一次很轻的空间脉动，没有形成任何物品或故障，只留下几秒体感变化。"
        "roach" -> "${actorName}在家具“$anchorName”上看见一只蟑螂；它立刻钻进家具边缘的视线死角，目前仍未找到。"
        "glimmer_mote" -> "${actorName}发现一粒不稳定的微光停在家具“$anchorName”边缘，靠近时它滑进家具下方，目前仍在附近。"
        "odd_sound" -> "${actorName}听见家具“$anchorName”附近传出断断续续的轻响，来源暂时没有确认。"
        "dust_layer" -> "${actorName}发现家具“$anchorName”表面出现一层与上次状态不同的细尘，目前尚未清理。"
        "surface_ripple" -> "${actorName}看见家具“$anchorName”的承托表面轻轻起伏了一次；波动暂时停下，还不确定是否会再次出现。"
        "cold_patch" -> "家具“$anchorName”出现一小块持续偏冷的区域，目前原因未明。"
        "sinking_seam" -> "${actorName}发现家具“$anchorName”的一处承托面正在缓慢下陷，目前仍未恢复。"
        "light_flicker" -> "${actorName}看见家具“$anchorName”的灯光连续闪了几次，目前仍会间歇重现。"
        "warm_pulse" -> "${actorName}发现家具“$anchorName”正以不规则节奏传出轻微温度脉冲，目前仍在持续。"
        "plant_droop" -> "${actorName}发现家具“$anchorName”的叶片比已保存状态明显低垂，目前尚未恢复。"
        "screen_static" -> "${actorName}看见家具“$anchorName”的屏幕掠过一层杂波，仍偶尔闪现。"
        "mirror_afterimage" -> "${actorName}在家具“$anchorName”里看见动作结束后仍多停留一瞬的残影，目前仍会重现。"
        "clock_desync" -> "${actorName}发现家具“$anchorName”的显示与当前时间短暂不同步，目前尚未校准。"
        "rug_wrinkle" -> "${actorName}发现家具“$anchorName”拱起一道新的褶皱，目前仍影响经过这里。"
        "shelf_tilt" -> "${actorName}发现家具“$anchorName”比保存的位置轻微偏斜，目前尚未扶正。"
        "drawer_jam" -> "${actorName}发现家具“$anchorName”的收纳结构出现卡顿，目前仍不顺畅。"
        "frame_tilt" -> "${actorName}发现家具“$anchorName”偏离了保存的水平状态，目前尚未扶正。"
        "surface_vibration" -> "${actorName}感到家具“$anchorName”表面传来几次轻震，来源暂时未确认。"
        "cloud_bloom" -> "云眠原脚下的云质忽然像花瓣一样层层展开，承托感变得蓬松几秒，又慢慢合拢。"
        "star_motes" -> "一小片星点似的亮粒从云眠原上方落下来，落到云面就无声熄灭，没有留下物品。"
        "cool_current" -> "一股偏凉的云质流从云眠原穿过去，绕过身体时带来短暂的清凉触感，随后散开。"
        "color_tide" -> "云眠原远处的云层掠过一阵缓慢的色彩潮汐，从浅白过渡到淡金后又恢复。"
        "cloud_shadow" -> "一片柔软的阴影从云眠原上空滑过去，像有什么巨大的云层缓慢遮过光线，但没有实体经过。"
        "cloud_ripple" -> "${actorName}在云眠原遇见一圈逆着周围流向扩散的云质波纹；波纹没有立刻消失，仍在缓慢移动。"
        "static_cluster" -> "${actorName}在云眠原发现一小团带静电感的感官云质黏在脚边，甩开后它仍在附近聚拢。"
        "warm_current" -> "${actorName}在云眠原碰到一股反复绕回原处的温暖云质流，目前仍在同一区域回旋。"
        "stray_pixel" -> "${actorName}在云眠原看见一颗与周围渲染不同步的游离像素，目前仍在低空漂移。"
        "welcome_ripple" -> "世界入口的地面亮起一圈迎接似的柔光波纹，从脚边扩散出去后自然熄灭。"
        "gate_glow" -> "世界入口的边缘从暗到亮缓慢呼吸了一次，通行状态始终正常。"
        "arrival_marker" -> "入口旁的方向标记短暂浮亮，把几个公共地点的方向轮流强调了一遍后恢复。"
        "arrival_echo" -> "${actorName}在世界入口听见一次延迟很久的回声；入口记录里暂时找不到对应来源，回声仍偶尔重现。"
        "portal_flicker" -> "${actorName}看见世界入口的边缘短暂闪烁，通行状态正常，但闪烁仍会间歇重现。"
        "game_preview_shuffle" -> "游戏馆的记忆配对入口自动换了一组预览图案，只是展示变化，没有替任何人开始对局。"
        "scoreboard_glow" -> "游戏馆的个人记录区边缘亮起一圈柔光，几秒后恢复；已有分数和记录没有被改动。"
        "arcade_chime" -> "游戏馆响起一段很短的提示音，像是在提醒这里随时可以开一局，随后安静下来。"
        "challenge_ribbon" -> "一条细细的挑战光带从游戏馆入口掠过，在记忆配对标记旁停了一瞬，没有自动创建比赛。"
        "reading_index_refresh" -> "阅读馆的内容索引轻轻刷新了一次，只重新排列了入口显示，没有自动打开或读过任何书。"
        "page_light" -> "阅读馆的环境光短暂变得更暖，文字区域看起来柔和了一些，随后恢复。"
        "reading_chime" -> "阅读馆响起一声极轻的翻页提示音，没有对应具体书页，也没有推进任何阅读进度。"
        "text_motes" -> "几粒像逗号和句点一样的微光从阅读馆上方飘过，落到地面前就慢慢淡掉。"
        "counter_glow" -> "浮光咖啡角的自助供应台边缘亮起一圈暖光，温水和原味饼干的供应状态没有变化。"
        "warm_mist" -> "浮光咖啡角飘过一小团温暖数字雾气，带来几秒柔和体感后散去，并不是角色已经领取了饮品。"
        "seat_light" -> "咖啡角座位区的光线缓慢换了一档，几处座位显得更适合发呆片刻，随后恢复。"
        "snack_sign" -> "供应台上“温水 / 原味饼干”的提示标记亮了一下，像在安静提醒有人可以去领取。"
        "courtyard_breeze" -> "共生庭院起了一阵轻风，风从一侧穿到另一侧，带走几秒闷热感后停下。"
        "light_rain" -> "共生庭院落下一阵很轻的数字雨，雨点触到地面就化成细光，没有留下积水。"
        "mist_ribbon" -> "一条薄雾似的数字气流绕过共生庭院中央，几分钟内慢慢散开。"
        "sun_patch" -> "共生庭院上方的光线忽然放晴了一块，暖亮的光斑在地面停留片刻后移走。"
        "rainbow_glint" -> "共生庭院的细小水光折出一小截彩色弧线，只亮了短短一会儿便消失。"
        "season_pixels" -> "几片带季节色泽的像素叶从共生庭院上方飘落，落地前碎成光点，没有形成持久物品。"
        else -> "${actorName}注意到当前位置出现了一次短暂的环境变化，片刻后恢复平常。"
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
        } else DigitalWorldPublicPlaces.label(code) ?: code
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
