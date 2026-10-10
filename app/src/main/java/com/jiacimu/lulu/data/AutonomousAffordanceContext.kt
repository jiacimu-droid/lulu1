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
    ): String = buildString {
        appendLine("【此刻可实际尝试的自主行动｜不是强制任务或固定动作轮换】")
        appendLine("- 想对某件已知小事发表意见：可以自己写日记或发朋友圈；只有真的有话想说时才做。")
        appendLine("- 想自己找消遣：solo_game + gameId=memory_match 能运行真实的一局游戏；无须用户陪同。")
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
            if (publicPlaces.isNotEmpty()) {
                appendLine("- 已存在的公共地点，可选择前往探索（不用建家具；worldAction=visit_public_place）：")
                publicPlaces.take(6).forEach { (code, label) ->
                    appendLine("  · action=digital_world；location=" + code + "；" + label)
                }
            }
        }
        appendLine("先看本人此刻真正想做什么，再从真实能力选择；没有可执行内容可保持安静。")
    }
}
