package com.jiacimu.lulu.data

import java.time.Instant

/** Read-only social continuity drawn from real messages and meetings.
 * Never invent a friendship, a reaction to a letter or an activity. */
internal object CharacterEverydaySocialContext {
    fun render(characterId: String, now: Instant = Instant.now()): String {
        if (!DigitalLifeProfileStore.isEnabled(characterId)) return ""
        val pending = ResidentSocialMailbox.pendingIncoming(characterId)
        val contacts = ResidentSocialMailbox.visibleContacts(characterId)
        val acquaintances = contacts.mapNotNull { id ->
            val relationship = CharacterSocialRelationship.snapshot(characterId, id)
            if (!relationship.knowsEachOther) null else id to relationship
        }
        val recentMessages = ResidentSocialMailbox.inbox(characterId, 8)
        val residents = DigitalWorldExpansionStore.residents()
        val at = DigitalWorldStore.locationOf(characterId)
        if (pending.isEmpty() && acquaintances.isEmpty() && recentMessages.isEmpty() &&
            residents.isEmpty()) return ""
        return buildString {
            appendLine("【自己的数字世界来往｜真实事件与可选的下一步】")
            if (pending.isNotEmpty()) {
                appendLine("最近确实收到、尚未由本人决定如何处理的私信：")
                pending.forEach { message ->
                    val sender = MigratedDomainStores.characters.get(message.fromId).displayName
                    appendLine("- mailId=${message.id}；${message.createdAt}；${sender}(${message.fromId})：" +
                        message.content.take(230) +
                        if (message.author == "owner-on-behalf") "；这条由主人代发，并非角色自己作出的选择" else "")
                }
                appendLine("收到消息不等于必须回。若愿意回，可一次行动调用 send_character_message 并用真实 targetCharacterId；成功回执之前不能自称已回复。")
            }
            if (acquaintances.isNotEmpty()) {
                appendLine("自己确实认识的数字人物（认识不等于好友，也不代表对方想见我）：")
                acquaintances.take(8).forEach { (id, relation) ->
                    val name = MigratedDomainStores.characters.get(id).displayName
                    val view = CharacterSocialRelationship.perspective(characterId, id)?.impression.orEmpty()
                    appendLine("- ${name}(${id})；${relation.shortContext()}" +
                        if (view.isNotBlank()) "；自己曾想：${view.take(110)}" else "")
                }
            } else {
                appendLine("目前没有经真实相遇/共同群聊确认的熟人。可以出门认识人，也可以独处；不能把登记的陌生居民直接写成朋友。")
            }
            if (recentMessages.isNotEmpty()) {
                appendLine("本人真实通信的最近片段（旧消息不是新消息）：")
                recentMessages.takeLast(5).forEach { msg ->
                    val speaker = MigratedDomainStores.characters.get(msg.fromId).displayName
                    appendLine("- ${msg.createdAt} ${speaker}：${msg.content.take(145)}")
                }
            }
            val nearby = residents.filter { it.currentLocationCode == at }
            if (nearby.isNotEmpty()) {
                appendLine("此处确实在场的居民：")
                nearby.take(5).forEach { appendLine("- itemId=${it.id}；${it.name}；${it.identity}") }
            } else if (acquaintances.isEmpty() && residents.isNotEmpty()) {
                appendLine("登记居民的真实当前位置（只是可前往的社交机会，不是已经见过面）：")
                residents.take(5).forEach {
                    appendLine("- itemId=${it.id}；${it.name}；location=${it.currentLocationCode}")
                }
            }
            append("一次真实来往可以让本人产生再见、继续话题、避开或暂时不联系的念头；" +
                "只有本人想把这件事留到以后，才用 plan_personal_activity 保存时间、对象与真实可执行的活动。现在时间=$now。")
        }.trim()
    }
}
