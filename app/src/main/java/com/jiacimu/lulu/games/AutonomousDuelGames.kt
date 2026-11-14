package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.data.DigitalLifeProfileStore
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.ResidentSocialMailbox
import com.jiacimu.lulu.data.SharedExperienceTimeline
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/** Rules shared by protagonist↔protagonist, protagonist↔resident and resident↔resident.
 * Players only know exposed memory cards; no model call is purchased for every flip/roll. */
internal object AutonomousDuelRules {
    const val MEMORY_PAIRS = 12
    val games = mapOf("yacht_dice" to "快艇骰子", "memory_match" to "记忆配对")
    data class Match(val gameId: String, val points: List<Int>, val turns: Int, val details: JSONObject) {
        init { require(points.size == 2) }
    }

    fun play(gameId: String, seed: Int): Match {
        require(gameId in games) { "目前只有快艇骰子与记忆配对支持人物双人对抗" }
        val random = Random(seed)
        if (gameId == "yacht_dice") {
            val sheets = List(2) { linkedMapOf<YachtCategory, Int>() }
            val turns = JSONArray()
            for (round in 1..YachtCategory.entries.size) {
                for (player in 0..1) {
                    var dice = List(5) { random.nextInt(1, 7) }
                    val rolls = JSONArray().put(JSONArray(dice))
                    repeat(2) {
                        val held = chooseYachtHolds(dice)
                        dice = dice.mapIndexed { index, number ->
                            if (index in held) number else random.nextInt(1, 7)
                        }
                        rolls.put(JSONArray(dice))
                    }
                    val category = YachtCategory.entries.filterNot { it in sheets[player] }
                        .maxByOrNull { scoreYacht(it, dice) }
                        ?: error("快艇骰子类别不足")
                    val scored = scoreYacht(category, dice)
                    sheets[player][category] = scored
                    turns.put(JSONObject().put("round", round).put("player", player)
                        .put("rolls", rolls).put("category", category.name)
                        .put("points", scored).put("total", sheets[player].values.sum()))
                }
            }
            return Match(gameId, sheets.map { it.values.sum() }, turns.length(),
                JSONObject().put("game", gameId).put("mode", "ai_duel")
                    .put("completed", true).put("roundCountPerPlayer", YachtCategory.entries.size)
                    .put("scoreSheets", JSONObject().apply {
                        sheets.forEachIndexed { i, sheet ->
                            put("player$i", JSONObject().put("total", sheet.values.sum())
                                .put("categories", JSONObject().apply {
                                    YachtCategory.entries.forEach { put(it.name, sheet[it] ?: 0) }
                                }))
                        }
                    }).put("turns", turns))
        }
        val symbols = listOf("🌙", "🍰", "🎧", "🌸", "🧸", "☕",
            "⭐", "🐚", "🎲", "🦋", "🍀", "🍓")
        val cards = symbols.flatMap { listOf(it, it) }.shuffled(random)
        val observed = mutableMapOf<Int, String>()
        val matched = mutableSetOf<Int>()
        val pairs = intArrayOf(0, 0)
        val turns = JSONArray()
        var player = 0
        var moves = 0
        while (matched.size < cards.size) {
            // The same observed-card strategy as the interactive memory game:
            // unknown backs are never peeked at for either participant.
            val first = chooseObservedMemoryCard(
                MemoryMatchState(cards = cards, matched = matched, observed = observed), random)
                ?: error("记忆配对未能找到第一张牌")
            observed[first] = cards[first]
            val second = chooseObservedMemoryCard(
                MemoryMatchState(cards = cards, matched = matched, opened = setOf(first), observed = observed), random)
                ?: error("记忆配对未能找到第二张牌")
            observed[second] = cards[second]
            val success = cards[first] == cards[second]
            if (success) {
                matched.add(first)
                matched.add(second)
                pairs[player]++
            }
            moves++
            turns.put(JSONObject().put("player", player).put("first", first)
                .put("second", second).put("firstValue", cards[first])
                .put("secondValue", cards[second]).put("matched", success)
                .put("pairs", JSONArray(listOf(pairs[0], pairs[1]))))
            if (!success) player = 1 - player
        }
        return Match(gameId, pairs.toList(), moves,
            JSONObject().put("game", gameId).put("mode", "ai_duel")
                .put("completed", true).put("pairCount", MEMORY_PAIRS)
                .put("cards", JSONArray(cards)).put("turns", turns))
    }
}

