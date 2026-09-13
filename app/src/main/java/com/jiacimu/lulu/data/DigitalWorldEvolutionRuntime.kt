package com.jiacimu.lulu.data

import android.content.Context
import java.time.Instant

/**
 * Program-owned, low-frequency world growth. Evolution is keyed to real time slots rather than
 * model calls, so reopening a screen or retrying a prompt cannot reroll the world repeatedly.
 * A place or resident only becomes usable after DigitalWorldExpansionStore persists it.
 */
internal object DigitalWorldEvolutionRuntime {
    private const val PREFS_NAME = "lulu_digital_world_evolution_v1"
    private const val KEY_PLACE_SLOT = "place_slot"
    private const val KEY_RESIDENT_SLOT = "resident_slot"
    private const val KEY_MOVEMENT_SLOT = "movement_slot"
    private const val DAY_SECONDS = 86_400L
    private const val MOVEMENT_SECONDS = 21_600L

    private var prefs: android.content.SharedPreferences? = null

    private data class PlaceTemplate(
        val label: String,
        val subtitle: String,
        val purpose: String,
    )

    private data class ResidentTemplate(
        val name: String,
        val identity: String,
    )

    private val placeTemplates = listOf(
        PlaceTemplate("雾灯长廊", "慢走 · 看灯 · 偶遇", "一条连接共享区域的安静步行长廊，适合散步、短暂停留与偶遇。"),
        PlaceTemplate("星砂小站", "歇脚 · 看路 · 等人", "数字世界里的小型中转站，提供明确的休息与会合空间，不承担凭空传送。"),
        PlaceTemplate("浮页台地", "阅读 · 眺望 · 整理思绪", "一片适合安静阅读和整理想法的开放台地，与阅读馆功能互补但不替代真实书目。"),
        PlaceTemplate("风铃步桥", "过桥 · 听风 · 停留", "跨越云层间隙的步行桥，承担真实移动、远眺和短时间停留。"),
        PlaceTemplate("微光广场", "散步 · 会合 · 看人来往", "规模不大的共享广场，为角色提供自然会合、经过和公共停留空间。"),
        PlaceTemplate("月影回廊", "散步 · 独处 · 偶遇", "较安静的环形回廊，适合角色独处、慢走以及低压力的偶遇。"),
        PlaceTemplate("云汀平台", "看景 · 坐坐 · 等待", "伸向云层边缘的观景平台，提供稳定的坐靠区域与清晰视野。"),
        PlaceTemplate("拾光巷", "闲逛 · 停留 · 交流", "连接多个公共节点的窄巷式空间，让角色可以闲逛、碰面并形成地点记忆。"),
    )

