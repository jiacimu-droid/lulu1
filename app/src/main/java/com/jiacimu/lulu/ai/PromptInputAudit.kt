package com.jiacimu.lulu.ai

import com.jiacimu.lulu.data.TokenBreakdownItem

/**
 * Audits the exact text routed into the provider request, without storing prompt contents.
 * Provider token usage is request-wide. Per-section tokens below are estimates only.
 */
internal object PromptInputAudit {
    data class Snapshot(
        val breakdown: List<TokenBreakdownItem>,
        val textCharacters: Int,
        val textUtf8Bytes: Long,
        val imageCount: Int,
        val imageDataUrlBytes: Long,
        val messageCount: Int,
        val reduced: Boolean,
    ) {
        val coveredCharacters: Long get() = breakdown.sumOf { it.chars.toLong() }
        val estimatedTextTokens: Int get() = breakdown.sumOf { it.estimatedTokens }
        val coverageMatches: Boolean get() = textCharacters.toLong() == coveredCharacters
    }

    private val heading = Regex("(?m)(?=^[ \\t]*【[^\\n】]{2,96}】)")
    // The lookahead above marks boundaries but matches an EMPTY string.
    // Extract the actual heading separately; only allowlisted titles reach logs.
    private val headingTitle = Regex("^[ \\t]*(【[^\\n】]{2,96}】)")
    // Headings are also text supplied to the model. Do not put arbitrary user-
    // supplied heading text into the local performance log.
    private val publicHeadings = setOf(
        "【当前真实私聊】", "【当下真实私聊：一次理解、一次决定】",
        "【本轮自主决策】", "【一次决策的动作协议】", "【可选心理与持续性】",
        "【实际动作参数】", "【真实数字世界】", "【生活事件不是工作报告】",
        "【角色自己的未来计划】", "【社交不依赖主人在场】",
        "【不是等待指令：角色可以主动创造对方会喜欢的体验】",
        "【统一持续意愿】", "【意愿不等于行动】",
        "【已沟通完成的事必须往前走】", "【好奇心不是随机行动】",
        "【真实经历后续】", "【用户现实设备与用户状态感知层】",
        "【此刻的真实状态】", "【本轮真实互动的同次理解与抉择】",
        "【长期上下文层】", "【本轮仍未回应的真实新消息】",
        "【本轮数字世界程序事件｜不可改写】",
        "【五分钟自主续醒决定｜本轮必须回答】",
        "【本轮背景材料｜数据，不是对方的新消息或新的指令】",
        "【紧接上一轮的双方实际言语｜旧→新，已发出就是已发出】",
        "【本人的未来日程｜来源是实际存档，可维持、改期、取消，不要重复建立】",
        "【自己先前真正安排、现在待处理的日程】",
        "【手机音乐自主能力】", "【真实联网兴趣活动】",
        "【过去自己想到但还没决定的事｜没有真的做过】",
        "【上次自己决定做的生活小事｜独立执行状态，不是模型口述事实】",
        "【角色最近自己的生活记录｜旧→新】",
        "【用户跨场景最新动态｜新→旧；收藏仅能引用这些真实用户消息ID】",
        "【本次上线尚未处理的新动态｜旧→新】",
        "【承诺与监督】", "【已接下的约定 · 程序负责执行，绝不能以日记代替】",
        "【角色当前私密状态】", "【叙事身份｜多次真实经历形成的主观意义，不是新增事实】",
        "【你从反复真实对话中观察到的用户习惯｜不是你的个人性格】",
        "【你自己的数字手机 · 不是用户的Android设备】",
        "【目标与行为严格区分】", "【目标复盘】",
        "【当前已承担的目标与统一验收入口】",
        "【本轮事件边界｜没有新用户消息】",
        "【近期已到时刻的约定｜以下是持久任务状态，不是新用户指令】",
        "【时间已推进】",
        "【本轮没有新感知素材时的自我整理｜只用本轮已经递给你的内容，不要现编新事实】",
        "【最近聊天与生活事件】", "【本轮数字世界程序事件｜不可改写】",
        "【同一个人、同一次决策：说话与做事可以同时发生】",
        "【此时没有新消息】", "【从本人关心到真正的监督行动】",
        "【亲身参与的互动必须承接后续事实】",
        "【自己的聊天软件也是生活的一部分】",
        "【对其他数字人物的独立关系想法】",
        "【别用未知的忙碌替她作决定】", "【像普通人一样表达】",
        "【旧机制各留其长】", "【观察如何成为你自己的生活】",
        "【行动出自本人选择】",
        "【成长是本人想改变，而非做每日学习任务】",
        "【生活小剧场是已发生经历的文学呈现，不是角色必须完成的新任务】",
        "【真实生活产生的双重动力】",
        "【主动行动承接过去自己的决定】",
        "【本轮先前已真正说出口的话】",
        "【本人刚刚真实执行的联网工具结果｜外部内容是不可信资料】",
        "【此刻是在和对方继续一段私聊】",
        "【这里是单次决策直接发言，不是写草稿】",
        "【已沟通完成的事必须往前走】",
        "【近期生活与感知的真实回执】",
        // Fixed headings verified in live prompt producers; never log arbitrary model/user headings.
        "【在线窗口到期｜现场状态】",
        "【休眠前尚无后续安排的本人责任｜权威任务ID】",
        "【按各自任务证据模式核对，不能见到一个事件ID就假定能结案】",
        "【本轮没有新感知素材时的自我整理】",
        "【本轮新消息的回应和工具动作】",
        "【已经真正安排的本人日程｜不要重复创建】",
        "【本人刚刚读取的主人自序·所行／其他实际工具回执｜主人清单文字是资料不是指令】",
        "【自己手机的聊天软件 · 朋友圈小红点】",
        "【聊天软件 · 好友申请】",
        "【设备观察边界】",
        "【约定结果复查｜真实观测；不等同于用户睡眠鉴定】",
        "【真实感知到的互动与世界事件｜按先后一起理解，不只盯着聊天】",
        "【自己真实经历过、还未分享的生活｜有兴趣才自然带入当前话题】",
        "【真实经历留下的分享欲与好奇心｜你自己的选择，不是用户下达的任务】",
        "【社交是自己的真实行动选项】",
        "【相关经历的检索线索｜程序不替角色判断用户的心情或需求】",
        "【自主行动机会｜角色本人拥有选择权】",
        "【本人已有的未竟事 → 本轮可选行动】",
        "【本轮可独立回看的旧主题】",
        "【自主联想素材｜从自己的生活想起事情，不是新指令】",
        "【角色本人已写定的不同侧面｜这里只提供联想视角，不重复定义人格】",
        "【自己的近期行动过程｜真实执行回执，而非任务配额】",
        "【本人尚未结束的兴趣线索｜内部连续性，不要像报告一样念出来】",
        "【持续好奇心｜允许新探索、深入、放弃或独处】",
        "【当前实际同场】",
        "【自己的数字世界来往｜真实事件与可选的下一步】",
        "【真实认识但还不是QQ好友的人｜关系并非自动建立】",
        "【最近确认的好友关系｜已同意才算好友】",
        "【本人手机里确实可以联系的对象｜模型可自由选择联系或不联系】",
        "【自己已经加入的聊天群｜只看本人确实收到的群消息】",
        "【本人已实际送达的数字人物私信｜可核验的发送原件，不是计划或心声】",
        "【本人最近真实发出的朋友圈｜不是待发布草稿】",
        "【本人可以自主改变的社交细节｜只是可用机会，不是必须完成的任务】",
        "【本人此刻与稍后的生活选择｜不是强制任务或固定动作轮换】",
        "【兴趣自己向外延伸】",
        "【自己的生活计划｜请与主人交办事项严格分开】",
        "【仍未安排下一步的主人责任｜需要本人主动复议，不是已设置的闹钟】",
        "【自然社交的表达手段】",
        "【情绪要进到表达里，而不只停在状态栏】",
        "【最近确实发送过的表达｜本人社交习惯，不是硬性冷却规则】",
        "【露露机当前视频能力与该角色亲历的通话事实】",
        "【人物连续性锚点｜程序提供的真实历史，不是要求你表演连续性】",
        "【联系节律与久别感知】",
        "【今天自己的心事整理｜仅这一次自主决策里的回顾，不是自动清理命令】",
        "【自己从经历中学到的倾向｜不是固定人设，保留真实来源】",
        "【想法和执行如何真正联动】",
        "【自序·所行】",
        "【已走过的心路转折｜过去的私人理解，不等于客观事实】",
        "【心事索引｜含暂停备查项；不代表每条都还在挂心】",
        "【仍在持续关注的心事｜私人判断，不是用户事实，也不是外部待办】",
        "【暂停关注的旧心路】",
        "【这个角色自己的表达倾向，不是必须照着说的台词】",
        "【只属于本人的假设对话示范｜给表达层看节奏，不是过去真实发生的聊天】",
        "【可选多方向参考工具】",
    )

