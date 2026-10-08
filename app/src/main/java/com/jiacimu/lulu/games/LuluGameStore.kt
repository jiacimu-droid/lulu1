package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/** Product-parity game identifiers from lulu/master plus Lulu1 additions. */
enum class LuluGameType {
    PerfectMan,
    RoleplayAdventure,
    TurtleSoup,
    RapportQuiz,
    RockPaperScissors,
    YachtDice,
    Gomoku,
    MemoryMatch,
    DeepSeaJourney,
    MoodGuess,
}

data class LuluGameRecord(
    val id: String = UUID.randomUUID().toString(),
    val type: LuluGameType,
    val title: String,
    val score: Int,
    val rewardCoins: Int,
    val characterId: String,
    val playedWithCharacter: Boolean,
    val summary: String,
    val detailsJson: String = "{}",
    val characterReply: String = "",
    val activityMessageId: String? = null,
    val createdAt: Instant = Instant.now(),
)

data class AutonomousGameResult(
    val recordId: String,
    val gameId: String,
    val title: String,
    val score: Int,
    val summary: String,
    val detailsJson: String,
)

data class MemoryMatchState(
    val cards: List<String> = listOf("🌙", "🍰", "🎧", "🌸", "🧸", "☕", "🌙", "🍰", "🎧", "🌸", "🧸", "☕").shuffled(),
    val opened: Set<Int> = emptySet(),
    val matched: Set<Int> = emptySet(),
    val moves: Int = 0,
    val userPairs: Int = 0,
    val characterPairs: Int = 0,
    val turn: MemoryTurn = MemoryTurn.User,
    val lastEvent: String = "轮到你翻牌",
    val finished: Boolean = false,
)

enum class MemoryTurn { User, Character }

data class MoodGuessRound(
    val clue: String,
    val options: List<String>,
    val answer: String,
)

data class LuluGameState(
    val coins: Int = 0,
    val selectedCharacterId: String = "lulu",
    val selectedCharacterIds: List<String> = listOf("lulu"),
    val playWithCharacter: Boolean = true,
    val memoryMatch: MemoryMatchState = MemoryMatchState(),
    val moodRound: MoodGuessRound = defaultMoodRounds().first(),
    val moodAnswered: String? = null,
    val records: List<LuluGameRecord> = emptyList(),
)