/** A game-room invitation, NOT a second relationship or chat store.
 * Only the addressee's explicit accepted decision starts a match. */
internal object AutonomousDuelGames {
    private const val PREFS = "lulu_autonomous_duel_v1"
    private const val KEY = "invites"
    data class Invite(val id: String, val from: String, val to: String,
        val gameId: String, val status: String, val text: String)
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun entries(context: Context): MutableList<JSONObject> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun commit(context: Context, list: List<JSONObject>) {
        check(prefs(context).edit().putString(KEY, JSONArray(list).toString()).commit()) {
            "双人邀约状态未能安全保存"
        }
    }
    private fun JSONObject.toInvite() = Invite(optString("id"), optString("from"),
        optString("to"), optString("gameId"), optString("status"), optString("text"))

    /** A withdrawn/edited mail can no longer authorize accepting its old invitation. */
    private fun hasLiveDelivery(row: JSONObject): Boolean {
        val target = row.optString("to")
        val mailId = row.optString("mailId")
        if (target.isBlank() || mailId.isBlank()) return false
        return SharedExperienceTimeline.eventsByIds(target,
            listOf("native-mail-$mailId-viewer-$target")).any { event ->
            event.revision == 1L && event.source == "character-message" &&
                event.channel == "数字人物私信"
        }
    }

    @Synchronized fun pendingFor(context: Context, role: String): List<Invite> =
        entries(context).filter { it.optString("to") == role &&
            it.optString("status") == "pending" && hasLiveDelivery(it) }.map { it.toInvite() }

    @Synchronized fun contextFor(context: Context, role: String): String {
        val pending = pendingFor(context, role)
        val outgoing = entries(context).filter { it.optString("from") == role &&
            it.optString("status") in setOf("preparing", "pending", "accepted") }
        return buildString {
            appendLine("【游戏馆真人物对抗｜是否邀请、接受或拒绝完全由本人决定】")
            appendLine("可邀真正认识的数字人物：${ResidentSocialMailbox.visibleContacts(role).joinToString("、") { "$it=${MigratedDomainStores.characters.get(it).displayName}" }.ifBlank { "当前没有可邀请的熟人" }}")
            if (pending.isNotEmpty()) {
                appendLine("本人收到尚未回答的对局邀请：")
                pending.forEach { invite ->
                    appendLine("- challengeId=${invite.id}；游戏=${AutonomousDuelRules.games[invite.gameId]}；邀请者=${invite.from}；原话=${invite.text.take(120)}")
                }
            }
            if (outgoing.isNotEmpty()) {
                appendLine("本人已发出、尚未结算的邀请：")
                outgoing.takeLast(5).forEach {
                    appendLine("- challengeId=${it.optString("id")}；收件人=${it.optString("to")}；状态=${it.optString("status")}")
                }
            }
            append("邀请送达不是应邀；对方明确接受后才开始同一局真实游戏，结束后双方各自得知胜负和成绩。")
        }
    }

    @Synchronized fun invite(context: Context, from: String, to: String,
        gameId: String, text: String, now: Instant = Instant.now()): Invite {
        ResidentSocialMailbox.initialize(context)
        require(gameId in AutonomousDuelRules.games) { "该游戏暂不支持角色间对抗" }
        require(text.isNotBlank()) { "发出邀请须提供本人想说的话" }
        require(ResidentSocialMailbox.canContact(from, to) &&
            DigitalLifeProfileStore.isEnabled(from) &&
            DigitalLifeProfileStore.isEnabled(to)) { "双方必须是真实可联系的数字人物" }
        require(DigitalLifeProfileStore.allowsTimestamp(from, now) &&
            DigitalLifeProfileStore.allowsTimestamp(to, now)) { "邀请时间早于人物诞生时间" }
        val all = entries(context)
        all.firstOrNull { it.optString("from") == from && it.optString("to") == to &&
            it.optString("gameId") == gameId && it.optString("status") == "pending" &&
            hasLiveDelivery(it) }
            ?.let { return it.toInvite() }
        val id = UUID.randomUUID().toString()
        val row = JSONObject().put("id", id).put("from", from).put("to", to)
            .put("gameId", gameId).put("text", text.trim().take(240))
            .put("status", "preparing").put("createdAt", now.toString())
        all.add(row)
        commit(context, all)
        try {
            // Same durable character↔character QQ mailbox and its wake/observations.
            // Never create a fake owner-chat invitation or speak for the recipient.
            val delivered = ResidentSocialMailbox.send(from, to,
                "[游戏馆邀约|$id|$gameId] ${text.trim().take(240)}",
                at = now)
            row.put("mailId", delivered.id)
        } catch (error: Exception) {
            row.put("status", "delivery_failed")
            commit(context, all)
            throw error
        }
        row.put("status", "pending")
        commit(context, all)
        return row.toInvite()
    }

