package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.data.MigratedDomainStores
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/** The local renderer submits actions only. Existing app saves and the relic ledger own game state. */
internal class ArcadeNativeGame(
    context: Context,
    private val store: LuluGameStore,
    val kind: String,
    initialParty: List<String>,
) {
    private val app = context.applicationContext
    private val islandPrefs = app.getSharedPreferences("lulu_block_island_v2", Context.MODE_PRIVATE)
    private val ledger = RelicEscapeLedger(app)
    private val partySession = UUID.randomUUID().toString()
    private val firstParty = initialParty
    private val party = linkedSetOf<String>()
    private var prepared = false
    var islandAutonomous = true
        @Synchronized get
        @Synchronized set

    private val gameId = if (kind == "island") "block_island" else "relic_escape"

    @Synchronized fun island(): BlockIslandState? =
        BlockIslandState.fromJson(islandPrefs.getString("world", "").orEmpty())
            ?: ExpeditionState.fromJson(
                app.getSharedPreferences("lulu_expedition_homestead_v1", Context.MODE_PRIVATE)
                    .getString("world", "").orEmpty(), ExpeditionMode.HOMESTEAD,
            )?.let(BlockIslandState::fromLegacy)

    @Synchronized fun relic(): RelicRun? = ledger.read().run

    @Synchronized fun saveIsland(next: BlockIslandState) {
        check(islandPrefs.edit().putString("world", next.toJson()).commit()) {
            "无法保存岛屿，本次操作未确认。"
        }
    }

    @Synchronized fun relicCompanion(runId: String, id: String, choice: IslandActorChoice): Boolean =
        runCatching { ledger.companion(runId, id, choice) }.isSuccess

    @Synchronized fun pulseRelic(runId: String): RelicRun? {
        val before = relic()?.takeIf { it.id == runId } ?: return null
        val actors = GameCoopPacing.relicTickActors(before)
        if (actors.isEmpty()) return null
        val result = ledger.perform("companion_tick", autonomousIds = actors, expectedRunId = runId)
        syncRelic(result)
        return result.run?.takeIf { it.id == runId && it.teamSteps > before.teamSteps }
    }

    private fun syncRelic(snapshot: RelicSnapshot) {
        ledger.syncExperiences(snapshot, store)
    }

    private fun profiles() = MigratedDomainStores.characters.settings.value
    private fun validId(id: String): Boolean = id.isNotBlank() && profiles().containsKey(id)

    private fun initialRoster() {
        if (prepared) return
        val saved = if (kind == "island") island()?.world?.friends?.map { it.id }.orEmpty()
            else relic()?.allies?.map { it.id }.orEmpty()
        party.addAll((firstParty + saved).filter(::validId).distinct().take(2))
        prepared = true
    }

    @Synchronized private fun bootstrap(): JSONObject {
        initialRoster()
        val snapshot = if (kind == "relic") ledger.read() else null
        val run = snapshot?.run
        val weapons = snapshot?.purchasedWeapons.orEmpty()
        val saved = if (kind == "island") island()?.world?.seed?.toString() else run?.id
        return JSONObject().put("party", JSONArray(party.toList()))
            .put("roles", JSONArray(profiles().values.sortedBy { it.displayName }.map { character ->
                JSONObject().put("id", character.characterId).put("name", character.displayName)
            }))
            .put("session", saved ?: JSONObject.NULL)
            .put("activeRun", run?.phase in listOf(RelicPhase.EXPLORE, RelicPhase.EXTRACT, RelicPhase.DOWNED))
            .put("weapons", JSONArray(weapons.sorted()))
    }

    /** Only a reply from the existing game invitation path adds the real character. */
    suspend fun invite(id: String): JSONObject {
        val current = synchronized(this) {
            initialRoster()
            check(validId(id) && id !in party && party.size < 2) { "这个角色现在无法入队。" }
            party.toList()
        }
        val (accepted, speech) = if (kind == "island") {
            IslandInvitation.ask(id, current).getOrThrow().let { it.accepted to it.speech }
        } else {
            RelicLobbyInvitation.ask(id, current).getOrThrow().let { it.accepted to it.speech }
        }
        return synchronized(this) {
            val joined = accepted && validId(id) && party.size < 2 && party.add(id)
            if (joined) GameInvitationContinuity.recordPartyJoined(app, id, gameId, partySession)
            JSONObject().put("party", JSONArray(party.toList()))
                .put("message", speech.ifBlank { if (joined) "接受邀请，已入队。" else "这次先不加入。" })
        }
    }

    @Synchronized fun request(q: JSONObject): JSONObject {
        val verb = q.optString("verb")
        if (verb == "lobby") return bootstrap()
        if (verb == "remove") {
            val id = q.optString("actor")
            if (party.remove(id)) GameInvitationContinuity.recordPartyLeft(app, id, gameId, partySession)
            return JSONObject().put("party", JSONArray(party.toList()))
        }
        check(verb in setOf("view", "act", "start", "resume")) { "未知游戏指令。" }
        val input = ArcadeInput.read(q)
        val selected = input.selected.takeUnless { verb == "act" && input.action == "move" }
        return if (kind == "island") {
            val before = island()
            val current = when (verb) {
                "start" -> {
                    initialRoster()
                    val ids = party.filter(::validId)
                    store.selectCharacters(ids)
                    val next = if (before == null) ArcadeMaps.coast(4317, ids)
                        else with(BlockIslandRules) { before.withPartners(ids) }
                    saveIsland(next)
                    ids.forEach { GameInvitationContinuity.recordPartyStarted(app, it, gameId, partySession) }
                    next
                }
                else -> {
                    val saved = checkNotNull(before) { "暂无岛屿存档。" }
                    check(q.optString("session") == saved.world.seed.toString()) { "岛屿已切换，请重新进场。" }
                    if (verb == "act") {
                        val ids = if (islandAutonomous) saved.world.friends.map { it.id }.toSet() else emptySet()
                        arcadeIslandAct(saved, input, ids).also { if (it != saved) saveIsland(it) }
                    } else saved
                }
            }
            val placed = verb == "act" && input.action == input.build && current.world.turns > (before?.world?.turns ?: 0)
            arcadeIslandView(current, selected, input.facing, if (placed) "" else input.build)
                .put("session", current.world.seed.toString())
        } else {
            var step: ArcadeRelicStep? = null
            val before = ledger.read()
            val snapshot = when (verb) {
                "start" -> {
                    initialRoster()
                    val ids = party.filter(::validId)
                    val difficulty = RelicDifficulty.entries.firstOrNull { it.name == q.optString("difficulty") }
                        ?: RelicDifficulty.NORMAL
                    val profession = RelicProfession.entries.firstOrNull { it.name == q.optString("profession") }
                        ?: RelicProfession.SCOUT
                    val route = q.optString("map")
                    val started = ledger.begin(ids, minOf(1, before.kits), difficulty,
                        q.optString("weapon", "blade"), RelicContract.FIRST_HAUL, profession) {
                        ArcadeMaps.cloister(it, route == "ambush")
                    }
                    if (started.run?.id != before.run?.id) {
                        store.selectCharacters(ids)
                        ids.forEach { GameInvitationContinuity.recordPartyStarted(app, it, gameId, partySession) }
                    }
                    started
                }
                "act" -> ledger.playArcade(q.optString("session"), input).let { (updated, receipt) ->
                    step = receipt
                    updated
                }
                else -> before.also {
                    check(it.run?.id == q.optString("session")) { "对局已切换，请重新进场。" }
                }
            }
            if (verb != "view") syncRelic(snapshot)
            val current = checkNotNull(snapshot.run) { "暂无遗迹存档。" }
            val view = arcadeRelicView(current, selected, input.facing).put("session", current.id)
            step?.annotate(view) ?: view
        }
    }
}
