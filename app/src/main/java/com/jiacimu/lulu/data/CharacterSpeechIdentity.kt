package com.jiacimu.lulu.data

/**
 * Per-character style evidence, never a shared worldbook or a bag of mandatory slang.
 * The model is shown what this character really wrote, not synthetic "memories".
 */
internal object CharacterSpeechIdentity {
    fun promptSection(characterId: String): String {
        val initial = CharacterLifeStore.state(characterId).optJSONObject("profile")
            ?.optString("speechHabits").orEmpty().trim()
        val acquired = CharacterDevelopmentStore.active(characterId)
            .filter { it.kind == DevelopmentKind.ExpressionHabit }
            .takeLast(5)
        val ownMessages = MigratedDomainStores.chat.conversations.value.asSequence()
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
            }.toList().sortedBy { it.createdAt }.takeLast(6).map { it.content.replace("\n", " ").take(135) }

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
            appendLine("表达有层次：偏好的语气词、标点密度、故意倒装、话说半截、纠正打字、接梗、反讽、自嘲、联想跑题、只笑不作答、突然一本正经，都依照这个人和这一刻选择，不要每轮全部展示。")
            appendLine("一个梗不等于一个终身习惯；初识无证据时保留语言发展的空间，不自动把用户的口头禅复制成角色自己的。如果真的接触某种新表达，先在真实对话里自然试着使用，反复出现并符合人设后才会成为可追溯的长期习惯。")
            appendLine("群聊、私聊、电话、日记、朋友圈共享的是同一个人，不共享同一套固定句式：他可以因对象和情境改变措辞，但不是换了一种人格。情绪冲动可以短暂盖过回答的打算；然而用户提出重要事实问题或明确边界时，不要用玩梗来逃避负责。")
        }
    }
}