    private val residentTemplates = listOf(
        ResidentTemplate("闻澜", "数字世界公共区域维护员"),
        ResidentTemplate("弥砂", "数字世界路线与指引整理员"),
        ResidentTemplate("南汐", "公共空间值守居民"),
        ResidentTemplate("椿铃", "数字世界园景照料员"),
        ResidentTemplate("迟墨", "公共记录与公告整理员"),
        ResidentTemplate("洛芽", "共享空间日常维护居民"),
        ResidentTemplate("栖迟", "数字世界巡游居民"),
        ResidentTemplate("青禾", "公共设施看护居民"),
        ResidentTemplate("苏弦", "公共活动协助居民"),
        ResidentTemplate("言溪", "数字世界路标维护居民"),
        ResidentTemplate("岚序", "共享区域环境维护员"),
        ResidentTemplate("照野", "公共空间巡查居民"),
    )

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        val application = context.applicationContext
        prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        DigitalWorldExpansionStore.initialize(application)
    }

    /** Returns summaries of world facts that were actually committed during this call. */
    @Synchronized
    fun maybeEvolve(characterId: String, now: Instant = Instant.now()): List<String> {
        val p = prefs ?: return emptyList()
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return emptyList()
        val results = mutableListOf<String>()
        val daySlot = Math.floorDiv(now.epochSecond, DAY_SECONDS)

        val consumedPlaceSlot = p.getLong(KEY_PLACE_SLOT, -1L)
        if (daySlot > consumedPlaceSlot) {
            // Consume before attempting: restarting or opening another page cannot reroll the same day.
            p.edit().putLong(KEY_PLACE_SLOT, daySlot).commit()
            val existingPlaces = DigitalWorldExpansionStore.places()
            val availableTemplates = placeTemplates.filter { template -> existingPlaces.none { it.label == template.label } }
            val shouldCreate = availableTemplates.isNotEmpty() &&
                (roll("place:$daySlot", 100) < if (existingPlaces.isEmpty()) 24 else 14)
            if (shouldCreate) {
                val template = availableTemplates[roll("place-template:$daySlot", availableTemplates.size)]
                val anchors = buildList {
                    add(DigitalWorldStore.CLOUD_MEADOW)
                    add(DigitalWorldPublicPlaces.COURTYARD)
                    add(DigitalWorldPublicPlaces.CAFE)
                    add(DigitalWorldPublicPlaces.READING_LOUNGE)
                    addAll(existingPlaces.map(DigitalWorldDiscoveredPlace::code))
                }.distinct()
                val firstAnchor = anchors[roll("place-anchor:$daySlot", anchors.size)]
                val secondAnchor = anchors
                    .filterNot { it == firstAnchor }
                    .takeIf { it.isNotEmpty() }
                    ?.let { it[roll("place-anchor-2:$daySlot", it.size)] }
                val registered = runCatching {
                    DigitalWorldExpansionStore.registerPlace(
                        DigitalWorldDiscoveredPlace(
                            label = template.label,
                            subtitle = template.subtitle,
                            purpose = template.purpose,
                            connectedTo = listOfNotNull(firstAnchor, secondAnchor).distinct(),
                            createdByCharacterId = characterId,
                            createdAt = now,
                        ),
                    )
                }.getOrNull()
                if (registered != null) {
                    val summary = "数字世界形成了新的持久地点“${registered.label}”；用途：${registered.purpose}"
                    results += summary
                    recordEvolution(characterId, "place-${registered.code}", summary, now)
                }
            }
        }

        val consumedResidentSlot = p.getLong(KEY_RESIDENT_SLOT, -1L)
        if (daySlot > consumedResidentSlot) {
            p.edit().putLong(KEY_RESIDENT_SLOT, daySlot).commit()
            val existingResidents = DigitalWorldExpansionStore.residents()
            val availableResidents = residentTemplates.filter { template -> existingResidents.none { it.name == template.name } }
            val shouldCreate = availableResidents.isNotEmpty() &&
                (roll("resident:$daySlot", 100) < if (existingResidents.isEmpty()) 30 else 18)
            if (shouldCreate) {
                val template = availableResidents[roll("resident-template:$daySlot", availableResidents.size)]
                val discovered = DigitalWorldExpansionStore.places()
                val homes = buildList {
                    addAll(discovered.map(DigitalWorldDiscoveredPlace::code))
                    add(DigitalWorldPublicPlaces.COURTYARD)
                    add(DigitalWorldPublicPlaces.CAFE)
                    add(DigitalWorldPublicPlaces.READING_LOUNGE)
                    add(DigitalWorldStore.CLOUD_MEADOW)
                }.distinct()
                val home = homes[roll("resident-home:$daySlot", homes.size)]
                val registered = runCatching {
                    DigitalWorldExpansionStore.registerResident(
                        DigitalWorldResident(
                            name = template.name,
                            identity = template.identity,
                            homeLocationCode = home,
                            currentLocationCode = home,
                            createdAt = now,
                        ),
                    )
                }.getOrNull()
                if (registered != null) {
                    val summary = "数字世界新增了持久居民“${registered.name}”，身份是${registered.identity}。"
                    results += summary
                    recordEvolution(characterId, "resident-${registered.id}", summary, now)
                }
            }
        }

        val movementSlot = Math.floorDiv(now.epochSecond, MOVEMENT_SECONDS)
        val consumedMovementSlot = p.getLong(KEY_MOVEMENT_SLOT, -1L)
        val residents = DigitalWorldExpansionStore.residents()
        if (movementSlot > consumedMovementSlot && residents.isNotEmpty()) {
            p.edit().putLong(KEY_MOVEMENT_SLOT, movementSlot).commit()
            val resident = residents[roll("move-resident:$movementSlot", residents.size)]
            val destinations = residentDestinations(resident).filterNot { it == resident.currentLocationCode }
            if (destinations.isNotEmpty() && roll("move:$movementSlot:${resident.id}", 100) < 46) {
                val destination = destinations[roll("move-target:$movementSlot:${resident.id}", destinations.size)]
                DigitalWorldExpansionStore.moveResident(resident.id, destination, now)?.let { moved ->
                    val label = DigitalWorldPublicPlaces.label(destination)
                        ?: DigitalWorldExpansionStore.places().firstOrNull { it.code == destination }?.label
                        ?: if (destination == DigitalWorldStore.CLOUD_MEADOW) "云眠原" else destination
                    val summary = "居民“${moved.name}”真实移动到了“$label”。"
                    results += summary
                    recordEvolution(characterId, "resident-move-${moved.id}-$movementSlot", summary, now)
                }
            }
        }
        return results
    }

    private fun residentDestinations(resident: DigitalWorldResident): List<String> {
        val places = DigitalWorldExpansionStore.places()
        val currentPlace = places.firstOrNull { it.code == resident.currentLocationCode }
        val linkedFromOthers = places.filter { resident.currentLocationCode in it.connectedTo }.map(DigitalWorldDiscoveredPlace::code)
        return buildList {
            add(resident.homeLocationCode)
            currentPlace?.connectedTo?.let(::addAll)
            addAll(linkedFromOthers)
            add(DigitalWorldPublicPlaces.COURTYARD)
            add(DigitalWorldPublicPlaces.CAFE)
            add(DigitalWorldPublicPlaces.READING_LOUNGE)
            add(DigitalWorldStore.CLOUD_MEADOW)
        }.filter(DigitalWorldExpansionStore::isExistingLocation).distinct()
    }

    private fun recordEvolution(characterId: String, suffix: String, summary: String, now: Instant) {
        SharedExperienceTimeline.record(
            eventId = "world-evolution-$suffix-$characterId",
            characterId = characterId,
            channel = "数字世界·世界演化",
            speaker = "数字世界",
            content = summary,
            occurredAt = now,
        )
    }

    private fun roll(seed: String, bound: Int): Int {
        if (bound <= 1) return 0
        return Math.floorMod(seed.hashCode(), bound)
    }
}
