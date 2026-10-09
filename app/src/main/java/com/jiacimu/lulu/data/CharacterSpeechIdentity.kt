package com.jiacimu.lulu.data

/**
 * Per-character style evidence, never a shared worldbook or a bag of mandatory slang.
 * The model is shown what this character really wrote, not synthetic "memories".
 */
internal object CharacterSpeechIdentity {
    fun promptSection(
        characterId: String,
        includeObserved: Boolean = true,
        includePersonalSamples: Boolean = true,
    ): String {
        val initial = CharacterLifeStore.state(characterId).optJSONObject("profile")
            ?.optString("speechHabits").orEmpty().trim()
        val acquired = if (includeObserved) CharacterDevelopmentStore.active(characterId)
            .filter { it.kind == DevelopmentKind.ExpressionHabit }.takeLast(5) else emptyList()
        val ownMessages = if (includeObserved && includePersonalSamples) MigratedDomainStores.chat.conversations.value.asSequence()
            .filter { chat ->
                chat.characterId == characterId ||
                    chat.groupChat?.members?.any { it.characterId == characterId } == true
            }
            .flatMap { chat ->
                MigratedDomainStores.chat.messages(chat.id).value.asSequence()
                    .filter { message ->
                        message.status == LuluChatMessage.Status.Sent &&
                            message.sender == LuluChatMessage.Sender.Character &&
                            (message.authorCharacterId == characterId ||
                                message.authorCharacterId == null && chat.groupChat == null && chat.characterId == characterId)
                    }
            }.toList().sortedBy { it.createdAt }.takeLast(6).map { it.content.replace("\n", " ").take(135) } else emptyList()

        return buildString {
            appendLine("【这个角色个人的说话指纹，不是所有人通用的口头禅】")
            if (initial.isNotBlank()) appendLine("用户为此角色明确设定的语言小癖好（优先遵守）：$initial")
            if (acquired.isNotEmpty()) {
                appendLine("来自真实多次经历、经证据校验才获得的语言习惯：")
                acquired.forEach { appendLine("- ${it.content}") }
            }
            if (ownMessages.isNotEmpty()) {
                appendLine("这个角色最近真实说过的少量原话，仅用来感觉个人节奏和语气，不能复读这些句子：")
                ownMessages.forEach { appendLine("「$it」") }
            }
            appendLine("把本人真实经历和表达特点当作倾向，而不是固定台词表。根据当下情绪与对象自由组织语言；临时模仿、反复尝试和真正养成的语言习惯不是一回事，不能把别人的特点直接当成自己的。")
            appendLine("根据交流场景切换语体：私下熟悉时能有这个角色自己的口语、玩笑、吐槽、笨拙或随性，不必把轻松荒诞的话题解读成严肃的情感宣言；真正重大的问题可以郑重。不要为显得活泼生硬模仿流行语。")
        }
    }

}
