package com.jiacimu.lulu.games

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Product-parity game identifiers from lulu/master plus Lulu1 additions. */
enum class LuluGameType {
    SignalHunt,
    PerfectMan,
    RoleplayAdventure,
    TurtleSoup,
    RapportQuiz,
    RockPaperScissors,
    YachtDice,
    Gomoku,
    MemoryMatch,
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
    val createdAt: Instant = Instant.now(),
)

data class SignalHuntMove(
    val cell: Int,
    val foundSignal: Boolean,
    val points: Int,
)

data class SignalHuntState(
    val signalCells: Set<Int> = (0..8).shuffled().take(3).toSet(),
    val moves: List<SignalHuntMove> = emptyList(),
    val started: Boolean = false,
    val finished: Boolean = false,
)

data class MemoryMatchState(
    val cards: List<String> = listOf("露", "书", "月", "茶", "露", "书", "月", "茶").shuffled(),
    val opened: Set<Int> = emptySet(),
    val matched: Set<Int> = emptySet(),
    val moves: Int = 0,
    val finished: Boolean = false,
)

data class MoodGuessRound(
    val clue: String,
    val options: List<String>,
    val answer: String,
)

data class LuluGameState(
    val coins: Int = 0,
    val selectedCharacterId: String = "lulu",
    val playWithCharacter: Boolean = true,
    val signalHunt: SignalHuntState = SignalHuntState(),
    val memoryMatch: MemoryMatchState = MemoryMatchState(),
    val moodRound: MoodGuessRound = defaultMoodRounds().first(),
    val moodAnswered: String? = null,
    val records: List<LuluGameRecord> = emptyList(),
)

class LuluGameStore internal constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(loadState())
    val state: StateFlow<LuluGameState> = mutableState.asStateFlow()

    fun setPlayWithCharacter(enabled: Boolean) = mutate { it.copy(playWithCharacter = enabled) }

    fun selectCharacter(characterId: String) {
        if (characterId.isBlank()) return
        mutate { it.copy(selectedCharacterId = characterId) }
    }

    fun startSignalHunt() = mutate {
        it.copy(signalHunt = SignalHuntState(started = true))
    }

    fun guessSignal(cell: Int) {
        require(cell in 0..8)
        val current = mutableState.value.signalHunt
        if (!current.started || current.finished || current.moves.any { it.cell == cell }) return
        val found = cell in current.signalCells
        val streak = if (found && current.moves.lastOrNull()?.foundSignal == true) 2 else 1
        val move = SignalHuntMove(cell, found, if (found) 20 + (streak - 1) * 5 else 0)
        val moves = current.moves + move
        val finished = moves.count { it.foundSignal } >= 3 || moves.size >= 5
        mutate { it.copy(signalHunt = current.copy(moves = moves, finished = finished)) }
        if (finished) {
            val foundCount = moves.count { it.foundSignal }
            val score = moves.sumOf { it.points }
            recordExternalGame(
                type = LuluGameType.SignalHunt,
                title = "信号追踪",
                score = score,
                reward = foundCount * 5,
                summary = "探测 ${moves.size} 格，找到 $foundCount/3 个信号，得分 $score",
                detailsJson = JSONObject()
                    .put("game", "signal_hunt")
                    .put("score", score)
                    .put("max_score", 75)
                    .put(
                        "moves",
                        JSONArray().apply {
                            moves.forEach { item ->
                                put(
                                    JSONObject()
                                        .put("cell", item.cell)
                                        .put("found_signal", item.foundSignal)
                                        .put("points", item.points),
                                )
                            }
                        },
                    )
                    .toString(),
            )
        }
    }

    fun resetSignalHunt() = mutate { it.copy(signalHunt = SignalHuntState()) }

    fun openMemoryCard(index: Int) {
        val current = mutableState.value.memoryMatch
        if (current.finished || index !in current.cards.indices || index in current.matched || index in current.opened) return
        val opened = current.opened + index
        if (opened.size < 2) {
            mutate { it.copy(memoryMatch = current.copy(opened = opened)) }
            return
        }
        val pair = opened.toList()
        val matched = if (current.cards[pair[0]] == current.cards[pair[1]]) current.matched + opened else current.matched
        val finished = matched.size == current.cards.size
        val next = current.copy(
            opened = if (opened.all { it in matched }) emptySet() else opened,
            matched = matched,
            moves = current.moves + 1,
            finished = finished,
        )
        mutate { it.copy(memoryMatch = next) }
        if (finished) {
            val score = (160 - next.moves * 10).coerceAtLeast(40)
            recordExternalGame(LuluGameType.MemoryMatch, "记忆配对", score, 15, "用 ${next.moves} 步完成全部配对")
        }
    }

    fun closeUnmatchedCards() {
        val current = mutableState.value.memoryMatch
        if (current.opened.any { it !in current.matched }) {
            mutate { it.copy(memoryMatch = current.copy(opened = emptySet())) }
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
    ): String {
        val snapshot = mutableState.value
        val record = LuluGameRecord(
            type = type,
            title = title,
            score = score.coerceAtLeast(0),
            rewardCoins = reward.coerceAtLeast(0),
            characterId = snapshot.selectedCharacterId,
            playedWithCharacter = snapshot.playWithCharacter,
            summary = summary,
            detailsJson = detailsJson,
        )
        mutate { state ->
            state.copy(
                coins = state.coins + record.rewardCoins,
                records = (listOf(record) + state.records).take(MAX_RECORDS),
            )
        }
        return record.id
    }

    fun attachCharacterReply(recordId: String, reply: String) {
        if (reply.isBlank()) return
        mutate { state ->
            state.copy(records = state.records.map { record ->
                if (record.id == recordId) record.copy(characterReply = reply.trim()) else record
            })
        }
    }

    fun clearRecords() = mutate { it.copy(records = emptyList()) }

    private fun mutate(transform: (LuluGameState) -> LuluGameState) {
        mutableState.update(transform)
        persist(mutableState.value)
    }

    private fun persist(state: LuluGameState) {
        val json = JSONObject()
            .put("coins", state.coins)
            .put("selectedCharacterId", state.selectedCharacterId)
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
                        createdAt = Instant.ofEpochMilli(item.optLong("createdAt", System.currentTimeMillis())),
                    ),
                )
            }
        }
        LuluGameState(
            coins = json.optInt("coins"),
            selectedCharacterId = json.optString("selectedCharacterId", "lulu"),
            playWithCharacter = json.optBoolean("playWithCharacter", true),
            records = records,
        )
    }.getOrElse { LuluGameState() }

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
