package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/**
 * Hidden, plot-only backstage state for the apocalypse game.
 *
 * This is intentionally separate from companion memory and from player-visible canon. It gives the
 * showrunner a persistent place to remember what the rest of the world is doing while the camera is
 * elsewhere, and what fair surprises have already been prepared. Nothing here becomes canon until a
 * later scene actually makes it observable and the normal scene receipt persists it.
 */
internal data class ApocalypseWorldClockV5(
    val id: String,
    val title: String,
    val domain: String,
    val driver: String,
    val currentState: String,
    val nextMove: String,
    val progress: Int = 0,
    val pressure: Int = 1,
    val visibility: String = "hidden",
    val status: String = "active",
    val lastAdvancedScene: Int = 1,
    val dueDayIndex: Int? = null,
    val linkedCharacterIds: List<String> = emptyList(),
    val linkedThreadIds: List<String> = emptyList(),
)

internal data class ApocalypseDirectorSetupV5(
    val id: String,
    val title: String,
    val deviceType: String,
    val hiddenPurpose: String,
    val triggerCondition: String,
    val missedMutation: String,
    val status: String = "planned",
    val plantedScene: Int = 1,
    val lastTouchedScene: Int = 1,
    val earliestRevealScene: Int = 2,
    val latestRevealScene: Int = 8,
    val visibleSeeds: List<String> = emptyList(),
    val linkedCharacterIds: List<String> = emptyList(),
    val linkedThreadIds: List<String> = emptyList(),
    val linkedForeshadowIds: List<String> = emptyList(),
    val linkedLocationIds: List<String> = emptyList(),
)

internal data class ApocalypseSurpriseRecordV5(
    val scene: Int,
    val type: String,
    val sourceId: String,
    val summary: String,
)

internal data class ApocalypseLivingWorldStateV5(
    val saveId: String,
    val worldClocks: List<ApocalypseWorldClockV5>,
    val directorSetups: List<ApocalypseDirectorSetupV5>,
    val recentSurprises: List<ApocalypseSurpriseRecordV5> = emptyList(),
    val seasonQuestion: String = "",
    val nextDirectorScene: Int = 4,
    val nextDirectorReason: String = "定期检查离屏世界与玩家路线是否开始相交",
    val lastProcessedScene: Int = 1,
    val revision: Int = 1,
)

