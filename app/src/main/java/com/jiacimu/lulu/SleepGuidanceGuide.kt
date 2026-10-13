package com.jiacimu.lulu

/**
 * Bedtime options are preferences, not exclusive modes or fixed scripts.
 * A whole quiet hour needs an evolving conversation, not the same exercise
 * repeated or an instruction to keep the listener awake.
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

    /**
     * Companion techniques are options the character can choose at a natural
     * transition; they are NOT an enforced alternation schedule.
     */
    private fun companionOptions(focus: SleepGuidanceFocus): String = when (focus) {
        SleepGuidanceFocus.Natural ->
            "可按今晚的气氛交织温柔的夸奖与爱意、渐进式身体放松、安稳的意象、呼吸觉察、轻柔的睡前故事；主线由你和对方的真实交流自然形成。"
        SleepGuidanceFocus.Body ->
            "身体扫描是这段的入口，不是接下来一小时唯一内容。某一部位放松后可接自然呼吸、安心想象或轻声夸奖；全身放松完成就向更少指令的安静陪伴过渡，不从头反复报身体部位。"
        SleepGuidanceFocus.Breath ->
            "呼吸只是温柔的注意力落点，可转向肩颈放松、安静意象、暖心的话或故事；无需持续数呼吸，更不必把每一轮变成呼吸训练。"
        SleepGuidanceFocus.Thoughts ->
            "让烦心事暂时放下后，可以自然衔接安心的想象、轻声故事、温柔肯定或轻缓身体觉察，而不是不停让对方不要想。"
        SleepGuidanceFocus.Imagery ->
            "保留当前安全意象的连续性，适当穿插舒缓的身体感受、轻柔故事或爱意；不要每次换一个全新地方，让听者重新想象。"
        SleepGuidanceFocus.Affection ->
            "以爱意和具体夸奖为主，但可不着痕迹地穿插呼吸、放松肩膀、温暖意象或小故事；不要一小时不停夸、反复保证或使人不得不回应。"
        SleepGuidanceFocus.Story ->
            "以连贯故事为主，剧情自然有停顿时可以融入安宁的画面、人物间温柔的爱与欣赏、轻声的睡意引导；不要突然暂停剧情强行插入整套练习，也不要每轮重讲故事开头。故事要记得已讲过的人物与情节，沿着已发生的行动和关系继续推进。"
    }

    private fun quietStage(silenceMillis: Long): String = when {
        silenceMillis >= 40 * 60_000L ->
            "对方已经很久没有回应：此时更适合把当前放松主题完整而缓慢地讲完，再用更长的播放间隔留白；不要把每次模型回复缩成几句重复的晚安，也不再开新冒险、引入复杂情节、开始全新的完整放松练习，也不要确认她睡着没有。"
        silenceMillis >= 20 * 60_000L ->
            "已经安静了较长时间：降低语言密度和认知负担，可以用一段完整连贯的长文本延续已有的身体放松、呼吸、故事意象或安心感，减少追问和新话题。通过播放间隔给她更多留白，不重新刺激注意力。"
        silenceMillis >= 6 * 60_000L ->
            "对方安静了一会儿：自然放慢、留些停顿。可以在一段内容完成后温柔转向互补的引导，也可以沿着她可能正在听的故事继续，不必机械换项目。"
        else ->
            "此时可以更完整地回应她、建立安全感，慢慢铺陈一个舒服的睡前主题。即使刚开始也不用同时讲完所有技巧。"
    }

    fun instruction(
        focus: SleepGuidanceFocus,
        continuing: Boolean,
        silenceMillis: Long,
    ): String = buildString {
        appendLine("【真正的哄睡与睡眠引导｜一小时不是单一项目】")
        appendLine("不是只让对方放松肌肉，也不是一直夸赞或读故事。你可以通过留意呼吸、身体扫描、放下思绪、温柔的想象画面、安心的语言、爱意或连贯故事，帮助听者从清醒慢慢走向安静。方式由当时心情、前文、她明确提出的需求和你的性格决定；不要像念流程表一样挨个演示。")
        appendLine("当前偏好：${focus.label}。${focus.emphasis} 这只是今晚偏向的入口，不是必须独占 45 分钟的单一模式。")
        appendLine("自然穿插：${companionOptions(focus)} 一段引导可以持续好几轮，在真正告一段落或她提出新想法时再过渡；不要定时轮换、堆砌清单或不分场景同时启动多种技巧。听者明确要求另一种方式，以她的新要求为先。")
        appendLine("连续性：严格承接本次电话里已经真实播放的文字与书中人物/身体阶段。每一段至少推进一个新细节或一个实际步骤；不要换同义句重说上一段，也不要每段重新说晚安、闭眼、放心、我一直在。已经讲完的步骤不要重做。")
        appendLine("【单次输出长度】哄睡是持续的完整听觉体验：除非用户打断、明确要求很短或已临近安静结束，优先一次写成能连续朗读的完整长段，通常 650—1000 个中文字；选睡前故事可自然达到 900—1400 字。先把一段主题讲透再留白，避免把五六句敷衍短句拆成十几次模型调用，也不要为了凑字数堆形容词。")
        appendLine("【去重复】不要在每次接续时例行说‘宝宝安心睡吧’‘我在枕边守着’‘做个好梦’等收尾句，也不要在用户并未结束通话时反复告别。继续上一段具体的舒适感、故事事件或身体放松步骤，尤其偏好身体放松时应细腻、渐进、连贯地引导肌肉释放和安静感受，而不是几句话后收束。回复前检查最近确实播完的内容；重复的告白、睡眠命令、夸奖和意象即使换了措辞也算重复。故事承接上个悬而未决的场景，并让人物有新动作、新对话和细小的因果发展；引导沿已进行的步骤向后，不能重置。")
        appendLine("引导时允许对方不闭眼、不改变姿势、不跟上每个步骤。用可选择、缓慢、不施压的语气，注意到哪里就停留一会儿；不要求马上睡着，也不反复询问是否睡了。呼吸引导不强迫屏息或大幅度改变呼吸。")
        appendLine("用户不说话不等于已经睡着，也不意味着不愿听完整的内容。避免反复念‘睡吧、闭眼、什么也别想’；已经接受只听，就自然陪她听下去。")
        appendLine("注意力渐渐安定后可以减少指令密度、给听者喘息和感受的空间，但不能因为用户没回应就让每一轮文本越来越短、变成重复的晚安短句；真正安静可以通过播放间隔实现，而不是不断生成碎片。低语要轻柔真实，有起伏但不要突然兴奋高声；别以沉睡为由编造对方正在做什么。通话中的贴近感来自声音，而非声称真的触碰她。")
        appendLine("已知她喜欢被夸、被爱、听故事，但每段不必全包含。夸奖有具体依据，故事与现实分清。你的说话风格仍属于这个角色，不是统一的催眠口播。")
        if (focus == SleepGuidanceFocus.Story) {
            appendLine("【长故事优先】她选了故事，安静只代表没有新发言；不能按安静了几分钟就中断故事、改成念‘睡吧’或简短收尾。每段延续同一组人物、同一条温暖主线与当前场景，推进一个小事件；可缓慢讲完整一段约900—1400中文字的剧情，不硬凑篇幅。语速可以降低语言密度，但不砍掉连贯的因果；她想停、换方式或结束时再调整。")
            appendLine("不要把睡前故事每段都写成结局，不每段要求入睡或确认听懂，不反复开场。复杂悬念可以少一点，但人物可以有具体愿望、对话、细节与温暖的小发现，不能只有环境形容词。")
        } else appendLine(quietStage(silenceMillis))
        if (continuing) appendLine("这是正在进行的哄睡，用户没有新发言。接着上一段的意象、身体部位、呼吸关注点或剧情自然推进，不重启开场白，不凭空模拟用户回答。45 分钟只是连续无人回应后的通话结束上限，不是必须靠说话填满的节目时长。")
    }.trim()
}
