package com.jiacimu.lulu.games

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
import org.json.JSONArray
import org.json.JSONObject

/** Role-owned choices; no automatic following, combat preference or invented success. */
internal object RelicActorPlay {
    private val live = setOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT, RelicPhase.DOWNED)
    fun acted(s: RelicRun, id: String) = s.events.any {
        it.who == id && it.action == "decision" && it.turn == s.turns
    }

    fun options(s: RelicRun, id: String): List<IslandActorOption> {
        val a = s.allies.firstOrNull { it.id == id && it.hp > 0 } ?: return emptyList()
        if (s.phase !in live) return emptyList()
        val result = mutableListOf(IslandActorOption("none", "观察或交谈", "none"))
        if (acted(s, id)) return result
        for ((x, y) in listOf(a.x+1 to a.y, a.x-1 to a.y, a.x to a.y+1, a.x to a.y-1)) {
            if (!s.bounds(x, y)) continue
            if (s.mobs.any { it.hp > 0 && it.x == x && it.y == y }) {
                result += IslandActorOption("attack:$x:$y", "攻击($x,$y)的守卫", "attack", x, y)
            } else if (s.walk(x, y) && !(s.hero.x == x && s.hero.y == y) &&
                s.allies.none { it.id != id && it.hp > 0 && it.x == x && it.y == y }) {
                result += IslandActorOption("move:$x:$y", "走到($x,$y)", "move", x, y)
            }
        }
        s.chests.filter { !it.opened && s.visible(it.x,it.y) &&
            RelicEscapeRules.distance(a.x,a.y,it.x,it.y) <= 1 &&
            a.load + it.tier.weight <= RelicEscapeRules.CAPACITY }.forEach {
            result += IslandActorOption("open:${it.x}:${it.y}", "打开${it.tier.label}宝箱(${it.x},${it.y})", "open", it.x, it.y)
        }
        if (a.kits > 0 && a.hp < 10)
            result += IslandActorOption("heal", "使用自己的药剂治疗自己", "heal")
        if (a.kits > 0 && s.hero.hp <= 0 &&
            RelicEscapeRules.distance(a.x,a.y,s.hero.x,s.hero.y) <= 1)
            result += IslandActorOption("rescue", "消耗自己的药剂救起玩家", "rescue")
        return result
    }

    fun apply(s: RelicRun, id: String, choice: IslandActorChoice): RelicRun? {
        if (acted(s, id)) return null
        val a = s.allies.firstOrNull { it.id == id && it.hp > 0 } ?: return null
        val option = options(s,id).firstOrNull { it.id == choice.actionId } ?: return null
        var actor = a
        var next = s
        val outcome = when (option.kind) {
            "none" -> "观察现场"
            "move" -> {
                val trap = s.sites.firstOrNull { !it.spent && it.kind == "trap" && it.x == option.x && it.y == option.y }
                val damage = (if (s.tile(option.x,option.y) == 2) 1 else 0) + (if (trap != null) 2 else 0)
                actor = a.copy(x=option.x,y=option.y,hp=(a.hp-damage).coerceAtLeast(0),
                    downs=a.downs + if (a.hp <= damage) 1 else 0)
                if (trap != null) next = next.copy(sites=next.sites.map { if (it.id == trap.id) it.copy(spent=true) else it })
                "前进到(${option.x},${option.y})" + if (damage > 0) "，地形伤害$damage" else ""
            }
            "attack" -> {
                next = s.copy(mobs=s.mobs.map { if (it.hp > 0 && it.x == option.x && it.y == option.y)
                    it.copy(hp=(it.hp-2).coerceAtLeast(0)) else it })
                "攻击守卫，造成2点伤害"
            }
            "open" -> {
                val chest = s.chests.first { !it.opened && it.x == option.x && it.y == option.y }
                val loot = RelicEscapeRules.treasure(s,chest)
                actor = a.copy(bag=a.bag+loot)
                next = s.copy(chests=s.chests.map { if (it.id == chest.id) it.copy(opened=true) else it })
                "打开宝箱，${loot.name}放入自己的背包"
            }
            "heal" -> {
                actor = a.copy(hp=(a.hp+5).coerceAtMost(10),kits=a.kits-1)
                "使用药剂治疗自己"
            }
            "rescue" -> {
                actor = a.copy(kits=a.kits-1,rescues=a.rescues+1)
                next = s.copy(hero=s.hero.copy(hp=4),phase=RelicPhase.EXPLORE,downTurns=0)
                "救起玩家"
            }
            else -> return null
        }
        val seen = next.seen.toMutableSet()
        for (dy in -3..3) for (dx in -3..3)
            if (kotlin.math.abs(dx)+kotlin.math.abs(dy) <= 3 && next.bounds(actor.x+dx,actor.y+dy))
                seen += next.index(actor.x+dx,actor.y+dy)
        next = next.copy(allies=next.allies.map { if (it.id == id) actor else it },seen=seen)
            .log(id, "decision", outcome)
        if (choice.speech.isNotBlank()) next = next.log(id, "speech", choice.speech)
        return next
    }

    fun facts(s: RelicRun,id: String,heard: String): String = JSONObject()
        .put("game","迷雾摸金").put("turn",s.turns).put("phase",s.phase.name)
        .put("self",s.allies.first { it.id == id }.json())
        .put("player",s.hero.json()).put("exit",JSONArray(listOf(s.exitX,s.exitY)))
        .put("map",JSONArray(s.seen.sorted().map { index ->
            JSONObject().put("x",index%s.w).put("y",index/s.w).put("tile",s.tiles[index]) }))
        .put("chests",JSONArray(s.chests.filter { s.visible(it.x,it.y) }.map { it.json() }))
        .put("mobs",JSONArray(s.mobs.filter { it.hp > 0 && s.visible(it.x,it.y) }.map { it.json() }))
        .put("events",JSONArray(s.events.takeLast(10).map { it.json() }))
        .put("playerSpeech",heard)
        .put("options",JSONArray(options(s,id).map { JSONObject().put("id",it.id).put("meaning",it.name) }))
        .toString()

    suspend fun decide(s: RelicRun,id: String,heard: String): Result<IslandActorChoice> =
        LuluAiServices.gateway.generate(characterId=id,facts=facts(s,id,heard),
            instruction="""你是角色本人，正在陪玩家合作探索遗迹。按自己的性格、关系与现场自主选择操作，可以分头搜宝、战斗、救援、撤退或观察；不必等玩家命令或固定跟随。
只选options中的真实id。未探索的地图内容未知，台词不能冒充执行成功。可以自然交流也可以沉默。
只输出JSON：{"actionId":"options中的id","speech":"愿意说出口的话；沉默则为空字符串"}。实际执行结果由游戏返回。""",
            source="游戏 · 迷雾摸金自主同行",title="摸金伙伴自主决策",usage=ModelUsage.Game,
            contextMode=CompanionContextMode.CharacterAndScenario,maxTokens=260)
            .mapCatching { IslandActorPlay.parse(it.text) ?: error("伙伴决定格式无效，未执行操作") }
}
