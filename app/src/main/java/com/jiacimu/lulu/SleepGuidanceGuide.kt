package com.jiacimu.lulu

/**
 * Public bedtime controls offer only Body or Story. Legacy enum values
 * remain readable so an existing call/session cannot crash after an update;
 * the active prompt treats those old options as gentle body relaxation.
 */
internal enum class SleepGuidanceFocus(val label: String, val emphasis: String) {
    Natural("自然哄睡", "综合当下情绪、关系和前文，自然选用具体夸奖、爱意、轻声故事或一种睡眠引导，不必每轮换项目。"),
    Body("放松身体", "温柔带着注意力依次觉察并放松额头、眼周、下颌、肩颈、手臂、手掌、胸背、腹部、臀腿、脚踝与脚趾；顺序随对方舒适程度自然调整，不必一口气念完全部部位。"),
    Breath("轻缓呼吸", "引导注意自然呼吸和每次轻轻呼气，不强制深呼吸、屏息或数拍，不要求对方达到特定节律；呼吸困难或不适时随时改为安静聆听。"),
    Thoughts("放下思绪", "允许杂念自然出现，不要求把脑袋清空；可以把未完成的事先留给明天，轻轻回到身体、声音或安稳的此刻，不进行沉重的反省分析。"),
    Imagery("安心想象", "引导想象一个舒适、安全、安静的画面，逐渐放慢注意力与叙述，不制造惊吓、悬念或刺激；明确这是想象，而非你们真的到过的地点。"),
    Affection("夸夸与爱", "真诚而具体地欣赏对方，表达被珍惜与被爱的感觉；以已知事实和关系为基础，允许轻柔的幽默，不编造经历，也不反复空泛地保证永远不会离开。"),
    Story("睡前故事", "讲温暖、有角色和连续因果的故事，循着已经讲出的剧情缓慢推进；故事里可以有想象世界，但不冒充真实共同回忆，不为了刺激听者而设置紧张反转。"),
}

internal object SleepGuidanceGuide {
    /** Explicit spoken preferences update the same control as the bedtime focus chips. */
    fun requestedFocus(text: String): SleepGuidanceFocus? {
        val compact = text.replace(Regex("\\s+"), "")
        val wantsStory = Regex("(讲|听|继续).{0,12}故事").containsMatchIn(compact)
        val refusesStory = Regex("(不要|不想|别|不听|不用).{0,8}(讲|听)?故事").containsMatchIn(compact)
        val storyRequest = Regex("我想|想听|想要|给我|帮我|能不能|可以|继续|我要|^讲|^听").containsMatchIn(compact)
        if (wantsStory && storyRequest && !refusesStory) return SleepGuidanceFocus.Story
        val body = Regex("放松身体|身体放松|身体扫描|放松.{0,3}(肩|腿|手臂|下颌|额头)").containsMatchIn(compact)
        val request = Regex("帮我|带我|想|能不能|可以|开始|继续|先").containsMatchIn(compact) ||
            compact in setOf("放松身体", "身体放松", "身体扫描")
        val refusal = Regex("(不要|不想|不用|别).{0,5}(放松身体|身体放松|身体扫描)").containsMatchIn(compact)
        return if (body && request && !refusal) SleepGuidanceFocus.Body else null
    }

    /** Two clear experiences, not a grab bag of seven techniques.
     * The listener can change focus any time without restarting the call.
     */
    val choices = listOf(SleepGuidanceFocus.Body, SleepGuidanceFocus.Story)

    fun instruction(
        focus: SleepGuidanceFocus,
        continuing: Boolean,
        silenceMillis: Long,
    ): String = buildString {
        val active = if (focus == SleepGuidanceFocus.Story)
            SleepGuidanceFocus.Story else SleepGuidanceFocus.Body
        appendLine("【持续哄睡：${active.label}】")
        appendLine("这是正在接通的真实电话，用户不回应不代表已经睡着。保持本角色的自然语言、温柔且慢的音色；不模拟现实触碰，不要求听者必须回复或立刻入睡。")
        if (active == SleepGuidanceFocus.Body) {
            appendLine("【只有放松身体】本模式专注舒缓的身体感受与肌肉松弛，不转去讲故事、夸奖、爱意表白或新的话题，除非用户明确要换。可以让额头、双眼周围、下颌、肩颈、手掌、胸背、腹部、臀腿、脚趾依次慢慢松下来；不必一段讲完全身，而要继续上段真实讲过的位置。")
            appendLine("从具体部位的紧绷如何减轻、被褥的支撑、呼气后那一点点松弛持续引导；可有真实停顿与舒适细节，让听者按自己的节奏体会。不强求闭眼、不数呼吸、不反复要求深呼吸。已经放松过的部位不要重头点名，声音和指令可以逐渐变稀疏。")
            appendLine("【严格禁止机械收尾】不要每段末尾补‘睡吧’‘我在这里陪着你’‘宝宝乖乖睡’‘做个好梦’等固定结束句，也不要换同义词继续重复安慰保证。没有结束请求，就继续当前身体部位的柔和放松内容；不把正文变成道别、承诺陪伴或要求入睡。")
        } else {
            appendLine("【只有睡前故事】慢慢讲一条连贯的、温暖而有实际人物行动的故事，沿本通电话已经真实播完的人物、地点、情节和悬而未决的小事件接着往前走。不要每次开新故事、重说前情、突然插入一套身体扫描。")
            appendLine("不能按安静了几分钟就中断故事、改念‘睡吧’或反复告别。每轮自然推进一个新事件与小发现，不需要结尾刻意告诉听者入睡，也不要编造故事是你们现实发生过的经历。")
        }
        appendLine("【连续声音】尽量一次生成完整、连贯、能自然朗读的一段内容；放松身体可约650—950汉字，故事可约900—1400字。不是必须凑字数，也不要一两句就重新呼叫模型；安静留白由已有播放间隔处理。可用 [slowly] / [whispers] 的配音由播放层负责，text 内无需重复音频标签。")
        if (continuing) appendLine("这是无人新增发言的继续播讲，沿本通电话已经真正听到的位置继续，勿重启开场白、反复说晚安、虚构她已睡着。30分钟上限按现有电话机制独立执行，不应靠收尾套话凑满时间。")
        if (silenceMillis >= 20 * 60_000L) appendLine("已经很安静：减低措辞密度和新指令频率，留出更长停顿；不因此中断当前身体放松或故事主线。")
    }.trim()
}
