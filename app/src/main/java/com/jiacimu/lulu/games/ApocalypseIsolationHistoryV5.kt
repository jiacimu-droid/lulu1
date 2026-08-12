package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ApocalypseV5HistoryEntry(
    val id: String,
    val saveId: String,
    val sceneBefore: Int,
    val partyIdsBefore: List<String>,
    val narrationBefore: String,
    val directorBefore: ApocalypseV3Director,
    val statsBefore: ApocalypseV3Stats,
    val logCountBefore: Int,
    val action: String,
    val narrationAfter: String,
    val createdAt: Long,
) {
    fun restoreOnto(current: ApocalypseV3Save): ApocalypseV3Save = current.copy(
        scene = sceneBefore,
        partyIds = partyIdsBefore,
        narration = narrationBefore,
        director = directorBefore,
        stats = statsBefore,
        log = current.log.take(logCountBefore.coerceIn(0, current.log.size)),
        updatedAt = System.currentTimeMillis(),
    )
}

internal data class ApocalypseV5HistoryRollback(
    val target: ApocalypseV5HistoryEntry,
    val removedCount: Int,
)

internal data class ApocalypseV5ReadableScene(
    val sceneNumber: Int,
    val narration: String,
    val actionThatLedHere: String?,
    val deleteEntryId: String?,
)

/** A stored history entry is a transition; expose its before/after sides as actual readable scenes. */
internal fun readableApocalypseScenesV5(
    current: ApocalypseV3Save,
    history: List<ApocalypseV5HistoryEntry>,
): List<ApocalypseV5ReadableScene> {
    if (history.isEmpty()) {
        return listOf(
            ApocalypseV5ReadableScene(current.scene, current.narration, null, null),
        )
    }
    return buildList {
        val first = history.first()
        add(ApocalypseV5ReadableScene(first.sceneBefore, first.narrationBefore, null, null))
        history.forEach { entry ->
            add(
                ApocalypseV5ReadableScene(
                    sceneNumber = entry.sceneBefore + 1,
                    narration = entry.narrationAfter,
                    actionThatLedHere = entry.action,
                    deleteEntryId = entry.id,
                ),
            )
        }
    }.filter { it.narration.isNotBlank() }
        .distinctBy { it.sceneNumber }
        .sortedBy { it.sceneNumber }
}

internal class ApocalypseV5HistoryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(saveId: String): List<ApocalypseV5HistoryEntry> {
        if (saveId.isBlank()) return emptyList()
        val raw = prefs.getString(key(saveId), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::decodeEntry)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun append(saveBefore: ApocalypseV3Save, action: String, narrationAfter: String): ApocalypseV5HistoryEntry {
        val entry = ApocalypseV5HistoryEntry(
            id = UUID.randomUUID().toString(),
            saveId = saveBefore.id,
            sceneBefore = saveBefore.scene,
            partyIdsBefore = saveBefore.partyIds,
            narrationBefore = saveBefore.narration,
            directorBefore = saveBefore.director,
            statsBefore = saveBefore.stats,
            logCountBefore = saveBefore.log.size,
            action = action,
            narrationAfter = narrationAfter,
            createdAt = System.currentTimeMillis(),
        )
        persist(saveBefore.id, (load(saveBefore.id) + entry).takeLast(MAX_HISTORY))
        return entry
    }

    /**
     * Hard-delete is causal: deleting one scene also drops every later scene whose state depended on it.
     * The returned target carries a log count aligned to the currently retained history window, including
     * any legacy prefix that existed before V5 rollback checkpoints were introduced.
     */
    @Synchronized
    fun rollback(saveId: String, entryId: String): ApocalypseV5HistoryRollback? {
        val entries = load(saveId)
        val index = entries.indexOfFirst { it.id == entryId }
        if (index < 0) return null
        val target = entries[index]
        val inferredCurrentLogSize = (entries.lastOrNull()?.logCountBefore?.plus(1) ?: target.logCountBefore)
            .coerceAtMost(MAX_SAVE_LOG)
        val legacyPrefixCount = (inferredCurrentLogSize - entries.size).coerceAtLeast(0)
        val restoredLogCount = (legacyPrefixCount + index).coerceIn(0, inferredCurrentLogSize)
        val restoredTarget = target.copy(logCountBefore = restoredLogCount)
        val removedCount = entries.size - index
        persist(saveId, entries.take(index))
        return ApocalypseV5HistoryRollback(target = restoredTarget, removedCount = removedCount)
    }

