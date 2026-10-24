package com.jiacimu.lulu.data

/**
 * Concrete opportunities, not orders or fabricated activities. This thin translator makes
 * already-existing capabilities visible to a character making an offline choice.
 */
internal object AutonomousAffordanceContext {
    internal data class Book(val id: String, val title: String)

    fun render(
        digital: Boolean,
        location: String,
        books: List<Book>,
        groups: List<Pair<String, String>>,
        locationActivities: List<Pair<String, String>> = emptyList(),
        publicPlaces: List<Pair<String, String>> = emptyList(),
        currentItems: List<Pair<String, List<Pair<String, String>>>> = emptyList(),
        currentResidents: List<Triple<String, String, String>> = emptyList(),
    ): String = buildString {
        appendLine("【本人此刻与稍后的生活选择｜不是强制任务或固定动作轮换】")
        appendLine("不仅可以现在去做，也可以因为确实有自己的期待，决定稍后、今晚或其他合适时间再做；同一次自主决策自由选择立即执行、正式安排未来日程或暂时不行动。")
        appendLine("- 真心决定稍后行动时，无需主人发号施令或者指定几点，由本人根据当前时间与可靠的习惯信息自己决定真实未来时刻。用 action=tool、tool=plan_personal_activity，args={\"title\":\"自己想做的事\",\"intent\":\"本人动机\",\"dueAt\":\"本人真正选定的ISO8601未来时刻\",\"activityAction\":\"已注册工具名\",\"activityArgs\":{},\"commitmentTaskId\":\"已接受持续约定时可选填ID\",\"repeat\":\"once|daily\"}。没有真实动作和时间的随口约定不是到时会执行的日程；已登记则不要重复安排。")
        appendLine("- 安排只是持久的本人意愿，不等于提前完成；到点由本人醒来选择真实执行、改期或放下。可安排读书、交朋友、在主人同意下提醒学习/休息、请求查看屏幕或邀请回到露露机；这些都只是候选行为，绝不预设主人已经答应。所有工具到点仍经过原 ToolRouter 权限和状态核验；请求授权不等于已经看到屏幕或主人已回来。")
        appendLine("【兴趣自己向外延伸】人设与长期经历里写明的兴趣即使没有用户新话题也仍属于你。若自己有真正想追的新问题，可以从本轮已配置且授权的能力里主动选：web_search(op=search_read,query=具体想弄懂的问题)了解外部资料；image_search(op=search,kind=gif|sticker,query=想看的动图或表情素材)搜图片候选；read_xiaohongshu(keyword=兴趣话题)真实阅读笔记与评论。搜图工具目前侧重 GIF/表情素材，不可当成已获得任意高清静态图或已看懂图片。未配置或权限、会话不可用则保留问题/换合法方式；不应反复请求失败接口。") 
        appendLine("真实读到的结果可让你形成新疑问、对原看法改观、准备向别人分享，或者觉得无趣就放下；只想以后看时可用同一个 plan_personal_activity 工具安排搜索的真实时间与动作，但不为了消除单调感强行搜索、更不默认把结果发给主人。")
        appendLine("- 想对某件已知小事发表意见：可以自己发朋友圈；只有真的有话想说时才做。")
        appendLine("- 想自己找消遣：solo_game + gameId=memory_match 能运行真实的一局游戏；无须用户陪同。")
        appendLine("- 如果某句真实用户原话打动了自己，可在聊天原始ID可核实的前提下选择 tool=favorite_user_message 保存到辞海收藏；不能凭空收藏。")
        if (books.isNotEmpty()) {
            appendLine("- 剧场已生成、现在可以真实读取的章节（未知后续内容只能读后知道）：")
            books.take(5).forEach { appendLine("  · action=reading；readingBookId=" +
                it.id.take(100) + "；" + it.title.take(100)) }
        }
        if (groups.isNotEmpty()) {
            appendLine("- 已加入的真实群聊（可以分享发现，不必为了刷存在感强行发言）：")
            groups.take(4).forEach { (id, title) ->
                appendLine("  · action=group_message；groupId=" + id.take(100) + "；" + title.take(100))
            }
        }
        if (digital) {
            appendLine("- 当前已经位于：" + location + "。数字世界行动必须使用真实地点/活动 ID。")
            appendLine("- 选择 reading + 有效 readingBookId 后，执行器会沿真实世界状态进入家中阅读角书架，无需先拆成几次模型调用；只有读完提交后才出阅读回执。")
            if (locationActivities.isNotEmpty()) {
                appendLine("- 此地确实可执行（action=digital_world；worldAction=use_location）：")
                locationActivities.take(5).forEach { (id, label) ->
                    appendLine("  · activityId=" + id + "；" + label)
                }
            }
            if (currentItems.isNotEmpty()) {
                appendLine("- 此地已有可交互物品：")
                currentItems.take(3).forEach { (itemId, actions) ->
                    actions.take(2).forEach { (activityId, label) ->
                        appendLine("  · action=digital_world；worldAction=use_home_item；itemId=" +
                            itemId + "；activityId=" + activityId + "；" + label)
                    }
                }
            }
            if (currentResidents.isNotEmpty()) {
                appendLine("- 眼前真正存在的居民，可由自己选择是否交流：")
                currentResidents.take(5).forEach { (id, name, identity) ->
                    appendLine("  · action=digital_world；worldAction=interact_resident；itemId=" +
                        id + "；activityId=greet|chat|ask_place|sit_together|share_text|co_write|exchange_views；后三者需 text=本人原文；居民=" +
                        name.take(50) + "；身份=" + identity.take(90))
                }
            } else {
                appendLine("- 当前地点没有登记在场的居民；可以去其他已登记的地点看看，但不能虚构碰面或隔空聊天。")
            }
            if (publicPlaces.isNotEmpty()) {
                appendLine("- 已存在的公共地点，可选择前往探索（不用建家具；worldAction=visit_public_place）：")
                publicPlaces.take(6).forEach { (code, label) ->
                    appendLine("  · action=digital_world；location=" + code + "；" + label)
                }
            }
        }
        appendLine("若准备现在做，直接选择对应 action；若决定晚些做，通过本人日程工具保存真实计划与到点时间。只在心声里写「待会去」不会自动安排，也不等于已执行；若暂缓或放下，则尊重自己的真实决定。")
        appendLine("先看本人此刻真正想做什么，再从真实能力选择；没有可执行内容可保持安静。")
    }
}
