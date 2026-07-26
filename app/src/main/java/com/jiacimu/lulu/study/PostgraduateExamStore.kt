package com.jiacimu.lulu.study

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

data class StudyTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val pomodoroCount: Int,
    val completedPomodoros: Int = 0,
    val completed: Boolean = false,
)

data class StudyDay(
    val date: LocalDate = LocalDate.now(),
    val tasks: List<StudyTask> = defaultStudyTasks(),
    val studyMinutes: Int = 0,
    val vocabularyReviewed: Int = 0,
)

data class TicketWallet(
    val singleTickets: Int = 3,
    val tenPullTickets: Int = 1,
    val purpleFragments: Int = 0,
    val blueFragments: Int = 0,
)

enum class DrawRewardType { PurpleFragment, Douyin, SideStory, BlueFragment, StudyCoin }

data class DrawReward(
    val id: String = UUID.randomUUID().toString(),
    val type: DrawRewardType,
    val title: String,
    val description: String,
)

data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val target: Int,
    val progress: Int = 0,
    val claimed: Boolean = false,
) {
    val unlocked: Boolean get() = progress >= target
}

data class StudyProfile(
    val praisePoints: Int = 0,
    val studyCoins: Int = 0,
    val totalStudyMinutes: Int = 0,
    val totalPomodoros: Int = 0,
    val streakDays: Int = 0,
)

data class PomodoroState(
    val durationMinutes: Int = 25,
    val remainingSeconds: Int = durationMinutes * 60,
    val running: Boolean = false,
    val voiceEnabled: Boolean = false,
)

data class ExamAppState(
    val today: StudyDay = StudyDay(),
    val wallet: TicketWallet = TicketWallet(),
    val achievements: List<Achievement> = defaultAchievements(),
    val profile: StudyProfile = StudyProfile(),
    val pomodoro: PomodoroState = PomodoroState(),
    val drawHistory: List<DrawReward> = emptyList(),
)

interface PostgraduateExamStore {
    val state: StateFlow<ExamAppState>
    fun addTask(title: String, pomodoros: Int)
    fun toggleTask(taskId: String)
    fun reviewVocabulary(count: Int)
    fun setPomodoroDuration(minutes: Int)
    fun togglePomodoro()
    fun togglePomodoroVoice()
    fun tick()
    fun completePomodoro()
    fun drawSingle(): List<DrawReward>
    fun drawTen(): List<DrawReward>
    fun claimAchievement(id: String)
}

class InMemoryPostgraduateExamStore : PostgraduateExamStore {
    private val mutableState = MutableStateFlow(ExamAppState())
    override val state: StateFlow<ExamAppState> = mutableState.asStateFlow()

    override fun addTask(title: String, pomodoros: Int) {
        val clean = title.trim()
        if (clean.isBlank()) return
        mutableState.update { current ->
            current.copy(today = current.today.copy(tasks = current.today.tasks + StudyTask(title = clean, pomodoroCount = pomodoros.coerceIn(1, 12))))
        }
    }

    override fun toggleTask(taskId: String) {
        mutableState.update { current ->
            current.copy(today = current.today.copy(tasks = current.today.tasks.map { task ->
                if (task.id == taskId) task.copy(completed = !task.completed) else task
            }))
        }
        refreshAchievements()
    }

    override fun reviewVocabulary(count: Int) {
        mutableState.update { current ->
            current.copy(today = current.today.copy(vocabularyReviewed = current.today.vocabularyReviewed + count.coerceAtLeast(0)))
        }
        refreshAchievements()
    }

    override fun setPomodoroDuration(minutes: Int) {
        mutableState.update { current ->
            current.copy(pomodoro = PomodoroState(durationMinutes = minutes, remainingSeconds = minutes * 60, voiceEnabled = current.pomodoro.voiceEnabled))
        }
    }

    override fun togglePomodoro() {
        mutableState.update { it.copy(pomodoro = it.pomodoro.copy(running = !it.pomodoro.running)) }
    }

    override fun togglePomodoroVoice() {
        mutableState.update { it.copy(pomodoro = it.pomodoro.copy(voiceEnabled = !it.pomodoro.voiceEnabled)) }
    }

    override fun tick() {
        val snapshot = mutableState.value.pomodoro
        if (!snapshot.running) return
        if (snapshot.remainingSeconds <= 1) completePomodoro()
        else mutableState.update { it.copy(pomodoro = it.pomodoro.copy(remainingSeconds = it.pomodoro.remainingSeconds - 1)) }
    }

    override fun completePomodoro() {
        mutableState.update { current ->
            val minutes = current.pomodoro.durationMinutes
            val updatedTasks = current.today.tasks.mapIndexed { index, task ->
                if (index == current.today.tasks.indexOfFirst { !it.completed } && !task.completed) {
                    val done = (task.completedPomodoros + 1).coerceAtMost(task.pomodoroCount)
                    task.copy(completedPomodoros = done, completed = done >= task.pomodoroCount)
                } else task
            }
            current.copy(
                today = current.today.copy(studyMinutes = current.today.studyMinutes + minutes, tasks = updatedTasks),
                profile = current.profile.copy(
                    praisePoints = current.profile.praisePoints + 1,
                    studyCoins = current.profile.studyCoins + 5,
                    totalStudyMinutes = current.profile.totalStudyMinutes + minutes,
                    totalPomodoros = current.profile.totalPomodoros + 1,
                ),
                pomodoro = current.pomodoro.copy(remainingSeconds = current.pomodoro.durationMinutes * 60, running = false),
            )
        }
        refreshAchievements()
    }