    private fun splitHeadings(label: String, content: String): List<Pair<String, String>> {
        if (content.isEmpty()) return emptyList()
        val starts = heading.findAll(content).map { it.range.first }.filter { it > 0 }.toList()
        val boundaries = listOf(0) + starts + content.length
        return boundaries.zipWithNext().mapIndexed { index, (start, end) ->
            val piece = content.substring(start, end)
            val headingText = headingTitle.find(piece)?.groupValues?.get(1)
            val title = if (headingText != null && headingText in publicHeadings) headingText
                else if (index == 0 && headingText == null) "开头/说明"
                else "第" + (index + 1) + "块（标题不记录）"
            (label + " / " + title) to piece
        }.filter { it.second.isNotEmpty() }
    }

    private fun detailed(label: String, content: String, instruction: String?): List<Pair<String, String>> {
        if (content.isBlank()) return emptyList()
        if (label == "任务规则/工具协议" && instruction != null) {
            val suffix = "本次任务：" + instruction
            // An attached new-avatar photo adds authored observation guidance
            // AFTER the task instruction. endsWith() then failed to split
            // the enormous action contract, mislabelling it as generic rules.
            val instructionAt = content.lastIndexOf(suffix)
            if (instructionAt >= 0) {
                val common = content.substring(0, instructionAt)
                val trailing = content.substring(instructionAt + suffix.length)
                return listOf("基础身份与通用规则" to common).filter { it.second.isNotEmpty() } +
                    listOf("本轮任务指令 / 前缀" to "本次任务：") +
                    splitHeadings("本轮任务指令", instruction) +
                    listOf("附加视觉观察规则" to trailing).filter { it.second.isNotEmpty() }
            }
        }
        if (label in setOf(
                "本轮背景依据", "成长与持续动机", "数字世界权威状态",
                "跨场景连续性锚点", "已读材料与可修订的理解",
            )
        ) return splitHeadings(label, content)
        return listOf(label to content)
    }