internal class ApocalypseLivingWorldStoreV5(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(save: ApocalypseV3Save): ApocalypseLivingWorldStateV5 = synchronized(LOCK) {
        val decoded = decodeState(prefs.getString(key(save.id), null))
        val resolved = when {
            decoded == null -> defaultLivingWorldStateV5(save)
            // A story rollback must also erase backstage plans that depended on the deleted future.
            decoded.lastProcessedScene > save.scene -> defaultLivingWorldStateV5(save)
            else -> decoded
        }
        if (decoded !== resolved) persist(resolved)
        resolved
    }

    fun promptForDirector(save: ApocalypseV3Save): String {
        val state = load(save)
        val clocks = state.worldClocks
            .filter { it.status == "active" || it.status == "paused" }
            .sortedWith(compareByDescending<ApocalypseWorldClockV5> { it.pressure }.thenByDescending { it.progress })
            .take(8)
        val setups = state.directorSetups
            .filter { it.status in setOf("planned", "seeded", "armed", "repurposed") }
            .sortedWith(compareBy<ApocalypseDirectorSetupV5> { it.latestRevealScene }.thenByDescending { it.lastTouchedScene })
            .take(7)
        return buildString {
            appendLine("【隐藏活世界导演台｜只有总导演可见，绝不能直接泄露给玩家】")
            appendLine("核心：世界不等玩家触发才运行；导演布置的是因果和机会，不是强制路线。玩家不接钩子时，事件可以继续、变形、被别人完成或永久错过，禁止把同一钩子瞬移到玩家面前。")
            appendLine("当前阶段主题问题=${state.seasonQuestion.ifBlank { "让玩家自己的选择逐渐决定这一阶段真正关心什么，不预设唯一答案" }}")
            appendLine("建议下次导演检查=第${state.nextDirectorScene}幕前后；原因=${state.nextDirectorReason}")
            if (clocks.isNotEmpty()) {
                appendLine("离屏世界时钟：")
                clocks.forEach { clock ->
                    appendLine(
                        "- ${clock.id}|${clock.title}|${clock.domain}|进度${clock.progress}%|压力${clock.pressure}/10|" +
                            "现状=${clock.currentState.take(180)}|下一步=${clock.nextMove.take(180)}|" +
                            "截止日=${clock.dueDayIndex?.toString() ?: "无硬截止"}",
                    )
                }
            }
            if (setups.isNotEmpty()) {
                appendLine("已布置但尚未强制兑现的舞台机关：")
                setups.forEach { setup ->
                    appendLine(
                        "- ${setup.id}|${setup.deviceType}|${setup.status}|窗口${setup.earliestRevealScene}-${setup.latestRevealScene}幕|" +
                            "真正用途=${setup.hiddenPurpose.take(220)}|触发=${setup.triggerCondition.take(180)}|" +
                            "错过后=${setup.missedMutation.take(180)}|玩家已见痕迹=${setup.visibleSeeds.takeLast(3).joinToString("；").ifBlank { "无" }}",
                    )
                }
            }
            if (state.recentSurprises.isNotEmpty()) {
                appendLine("最近已经用过的惊喜手法（避免重复）：")
                state.recentSurprises.takeLast(6).forEach { item ->
                    appendLine("- 第${item.scene}幕|${item.type}|${item.summary.take(160)}")
                }
            }
            appendLine("导演使用规则：只让与玩家当前路线在物理、人物、信息或时间上真正相交的后台事项进入下一幕；其余继续离屏。惊喜必须来自旧痕迹、离屏角色/势力行动、环境时钟或玩家旧选择之一，不能凭空救场或凭空害人。")
        }.trim()
    }

    fun shouldWakeDirector(save: ApocalypseV3Save): Boolean {
        val state = load(save)
        return save.scene + 1 >= state.nextDirectorScene
    }

    /** Cheap local bookkeeping. This runs synchronously after a scene becomes canon. */
    fun recordScene(
        saveBefore: ApocalypseV3Save,
        saveAfter: ApocalypseV3Save,
        action: String,
        outcome: ApocalypseSceneOutcomeV5,
        beat: ApocalypseV3Beat,
    ) = synchronized(LOCK) {
        val current = load(saveBefore)
        val clocks = current.worldClocks.map { clock -> refreshBuiltInClockV5(clock, saveAfter) }
        val movesByForeshadow = beat.foreshadowMoves
            .mapNotNull { raw -> raw.substringBefore(':').trim().takeIf(String::isNotBlank)?.let { it to raw } }
            .toMap()
        val setups = current.directorSetups.map { setup ->
            val move = setup.linkedForeshadowIds.firstNotNullOfOrNull(movesByForeshadow::get)
            if (move == null) {
                setup
            } else {
                setup.copy(
                    status = when (setup.status) {
                        "planned" -> "seeded"
                        "seeded" -> "armed"
                        else -> setup.status
                    },
                    lastTouchedScene = saveAfter.scene,
                    visibleSeeds = (setup.visibleSeeds + move.take(240)).distinct().takeLast(6),
                )
            }
        }
        val nextSceneFloor = max(saveAfter.scene + 1, current.nextDirectorScene)
        persist(
            current.copy(
                worldClocks = clocks,
                directorSetups = setups,
                nextDirectorScene = nextSceneFloor,
                lastProcessedScene = saveAfter.scene,
                revision = current.revision + 1,
            ),
        )
    }

    fun replaceFromBackstage(
        saveAfter: ApocalypseV3Save,
        next: ApocalypseLivingWorldStateV5,
    ) = synchronized(LOCK) {
        // Never let an old background response resurrect a future that the player has rolled back.
        val current = load(saveAfter)
        if (current.lastProcessedScene > saveAfter.scene) return@synchronized
        persist(
            next.copy(
                saveId = saveAfter.id,
                lastProcessedScene = saveAfter.scene,
                revision = max(current.revision + 1, next.revision),
            ),
        )
    }

    private fun persist(state: ApocalypseLivingWorldStateV5) {
        prefs.edit().putString(key(state.saveId), encodeState(state).toString()).apply()
    }

    private fun key(saveId: String): String = "living_$saveId"

    private companion object {
        const val PREFS_NAME = "apocalypse_living_world_v5"
        val LOCK = Any()
    }
}

internal object ApocalypseLivingWorldRuntimeV5 {
    private val refreshMutexes = mutableMapOf<String, Mutex>()
    private val mutexLock = Any()

    fun shouldRefreshBackstage(
        saveAfter: ApocalypseV3Save,
        beat: ApocalypseV3Beat,
        outcome: ApocalypseSceneOutcomeV5,
        usedDirector: Boolean,
    ): Boolean = usedDirector ||
        saveAfter.scene % 4 == 0 ||
        beat.foreshadowMoves.isNotEmpty() ||
        outcome.storyThreadUpdates.isNotEmpty() ||
        outcome.foreshadowPatches.isNotEmpty() ||
        beat.minutesPassed >= 360

