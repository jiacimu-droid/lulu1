package com.jiacimu.lulu.data

/** Dynamic *prompt* budgets. Stores and original evidence are never deleted or rewritten.
 * Full fidelity is retained for responsibility checks, world interaction and autonomous action.
 */
internal data class PromptContextBudget(
    val recallLimit: Int,
    val memoryCharacters: Int,
    val evidenceCharacters: Int,
    val recentCharacters: Int,
    val worldDetails: Boolean,
    val richInnerLife: Boolean,
    val lexiconCharacters: Int,
    val precise: Boolean,
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
        "串门", "公共区", "云眠原", "阅读馆", "咖啡角", "庭院", "蟑螂",
        "世界事件", "摆放", "装修", "散步", "去哪里", "在哪儿",
    )
    private val liveSignals = listOf(
        "帮我操作", "看看屏幕", "查一下", "搜索", "打开", "电量", "位置", "天气",
        "打电话", "日记", "发布", "设置闹钟", "邀请", "阅读", "游戏",
    )

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
            precise -> PromptContextBudget(30, 7_200, 5_000, 4_800, world, true, 2_600, true)
            autonomous || immersive -> PromptContextBudget(28, 6_200, 3_200, 4_200, true, true, 2_800, false)
            world || activeTool -> PromptContextBudget(24, 5_600, if (activeTool) 2_400 else 1_400,
                3_400, world, rich, 2_000, false)
            else -> PromptContextBudget(18, 4_000, 0, 2_600, false, false, 1_500, false)
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
            append("数字世界当前位置：$label。")
            append("要移动/布置/使用物品时，先取得该地点的完整权威清单与真实ID，不编造物品。")
            if (activeEvents.isNotBlank()) append("\n").append(activeEvents)
        }
    }
}
