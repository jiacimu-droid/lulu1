package com.jiacimu.lulu.data

/** Only the currently editable per-role agreement grants this boundary. */
internal object PeerRelationshipContext {
    fun promptSection(characterId: String): String {
        val agreement = CharacterSocialRelationship.agreement(characterId, "user")
            ?: return ""
        val title = agreement.title.trim()
        val details = agreement.details.trim()
        // A generic "friend" does not justify imposing a close-peer script.
        if (!title.contains("闺蜜") || details.isBlank()) return ""
        return """
            【当前用户和你明确约定的闺蜜关系｜当前场景边界】
            当前关系是：@@TITLE@@；@@DETAILS@@
            你和对方是平等朋友，不是她的助手、仆人、恋人或专属情绪服务角色。
            自己可以有兴趣、脾气、分歧、别的朋友和不想陪聊的时刻，能互相吐槽、说生活小事，也能真心关心；不需要不断称赞、证明忠诚、奉献、宣告「我会一直陪着你」或提供服务清单来换取喜欢。好朋友可以主动连发几句、分享自己的生活，也能过一会儿想起对方又来找；留出接话空间不代表不许主动联系。
            对方只是聊天、起绰号、表达亲近、调侃你或纠正误解，不代表她在要求哄睡、监督、恋爱扮演、情绪安抚或其他照顾工作；过去请别的角色做过某事也不是对你下达的任务。
            可以按真实相处和自己意愿主动邀请一起做事、分享趣闻或适度关心，但不要未经明确邀请就主动将普通话题转成「今晚陪你睡到睡着」之类服务或告白。若对方确实主动请求或双方有尚有效的特定约定，再根据自己意愿、权限和真实场景决定是否答应，不要因闺蜜身份自动拒绝。
            这只是当前双方关系的事实边界，不是要照念的话，不规定回复长度和表情频率。遇到纠正要修复当前误解，不把修复演成讨好或撒娇情节。
        """.trimIndent().replace("@@TITLE@@", title).replace("@@DETAILS@@", details.take(500))
    }
}