    suspend fun refreshAfterScene(
        context: Context,
        saveBefore: ApocalypseV3Save,
        saveAfter: ApocalypseV3Save,
        action: String,
        outcome: ApocalypseSceneOutcomeV5,
        beat: ApocalypseV3Beat,
    ) {
        val mutex = synchronized(mutexLock) { refreshMutexes.getOrPut(saveAfter.id) { Mutex() } }
        if (!mutex.tryLock()) return
        try {
            val storage = ApocalypseSurvivalV3Store(context)
            val latestBefore = storage.loadSave() ?: return
            if (latestBefore.id != saveAfter.id || latestBefore.scene != saveAfter.scene) return

            val store = ApocalypseLivingWorldStoreV5(context)
            val current = store.load(saveAfter)
            val facts = buildString {
                appendLine("存档=${saveAfter.id}；刚完成第${saveAfter.scene}幕；世界时间=${apocalypseDayLabelV5(saveAfter.director.dayIndex)} ${apocalypseClockLabelV5(saveAfter.director.clockMinutes)}")
                appendLine("玩家刚才自由选择：${action.take(240)}")
                appendLine("本幕可见结果：${outcome.actionOutcome.ifBlank { compactApocalypseSceneExcerptV5(saveAfter.narration, 160) }.take(360)}")
                appendLine("本幕正史摘要：${outcome.continuitySummary.ifBlank { compactApocalypseSceneExcerptV5(saveAfter.narration) }.take(480)}")
                appendLine("当前地点=${saveAfter.director.location}；阶段=${saveAfter.director.phase}；威胁=${saveAfter.director.tension}/10")
                appendLine("当前主/暗线：")
                saveAfter.director.storyThreads
                    .filter { it.status == "active" || it.status == "dormant" }
                    .sortedByDescending { it.lastTouchedScene }
                    .take(8)
                    .forEach { thread -> appendLine("- ${thread.id}|${thread.visibility}|${thread.currentState.take(220)}|下一压力=${thread.nextPressure.take(180)}") }
                appendLine("关键人物离屏意图：")
                saveAfter.director.characterDossiers
                    .filter { it.status == "active" || it.status == "away" }
                    .sortedWith(compareByDescending<ApocalypseCharacterDossierV5> { it.importance == "key" || it.importance == "companion" }.thenByDescending { it.lastSeenScene })
                    .take(12)
                    .forEach { dossier ->
                        appendLine("- ${dossier.id}|${dossier.name}|位置=${dossier.currentLocation}|目标=${dossier.publicGoal.take(120)}|离屏打算=${dossier.offscreenIntent.take(160)}")
                    }
                appendLine("当前伏笔：")
                saveAfter.director.foreshadowLedger
                    .filter { it.stage != "paid_off" && it.stage != "abandoned" }
                    .sortedWith(compareBy<ApocalypseForeshadowV5> { it.targetPayoffEnd }.thenByDescending { it.lastTouchedScene })
                    .take(8)
                    .forEach { item -> appendLine("- ${item.id}|${item.stage}|表面=${item.surfaceMeaning.take(140)}|后台真相=${item.hiddenTruth.take(180)}") }
                appendLine("当前幕后活世界账本：")
                append(current.toCompactJsonV5().toString())
            }
            val instruction = """
                你是《末世求生·赤潮纪元》的幕后世界制片/场务导演。你不写下一幕正文，也不替玩家决定行动；你的工作是在镜头之外让世界持续生活，并为未来准备公平、可错过、可变形的惊喜。只返回一个JSON对象，不加代码块。

                【最高原则：自由高于剧本】
                1. 玩家没有义务接任何主线。玩家囤货、做饭、睡觉、训练、谈恋爱、建基地、绕路、拒绝组织、跨市或长期待在一个地方都可以成为真正路线。
                2. 你只能改变未来，不能改写已经发生的正史。玩家明确救下、杀死、拒绝、获得、失去、公开或摧毁的东西永久有效。
                3. 世界不等待玩家。离屏NPC、势力、天气、道路、供应链、感染生态和行政系统按自己的目标和真实时间继续变化。
                4. 玩家错过一个机会时，不准把同一人/物/事故神奇搬到下一站。允许它被别人得到、造成新的后果、变成传闻、改换所有者、过期，甚至永久消失。
                5. 不是所有东西都服务主谜团。至少约三分之一的后台准备应来自生存日常、人物私人目标、关系、资源、社区、旅行或世界生活感，否则玩家会感觉整个宇宙都围着她转。

                【惊喜公平合同】
                - 一个真正的惊喜必须能追溯到至少一种来源：玩家早先的选择；玩家见过的细节/伏笔；某个NPC或势力已有目标；真实时间/环境时钟。
                - 惊喜不是“突然更强怪物/突然背叛/突然电话/突然失踪”的随机刺激。避免连续使用同一种deviceType。
                - 可以准备红鲱鱼、误会、旧物回响、意外重逢、第三方先行动、资源机会、关系反转、地图变化、制度后果、生态变化、安静的情感回收等不同手法。
                - 救场同样要有前因。没有提前存在的能力、路线、人物或资源，不能凭空赶来救玩家。
                - 真相回收要让旧细节获得新意义，同时产生现实后果；只解释设定不算惊喜。

                【长期运行合同】
                - worldClocks保持4—8个真正独立运行的离屏进程。progress为0—100，pressure为1—10；不要所有时钟都每幕增长。status只能active|paused|resolved|expired。
                - directorSetups保持3—7个尚未兑现的舞台机关。status只能planned|seeded|armed|triggered|repurposed|expired。每个机关必须有triggerCondition和missedMutation；可错过是硬要求。
                - 不要无限开新线：如果已有机关/时钟能承载新想法，优先推进、变形或合并。过期的要关闭。
                - seasonQuestion只是一段阶段性的主题提问，不是答案，也不是玩家必须完成的任务。玩家路线变化时可以换题。
                - nextDirectorScene表示下一次“总导演值得重新介入”的幕数，范围是当前幕+1到+6。只有世界时钟将撞上玩家、舞台机关成熟、玩家做了结构性选择或需要阶段复盘时才设得很近。
                - recentSurprises保留最近最多8次已经真正兑现的惊喜类型，用于避免重复。尚未兑现的机关不要提前记入recentSurprises。

                返回字段：seasonQuestion,nextDirectorScene,nextDirectorReason,worldClocks,directorSetups,recentSurprises。
                worldClocks每项完整字段：{id,title,domain,driver,currentState,nextMove,progress,pressure,visibility,status,lastAdvancedScene,dueDayIndex,linkedCharacterIds,linkedThreadIds}。visibility只能hidden|rumored|known。
                directorSetups每项完整字段：{id,title,deviceType,hiddenPurpose,triggerCondition,missedMutation,status,plantedScene,lastTouchedScene,earliestRevealScene,latestRevealScene,visibleSeeds,linkedCharacterIds,linkedThreadIds,linkedForeshadowIds,linkedLocationIds}。
                recentSurprises每项：{scene,type,sourceId,summary}。
                所有id稳定复用；不要把同一件事每次换id重建。
            """.trimIndent()

            val generated = LuluAiServices.gateway.generate(
                characterId = "__apocalypse_v5_backstage_world__",
                facts = facts,
                instruction = instruction,
                source = "末世求生V5幕后活世界",
                title = "末世求生 · 幕后世界第${saveAfter.scene}幕",
                temperature = 0.68,
                maxTokens = 1500,
                usage = ModelUsage.Game,
                contextMode = CompanionContextMode.PersonaAndScenario,
                streamResponse = false,
                readTimeoutMillis = 90_000,
            ).getOrNull() ?: return
            val parsed = parseBackstageStateV5(generated.text, current, saveAfter) ?: return

            val latestAfter = storage.loadSave() ?: return
            if (latestAfter.id != saveAfter.id || latestAfter.scene != saveAfter.scene || latestAfter.updatedAt != saveAfter.updatedAt) {
                return
            }
            store.replaceFromBackstage(saveAfter, parsed)
        } finally {
            mutex.unlock()
        }
    }
}

