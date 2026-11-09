package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import org.json.JSONObject

internal data class IslandInvitationAnswer(val accepted: Boolean, val speech: String)

/** Player asks; the actual character chooses. This is never a default "yes" in the UI. */
internal object IslandInvitation {
    fun parse(raw: String): IslandInvitationAnswer? = runCatching {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return@runCatching null
        val json = JSONObject(raw.substring(start, end + 1))
        val accepted = json.opt("accept")
        if (accepted !is Boolean) return@runCatching null
        IslandInvitationAnswer(accepted, json.optString("speech", "").take(180))
    }.getOrNull()

    suspend fun ask(characterId: String, party: List<String>): Result<IslandInvitationAnswer> {
        return LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = "场景：方块小岛开局大厅。玩家刚刚邀请你一起探索与建造，" +
                "但你还未加入、还没有作出决定，也没有进行任何游戏行动。" +
                "当前已入队的其他同伴数量：${party.size}。最多可有3位角色同行。" +
                "接受后你可以自己选择保护、采集、施工、交谈或沉默，也可以改变主意；" +
                "拒绝不会改变玩家的存档。",
            instruction = """你是这个角色本人，而不是后台替所有人物统一作决定。
玩家邀请你去《方块小岛》合作游玩。依据你自己的性格、关系、此刻状态和意愿，
决定是否愿意加入；可以接受，也可以拒绝，不要因为玩家邀请就一律同意。
只返回一个有效 JSON 对象，accept 必须是布尔值 true 或 false，不是字符串。\n例如接受时：{"accept":true,"speech":"你自然想告诉玩家的话"}。拒绝时把 accept 设为 false。
此刻只是决定是否入队，不要声称已经进岛、盖房、采集或保护成功。""",
            source = "游戏 · 方块小岛组队邀请",
            title = "方块小岛 · 同行邀请",
            usage = ModelUsage.Game,
            contextMode = CompanionContextMode.CharacterAndScenario,
            maxTokens = 180,
        ).mapCatching { response ->
            parse(response.text) ?: error("角色的邀请答复格式无效，请重新邀请")
        }
    }
}
