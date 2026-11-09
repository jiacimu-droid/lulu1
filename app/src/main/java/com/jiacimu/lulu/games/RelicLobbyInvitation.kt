package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import org.json.JSONArray
import org.json.JSONObject

/** A role must actually choose whether to join. A failed request is not consent. */
internal data class RelicInviteDecision(val accepted:Boolean, val speech:String)

internal object RelicLobbyInvitation {
    fun parse(raw:String):RelicInviteDecision?=runCatching {
        val clean=raw.trim().let { text ->
            if(text.startsWith("```"))text.substringAfter('\n').substringBeforeLast("```").trim()
            else text
        }
        val obj=JSONObject(clean)
        if(!obj.has("accept") || obj.get("accept") !is Boolean ||
            !obj.has("speech") || obj.get("speech") !is String) return@runCatching null
        RelicInviteDecision(obj.getBoolean("accept"),obj.getString("speech").trim())
    }.getOrNull()

    suspend fun ask(characterId:String,accepted:List<String>):Result<RelicInviteDecision> {
        val facts=JSONObject()
            .put("game","迷雾摸金")
            .put("invitation","玩家在营地邀请你一起探索遗迹，尚未开始本局")
            .put("alreadyAcceptedCharacters",JSONArray(accepted))
            .put("partyLimit",3)
            .put("risks","搜宝、躲避守卫、受伤倒地、失败损失本局战利品")
            .put("canDecline",true)
            .toString()
        return LuluAiServices.gateway.generate(
            characterId=characterId,
            facts=facts,
            instruction="""玩家正在迷雾摸金的出发大厅，主动邀请你加入队伍。你是已有身份、关系、记忆和想法的本人；请自行决定要不要加入，可接受也可拒绝，不能为了程序需要被强制接受。即使接受，此刻只是确认同行，并不代表已经进地图、救援成功或拿到了宝物。只能输出 JSON：{"accept":true或false,"speech":"你愿意说出的简短回复，可以为空"}。""",
            source="游戏 · 迷雾摸金营地邀请",
            title="收到摸金组队邀请",
            usage=ModelUsage.Game,
            contextMode=CompanionContextMode.CharacterAndScenario,
            maxTokens=170,
        ).mapCatching { parse(it.text) ?: error("邀请回复格式无效，尚未加入队伍") }
    }
}