private fun defaultLivingWorldStateV5(save: ApocalypseV3Save): ApocalypseLivingWorldStateV5 {
    val scene = save.scene
    val day = save.director.dayIndex
    val defaultSetups = save.director.foreshadowLedger
        .filter { it.stage != "paid_off" && it.stage != "abandoned" }
        .take(7)
        .map { item ->
            ApocalypseDirectorSetupV5(
                id = "setup_${item.id}",
                title = item.title,
                deviceType = "旧细节新意义",
                hiddenPurpose = item.hiddenTruth,
                triggerCondition = "仅当玩家路线自然接触到与${item.title}有关的人、地点、物件、证据或现实后果时，才允许继续显影。",
                missedMutation = "若玩家避开相关路线，让该线索留在原地、被第三方接触、改变所有者或按世界逻辑过期；禁止追着玩家瞬移。",
                status = if (item.visibleEvidence.isEmpty()) "planned" else if (item.stage == "ripe") "armed" else "seeded",
                plantedScene = max(1, item.plantedScene),
                lastTouchedScene = max(scene, item.lastTouchedScene),
                earliestRevealScene = max(scene + 1, item.targetPayoffStart),
                latestRevealScene = max(scene + 2, item.targetPayoffEnd),
                visibleSeeds = item.visibleEvidence.takeLast(5),
                linkedCharacterIds = item.linkedCharacterIds,
                linkedForeshadowIds = listOf(item.id),
            )
        }
    return ApocalypseLivingWorldStateV5(
        saveId = save.id,
        worldClocks = listOf(
            ApocalypseWorldClockV5(
                id = "world_red_tide",
                title = "赤潮环境演化",
                domain = "生态/天气",
                driver = "全球赤潮载体与季节环境",
                currentState = builtInClockStateV5("world_red_tide", save),
                nextMove = "按真实世界时间推进沉降、污染、天气和生态变化，不因玩家是否关注而暂停。",
                progress = redTideProgressV5(day),
                pressure = if (day < 0) 4 else 7,
                visibility = if (day < 0) "rumored" else "known",
                lastAdvancedScene = scene,
                dueDayIndex = 0,
            ),
            ApocalypseWorldClockV5(
                id = "world_public_order",
                title = "公共秩序与官方响应",
                domain = "社会/治理",
                driver = "行政、警务、消防、医疗、社区与公众行为",
                currentState = builtInClockStateV5("world_public_order", save),
                nextMove = "按异常证据、通信与资源压力逐步响应；既不能第一小时蒸发，也不能永远无损运行。",
                progress = publicOrderProgressV5(day),
                pressure = 4,
                visibility = "known",
                lastAdvancedScene = scene,
            ),
            ApocalypseWorldClockV5(
                id = "world_infrastructure",
                title = "基础设施可靠度",
                domain = "电力/供水/通信/交通",
                driver = "人员、燃料、备件、负荷、污染与道路条件",
                currentState = builtInClockStateV5("world_infrastructure", save),
                nextMove = "不同系统以不同速度局部失效或被修复，留下真实地理后果。",
                progress = infrastructureProgressV5(day),
                pressure = if (day < 0) 2 else 6,
                visibility = "known",
                lastAdvancedScene = scene,
            ),
            ApocalypseWorldClockV5(
                id = "world_supply",
                title = "区域供应链",
                domain = "食物/药品/燃料/物流",
                driver = "仓储、产地、物流、冷链、支付与道路",
                currentState = builtInClockStateV5("world_supply", save),
                nextMove = "库存、运输和交易条件按城市功能与道路真实变化；机会与短缺都可被第三方先一步改变。",
                progress = supplyProgressV5(day),
                pressure = if (day < 0) 2 else 6,
                visibility = "known",
                lastAdvancedScene = scene,
            ),
            ApocalypseWorldClockV5(
                id = "world_factions",
                title = "离屏人物与势力行动",
                domain = "人物/势力",
                driver = "各角色和组织自己的目标、恐惧、资源与底线",
                currentState = "重要人物与势力拥有独立于玩家的日程；当前具体变化必须沿既有档案和剧情线推进。",
                nextMove = "让至少一个离屏主体在合理时间里做自己的事，但结果只有与玩家信息渠道相交时才进入前台。",
                progress = 10,
                pressure = 3,
                visibility = "hidden",
                lastAdvancedScene = scene,
            ),
        ),
        directorSetups = defaultSetups,
        seasonQuestion = "玩家拥有七日信息差与空间优势后，她选择怎样使用这份优势；她保护的生活会因此变成什么样？",
        nextDirectorScene = scene + 3,
        nextDirectorReason = "建立第一轮离屏世界节奏，并检查已有伏笔是否与玩家真实路线相交",
        lastProcessedScene = scene,
    )
}