class LuluGameStore internal constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(loadState())
    val state: StateFlow<LuluGameState> = mutableState.asStateFlow()

    fun setPlayWithCharacter(enabled: Boolean) = mutate { it.copy(playWithCharacter = enabled) }

    fun selectCharacter(characterId: String) {
        if (characterId.isBlank()) return
        mutate { it.copy(selectedCharacterId = characterId, selectedCharacterIds = listOf(characterId)) }
    }

    fun selectCharacters(characterIds: List<String>) {
        val clean = characterIds.map(String::trim).filter(String::isNotBlank).distinct()
        mutate {
            if (clean.isEmpty()) {
                it.copy(selectedCharacterId = "", selectedCharacterIds = emptyList(), playWithCharacter = false)
            } else {
                it.copy(selectedCharacterId = clean.first(), selectedCharacterIds = clean, playWithCharacter = true)
            }
        }
    }

    fun openMemoryCard(index: Int) {
        if (mutableState.value.memoryMatch.turn != MemoryTurn.User) return
        openMemoryCard(index, MemoryTurn.User)
    }

    fun openCharacterMemoryCard(index: Int) {
        if (mutableState.value.memoryMatch.turn != MemoryTurn.Character) return
        openMemoryCard(index, MemoryTurn.Character)
    }

    private fun openMemoryCard(index: Int, player: MemoryTurn) {
        val current = mutableState.value.memoryMatch
        if (current.finished || index !in current.cards.indices || index in current.matched || index in current.opened) return
        if (player == MemoryTurn.User && mutableState.value.playWithCharacter) {
            com.jiacimu.lulu.data.CompanionOnlineStore.recordActivity(mutableState.value.selectedCharacterId)
        }
        val opened = current.opened + index
        if (opened.size < 2) {
            mutate { it.copy(memoryMatch = current.copy(opened = opened)) }
            return
        }
        val pair = opened.toList()
        val foundPair = current.cards[pair[0]] == current.cards[pair[1]]
        val matched = if (foundPair) current.matched + opened else current.matched
        val finished = matched.size == current.cards.size
        val next = current.copy(
            opened = if (foundPair) emptySet() else opened,
            matched = matched,
            moves = current.moves + 1,
            userPairs = current.userPairs + if (foundPair && player == MemoryTurn.User) 1 else 0,
            characterPairs = current.characterPairs + if (foundPair && player == MemoryTurn.Character) 1 else 0,
            lastEvent = if (foundPair) {
                if (player == MemoryTurn.User) "你配对成功，可以继续翻" else "角色配对成功，继续翻牌"
            } else {
                "没有配对，记住它们的位置"
            },
            finished = finished,
        )
        mutate { it.copy(memoryMatch = next) }
        if (finished) {
            val score = next.userPairs * 100
            recordExternalGame(
                LuluGameType.MemoryMatch,
                "记忆配对",
                score,
                0,
                "共翻了 ${next.moves} 轮；你找到 ${next.userPairs} 对，角色找到 ${next.characterPairs} 对。",
            )
        }
    }

    fun closeUnmatchedCards() {
        val current = mutableState.value.memoryMatch
        if (current.opened.any { it !in current.matched }) {
            val nextTurn = if (current.turn == MemoryTurn.User) MemoryTurn.Character else MemoryTurn.User
            mutate {
                it.copy(
                    memoryMatch = current.copy(
                        opened = emptySet(),
                        turn = nextTurn,
                        lastEvent = if (nextTurn == MemoryTurn.User) "轮到你翻牌" else "轮到角色翻牌",
                    ),
                )
            }
        }
    }

    fun resetMemoryMatch() = mutate { it.copy(memoryMatch = MemoryMatchState()) }

    fun answerMood(option: String) {
        if (mutableState.value.moodAnswered != null) return
        val round = mutableState.value.moodRound
        val correct = option == round.answer
        mutate { it.copy(moodAnswered = option) }
        recordExternalGame(
            LuluGameType.MoodGuess,
            "心情猜猜看",
            if (correct) 100 else 30,
            if (correct) 10 else 3,
            if (correct) "判断正确：${round.answer}" else "选择了 $option，正确答案是 ${round.answer}",
        )
    }

    fun resetMoodGuess() = mutate { it.copy(moodRound = defaultMoodRounds().random(), moodAnswered = null) }

    fun recordExternalGame(
        type: LuluGameType,
        title: String,
        score: Int,
        reward: Int,
        summary: String,
        detailsJson: String = "{}",
        characterIdOverride: String? = null,
        playedWithCharacterOverride: Boolean? = null,
        createdAtOverride: Instant? = null,
    ): String {
        val snapshot = mutableState.value
        val record = LuluGameRecord(
            type = type,
            title = title,
            score = score.coerceAtLeast(0),
            rewardCoins = reward.coerceAtLeast(0),
            characterId = characterIdOverride ?: snapshot.selectedCharacterId,
            playedWithCharacter = playedWithCharacterOverride ?: snapshot.playWithCharacter,
            summary = summary,
            detailsJson = detailsJson,
            createdAt = createdAtOverride ?: Instant.now(),
        )
        mutate { state ->
            state.copy(
                coins = state.coins + record.rewardCoins,
                records = (listOf(record) + state.records).take(MAX_RECORDS),
            )
        }
        if (record.playedWithCharacter) {
            SharedExperienceTimeline.record(
                eventId = "game-raw-${record.id}",
                characterId = record.characterId,
                channel = "共同游戏《${record.title}》",
                speaker = "游戏记录",
                content = buildString {
                    append("${record.summary}\n得分：${record.score}")
                    if (record.detailsJson != "{}") append("\n真实过程数据：${record.detailsJson}")
                },
                occurredAt = record.createdAt,
            )
            SharedExperienceTimeline.remember(
                memoryId = "game-${record.id}",
                characterId = record.characterId,
                label = "共同游戏《${record.title}》",
                detail = "${record.summary}；得分 ${record.score}。",
                occurredAt = record.createdAt,
                strength = 5,
                source = "game:${record.type.name}",
            )
            val conversation = MigratedDomainStores.chat.conversations.value
                .filter { it.characterId == record.characterId && it.parentConversationId == null && !it.id.endsWith("-study-focus") }
                .maxByOrNull { it.updatedAt }
                ?: MigratedDomainStores.chat.ensureConversation(record.characterId, "共同聊天")
            val activityMessage = MigratedDomainStores.chat.appendSystemMessage(conversation.id, "[共同活动] 刚刚一起玩了《${record.title}》")
            mutate { state ->
                state.copy(records = state.records.map { item ->
                    if (item.id == record.id) item.copy(activityMessageId = activityMessage.id) else item
                })
            }
        } else if (record.characterId.isNotBlank()) {
            SharedExperienceTimeline.record(
                eventId = "game-solo-${record.id}",
                characterId = record.characterId,
                channel = "独自游戏《${record.title}》",
                speaker = "游戏馆记录",
                content = buildString {
                    append("${record.summary}\n得分：${record.score}")
                    if (record.detailsJson != "{}") append("\n真实过程数据：${record.detailsJson}")
                },
                occurredAt = record.createdAt,
            )
        }
        return record.id
    }

    fun playAutonomousGame(
        characterId: String,
        gameId: String,
        now: Instant = Instant.now(),
    ): AutonomousGameResult? {
        if (characterId.isBlank()) return null
        val normalized = gameId.trim().lowercase()
        val random = Random("$characterId:$normalized:${now.toEpochMilli()}".hashCode())
        val played = when (normalized) {
            "memory_match" -> {
                val cards = listOf("🌙", "🍰", "🎧", "🌸", "🧸", "☕", "🌙", "🍰", "🎧", "🌸", "🧸", "☕")
                    .shuffled(random)
                val unmatched = cards.indices.toMutableSet()
                val known = mutableMapOf<String, Int>()
                val turns = JSONArray()
                var moves = 0
                while (unmatched.isNotEmpty()) {
                    val first = unmatched.random(random)
                    val firstValue = cards[first]
                    val rememberedPair = known[firstValue]?.takeIf { it in unmatched && it != first }
                    val second = rememberedPair ?: unmatched.filterNot { it == first }.random(random)
                    val matched = cards[first] == cards[second]
                    turns.put(
                        JSONObject()
                            .put("first", first)
                            .put("second", second)
                            .put("firstValue", cards[first])
                            .put("secondValue", cards[second])
                            .put("matched", matched),
                    )
                    moves += 1
                    if (matched) {
                        unmatched.remove(first)
                        unmatched.remove(second)
                        known.remove(firstValue)
                    } else {
                        known[firstValue] = first
                        known[cards[second]] = second
                    }
                }
                val score = (1_200 - (moves - 6).coerceAtLeast(0) * 60).coerceAtLeast(120)
                AutonomousGameDraft(
                    gameId = normalized,
                    type = LuluGameType.MemoryMatch,
                    title = "记忆配对",
                    score = score,
                    summary = "独自完成一局记忆配对：用 $moves 轮找齐 6 对卡片，得分 $score。",
                    detailsJson = JSONObject()
                        .put("game", "memory_match")
                        .put("cards", JSONArray(cards))
                        .put("turns", turns)
                        .toString(),
                )
            }
            else -> return null
        }
        val recordId = recordExternalGame(
            type = played.type,
            title = played.title,
            score = played.score,
            reward = 0,
            summary = played.summary,
            detailsJson = played.detailsJson,
            characterIdOverride = characterId,
            playedWithCharacterOverride = false,
            createdAtOverride = now,
        )
        return AutonomousGameResult(
            recordId = recordId,
            gameId = played.gameId,
            title = played.title,
            score = played.score,
            summary = played.summary,
            detailsJson = played.detailsJson,
        )
    }

    fun attachCharacterReply(recordId: String, reply: String) {
        if (reply.isBlank()) return
        val record = mutableState.value.records.firstOrNull { it.id == recordId } ?: return
        mutate { state ->
            state.copy(records = state.records.map { record ->
                if (record.id == recordId) record.copy(characterReply = reply.trim()) else record
            })
        }
        SharedExperienceTimeline.record(
            eventId = "game-reply-$recordId",
            characterId = record.characterId,
            channel = "共同游戏《${record.title}》",
            speaker = "角色",
            content = reply,
            occurredAt = Instant.now(),
        )
    }

    fun clearRecords() {
        val records = mutableState.value.records
        records.forEach { record ->
            SharedExperienceTimeline.deleteEvent("game-raw-${record.id}")
            SharedExperienceTimeline.deleteEvent("game-reply-${record.id}")
            record.activityMessageId?.let(MigratedDomainStores.chat::deleteMessage)
            scope.launch { LuluRepositories.memory.delete("game-${record.id}") }
        }
        mutate { it.copy(records = emptyList()) }
    }

    private fun mutate(transform: (LuluGameState) -> LuluGameState) {
        mutableState.update(transform)
        persist(mutableState.value)
    }

    private fun persist(state: LuluGameState) {
        val json = JSONObject()
            .put("coins", state.coins)
            .put("selectedCharacterId", state.selectedCharacterId)
            .put("selectedCharacterIds", JSONArray(state.selectedCharacterIds))
            .put("playWithCharacter", state.playWithCharacter)
            .put(
                "records",
                JSONArray().apply {
                    state.records.forEach { record ->
                        put(
                            JSONObject()
                                .put("id", record.id)
                                .put("type", record.type.name)
                                .put("title", record.title)
                                .put("score", record.score)
                                .put("rewardCoins", record.rewardCoins)
                                .put("characterId", record.characterId)
                                .put("playedWithCharacter", record.playedWithCharacter)
                                .put("summary", record.summary)
                                .put("detailsJson", record.detailsJson)
                                .put("characterReply", record.characterReply)
                                .put("activityMessageId", record.activityMessageId)
                                .put("createdAt", record.createdAt.toEpochMilli()),
                        )
                    }
                },
            )
        prefs.edit().putString(KEY_STATE, json.toString()).apply()
    }

    private fun loadState(): LuluGameState = runCatching {
        val raw = prefs.getString(KEY_STATE, null) ?: return@runCatching LuluGameState()
        val json = JSONObject(raw)
        val recordsJson = json.optJSONArray("records") ?: JSONArray()
        val records = buildList {
            for (index in 0 until recordsJson.length()) {
                val item = recordsJson.optJSONObject(index) ?: continue
                val type = runCatching { LuluGameType.valueOf(item.optString("type")) }.getOrNull() ?: continue
                add(
                    LuluGameRecord(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        type = type,
                        title = item.optString("title"),
                        score = item.optInt("score"),
                        rewardCoins = item.optInt("rewardCoins"),
                        characterId = item.optString("characterId", "lulu"),
                        playedWithCharacter = item.optBoolean("playedWithCharacter", true),
                        summary = item.optString("summary"),
                        detailsJson = item.optString("detailsJson", "{}"),
                        characterReply = item.optString("characterReply"),
                        activityMessageId = item.optString("activityMessageId").takeIf(String::isNotBlank),
                        createdAt = Instant.ofEpochMilli(item.optLong("createdAt", System.currentTimeMillis())),
                    ),
                )
            }
        }
        val playWithCharacter = json.optBoolean("playWithCharacter", true)
        val storedCharacterIds = json.optJSONArray("selectedCharacterIds")?.let { array ->
            buildList { for (index in 0 until array.length()) add(array.optString(index)) }
                .filter(String::isNotBlank)
        }.orEmpty()
        LuluGameState(
            coins = json.optInt("coins"),
            selectedCharacterId = json.optString("selectedCharacterId", "lulu"),
            selectedCharacterIds = if (playWithCharacter) {
                storedCharacterIds.ifEmpty { listOf(json.optString("selectedCharacterId", "lulu")) }
            } else {
                emptyList()
            },
            playWithCharacter = playWithCharacter,
            records = records,
        )
    }.getOrElse { LuluGameState() }

    private data class AutonomousGameDraft(
        val gameId: String,
        val type: LuluGameType,
        val title: String,
        val score: Int,
        val summary: String,
        val detailsJson: String,
    )

    private companion object {
        const val PREFS_NAME = "lulu_games"
        const val KEY_STATE = "state"
        const val MAX_RECORDS = 200
    }
}

private fun defaultMoodRounds(): List<MoodGuessRound> = listOf(
    MoodGuessRound(
        clue = "角色把一杯热茶放在桌边，安静等你学习结束。",
        options = listOf("安心", "生气", "慌张", "无聊"),
        answer = "安心",
    ),
    MoodGuessRound(
        clue = "你很久没有回复，角色看了几次时间，却没有连续催促。",
        options = listOf("担心", "愤怒", "轻松", "骄傲"),
        answer = "担心",
    ),
    MoodGuessRound(
        clue = "你完成今天最后一个番茄钟，角色马上记录了这个结果。",
        options = listOf("开心", "害怕", "失望", "困惑"),
        answer = "开心",
    ),
)

object LuluGames {
    private var storeInternal: LuluGameStore? = null
    val store: LuluGameStore
        get() = checkNotNull(storeInternal) { "LuluGames 尚未初始化" }

    fun initialize(context: Context) {
        if (storeInternal == null) storeInternal = LuluGameStore(context.applicationContext)
    }
}
