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
        val gender: String,
        val background: String,
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
        "青禾" to ("谨慎耐心，珍惜约定" to "游戏馆机台、修理旧物与桌面游戏"),
        "苏弦" to ("热情有分寸，也敢反对" to "小游戏"),
        "言溪" to ("温和固执，方向感强" to "地名与猜谜"),
        "岚序" to ("清冷寡言，措辞精确" to "光影"),
        "照野" to ("爽朗果断，讨厌拖延" to "晨间巡游"),
    )

    private val residentTemplates = listOf(
        ResidentTemplate("闻澜", "公共区域维护员", "女",
            "从小在庭院附近长大，跟着长辈学习修理长椅。工作细致，却因太在乎完好无损，常常不肯把任务交给同伴；休息时会独自走远路。"),
        ResidentTemplate("弥砂", "路线与指引整理员", "男",
            "小时候迷过路，从此喜欢把陌生路径画成地图。能迅速记住岔路，却不喜欢别人一边求指路一边不听；正尝试教新来的邻居认路。"),
        ResidentTemplate("南汐", "社区迎新接待员", "女",
            "在热闹街区生活多年，知道不少邻里小故事，也习惯主动问候。她能聊得热闹，但谈到自己的烦恼就转移话题，正在学会向熟人求助。"),
        ResidentTemplate("椿铃", "园景照料员", "女",
            "以前种坏过一片花圃，因此养成逐日观察植物的习惯。珍惜别人劳动成果，不会轻易原谅随手折枝的人；闲时收藏不同季节的种子。"),
        ResidentTemplate("迟墨", "公共记录整理员", "男",
            "继承了家中的旧公告册，对记错时间十分敏感。做事可靠又有点钻牛角尖，面对没有证据的传言总想追问来源，偶尔因此错过聚会。"),
        ResidentTemplate("洛芽", "游戏馆活动策划师", "女",
            "从外街搬来后靠帮人布置活动结识邻居，擅长色彩搭配。行动快、容易答应太多事，正在练习只接下真正有余力完成的工作。"),
        ResidentTemplate("栖迟", "街巷巡游居民", "男",
            "曾经长期守着固定岗位，后来选择每日走访不同街巷。喜欢独处也乐于偶遇，不爱被人追问计划；一直想找到能安静读书的小店。"),
        ResidentTemplate("青禾", "游戏馆机台管理员", "女",
            "在旧修理铺学会给器物分类和修补，如今负责游戏馆机台、设备状态与规则说明。说话慢、履约认真，游戏时却也喜欢认真较量；最近在练习更主动地结识玩家。"),
        ResidentTemplate("苏弦", "活动与游戏协助员", "男",
            "小时候常组织邻里小游戏，擅长把新人带进活动。热情却不爱输，遇到争议会认真讲规则；正在学习接受不同人的玩耍方式。"),
        ResidentTemplate("言溪", "路标维护居民", "女",
            "住在常年起雾的旧街附近，熟悉弯弯绕绕的路。待人温和但对方向问题很执拗，业余喜欢和邻居交换地名谜题，想整理一份旧街故事。"),
        ResidentTemplate("岚序", "光影环境维护员", "非二元",
            "从事夜间灯光巡查，习惯用照片记录不同时间的光影变化。观察力细密、社交时略显疏离；最近想学会不只记录现场，也说出自己的感受。"),
        ResidentTemplate("照野", "公共空间巡查员", "男",
            "原本负责清晨开门和道路巡查，天亮前常已走过半个街区。办事果断却偶尔急躁，喜欢晨跑，正在尝试与做事节奏较慢的同事合作。"),
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
        // Citizens are not authorised to pay for arbitrary WORLD SPAWN model
        // proposals every time they check their own job or answer a message.
        if (ResidentCharacterBridge.nativeResident(characterId) != null) {
            appContext?.let { ResidentDailyRoutine.refresh(it, now) }
            return emptyList()
        }
        if (appContext?.let(ResidentDailyPolicy::allowNewResidents) == true)
            scheduleValidatedModelProposal(characterId, now)
        val results = mutableListOf<String>()
        // A brand-new persistent world with literally ZERO residents has no
        // possible social encounter. The old 30% daily spawn probability could
        // leave it empty for days. Bootstrap one real stored local resident;
        // meeting her still requires the character to walk to her location and
        // choose an actual interaction through the executor.
        if (DigitalWorldExpansionStore.residents().isEmpty() &&
            appContext?.let { ResidentDailyPolicy.mayAddResident(it, 0) } == true &&
            !DigitalWorldExpansionStore.isRetiredTemplate(residentTemplates.first().name)) {
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
                    gender = initial.gender,
                    background = initial.background,
                ))
            }.getOrNull()
            if (resident != null) {
                val summary = "数字世界已经登记第一位持久居民“${resident.name}”，她在浮光咖啡角附近拥有自己的家，并在那里值守。角色可以自行决定要不要去遇见她。"
                results += summary
                recordEvolution(characterId, "first-resident-${resident.id}", summary, now)
            }
        }
        DigitalWorldExpansionStore.residents().forEach { resident ->
            npcQualities[resident.templateName]?.let { (temperament, preferences) ->
                val template = residentTemplates.firstOrNull { it.name == resident.templateName }
                DigitalWorldExpansionStore.fillResidentTraitsIfBlank(
                    resident.id, temperament, preferences,
                    gender = template?.gender.orEmpty(), background = template?.background.orEmpty())
            }
        }
        // Cafe employee is a world resident with an actual registered home.
        var barista = DigitalWorldExpansionStore.residents().firstOrNull { it.isCafeBarista }
        if (barista == null &&
            appContext?.let { ResidentDailyPolicy.mayAddResident(it, DigitalWorldExpansionStore.residents().size) } == true &&
            !DigitalWorldExpansionStore.isRetiredTemplate("岑灯")) {
            barista = runCatching {
                DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
                    name = "岑灯",
                    identity = "浮光咖啡角店员",
                    homeLocationCode = DigitalWorldPublicPlaces.CAFE,
                    createdAt = now,
                    temperament = "安静细心，重视手艺，有自己的边界",
                    preferences = "研究咖啡风味，记得真实聊过的顾客",
                    gender = "女",
                    background = "以前在流动小摊学做饮品，攒钱在浮光咖啡角开店；最近苦恼如何维持店铺与自己的休息时间，也有不想迎合客人的口味。",
                ))
            }.getOrNull()
            if (barista != null) {
                val summary = "浮光咖啡角登记了店员“${barista.name}”，她有自己的住处和轮班。"
                results += summary
                recordEvolution(characterId, "cafe-barista-${barista.id}", summary, now)
            }
        }
        // Only ResidentDailyRoutine may settle an on-duty shift. The removed
        // hour-based teleport used to overwrite her self-chosen outings.
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
            val allowNewResidents = appContext?.let { ResidentDailyPolicy.mayAddResident(it, existingResidents.size) } == true
            val availableResidents = if (!allowNewResidents) emptyList() else residentTemplates.filter { template ->
                existingResidents.none { it.templateName == template.name } &&
                    !DigitalWorldExpansionStore.isRetiredTemplate(template.name)
            }
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
                            gender = template.gender,
                            background = template.background,
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

        // Native citizens now follow their own persisted responsibilities
        // and physical needs. The old single randomly teleported resident
        // could not prove that two real people were in the cafe together.
        // Refresh all residents together, no extra character-model API call.
        appContext?.let { application ->
            ResidentDailyRoutine.refresh(application, now)
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
