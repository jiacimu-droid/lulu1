package com.jiacimu.lulu.games

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

enum class LuluGameType { SignalHunt, MemoryMatch, MoodGuess }

data class LuluGameRecord(
    val id: String = UUID.randomUUID().toString(),
    val type: LuluGameType,
    val title: String,
    val score: Int,
    val rewardCoins: Int,
    val playedWithCharacter: Boolean,
    val summary: String,
    val createdAt: Instant = Instant.now(),
)

data class SignalHuntState(
    val target: Int = (1..9).random(),
    val attempts: List<Int> = emptyList(),
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
    val moodRound: MoodGuessRound = MoodGuessRound(
        clue = "露露今天把热茶放在桌边，安静等主人学习结束。她更接近哪种心情？",
        options = listOf("安心", "生气", "慌张", "无聊"),
        answer = "安心",
    ),
    val moodAnswered: String? = null,
    val records: List<LuluGameRecord> = emptyList(),
)

class LuluGameStore {
    private val mutableState = MutableStateFlow(LuluGameState())
    val state: StateFlow<LuluGameState> = mutableState.asStateFlow()

    fun setPlayWithCharacter(enabled: Boolean) {
        mutableState.update { it.copy(playWithCharacter = enabled) }
    }

    fun guessSignal(position: Int) {
        require(position in 1..9)
        val current = mutableState.value.signalHunt
        if (current.finished || position in current.attempts) return
        val attempts = current.attempts + position
        if (position == current.target) {
            val score = (120 - (attempts.size - 1) * 15).coerceAtLeast(30)
            finishGame(
                type = LuluGameType.SignalHunt,
                title = "信号追踪",
                score = score,
                reward = 12,
                summary = "在第 ${attempts.size} 次定位到信号 ${current.target}",
            )
            mutableState.update { it.copy(signalHunt = current.copy(attempts = attempts, finished = true)) }
        } else {
            mutableState.update { it.copy(signalHunt = current.copy(attempts = attempts)) }
        }
    }

    fun resetSignalHunt() {
        mutableState.update { it.copy(signalHunt = SignalHuntState()) }
    }

    fun openMemoryCard(index: Int) {
        val current = mutableState.value.memoryMatch
        if (current.finished || index !in current.cards.indices || index in current.matched || index in current.opened) return
        val opened = current.opened + index
        if (opened.size < 2) {
            mutableState.update { it.copy(memoryMatch = current.copy(opened = opened)) }
            return
        }
        val pair = opened.toList()
        val matched = if (current.cards[pair[0]] == current.cards[pair[1]]) current.matched + opened else current.matched
        val finished = matched.size == current.cards.size
        val next = current.copy(opened = if (pair.toSet().all { it in matched }) emptySet() else opened, matched = matched, moves = current.moves + 1, finished = finished)
        mutableState.update { it.copy(memoryMatch = next) }
        if (finished) {
            val score = (160 - next.moves * 10).coerceAtLeast(40)
            finishGame(LuluGameType.MemoryMatch, "记忆配对", score, 15, "用 ${next.moves} 步完成全部配对")
        }
    }

    fun closeUnmatchedCards() {
        val current = mutableState.value.memoryMatch
        if (current.opened.any { it !in current.matched }) {
            mutableState.update { it.copy(memoryMatch = current.copy(opened = emptySet())) }
        }
    }

    fun resetMemoryMatch() {
        mutableState.update { it.copy(memoryMatch = MemoryMatchState()) }
    }

    fun answerMood(option: String) {
        if (mutableState.value.moodAnswered != null) return
        val round = mutableState.value.moodRound
        val correct = option == round.answer
        mutableState.update { it.copy(moodAnswered = option) }
        finishGame(
            LuluGameType.MoodGuess,
            "心情猜猜看",
            if (correct) 100 else 30,
            if (correct) 10 else 3,
            if (correct) "读懂了露露的心情" else "选择了 $option，正确答案是 ${round.answer}",
        )
    }

    fun resetMoodGuess() {
        val rounds = listOf(
            MoodGuessRound("露露悄悄把主人没做完的计划移到明天，还留了一句‘别急’。", listOf("体贴", "冷漠", "烦躁", "得意"), "体贴"),
            MoodGuessRound("主人很久没回消息，露露看了几次时间，却没有连续催促。", listOf("担心", "愤怒", "轻松", "骄傲"), "担心"),
            MoodGuessRound("主人完成今天最后一个番茄钟，露露立刻发来一个小烟花。", listOf("开心", "害怕", "失望", "困惑"), "开心"),
        )
        mutableState.update { it.copy(moodRound = rounds.random(), moodAnswered = null) }
    }

    private fun finishGame(type: LuluGameType, title: String, score: Int, reward: Int, summary: String) {
        mutableState.update { state ->
            val record = LuluGameRecord(
                type = type,
                title = title,
                score = score,
                rewardCoins = reward,
                playedWithCharacter = state.playWithCharacter,
                summary = summary,
            )
            state.copy(coins = state.coins + reward, records = listOf(record) + state.records)
        }
    }
}

object LuluGames {
    val store = LuluGameStore()
}