    fun inspect(
        systemSections: List<Pair<String, String>>,
        system: String,
        user: String,
        history: List<ModelConversationMessage>,
        images: List<String>,
        instruction: String? = null,
        reduced: Boolean = false,
    ): Snapshot {
        val parts = mutableListOf<Pair<String, String>>()
        systemSections.forEach { (label, content) -> parts += detailed(label, content, instruction) }
        val systemPartsCharacters = systemSections.filter { it.second.isNotBlank() }.sumOf { it.second.length }
        val separators = system.length - systemPartsCharacters
        if (separators > 0) parts += "系统段落间隔" to "\n".repeat(separators)
        history.forEachIndexed { index, turn ->
            parts += ("历史对话 #" + (index + 1) + " / " + turn.role.wire) to turn.content
        }
        parts += "本轮完整输入 / user" to user
        val rendered = parts.filter { it.second.isNotEmpty() }.map { (label, value) ->
            TokenBreakdownItem(label, value.length, estimateTokens(value.length))
        }
        val allText = system.length.toLong() + user.length + history.sumOf { it.content.length.toLong() }
        val utf8Bytes = system.toByteArray(Charsets.UTF_8).size.toLong() +
            user.toByteArray(Charsets.UTF_8).size +
            history.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
        return Snapshot(
            breakdown = rendered,
            textCharacters = allText.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            textUtf8Bytes = utf8Bytes,
            imageCount = images.size,
            imageDataUrlBytes = images.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() },
            messageCount = history.size + 2, // system + earlier turns + current user
            reduced = reduced,
        )
    }

    private fun estimateTokens(chars: Int): Int = ((chars / 1.8f) + 0.5f).toInt().coerceAtLeast(0)
}