    @Synchronized
    fun clear(saveId: String) {
        if (saveId.isBlank()) return
        prefs.edit().remove(key(saveId)).apply()
    }

    private fun persist(saveId: String, entries: List<ApocalypseV5HistoryEntry>) {
        prefs.edit().putString(
            key(saveId),
            JSONArray().apply { entries.forEach { put(encodeEntry(it)) } }.toString(),
        ).apply()
    }

    private fun key(saveId: String): String = "history_$saveId"

    private companion object {
        const val PREFS_NAME = "apocalypse_isolated_history_v5"
        const val MAX_HISTORY = 60
        const val MAX_SAVE_LOG = 100
    }
}

/**
 * Older apocalypse builds accidentally mirrored roleplay scenes into the companion's shared timeline,
 * memory, and normal chat. Remove those mirrors once the isolated game is opened. The actual apocalypse
 * save/history is intentionally untouched.
 */
internal suspend fun purgeApocalypseMainWorldLeaks(gameStore: LuluGameStore) {
    val leaked = gameStore.state.value.records.filter { record ->
        record.type == LuluGameType.RoleplayAdventure && record.title.startsWith("末世求生")
    }
    leaked.forEach { record ->
        SharedExperienceTimeline.deleteEvent("game-raw-${record.id}")
        SharedExperienceTimeline.deleteEvent("game-reply-${record.id}")
        record.activityMessageId?.let(MigratedDomainStores.chat::deleteMessage)
        runCatching { LuluRepositories.memory.delete("game-${record.id}") }
    }
}

private fun encodeEntry(value: ApocalypseV5HistoryEntry): JSONObject = JSONObject()
    .put("id", value.id)
    .put("saveId", value.saveId)
    .put("sceneBefore", value.sceneBefore)
    .put("partyIdsBefore", JSONArray(value.partyIdsBefore))
    .put("narrationBefore", value.narrationBefore)
    .put("directorBefore", encodeDirectorV5History(value.directorBefore))
    .put("statsBefore", encodeStatsV5History(value.statsBefore))
    .put("logCountBefore", value.logCountBefore)
    .put("action", value.action)
    .put("narrationAfter", value.narrationAfter)
    .put("createdAt", value.createdAt)

private fun decodeEntry(json: JSONObject): ApocalypseV5HistoryEntry? = runCatching {
    val saveId = json.optString("saveId")
    if (saveId.isBlank()) return@runCatching null
    ApocalypseV5HistoryEntry(
        id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
        saveId = saveId,
        sceneBefore = json.optInt("sceneBefore", 1).coerceAtLeast(1),
        partyIdsBefore = json.optJSONArray("partyIdsBefore").historyStrings(),
        narrationBefore = json.optString("narrationBefore").ifBlank { tagApocalypseNarrationAsNarrator(initialApocalypseV3Scene(emptyList())) },
        directorBefore = json.optJSONObject("directorBefore")?.let(::decodeDirectorV5History) ?: initialApocalypseV3Director(),
        statsBefore = json.optJSONObject("statsBefore")?.let(::decodeStatsV5History) ?: ApocalypseV3Stats(),
        logCountBefore = json.optInt("logCountBefore", 0).coerceAtLeast(0),
        action = json.optString("action"),
        narrationAfter = json.optString("narrationAfter"),
        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
    )
}.getOrNull()