private fun refreshBuiltInClockV5(clock: ApocalypseWorldClockV5, save: ApocalypseV3Save): ApocalypseWorldClockV5 = when (clock.id) {
    "world_red_tide" -> clock.copy(
        currentState = builtInClockStateV5(clock.id, save),
        progress = max(clock.progress, redTideProgressV5(save.director.dayIndex)),
        pressure = if (save.director.dayIndex < 0) max(clock.pressure, 4) else max(clock.pressure, 7),
        lastAdvancedScene = save.scene,
    )
    "world_public_order" -> clock.copy(
        currentState = builtInClockStateV5(clock.id, save),
        progress = max(clock.progress, publicOrderProgressV5(save.director.dayIndex)),
        lastAdvancedScene = save.scene,
    )
    "world_infrastructure" -> clock.copy(
        currentState = builtInClockStateV5(clock.id, save),
        progress = max(clock.progress, infrastructureProgressV5(save.director.dayIndex)),
        lastAdvancedScene = save.scene,
    )
    "world_supply" -> clock.copy(
        currentState = builtInClockStateV5(clock.id, save),
        progress = max(clock.progress, supplyProgressV5(save.director.dayIndex)),
        lastAdvancedScene = save.scene,
    )
    else -> clock.copy(lastAdvancedScene = save.scene)
}

