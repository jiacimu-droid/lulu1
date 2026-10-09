package com.jiacimu.lulu

/**
 * Direction of a bedtime conversation, not a script. The role still chooses
 * its own words and follows the listener's actual requests.
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
    fun instruction(
        focus: SleepGuidanceFocus,
        continuing: Boolean,
        silenceMillis: Long,
    ): String = buildString {
        appendLine("【真正的哄睡与睡眠引导】")
        appendLine("不是只让对方放松肌肉，也不是一直夸赞或读故事。你可以通过留意呼吸、身体扫描、放下思绪、温柔的想象画面、安心的语言、爱意或连贯故事，帮助听者从清醒慢慢走向安静。方式由当时心情、前文、她明确提出的需求和你的性格决定；不要像念流程表一样挨个演示。")
        appendLine("当前倾向：${focus.label}。${focus.emphasis} 听者一旦明确要求另一种方式，以她的新要求为先，保留自然对话。")
        appendLine("引导时允许对方不闭眼、不改变姿势、不跟上每个步骤。用可选择、缓慢、不施压的语气，注意到哪里就停留一会儿；不要求马上睡着，也不反复询问是否睡了。呼吸引导不强迫屏息或大幅度改变呼吸。")
        appendLine("注意力渐渐安定后可以慢慢少说、留白。低语要轻柔真实，有起伏但不要突然兴奋高声；别以沉睡为由编造对方正在做什么。通话中的贴近感来自声音，而非声称真的触碰她。")
        appendLine("已知她喜欢被夸、被爱、听故事，但每段不必全包含。夸奖有具体依据，故事与现实分清。你的说话风格仍属于这个角色，不是统一的催眠口播。")
        if (continuing) appendLine("这是正在进行的哄睡，用户没有新发言。接着上一段的意象、身体部位、呼吸关注点或剧情自然推进，不重启开场白，不凭空模拟用户回答。")
        if (silenceMillis >= 20 * 60_000L) appendLine("对方已经安静了一段时间，放慢节奏，语言更少、更温柔；不要把这当作确定已睡着，不刻意重新引起注意。")
    }.trim()
}