private fun encodeDirectorV5History(value: ApocalypseV3Director): JSONObject = JSONObject()
    .put("phase", value.phase)
    .put("location", value.location)
    .put("sceneGoal", value.sceneGoal)
    .put("activeThreads", JSONArray(value.activeThreads))
    .put("hiddenThreads", JSONArray(value.hiddenThreads))
    .put("worldFacts", JSONArray(value.worldFacts))
    .put("longTermPlan", JSONArray(value.longTermPlan))
    .put("factionStates", JSONArray(value.factionStates))
    .put("characterArcs", JSONArray(value.characterArcs))
    .put("foreshadowPlan", JSONArray(value.foreshadowPlan))
    .put("storyThreads", encodeApocalypseStoryThreadsV5(value.storyThreads))
    .put("characterDossiers", encodeApocalypseCharacterDossiersV5(value.characterDossiers))
    .put("foreshadowLedger", encodeApocalypseForeshadowLedgerV5(value.foreshadowLedger))
    .put("recentBeatTypes", JSONArray(value.recentBeatTypes))
    .put("recentEmotionalTurns", JSONArray(value.recentEmotionalTurns))
    .put("awakenedCompanionIds", JSONArray(value.awakenedCompanionIds))
    .put("presentCharacterIds", JSONArray(value.presentCharacterIds))
    .put("presentCharacterStateKnown", value.presentCharacterStateKnown)
    .put("directorRefreshNeeded", value.directorRefreshNeeded)
    .put("locations", JSONArray().apply {
        value.locations.forEach { location ->
            put(
                JSONObject()
                    .put("id", location.id)
                    .put("name", location.name)
                    .put("detail", location.detail)
                    .put("unlocked", location.unlocked),
            )
        }
    })
    .put("assets", JSONArray().apply {
        value.assets.forEach { asset ->
            put(
                JSONObject()
                    .put("id", asset.id)
                    .put("kind", asset.kind.name)
                    .put("title", asset.title)
                    .put("detail", asset.detail)
                    .put("quantity", asset.quantity)
                    .put("tag", asset.tag),
            )
        }
    })
    .put("tension", value.tension)
    .put("dayIndex", value.dayIndex)
    .put("clockMinutes", value.clockMinutes)
    .put("weather", value.weather)
    .put("temperatureC", value.temperatureC)

private fun decodeDirectorV5History(json: JSONObject): ApocalypseV3Director {
    val defaults = initialApocalypseV3Director()
    val restoredDayIndex = json.optInt("dayIndex", defaults.dayIndex).coerceIn(-30, 9999)
    return ApocalypseV3Director(
        phase = apocalypsePhaseForDayV5(restoredDayIndex),
        location = json.optString("location", defaults.location).ifBlank { defaults.location },
        sceneGoal = json.optString("sceneGoal", defaults.sceneGoal).ifBlank { defaults.sceneGoal },
        activeThreads = json.optJSONArray("activeThreads").historyStrings().ifEmpty { defaults.activeThreads },
        hiddenThreads = json.optJSONArray("hiddenThreads").historyStrings().ifEmpty { defaults.hiddenThreads },
        worldFacts = sanitizePrematureWorldFactsV5(
            restoredDayIndex,
            json.optJSONArray("worldFacts").historyStrings().ifEmpty { defaults.worldFacts },
        ),
        locations = json.optJSONArray("locations").historyObjects { item ->
            ApocalypseV3Location(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = item.optString("name").ifBlank { "未知地点" },
                detail = item.optString("detail"),
                unlocked = item.optBoolean("unlocked", true),
            )
        }.ifEmpty { defaults.locations },
        assets = json.optJSONArray("assets").historyObjects { item ->
            ApocalypseV3Asset(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                kind = runCatching { ApocalypseV3AssetKind.valueOf(item.optString("kind")) }.getOrDefault(ApocalypseV3AssetKind.Clue),
                title = item.optString("title").ifBlank { "未知物品" },
                detail = item.optString("detail"),
                quantity = item.optInt("quantity", 1).coerceAtLeast(1),
                tag = item.optString("tag"),
            )
        }.ifEmpty { defaults.assets },
        tension = json.optInt("tension", defaults.tension).coerceIn(1, 10),
        longTermPlan = json.optJSONArray("longTermPlan").historyStrings().ifEmpty { defaults.longTermPlan },
        factionStates = json.optJSONArray("factionStates").historyStrings().ifEmpty { defaults.factionStates },
        characterArcs = json.optJSONArray("characterArcs").historyStrings().ifEmpty { defaults.characterArcs },
        foreshadowPlan = json.optJSONArray("foreshadowPlan").historyStrings().ifEmpty { defaults.foreshadowPlan },
        storyThreads = decodeApocalypseStoryThreadsV5(json.optJSONArray("storyThreads"))
            .ifEmpty { defaults.storyThreads },
        characterDossiers = decodeApocalypseCharacterDossiersV5(json.optJSONArray("characterDossiers")),
        foreshadowLedger = decodeApocalypseForeshadowLedgerV5(json.optJSONArray("foreshadowLedger"))
            .ifEmpty { defaults.foreshadowLedger },
        recentBeatTypes = json.optJSONArray("recentBeatTypes").historyStrings().takeLast(8),
        recentEmotionalTurns = json.optJSONArray("recentEmotionalTurns").historyStrings().takeLast(8),
        awakenedCompanionIds = json.optJSONArray("awakenedCompanionIds").historyStrings().distinct().take(12),
        presentCharacterIds = json.optJSONArray("presentCharacterIds").historyStrings().distinct().take(10),
        presentCharacterStateKnown = json.optBoolean("presentCharacterStateKnown", json.has("presentCharacterIds")),
        directorRefreshNeeded = json.optBoolean("directorRefreshNeeded", false),
        dayIndex = restoredDayIndex,
        clockMinutes = json.optInt("clockMinutes", defaults.clockMinutes).coerceIn(0, 1439),
        weather = json.optString("weather", defaults.weather).ifBlank { defaults.weather },
        temperatureC = json.optInt("temperatureC", defaults.temperatureC).coerceIn(-35, 55),
    )
}