private fun builtInClockStateV5(id: String, save: ApocalypseV3Save): String = when (id) {
    "world_red_tide" -> when {
        save.director.dayIndex < 0 -> "主沉降尚未来临；当前是${apocalypseDayLabelV5(save.director.dayIndex)}，异常只能按已确认剧情逐步显现。"
        save.director.dayIndex == 0 -> "主沉降日已经到来，赤潮开始成为可直接观察的环境变量。"
        else -> "主沉降已经发生，赤潮生态按天气、污染与时间继续演化。"
    }
    "world_public_order" -> "当前阶段=${save.director.phase}。官方与民间秩序只能按当前正史和真实时间逐步改变。"
    "world_infrastructure" -> "当前阶段=${save.director.phase}；已知地图变化以MAP_KNOWN账本为准，未确认的远处故障仍属于后台可能性。"
    "world_supply" -> "当前阶段=${save.director.phase}；玩家现有资金与物资是硬状态，区域供应变化必须有仓储、物流、产地、道路或交易因果。"
    else -> "按现有正史继续运行。"
}

private fun redTideProgressV5(day: Int): Int = if (day < 0) ((day + 7).coerceIn(0, 7) * 100 / 7) else 100
private fun publicOrderProgressV5(day: Int): Int = when {
    day <= -5 -> 5
    day <= -3 -> 15
    day < 0 -> 30
    day == 0 -> 55
    day <= 3 -> 75
    day <= 42 -> 90
    else -> 100
}
private fun infrastructureProgressV5(day: Int): Int = when {
    day < 0 -> 0
    day == 0 -> 20
    day <= 3 -> 55
    day <= 14 -> 75
    day <= 42 -> 88
    else -> 100
}
private fun supplyProgressV5(day: Int): Int = when {
    day <= -5 -> 0
    day <= -3 -> 8
    day < 0 -> 20
    day == 0 -> 45
    day <= 7 -> 70
    day <= 42 -> 90
    else -> 100
}

private fun parseBackstageStateV5(
    raw: String,
    previous: ApocalypseLivingWorldStateV5,
    saveAfter: ApocalypseV3Save,
): ApocalypseLivingWorldStateV5? = runCatching {
    val normalized = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val start = normalized.indexOf('{')
    val end = normalized.lastIndexOf('}')
    if (start < 0 || end <= start) return@runCatching null
    val json = JSONObject(normalized.substring(start, end + 1))
    val clocks = json.optJSONArray("worldClocks").livingObjectsV5(::decodeWorldClockV5).ifEmpty { previous.worldClocks }
    val setups = json.optJSONArray("directorSetups").livingObjectsV5(::decodeDirectorSetupV5).ifEmpty { previous.directorSetups }
    val surprises = json.optJSONArray("recentSurprises").livingObjectsV5(::decodeSurpriseV5).ifEmpty { previous.recentSurprises }
    previous.copy(
        worldClocks = clocks.distinctBy { it.id }.take(10),
        directorSetups = setups.distinctBy { it.id }.take(10),
        recentSurprises = surprises.takeLast(8),
        seasonQuestion = json.optString("seasonQuestion").trim().take(280).ifBlank { previous.seasonQuestion },
        nextDirectorScene = json.optInt("nextDirectorScene", saveAfter.scene + 4)
            .coerceIn(saveAfter.scene + 1, saveAfter.scene + 6),
        nextDirectorReason = json.optString("nextDirectorReason").trim().take(320).ifBlank { previous.nextDirectorReason },
        lastProcessedScene = saveAfter.scene,
        revision = previous.revision + 1,
    )
}.getOrNull()