    /** A reply may be replayed after a crash; deterministic match IDs and record IDs
     * mean partial settlement cannot award/record a second game. */
    @Synchronized fun respond(context: Context, role: String, id: String,
        decision: String, now: Instant = Instant.now()): String {
        require(decision in setOf("accept", "decline")) { "只能由受邀者选择接受或拒绝" }
        val all = entries(context)
        val row = all.firstOrNull { it.optString("id") == id }
            ?: error("没有这份真实对局邀请")
        require(row.optString("to") == role) { "不能代其他人答复游戏邀约" }
        val status = row.optString("status")
        if (status == "declined") return "之前已拒绝这份游戏邀请"
        if (status == "finished") return row.optString("result")
        require(status == "pending" || (status == "accepted" && decision == "accept")) {
            "该邀请尚未送达、已取消或不再接受操作"
        }
        if (status == "pending") require(hasLiveDelivery(row)) {
            "原始游戏邀请已撤回或不再有效，不能据此替对方开局"
        }
        val from = row.getString("from")
        val gameId = row.getString("gameId")
        if (decision == "decline") {
            row.put("status", "declined")
            commit(context, all)
            val summary = "${MigratedDomainStores.characters.get(role).displayName}拒绝了《${AutonomousDuelRules.games[gameId]}》邀约；没有开局。"
            recordOutcome(id, listOf(from, role), summary, now, "duel-invite")
            return summary
        }
        if (status == "pending") {
            row.put("status", "accepted")
            commit(context, all)
        }
        val match = AutonomousDuelRules.play(gameId, id.hashCode())
        val actors = listOf(from, role)
        val names = actors.map { MigratedDomainStores.characters.get(it).displayName }
        val title = requireNotNull(AutonomousDuelRules.games[gameId])
        val outcome = when {
            match.points[0] > match.points[1] -> "胜者：${names[0]}"
            match.points[1] > match.points[0] -> "胜者：${names[1]}"
            else -> "平局"
        }
        val units = if (gameId == "memory_match") "对" else "分"
        val summary = "${names[0]} ${match.points[0]}$units，${names[1]} ${match.points[1]}$units；$outcome。" +
            if (gameId == "memory_match") "全场${match.turns}次翻牌回合，12对全部找齐。"
            else "双方各完成13轮计分。"
        LuluGames.initialize(context)
        actors.forEachIndexed { i, actor ->
            val details = JSONObject(match.details.toString())
                .put("challengeId", id)
                .put("participantIds", JSONArray(actors))
                .put("participantNames", JSONArray(names))
                .put("points", JSONArray(match.points))
                .put("winnerIds", JSONArray(actors.filterIndexed { index, _ ->
                    match.points[index] == match.points.maxOrNull()
                }))
                .put("playerIndex", i)
            LuluGames.store.recordExternalGame(
                if (gameId == "memory_match") LuluGameType.MemoryMatch else LuluGameType.YachtDice,
                title, match.points[i], 0, summary, details.toString(),
                characterIdOverride = actor, playedWithCharacterOverride = false,
                recordIdOverride = "duel-$id-$actor")
        }
        recordOutcome(id, actors, summary, now, "duel-game")
        row.put("status", "finished").put("result", summary)
        commit(context, all)
        return summary
    }

    private fun recordOutcome(id: String, actors: List<String>, summary: String,
        now: Instant, source: String) {
        actors.forEach { actor ->
            SharedExperienceTimeline.record(
                eventId = "$source-$id-$actor", characterId = actor,
                channel = "游戏馆人物对战", speaker = "游戏馆",
                content = summary, source = source,
                occurredAt = now, evidenceKind = EventEvidenceKind.Observation,
                triggerExtraction = false)
        }
    }
}
