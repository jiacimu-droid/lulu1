package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Fictional INNER narration attached to exactly one already-committed world action.
 * The actual map, residents, objects, conversations and resources remain unchanged.
 */
internal object WorldLifeEpisodeComposer {
    suspend fun compose(
        characterId: String,
        location: String,
        confirmedAction: String,
        suppliedTitle: String,
        suppliedScene: String,
    ): Pair<String, String> {
        // Reuse a sufficiently complete first-pass scene when the character
        // supplied one, rather than paying for a duplicate model response.
        if (suppliedScene.trim().length >= 180)
            return suppliedTitle.ifBlank { "在这里待过的一段时间" } to suppliedScene

        val character = MigratedDomainStores.characters.get(characterId)
        val locationName = DigitalWorldStore.locationLabel(location)
        val items = DigitalWorldStore.itemsAtLocation(characterId).take(9)
            .joinToString("；") { "${it.name}（${it.position.take(65)}）" }
        val prior = WorldLifeEpisodeStore.episodes(characterId).takeLast(1).firstOrNull()
        val model = try {
            LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = buildString {
                    appendLine("唯一已经执行并入库的世界动作：$confirmedAction")
                    appendLine("角色当前真实所在地点：$locationName（$location）")
                    appendLine("当前地点实际登记的物品：${items.ifBlank { "未列出任何可核验的物品" }}")
                    prior?.let {
                        appendLine("上一段已发生的独处经历：${it.title}；${it.scene.take(260)}")
                        appendLine("这是旧事，不要当作刚刚又发生过。")
                    }
                    appendLine("人物：${character.displayName}；由统一人格与记忆约束决定其真实口吻。")
                },
                instruction = """
                    写一段完整、自然的第三人称数字世界独处剧情，约240至420字，首尾相连，有细微起伏与人物自己的感受，而不是列出三个动作或系统回执。
                    已真实完成的动作只是环境锚点，允许写角色的感官注意、想起的旧事、矛盾心理、缓慢变化的兴趣、一个没说出口的念头。
                    不许捏造已和居民交谈、别人来到现场、发现新物件、去了另一个地点、完成游戏、打开未核实的书籍、获得新道具或改动持久世界状态。
                    不要总是模板式散步—停下来—想主人—结束。应符合本人的人格、最近确实发生过的事情及当下心情，允许幽默、烦躁、好奇和安静。
                    只返回 JSON：{"title":"自然简短小标题","scene":"完整连贯的第三人称段落"}。不要发表技术报告，不要给用户发送聊天消息。
                """.trimIndent(),
                source = "数字世界·完整离线独处",
                title = "${character.displayName}的独处片段",
                maxTokens = 1_100,
                contextMode = com.jiacimu.lulu.ai.CompanionContextMode.CharacterAndScenario,
            ).getOrNull()?.text
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
        val raw = model?.trim().orEmpty().removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
        val scene = parsed?.optString("scene")?.trim().orEmpty()
        if (scene.length >= 125) return (parsed?.optString("title").orEmpty()
            .ifBlank { suppliedTitle.ifBlank { "在这里待过的一段时间" } }) to scene.take(1_800)

        // Honest offline fallback: no invented objects, encounters or outcomes.
        return suppliedTitle.ifBlank { "独自度过的一小段时间" } to
            "${character.displayName}在${locationName}停留，${confirmedAction.removeSuffix("。")}。" +
            "他没有急着把这一刻变成什么特别的事件。这里确实发生的，只有已经记录下来的活动；至于心里究竟在想什么，没有人替他宣布答案。" +
            "他把注意力放回自己正在做的事上，容许这一段时间有一个自然的开头和结束，而不是为了证明生活还在继续，隔几分钟就重复一次相同的举动。" +
            "等到下次真的遇见新东西、产生新兴趣，才值得继续写下去。"
    }
}