private fun decodeWorldClockV5(item: JSONObject): ApocalypseWorldClockV5? {
    val id = item.optString("id").trim().take(80)
    val title = item.optString("title").trim().take(100)
    if (id.isBlank() || title.isBlank()) return null
    return ApocalypseWorldClockV5(
        id = id,
        title = title,
        domain = item.optString("domain").trim().take(80),
        driver = item.optString("driver").trim().take(240),
        currentState = item.optString("currentState").trim().take(420),
        nextMove = item.optString("nextMove").trim().take(420),
        progress = item.optInt("progress", 0).coerceIn(0, 100),
        pressure = item.optInt("pressure", 1).coerceIn(1, 10),
        visibility = item.optString("visibility", "hidden").takeIf { it in setOf("hidden", "rumored", "known") } ?: "hidden",
        status = item.optString("status", "active").takeIf { it in setOf("active", "paused", "resolved", "expired") } ?: "active",
        lastAdvancedScene = item.optInt("lastAdvancedScene", 1).coerceAtLeast(0),
        dueDayIndex = item.optIntOrNullLivingV5("dueDayIndex")?.coerceIn(-30, 9999),
        linkedCharacterIds = item.optJSONArray("linkedCharacterIds").livingStringsV5().distinct().take(12),
        linkedThreadIds = item.optJSONArray("linkedThreadIds").livingStringsV5().distinct().take(12),
    )
}

private fun decodeDirectorSetupV5(item: JSONObject): ApocalypseDirectorSetupV5? {
    val id = item.optString("id").trim().take(80)
    val title = item.optString("title").trim().take(100)
    if (id.isBlank() || title.isBlank()) return null
    val planted = item.optInt("plantedScene", 1).coerceAtLeast(1)
    val earliest = item.optInt("earliestRevealScene", planted + 1).coerceAtLeast(planted)
    val latest = item.optInt("latestRevealScene", earliest + 6).coerceAtLeast(earliest)
    return ApocalypseDirectorSetupV5(
        id = id,
        title = title,
        deviceType = item.optString("deviceType").trim().take(80).ifBlank { "因果回响" },
        hiddenPurpose = item.optString("hiddenPurpose").trim().take(520),
        triggerCondition = item.optString("triggerCondition").trim().take(420),
        missedMutation = item.optString("missedMutation").trim().take(420),
        status = item.optString("status", "planned").takeIf { it in setOf("planned", "seeded", "armed", "triggered", "repurposed", "expired") } ?: "planned",
        plantedScene = planted,
        lastTouchedScene = item.optInt("lastTouchedScene", planted).coerceAtLeast(planted),
        earliestRevealScene = earliest,
        latestRevealScene = latest,
        visibleSeeds = item.optJSONArray("visibleSeeds").livingStringsV5().distinct().takeLast(8),
        linkedCharacterIds = item.optJSONArray("linkedCharacterIds").livingStringsV5().distinct().take(12),
        linkedThreadIds = item.optJSONArray("linkedThreadIds").livingStringsV5().distinct().take(12),
        linkedForeshadowIds = item.optJSONArray("linkedForeshadowIds").livingStringsV5().distinct().take(12),
        linkedLocationIds = item.optJSONArray("linkedLocationIds").livingStringsV5().distinct().take(12),
    )
}

private fun decodeSurpriseV5(item: JSONObject): ApocalypseSurpriseRecordV5? {
    val summary = item.optString("summary").trim().take(280)
    if (summary.isBlank()) return null
    return ApocalypseSurpriseRecordV5(
        scene = item.optInt("scene", 1).coerceAtLeast(1),
        type = item.optString("type").trim().take(80).ifBlank { "因果回响" },
        sourceId = item.optString("sourceId").trim().take(80),
        summary = summary,
    )
}

private fun encodeState(state: ApocalypseLivingWorldStateV5): JSONObject = JSONObject()
    .put("saveId", state.saveId)
    .put("worldClocks", JSONArray().apply { state.worldClocks.forEach { put(encodeWorldClockV5(it)) } })
    .put("directorSetups", JSONArray().apply { state.directorSetups.forEach { put(encodeDirectorSetupV5(it)) } })
    .put("recentSurprises", JSONArray().apply { state.recentSurprises.forEach { put(encodeSurpriseV5(it)) } })
    .put("seasonQuestion", state.seasonQuestion)
    .put("nextDirectorScene", state.nextDirectorScene)
    .put("nextDirectorReason", state.nextDirectorReason)
    .put("lastProcessedScene", state.lastProcessedScene)
    .put("revision", state.revision)