private fun encodeStatsV5History(value: ApocalypseV3Stats): JSONObject = JSONObject()
    .put("money", value.money)
    .put("food", value.food)
    .put("water", value.water)
    .put("medicine", value.medicine)
    .put("materials", value.materials)
    .put("crystalCores", value.crystalCores)
    .put("playerAbilityLevel", value.playerAbilityLevel)
    .put("playerAbilityXp", value.playerAbilityXp)
    .put("baseLevel", value.baseLevel)
    .put("baseName", value.baseName)
    .put("health", value.health)
    .put("stamina", value.stamina)
    .put("infection", value.infection)
    .put("morale", value.morale)

private fun decodeStatsV5History(json: JSONObject): ApocalypseV3Stats = ApocalypseV3Stats(
    money = json.optInt("money", 3_000).coerceIn(0, 9_999_999),
    food = json.optInt("food", 2).coerceAtLeast(0),
    water = json.optInt("water", 2).coerceAtLeast(0),
    medicine = json.optInt("medicine", 1).coerceAtLeast(0),
    materials = json.optInt("materials", 0).coerceAtLeast(0),
    crystalCores = json.optInt("crystalCores", 0).coerceAtLeast(0),
    playerAbilityLevel = json.optInt("playerAbilityLevel", 1).coerceIn(1, 5),
    playerAbilityXp = json.optInt("playerAbilityXp", 0).coerceAtLeast(0),
    baseLevel = json.optInt("baseLevel", 0).coerceIn(0, 5),
    baseName = json.optString("baseName", "尚未建立").ifBlank { "尚未建立" },
    health = json.optInt("health", 100).coerceIn(0, 100),
    stamina = json.optInt("stamina", 85).coerceIn(0, 100),
    infection = json.optInt("infection", 0).coerceIn(0, 100),
    morale = json.optInt("morale", 72).coerceIn(0, 100),
)

private fun JSONArray?.historyStrings(): List<String> = buildList {
    val array = this@historyStrings ?: return@buildList
    for (index in 0 until array.length()) {
        array.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }
}

private fun <T> JSONArray?.historyObjects(mapper: (JSONObject) -> T): List<T> = buildList {
    val array = this@historyObjects ?: return@buildList
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let { item -> runCatching { mapper(item) }.getOrNull()?.let(::add) }
    }
}