    override fun drawSingle(): List<DrawReward> {
        if (mutableState.value.wallet.singleTickets <= 0) return emptyList()
        mutableState.update { it.copy(wallet = it.wallet.copy(singleTickets = it.wallet.singleTickets - 1)) }
        return performDraw(1)
    }

    override fun drawTen(): List<DrawReward> {
        if (mutableState.value.wallet.tenPullTickets <= 0) return emptyList()
        mutableState.update { it.copy(wallet = it.wallet.copy(tenPullTickets = it.wallet.tenPullTickets - 1)) }
        return performDraw(10)
    }

    override fun claimAchievement(id: String) {
        mutableState.update { current ->
            val achievement = current.achievements.firstOrNull { it.id == id } ?: return@update current
            if (!achievement.unlocked || achievement.claimed) return@update current
            current.copy(
                achievements = current.achievements.map { if (it.id == id) it.copy(claimed = true) else it },
                wallet = current.wallet.copy(singleTickets = current.wallet.singleTickets + 1),
                profile = current.profile.copy(studyCoins = current.profile.studyCoins + 20),
            )
        }
    }

    private fun performDraw(count: Int): List<DrawReward> {
        val rewards = List(count) { rollReward() }
        mutableState.update { current ->
            var wallet = current.wallet
            var profile = current.profile
            rewards.forEach { reward ->
                when (reward.type) {
                    DrawRewardType.PurpleFragment -> wallet = wallet.copy(purpleFragments = wallet.purpleFragments + 1)
                    DrawRewardType.BlueFragment -> wallet = wallet.copy(blueFragments = wallet.blueFragments + 1)
                    DrawRewardType.StudyCoin -> profile = profile.copy(studyCoins = profile.studyCoins + 10)
                    else -> Unit
                }
            }
            current.copy(wallet = wallet, profile = profile, drawHistory = (rewards + current.drawHistory).take(100))
        }
        return rewards
    }

    private fun rollReward(): DrawReward {
        val value = Random.nextDouble()
        return when {
            value < 0.01 -> DrawReward(type = DrawRewardType.SideStory, title = "番外小剧场", description = "解锁一段露露陪伴剧情")
            value < 0.06 -> DrawReward(type = DrawRewardType.Douyin, title = "抖音时刻", description = "解锁角色短视频灵感")
            value < 0.12 -> DrawReward(type = DrawRewardType.PurpleFragment, title = "紫色碎片", description = "稀有收藏碎片")
            value < 0.52 -> DrawReward(type = DrawRewardType.BlueFragment, title = "蓝色碎片", description = "即使已满也会展示本次抽中物")
            else -> DrawReward(type = DrawRewardType.StudyCoin, title = "学习币 ×10", description = "用于后续奖励商店")
        }
    }

    private fun refreshAchievements() {
        mutableState.update { current ->
            val completedTasks = current.today.tasks.count { it.completed }
            val metrics = mapOf(
                "first_pomodoro" to current.profile.totalPomodoros,
                "pomodoro_5" to current.profile.totalPomodoros,
                "pomodoro_20" to current.profile.totalPomodoros,
                "study_60" to current.profile.totalStudyMinutes,
                "study_300" to current.profile.totalStudyMinutes,
                "vocab_50" to current.today.vocabularyReviewed,
                "vocab_200" to current.today.vocabularyReviewed,
                "task_1" to completedTasks,
                "task_5" to completedTasks,
                "praise_10" to current.profile.praisePoints,
            )
            current.copy(achievements = current.achievements.map { achievement ->
                achievement.copy(progress = metrics[achievement.id] ?: achievement.progress)
            })
        }
    }
}

fun defaultStudyTasks(): List<StudyTask> = listOf(
    StudyTask(title = "考研英语真题训练", pomodoroCount = 2),
    StudyTask(title = "词汇复习", pomodoroCount = 1),
    StudyTask(title = "专业课重点整理", pomodoroCount = 2),
)

fun defaultAchievements(): List<Achievement> = listOf(
    Achievement("first_pomodoro", "第一次专注", "完成 1 个番茄钟", 1),
    Achievement("pomodoro_5", "进入状态", "累计完成 5 个番茄钟", 5),
    Achievement("pomodoro_20", "专注习惯", "累计完成 20 个番茄钟", 20),
    Achievement("study_60", "学习一小时", "累计学习 60 分钟", 60),
    Achievement("study_300", "五小时里程碑", "累计学习 300 分钟", 300),
    Achievement("vocab_50", "词汇热身", "复习 50 个单词", 50),
    Achievement("vocab_200", "词汇积累", "复习 200 个单词", 200),
    Achievement("task_1", "今日开张", "完成 1 项今日任务", 1),
    Achievement("task_5", "清单终结者", "完成 5 项今日任务", 5),
    Achievement("praise_10", "值得夸夸", "累计获得 10 点夸夸值", 10),
)

object PostgraduateExamStores {
    val main: PostgraduateExamStore = InMemoryPostgraduateExamStore()
}
