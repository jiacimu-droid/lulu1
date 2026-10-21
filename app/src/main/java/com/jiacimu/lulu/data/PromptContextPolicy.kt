package com.jiacimu.lulu.data

/** Dynamic *prompt* budgets, not device RAM/storage limits. Stores and evidence are never deleted or rewritten.
 * Full fidelity is retained for responsibility checks, world interaction and autonomous action.
 */
data class PromptContextBudget(
    val recallLimit: Int,
    val memoryCharacters: Int,
    val evidenceCharacters: Int,
    val recentCharacters: Int,
    val worldDetails: Boolean,
    val richInnerLife: Boolean,
    val lexiconCharacters: Int,
    val precise: Boolean,
    val workingSetCharacters: Int = if (precise) 31_000 else if (richInnerLife) 26_000 else 18_000,
)

internal object PromptContextPolicy {
    private val evidenceSignals = listOf(
        "原话", "原文", "证据", "当时说", "之前说", "上次说", "你说过", "我说过", "谁说",
        "答应", "承诺", "失约", "约定", "提醒", "叫醒", "闹钟", "兑现",
        "核实", "确认有没有", "删除记录", "时间点", "具体时间", "那天", "哪天",
        "什么时候说", "记错", "没做到", "做到了吗", "是否执行", "执行记录",
    )
    private val worldSignals = listOf(
        "数字世界", "见面", "家里", "你家", "我家", "房间", "家具", "物品",
        "串门", "公共区", "云眠原", "咖啡角", "庭院", "蟑螂",
        "世界事件", "摆放", "装修", "散步", "去哪里", "在哪儿",
    )
    private val liveSignals = listOf(
        "帮我操作", "看看屏幕", "查一下", "搜索", "打开", "电量", "位置", "天气",
        "打电话", "日记", "发布", "设置闹钟", "邀请", "阅读", "游戏",
    )

    /** Give the role more relevant source material; the transport independently handles
     * provider request-size pressure, without changing stored history or local memory.
     */
    fun forRequest(source: String, request: UnifiedMemoryRequest): PromptContextBudget {
        // Current input is the main relevance signal. Old history cannot force heavyweight
        // evidence/world context into every later turn forever.
        val current = request.currentInput.takeLast(1_600)
        val scene = request.sceneContext.take(240)
        val intent = request.taskIntent.take(180)
        val precise = evidenceSignals.any(current::contains) ||
            evidenceSignals.any(intent::contains)
        val world = source.contains("见面") || source.contains("数字世界") ||
            source.contains("世界事件") || worldSignals.any(current::contains) ||
            worldSignals.any(scene::contains)
        val autonomous = source.contains("后台主动") || source.contains("自发") ||
            source.contains("自主感知")
        val immersive = source.contains("剧情") || source.contains("游戏") ||
            source.contains("小剧场") || source.contains("多人群聊") ||
            source.contains("全员自然讨论")
        val activeTool = liveSignals.any(current::contains)
        val rich = autonomous || world || immersive || precise || activeTool
        return when {
            precise -> PromptContextBudget(42, 10_000, 7_000, 6_400, world, true, 3_200, true)
            autonomous || immersive -> PromptContextBudget(38, 8_800, 4_600, 5_800, autonomous || world, true, 3_400, false)
            world || activeTool -> PromptContextBudget(34, 7_800, if (activeTool) 3_200 else 2_400,
                4_800, world, rich, 2_700, false)
            else -> PromptContextBudget(26, 6_000, 2_000, 3_800, false, false, 2_200, false)
        }
    }

    /** When no detailed scene is needed, preserve truthful place and unresolved incidents.
     * Never pass a truncated itemId/activityId list as if it were executable.
     */
    fun compactWorld(characterId: String): String {
        val location = DigitalWorldStore.locationOf(characterId)
        val activeEvents = DigitalWorldLifeEventStore.contextFor(characterId)
            .takeUnless { it.startsWith("当前位置没有") }.orEmpty()
        val label = when (location) {
            DigitalWorldStore.ARRIVAL -> "世界入口"
            DigitalWorldStore.CLOUD_MEADOW -> "云眠原"
            else -> DigitalWorldPublicPlaces.label(location)
                ?: if (location.startsWith("home:")) "家中" else location
        }
        return buildString {
            append("数字世界当前位置：$label。角色持续拥有自己的数字身体与随身数字手机。")
            append("\n").append(DigitalWorldEnvironment.snapshot(java.time.Instant.now()).context())
            if (DigitalWorldStore.itemsAtHome(characterId).isEmpty() &&
                location.startsWith("home:")) append("家里暂时没有家具；不得凭空使用椅子或床。")
            append("要移动/布置/使用物品时，先取得该地点完整权威清单与真实ID，不编造物品。")
            DigitalWorldActivityStateStore.briefContextFor(characterId)
                .takeIf(String::isNotBlank)?.let { append("\n").append(it) }
            DigitalWorldLiveScene.context(characterId).takeIf(String::isNotBlank)?.let { append("\n").append(it) }
            if (activeEvents.isNotBlank()) append("\n").append(activeEvents)
        }
    }
}
