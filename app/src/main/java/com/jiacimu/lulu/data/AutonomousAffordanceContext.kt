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
        appendLine("【此刻可实际尝试的自主行动｜不是强制任务或固定动作轮换】")
        appendLine("- 想对某件已知小事发表意见：可以自己写日记或发朋友圈；只有真的有话想说时才做。")
        appendLine("- 想自己找消遣：solo_game + gameId=memory_match 能运行真实的一局游戏；无须用户陪同。")
        appendLine("- 如果某句真实用户原话打动了自己，可在聊天原始ID可核实的前提下选择 tool=favorite_user_message 保存到辞海收藏；不能凭空收藏。")
        if (books.isNotEmpty()) {
            appendLine("- 阅读 App 可实际读取的书（未知后续内容只能读后知道）：")
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
        appendLine("若准备现在做一件事，直接选择对应 action 和参数；心声中想做不算执行。若暂缓，请说具体原因；自己的愿望不需要等用户询问才行动。")
        appendLine("先看本人此刻真正想做什么，再从真实能力选择；没有可执行内容可保持安静。")
    }
}
