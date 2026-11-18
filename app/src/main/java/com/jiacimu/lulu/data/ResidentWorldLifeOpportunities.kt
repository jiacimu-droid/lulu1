package com.jiacimu.lulu.data

import java.time.Instant

/**
 * The resident's own, read-only world affordances at a real decision wake.
 * Nothing here invents an activity, event, recipient or message. The
 * resident chooses; the existing world/social tools own execution and receipts.
 */
internal object ResidentWorldLifeOpportunities {
    internal fun render(
        locationCode: String,
        locationActivities: List<Pair<String, String>>,
        itemActivities: String,
        sceneFacts: String,
        boredom: Int?,
        sharingUrge: Int,
        verifiedShareContext: String,
    ): String = buildString {
        appendLine("【今天在自己世界里真正能做的事｜本人决定，不是每日必做清单】")
        appendLine("当前真实地点=$locationCode；数字身体单调感=${boredom?.toString() ?: "未知"}/100；" +
            "已有真实经历形成的分享冲动=$sharingUrge/100。")
        appendLine("单调感高不意味着只能独自玩骰子；也可以先去一个真实公共地点、" +
            "与确实认识的人互动、观察正在发生的事、做一件有实际结果的生活活动，或安静休息。" +
            "数值不自动派发行动，也不能拿未执行的愿望当素材。")
        if (locationActivities.isNotEmpty()) {
            appendLine("此刻确实可执行的地点活动：digital_world_action，" +
                "args={worldAction:use_location,activityId:以下真实ID之一}：")
            locationActivities.forEach { (id, label) ->
                appendLine("- activityId=$id：$label")
            }
        } else appendLine("当前位置没有登记可执行的公共地点活动；不能编造 activityId。")
        if (itemActivities.isNotBlank()) {
            appendLine("本人在当前地点可接触的真实物品（需要执行 use_home_item + itemId/activityId）：")
            appendLine(itemActivities)
        }
        if (sceneFacts.isNotBlank()) {
            appendLine("【此刻地点的世界事件｜先辨别自己是否真正留意】")
            appendLine(sceneFacts)
            appendLine("若上面列出刚发生、尚未亲眼留意的 incidentId，可自选 " +
                "digital_world_action(worldAction=observe_scene,incidentId=准确ID)；" +
                "若有仍然持续的可处理事件，可自选 worldAction=handle_incident，并填写现场给出的 " +
                "incidentId 和 approach。执行前要核实位置/状态，别假装处理已成功。")
        }
        if (verifiedShareContext.isNotBlank()) {
            appendLine(verifiedShareContext)
        }
        append("分享欲要有具体来源：只有亲历且有真实证据的小事，才可能触发本人向熟人私信、群聊或发朋友圈；" +
            "选谁、说什么、要不要说，全由本人根据个性和关系决定。独处也是真实生活，" +
            "不为了提高活跃率而机械分享光影或一遍遍刷同一局。")
    }.trim()

    /** The world clock is optional input, not a prerequisite for a paid daily
     * decision. A local scenery failure must not consume the resident's chance
     * to reason about the rest of their real day. */
    fun noticeOnWake(context: android.content.Context, characterId: String, at: Instant) {
        if (!characterId.startsWith("resident:") ||
            !DigitalLifeProfileStore.isEnabled(characterId)) return
        runCatching { DigitalWorldLifeEventStore.tick(context, characterId, at) }
            .onFailure { android.util.Log.w("ResidentWorldLife",
                "Could not refresh factual world scene for $characterId", it) }
    }

    fun contextFor(characterId: String, at: Instant = Instant.now()): String {
        if (!characterId.startsWith("resident:") ||
            !DigitalLifeProfileStore.isEnabled(characterId)) return ""
        val location = DigitalWorldStore.locationOf(characterId)
        val activities = DigitalWorldActivityCatalog.locationOptions(location)
        val itemActivities = DigitalWorldActivityCatalog.promptFor(
            DigitalWorldStore.itemsAtLocation(characterId).take(5))
        val body = EmbodiedBodyRuntime.sensorySnapshot(characterId, at)
        return render(
            locationCode = location,
            locationActivities = activities,
            itemActivities = itemActivities,
            sceneFacts = DigitalWorldLifeEventStore.contextFor(characterId, at),
            boredom = body?.boredom?.toInt(),
            sharingUrge = CharacterLifeFollowThroughStore.sharingUrge(characterId, at).toInt(),
            verifiedShareContext = CharacterLifeFollowThroughStore.briefContext(characterId, at),
        )
    }
}
