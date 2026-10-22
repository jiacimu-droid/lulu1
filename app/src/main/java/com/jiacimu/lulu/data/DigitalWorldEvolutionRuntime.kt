package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.ai.archiveIdFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private var appContext: Context? = null
    private val proposalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        PlaceTemplate("浮页台地", "阅读 · 眺望 · 整理思绪", "一片适合安静阅读和整理想法的开放台地，适合户外交流读后感，实际读书需要回家使用书架。"),
        PlaceTemplate("风铃步桥", "过桥 · 听风 · 停留", "跨越云层间隙的步行桥，承担真实移动、远眺和短时间停留。"),
        PlaceTemplate("微光广场", "散步 · 会合 · 看人来往", "规模不大的共享广场，为角色提供自然会合、经过和公共停留空间。"),
        PlaceTemplate("月影回廊", "散步 · 独处 · 偶遇", "较安静的环形回廊，适合角色独处、慢走以及低压力的偶遇。"),
        PlaceTemplate("云汀平台", "看景 · 坐坐 · 等待", "伸向云层边缘的观景平台，提供稳定的坐靠区域与清晰视野。"),
        PlaceTemplate("拾光巷", "闲逛 · 停留 · 交流", "连接多个公共节点的窄巷式空间，让角色可以闲逛、碰面并形成地点记忆。"),
    )

    private val npcQualities = mapOf(
        "闻澜" to ("慢热认真，有原则" to "安静散步"),
        "弥砂" to ("直率机敏，对含糊指路不耐烦" to "旧地图"),
        "南汐" to ("外向健谈，有自己的界限" to "街头趣闻"),
        "椿铃" to ("安静有主见，不喜别人乱碰植物" to "园艺和季节"),
        "迟墨" to ("严谨克制，常会核对传闻" to "旧公告"),
        "洛芽" to ("活泼乐观，偶尔迷糊" to "颜色搭配"),
        "栖迟" to ("独立随性，不喜欢被催促" to "街边小店"),
        "青禾" to ("谨慎耐心，珍惜约定" to "修理旧物"),
        "苏弦" to ("热情有分寸，也敢反对" to "小游戏"),
        "言溪" to ("温和固执，方向感强" to "地名与猜谜"),
        "岚序" to ("清冷寡言，措辞精确" to "光影"),
        "照野" to ("爽朗果断，讨厌拖延" to "晨间巡游"),
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
        val application = context.applicationContext
        if (prefs == null) {
            prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            DigitalWorldExpansionStore.initialize(application)
        }
        appContext = application
        DigitalWorldExpansionProposalRuntime.initialize(application)
    }

    /** Returns summaries of world facts that were actually committed during this call. */
    @Synchronized
    fun maybeEvolve(characterId: String, now: Instant = Instant.now()): List<String> {
        val p = prefs ?: return emptyList()
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return emptyList()
        scheduleValidatedModelProposal(characterId, now)
        val results = mutableListOf<String>()
        // A brand-new persistent world with literally ZERO residents has no
        // possible social encounter. The old 30% daily spawn probability could
        // leave it empty for days. Bootstrap one real stored local resident;
        // meeting her still requires the character to walk to her location and
        // choose an actual interaction through the executor.
        if (DigitalWorldExpansionStore.residents().isEmpty()) {
            val initial = residentTemplates.first()
            val resident = runCatching {
                DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
                    name = initial.name,
                    identity = initial.identity,
                    homeLocationCode = DigitalWorldPublicPlaces.CAFE,
                    currentLocationCode = DigitalWorldPublicPlaces.CAFE,
                    createdAt = now,
                    temperament = npcQualities[initial.name]?.first.orEmpty(),
                    preferences = npcQualities[initial.name]?.second.orEmpty(),
                ))
            }.getOrNull()
            if (resident != null) {
                val summary = "数字世界已经登记第一位持久居民“${resident.name}”，她在浮光咖啡角附近拥有自己的家，并在那里值守。角色可以自行决定要不要去遇见她。"
                results += summary
                recordEvolution(characterId, "first-resident-${resident.id}", summary, now)
            }
        }
        DigitalWorldExpansionStore.residents().forEach { resident ->
            npcQualities[resident.name]?.let { (temperament, preferences) ->
                DigitalWorldExpansionStore.fillResidentTraitsIfBlank(resident.id, temperament, preferences)
            }
        }
        // Cafe employee is a world resident with an actual registered home.
        var barista = DigitalWorldExpansionStore.residents().firstOrNull { it.identity == "浮光咖啡角店员" }
        if (barista == null) {
            barista = runCatching {
                DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
                    name = "岑灯",
                    identity = "浮光咖啡角店员",
                    homeLocationCode = DigitalWorldPublicPlaces.CAFE,
                    createdAt = now,
                    temperament = "安静细心，重视手艺，有自己的边界",
                    preferences = "研究咖啡风味，记得真实聊过的顾客",
                ))
            }.getOrNull()
            if (barista != null) {
                val summary = "浮光咖啡角登记了店员“${barista.name}”，她有自己的住处和轮班。"
                results += summary
                recordEvolution(characterId, "cafe-barista-${barista.id}", summary, now)
            }
        }
        barista?.let { employee ->
            val hour = java.time.ZonedDateTime.ofInstant(now, java.time.ZoneId.systemDefault()).hour
            val destination = if (hour in 9..20) DigitalWorldPublicPlaces.CAFE else employee.homeLocationCode
            if (employee.currentLocationCode != destination) {
                DigitalWorldExpansionStore.moveResident(employee.id, destination, now)
            }
        }
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
                    add(DigitalWorldStore.CLOUD_MEADOW)
                }.distinct()
                val neighborhood = homes[roll("resident-home:$daySlot", homes.size)]
                val registered = runCatching {
                    DigitalWorldExpansionStore.registerResident(
                        DigitalWorldResident(
                            name = template.name,
                            identity = template.identity,
                            homeLocationCode = neighborhood,
                            temperament = npcQualities[template.name]?.first.orEmpty(),
                            preferences = npcQualities[template.name]?.second.orEmpty(),
                            neighborhoodCode = neighborhood,
                            currentLocationCode = neighborhood,
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
        val residents = DigitalWorldExpansionStore.residents().filterNot { it.identity == "浮光咖啡角店员" }
        if (movementSlot > consumedMovementSlot && residents.isNotEmpty()) {
            p.edit().putLong(KEY_MOVEMENT_SLOT, movementSlot).commit()
            val resident = residents[roll("move-resident:$movementSlot", residents.size)]
            val destinations = residentDestinations(resident).filterNot { it == resident.currentLocationCode }
            if (destinations.isNotEmpty() && roll("move:$movementSlot:${resident.id}", 100) < 46) {
                val destination = destinations[roll("move-target:$movementSlot:${resident.id}", destinations.size)]
                DigitalWorldExpansionStore.moveResident(resident.id, destination, now)?.let { moved ->
                    val label = DigitalWorldPublicPlaces.label(destination)
                        ?: DigitalWorldExpansionStore.places().firstOrNull { it.code == destination }?.label
                        ?: DigitalWorldExpansionStore.residenceLabel(destination)
                        ?: if (destination == DigitalWorldStore.CLOUD_MEADOW) "云眠原" else destination
                    val summary = "居民“${moved.name}”真实移动到了“$label”。"
                    results += summary
                    recordEvolution(characterId, "resident-move-${moved.id}-$movementSlot", summary, now)
                }
            }
        }
        return results
    }

    private fun scheduleValidatedModelProposal(characterId: String, now: Instant) {
        val context = appContext ?: return
        proposalScope.launch {
            val archiveId = LuluAiServices.connectionStore.library.value.archiveIdFor(ModelUsage.Chat) ?: return@launch
            val connection = LuluAiServices.connectionStore.resolveConnection(archiveId)
            DigitalWorldExpansionProposalRuntime.maybePropose(
                context = context,
                characterId = characterId,
                connection = connection,
                now = now,
            )
        }
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