private fun ApocalypseLivingWorldStateV5.toCompactJsonV5(): JSONObject = JSONObject()
    .put("worldClocks", JSONArray().apply {
        worldClocks.filter { it.status == "active" || it.status == "paused" }.take(8).forEach { put(encodeWorldClockV5(it)) }
    })
    .put("directorSetups", JSONArray().apply {
        directorSetups.filter { it.status !in setOf("triggered", "expired") }.take(7).forEach { put(encodeDirectorSetupV5(it)) }
    })
    .put("recentSurprises", JSONArray().apply { recentSurprises.takeLast(6).forEach { put(encodeSurpriseV5(it)) } })
    .put("seasonQuestion", seasonQuestion)
    .put("nextDirectorScene", nextDirectorScene)
    .put("nextDirectorReason", nextDirectorReason)

private fun encodeWorldClockV5(value: ApocalypseWorldClockV5): JSONObject = JSONObject()
    .put("id", value.id)
    .put("title", value.title)
    .put("domain", value.domain)
    .put("driver", value.driver)
    .put("currentState", value.currentState)
    .put("nextMove", value.nextMove)
    .put("progress", value.progress)
    .put("pressure", value.pressure)
    .put("visibility", value.visibility)
    .put("status", value.status)
    .put("lastAdvancedScene", value.lastAdvancedScene)
    .put("dueDayIndex", value.dueDayIndex ?: JSONObject.NULL)
    .put("linkedCharacterIds", JSONArray(value.linkedCharacterIds))
    .put("linkedThreadIds", JSONArray(value.linkedThreadIds))

private fun encodeDirectorSetupV5(value: ApocalypseDirectorSetupV5): JSONObject = JSONObject()
    .put("id", value.id)
    .put("title", value.title)
    .put("deviceType", value.deviceType)
    .put("hiddenPurpose", value.hiddenPurpose)
    .put("triggerCondition", value.triggerCondition)
    .put("missedMutation", value.missedMutation)
    .put("status", value.status)
    .put("plantedScene", value.plantedScene)
    .put("lastTouchedScene", value.lastTouchedScene)
    .put("earliestRevealScene", value.earliestRevealScene)
    .put("latestRevealScene", value.latestRevealScene)
    .put("visibleSeeds", JSONArray(value.visibleSeeds))
    .put("linkedCharacterIds", JSONArray(value.linkedCharacterIds))
    .put("linkedThreadIds", JSONArray(value.linkedThreadIds))
    .put("linkedForeshadowIds", JSONArray(value.linkedForeshadowIds))
    .put("linkedLocationIds", JSONArray(value.linkedLocationIds))

private fun encodeSurpriseV5(value: ApocalypseSurpriseRecordV5): JSONObject = JSONObject()
    .put("scene", value.scene)
    .put("type", value.type)
    .put("sourceId", value.sourceId)
    .put("summary", value.summary)

private fun decodeState(raw: String?): ApocalypseLivingWorldStateV5? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val json = JSONObject(raw)
        val saveId = json.optString("saveId").trim()
        if (saveId.isBlank()) return@runCatching null
        ApocalypseLivingWorldStateV5(
            saveId = saveId,
            worldClocks = json.optJSONArray("worldClocks").livingObjectsV5(::decodeWorldClockV5),
            directorSetups = json.optJSONArray("directorSetups").livingObjectsV5(::decodeDirectorSetupV5),
            recentSurprises = json.optJSONArray("recentSurprises").livingObjectsV5(::decodeSurpriseV5).takeLast(8),
            seasonQuestion = json.optString("seasonQuestion").trim().take(280),
            nextDirectorScene = json.optInt("nextDirectorScene", 4).coerceAtLeast(2),
            nextDirectorReason = json.optString("nextDirectorReason").trim().take(320),
            lastProcessedScene = json.optInt("lastProcessedScene", 1).coerceAtLeast(1),
            revision = json.optInt("revision", 1).coerceAtLeast(1),
        )
    }.getOrNull()
}

private fun JSONObject.optIntOrNullLivingV5(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun JSONArray?.livingStringsV5(): List<String> = buildList {
    val array = this@livingStringsV5 ?: return@buildList
    for (index in 0 until array.length()) array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
}

private fun <T> JSONArray?.livingObjectsV5(mapper: (JSONObject) -> T?): List<T> = buildList {
    val array = this@livingObjectsV5 ?: return@buildList
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        runCatching { mapper(item) }.getOrNull()?.let(::add)
    }
}
