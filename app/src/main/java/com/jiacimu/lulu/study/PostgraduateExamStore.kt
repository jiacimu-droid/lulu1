package com.jiacimu.lulu.study

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.DurationSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.random.Random

class PostgraduateExamStore internal constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(loadAndRepair())
    val state: StateFlow<StudyState> = mutableState.asStateFlow()

    init {
        syncDate()
    }

    fun syncDate(now: LocalDate = LocalDate.now()) {
        mutate { rollover(it, now) }
    }

    fun selectCharacter(characterId: String) {
        if (characterId.isBlank()) return
        mutate { it.copy(profile = it.profile.copy(selectedCharacterId = characterId)) }
    }

    fun signIn(today: LocalDate = LocalDate.now()): String {
        var message = "今天已经签到过了"
        mutate { current ->
            val state = rollover(current, today)
            if (state.profile.lastSignInDate == today.toString()) return@mutate state
            val streak = if (state.profile.lastSignInDate == today.minusDays(1).toString()) state.profile.streakDays + 1 else 1
            message = "签到成功：夸夸值 +50，连续 $streak 天"
            updateAchievements(
                state.copy(
                    profile = state.profile.copy(
                        praisePoints = state.profile.praisePoints + 50,
                        experience = state.profile.experience + 50,
                        streakDays = streak,
                        lastSignInDate = today.toString(),
                        totalSignIns = state.profile.totalSignIns + 1,
                    ),
                    events = addEvent(state.events, "每日签到", message),
                ),
            )
        }
        return message
    }

    suspend fun evaluateSleepReward(
        sleepTime: LocalTime,
        wakeTime: LocalTime,
        durationHours: Double,
        today: LocalDate = LocalDate.now(),
    ): Result<String> {
        val snapshot = rollover(mutableState.value, today)
        if (snapshot.profile.sleepRewardDate == today.toString()) return Result.success("今天的睡眠奖励已经判断过了")
        val facts = buildString {
            appendLine("日期：$today")
            appendLine("入睡时间：${sleepTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
            appendLine("起床时间：${wakeTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
            appendLine("睡眠时长：${"%.1f".format(durationHours)}小时")
            appendLine("个人参考：约01:30前入睡、约09:30前起床；只是参考，不是系统硬门槛。")
        }
        return LuluAiServices.gateway.generate(
            characterId = snapshot.profile.selectedCharacterId,
            facts = facts,
            instruction = "结合角色人设、关系边界和用户实际情况决定是否认可本次作息。第一行严格写 ALLOW 或 DENY，后面用角色自己的口吻说明。参考时间不能替代角色判断。",
            source = "考研",
            title = "作息奖励判断",
            maxTokens = 360,
        ).map { reply ->
            val allow = reply.text.lineSequence().firstOrNull()?.trim()?.uppercase() == "ALLOW"
            val roleText = reply.text.lineSequence().drop(1).joinToString("\n").trim().ifBlank { reply.text.trim() }
            var result = roleText
            mutate { current ->
                val state = rollover(current, today)
                val praise = if (allow) 500 else 0
                val tickets = if (allow) 1 else 0
                result = if (allow) "$roleText\n夸夸值 +500，十连抽券 +1" else roleText
                updateAchievements(
                    state.copy(
                        profile = state.profile.copy(
                            praisePoints = state.profile.praisePoints + praise,
                            experience = state.profile.experience + praise,
                            sleepRewardDate = today.toString(),
                        ),
                        inventory = state.inventory.copy(tenTickets = state.inventory.tenTickets + tickets),
                        events = addEvent(state.events, if (allow) "作息奖励通过" else "作息奖励未通过", result),
                    ),
                )
            }
            result
        }
    }

    fun applyInactivityPenalty(today: LocalDate = LocalDate.now()): String {
        var message = "今天不需要执行未学习惩罚"
        mutate { current ->
            val state = rollover(current, today)
            val last = state.profile.lastStudyDate.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: return@mutate state
            val days = ChronoUnit.DAYS.between(last, today).toInt()
            if (days < 2 || state.profile.inactivityPenaltyDate == today.toString()) return@mutate state
            val penalty = if (days >= 3) 100 else 50
            message = "连续 $days 天没有学习记录，夸夸值 -$penalty"
            state.copy(
                profile = state.profile.copy(
                    praisePoints = (state.profile.praisePoints - penalty).coerceAtLeast(0),
                    inactivityPenaltyDate = today.toString(),
                ),
                events = addEvent(state.events, "学习中断惩罚", message),
            )
        }
        return message
    }

    fun addTask(title: String, pomodoroTarget: Int = 1, date: LocalDate = LocalDate.now(), source: StudyTaskSource = StudyTaskSource.User) {
        val clean = title.trim()
        if (clean.isBlank()) return
        mutate { state ->
            state.copy(
                tasks = state.tasks + StudyTask(
                    title = clean,
                    date = date.toString(),
                    pomodoroTarget = pomodoroTarget.coerceIn(1, 12),
                    source = source,
                ),
                events = addEvent(state.events, "添加待办", clean),
            )
        }
    }

    fun deleteTask(taskId: String) = mutate { state -> state.copy(tasks = state.tasks.filterNot { it.id == taskId }) }

    fun toggleTask(taskId: String) {
        mutate { state ->
            val task = state.tasks.firstOrNull { it.id == taskId } ?: return@mutate state
            val complete = !task.completed
            val firstReward = complete
            val today = LocalDate.now().toString()
            val tasks = state.tasks.map {
                if (it.id == taskId) it.copy(
                    completed = complete,
                    pomodoroCompleted = if (complete) max(it.pomodoroCompleted, it.pomodoroTarget) else it.pomodoroCompleted,
                ) else it
            }
            val allTodayComplete = allTasksCompleteForDate(tasks, today)
            updateAchievements(
                state.copy(
                    tasks = tasks,
                    profile = state.profile.copy(
                        praisePoints = state.profile.praisePoints + if (firstReward) 50 else 0,
                        experience = state.profile.experience + if (firstReward) 50 else 0,
                        totalTasksCompleted = state.profile.totalTasksCompleted + if (firstReward) 1 else 0,
                        lastStudyDate = if (firstReward) today else state.profile.lastStudyDate,
                    ),
                    superMomentAvailable = state.superMomentAvailable ||
                        (allTodayComplete && state.superMomentClaimedDate != today),
                    events = addEvent(state.events, if (complete) "待办完成" else "待办取消", task.title),
                ),
            )
        }
    }

    fun reviewVocabulary(count: Int) {
        val safe = count.coerceAtLeast(0)
        if (safe == 0) return
        val date = LocalDate.now().toString()
        mutate { state ->
            state.copy(
                profile = state.profile.copy(vocabularyReviewed = state.profile.vocabularyReviewed + safe),
                dailyVocabularyReviewed = state.dailyVocabularyReviewed + (date to ((state.dailyVocabularyReviewed[date] ?: 0) + safe)),
                events = addEvent(state.events, "词汇复习", "记录复习 $safe 个词"),
            )
        }
    }

    fun addPlanItem(range: StudyPlanRange, title: String, note: String = "") {
        val clean = title.trim()
        if (clean.isBlank()) return
        mutate { it.copy(planItems = it.planItems + StudyPlanItem(range = range, title = clean, note = note.trim())) }
    }

    fun togglePlanItem(id: String) = mutate { state -> state.copy(planItems = state.planItems.map { if (it.id == id) it.copy(completed = !it.completed) else it }) }
    fun deletePlanItem(id: String) = mutate { state -> state.copy(planItems = state.planItems.filterNot { it.id == id }) }

    fun removePlanItemsByTitle(titles: Set<String>) = mutate { state ->
        state.copy(planItems = state.planItems.filterNot { it.title in titles })
    }

    fun replaceRollingPlanItems(items: List<StudyPlanItem>) = mutate { state ->
        state.copy(planItems = state.planItems.filterNot { it.id.startsWith("rolling:") } + items)
    }

    fun addTip(text: String, date: LocalDate = LocalDate.now()) {
        val clean = text.trim()
        if (clean.isBlank()) return
        mutate { it.copy(tips = listOf(StudyTip(text = clean, date = date.toString())) + it.tips) }
    }

    suspend fun generateTodaySchedule(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): Result<List<StudyScheduleBlock>> {
        val snapshot = rollover(mutableState.value, today)
        val tasks = snapshot.tasks.filter { it.date == today.toString() && !it.completed }
        val plans = snapshot.planItems.filterNot { it.completed }
        val facts = buildString {
            appendLine("当前日期：$today")
            appendLine("当前时间：${now.format(DateTimeFormatter.ofPattern("HH:mm"))}")
            appendLine("今日未完成任务：")
            tasks.forEach { appendLine("- ${it.title}，预计${it.pomodoroTarget}个番茄钟") }
            appendLine("周月计划参考：")
            plans.take(12).forEach { appendLine("- ${it.range.name}：${it.title} ${it.note}") }
            appendLine("必须保留缓冲，不能把时间排满。")
        }
        return LuluAiServices.gateway.generate(
            characterId = snapshot.profile.selectedCharacterId,
            facts = facts,
            instruction = "生成从当前时间开始的现实日程，只返回JSON数组，每项格式 {\"start\":\"HH:mm\",\"end\":\"HH:mm\",\"title\":\"任务\"}。",
            source = "考研",
            title = "生成今日计划",
            temperature = 0.35,
            maxTokens = 1200,
        ).mapCatching { reply ->
            val clean = reply.text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val array = JSONArray(clean)
            val blocks = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val start = item.optString("start")
                    val end = item.optString("end")
                    val title = item.optString("title").trim()
                    if (TIME_REGEX.matches(start) && TIME_REGEX.matches(end) && title.isNotBlank()) {
                        add(StudyScheduleBlock(date = today.toString(), start = start, end = end, title = title))
                    }
                }
            }.sortedBy { it.start }
            check(blocks.isNotEmpty()) { "模型没有返回可读取的时间表" }
            mutate { state ->
                state.copy(
                    schedules = state.schedules.filterNot { it.date == today.toString() } + blocks,
                    events = addEvent(state.events, "生成今日计划", "生成 ${blocks.size} 个时间块"),
                )
            }
            blocks
        }
    }

    fun toggleSchedule(id: String) = mutate { state -> state.copy(schedules = state.schedules.map { if (it.id == id) it.copy(completed = !it.completed) else it }) }
    fun clearSchedule(date: LocalDate = LocalDate.now()) = mutate { state -> state.copy(schedules = state.schedules.filterNot { it.date == date.toString() }) }

    fun setPomodoroDuration(minutes: Int) {
        val safe = minutes.coerceIn(1, 180)
        mutate { state ->
            if (state.pomodoro.running) state else state.copy(
                pomodoro = state.pomodoro.copy(selectedMinutes = safe, remainingSeconds = safe * 60, endAtEpochMillis = 0L),
            )
        }
    }

    fun togglePomodoroVoice() = mutate { state -> state.copy(pomodoro = state.pomodoro.copy(voiceEnabled = !state.pomodoro.voiceEnabled)) }

    fun togglePomodoro(nowMillis: Long = System.currentTimeMillis()) = mutate { state ->
        val timer = state.pomodoro
        if (timer.running) {
            val remaining = ((timer.endAtEpochMillis - nowMillis) / 1000L).toInt().coerceAtLeast(0)
            state.copy(pomodoro = timer.copy(running = false, remainingSeconds = remaining, endAtEpochMillis = 0L))
        } else {
            val seconds = timer.remainingSeconds.takeIf { it > 0 } ?: timer.selectedMinutes * 60
            state.copy(pomodoro = timer.copy(running = true, remainingSeconds = seconds, endAtEpochMillis = nowMillis + seconds.toLong() * 1000L))
        }
    }

    fun syncPomodoroClock(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val timer = mutableState.value.pomodoro
        if (!timer.running) return false
        val remaining = ((timer.endAtEpochMillis - nowMillis) / 1000L).toInt()
        return if (remaining <= 0) {
            completePomodoro(timer.selectedMinutes)
            true
        } else {
            mutate { state -> state.copy(pomodoro = state.pomodoro.copy(remainingSeconds = remaining)) }
            false
        }
    }

    fun resetPomodoro() = mutate { state ->
        state.copy(pomodoro = state.pomodoro.copy(running = false, remainingSeconds = state.pomodoro.selectedMinutes * 60, endAtEpochMillis = 0L))
    }

    fun completePomodoro(recordedMinutes: Int = mutableState.value.pomodoro.selectedMinutes): String {
        val minutes = recordedMinutes.coerceAtLeast(0)
        var message = "未记录学习时长"
        if (minutes <= 0) {
            resetPomodoro()
            return message
        }
        val date = LocalDate.now().toString()
        mutate { state ->
            val rewardMinutes = state.pendingRewardMinutes + minutes
            val rewardCount = rewardMinutes / STUDY_REWARD_INTERVAL_MINUTES
            val remainder = rewardMinutes % STUDY_REWARD_INTERVAL_MINUTES
            val praise = rewardCount * STUDY_REWARD_PRAISE
            message = if (rewardCount > 0) {
                "学习 $minutes 分钟，夸夸值 +$praise"
            } else {
                "学习 $minutes 分钟，抽卡进度 $remainder/$STUDY_REWARD_INTERVAL_MINUTES 分钟"
            }
            val firstPendingIndex = state.tasks.indexOfFirst { it.date == date && !it.completed }
            var taskCompleted = false
            val tasks = state.tasks.mapIndexed { index, task ->
                if (index == firstPendingIndex) {
                    val progress = (task.pomodoroCompleted + 1).coerceAtMost(task.pomodoroTarget)
                    taskCompleted = progress >= task.pomodoroTarget && !task.completed
                    task.copy(pomodoroCompleted = progress, completed = task.completed || progress >= task.pomodoroTarget)
                } else task
            }
            val allTodayComplete = allTasksCompleteForDate(tasks, date)
            val totalPomodoros = state.profile.totalPomodoros + 1
            updateAchievements(
                state.copy(
                    tasks = tasks,
                    pendingRewardMinutes = remainder,
                    profile = state.profile.copy(
                        praisePoints = state.profile.praisePoints + praise,
                        experience = state.profile.experience + praise,
                        totalStudyMinutes = state.profile.totalStudyMinutes + minutes,
                        totalPomodoros = totalPomodoros,
                        totalTasksCompleted = state.profile.totalTasksCompleted + if (taskCompleted) 1 else 0,
                        lastStudyDate = date,
                    ),
                    dailyStudyMinutes = state.dailyStudyMinutes + (date to ((state.dailyStudyMinutes[date] ?: 0) + minutes)),
                    dailyPomodoros = state.dailyPomodoros + (date to ((state.dailyPomodoros[date] ?: 0) + 1)),
                    superMomentAvailable = state.superMomentAvailable ||
                        (allTodayComplete && state.superMomentClaimedDate != date),
                    pomodoro = state.pomodoro.copy(running = false, remainingSeconds = state.pomodoro.selectedMinutes * 60, endAtEpochMillis = 0L),
                    events = addEvent(state.events, "番茄钟完成", message),
                ),
            )
        }
        LuluRepositories.performance.updateDurations(
            DurationSummary(studyMinutes = mutableState.value.profile.totalStudyMinutes, chatMinutes = 0, callMinutes = 0),
        )
        return message
    }

    fun drawSingle(): List<StudyDrawResult> = draw(1)
    fun drawTen(): List<StudyDrawResult> = draw(10)

    private fun draw(count: Int): List<StudyDrawResult> {
        val drawCount = if (count >= 10) 10 else 1
        var results = emptyList<StudyDrawResult>()
        mutate { state ->
            val price = if (drawCount == 10) TEN_DRAW_COST else SINGLE_DRAW_COST
            val hasTicket = if (drawCount == 10) state.inventory.tenTickets > 0 else state.inventory.singleTickets > 0
            if (!hasTicket && state.profile.praisePoints < price) return@mutate state
            var working = if (hasTicket) {
                state.copy(
                    inventory = if (drawCount == 10) state.inventory.copy(tenTickets = state.inventory.tenTickets - 1)
                    else state.inventory.copy(singleTickets = state.inventory.singleTickets - 1),
                )
            } else {
                state.copy(profile = state.profile.copy(praisePoints = state.profile.praisePoints - price))
            }
            val generated = mutableListOf<StudyDrawResult>()
            repeat(drawCount) {
                val forcedRare = working.drawsSinceNonNormal >= NON_NORMAL_PITY - 1
                val base = if (forcedRare) drawRare() else drawOne()
                val applied = applyDraw(working, base)
                working = applied.first
                generated += applied.second
            }
            results = generated
            updateAchievements(
                working.copy(
                    profile = working.profile.copy(totalDraws = working.profile.totalDraws + drawCount),
                    events = addEvent(
                        working.events,
                        if (drawCount == 10) "十连抽" else "单抽",
                        generated.joinToString("、") { it.title },
                    ),
                ),
            )
        }
        return results
    }

    private fun drawOne(): StudyDrawResult {
        val roll = Random.nextDouble()
        return when {
            roll < 0.9395 -> {
                val scroll = blueFragmentCatalog.random()
                StudyDrawResult(kind = StudyDrawKind.OutfitFragment, title = "$scroll · 专属碎片", inventoryChanged = true)
            }
            roll < 0.9645 -> StudyDrawResult(
                kind = StudyDrawKind.DouyinTicket,
                title = StudyDrawKind.DouyinTicket.label,
                inventoryChanged = true,
            )
            roll < 0.9745 -> StudyDrawResult(
                kind = StudyDrawKind.GameRoundTicket,
                title = StudyDrawKind.GameRoundTicket.label,
                inventoryChanged = true,
            )
            roll < 0.9845 -> StudyDrawResult(
                kind = StudyDrawKind.TheaterFragment,
                title = StudyDrawKind.TheaterFragment.label,
                inventoryChanged = true,
            )
            roll < 0.9925 -> StudyDrawResult(
                kind = StudyDrawKind.GameTicket,
                title = StudyDrawKind.GameTicket.label,
                inventoryChanged = true,
            )
            roll < 0.9965 -> StudyDrawResult(
                kind = StudyDrawKind.VideoCard,
                title = StudyDrawKind.VideoCard.label,
                inventoryChanged = true,
            )
            else -> StudyDrawResult(
                kind = StudyDrawKind.AnimeTicket,
                title = StudyDrawKind.AnimeTicket.label,
                inventoryChanged = true,
            )
        }
    }

    private fun drawRare(): StudyDrawResult {
        val roll = Random.nextDouble()
        return when {
            roll < 5.0 / 9.0 -> StudyDrawResult(
                kind = StudyDrawKind.DouyinTicket,
                title = StudyDrawKind.DouyinTicket.label,
                inventoryChanged = true,
            )
            roll < 7.0 / 9.0 -> StudyDrawResult(
                kind = StudyDrawKind.GameRoundTicket,
                title = StudyDrawKind.GameRoundTicket.label,
                inventoryChanged = true,
            )
            else -> StudyDrawResult(
                kind = StudyDrawKind.TheaterFragment,
                title = StudyDrawKind.TheaterFragment.label,
                inventoryChanged = true,
            )
        }
    }

    private fun applyDraw(state: StudyState, initial: StudyDrawResult): Pair<StudyState, StudyDrawResult> {
        var inventory = state.inventory
        var result = initial
        when (initial.kind) {
            StudyDrawKind.OutfitFragment -> {
                val scroll = initial.title.substringBefore(" ·")
                val old = inventory.blueFragments[scroll] ?: 0
                val full = old >= BLUE_FRAGMENTS_PER_SCROLL
                result = initial.copy(
                    title = if (full) "$scroll · 专属碎片（已满）" else "$scroll · 专属碎片 ${old + 1}/$BLUE_FRAGMENTS_PER_SCROLL",
                    inventoryChanged = !full,
                )
                if (!full) {
                    val next = old + 1
                    inventory = inventory.copy(
                        blueFragments = inventory.blueFragments + (scroll to next),
                        unlockedScrolls = if (next >= BLUE_FRAGMENTS_PER_SCROLL && scroll !in inventory.unlockedScrolls) inventory.unlockedScrolls + scroll else inventory.unlockedScrolls,
                    )
                }
            }
            StudyDrawKind.DouyinTicket -> inventory = inventory.copy(douyinTickets = inventory.douyinTickets + 1)
            StudyDrawKind.GameRoundTicket -> inventory = inventory.copy(gameRoundTickets = inventory.gameRoundTickets + 1)
            StudyDrawKind.TheaterFragment -> inventory = inventory.copy(theaterFragments = inventory.theaterFragments + 1)
            StudyDrawKind.GameTicket -> inventory = inventory.copy(gameTickets = inventory.gameTickets + 1)
            StudyDrawKind.VideoCard -> inventory = inventory.copy(videoCards = inventory.videoCards + 1)
            StudyDrawKind.AnimeTicket -> inventory = inventory.copy(animeTickets = inventory.animeTickets + 1)
        }
        val streak = if (result.rarity == StudyRarity.Normal) state.drawsSinceNonNormal + 1 else 0
        return state.copy(inventory = inventory, drawsSinceNonNormal = streak.coerceAtMost(NON_NORMAL_PITY - 1)) to result
    }

    fun claimSuperMoment(): String {
        var message = "当前没有可领取的超神奖励"
        mutate { state ->
            val today = LocalDate.now().toString()
            if (!state.superMomentAvailable || state.superMomentClaimedDate == today) return@mutate state
            message = "今日待办全清：十连抽券 +2（20连）"
            state.copy(
                inventory = state.inventory.copy(tenTickets = state.inventory.tenTickets + 2),
                superMomentAvailable = false,
                superMomentClaimedDate = today,
                events = addEvent(state.events, "超神时刻", message),
            )
        }
        return message
    }

    fun redeemEntertainment(kind: StudyEntertainmentKind): String {
        var message = "对应收藏数量不足"
        mutate { state ->
            val inventory = state.inventory
            when (kind) {
                StudyEntertainmentKind.Douyin -> {
                    if (inventory.douyinTickets <= 0) return@mutate state
                    message = "抖音时长券已使用 · 20分钟"
                    state.copy(inventory = inventory.copy(douyinTickets = inventory.douyinTickets - 1), events = addEvent(state.events, "娱乐券", message))
                }
                StudyEntertainmentKind.GameRound -> {
                    if (inventory.gameRoundTickets <= 0) return@mutate state
                    message = "游戏局数券已使用 · 可畅玩4局"
                    state.copy(inventory = inventory.copy(gameRoundTickets = inventory.gameRoundTickets - 1), events = addEvent(state.events, "娱乐券", message))
                }
                StudyEntertainmentKind.Theater -> {
                    if (inventory.theaterFragments <= 0) return@mutate state
                    val next = theaterCatalog.firstOrNull { it !in inventory.unlockedTheaters }
                    if (next == null) {
                        message = "小剧场已经全部解锁"
                        return@mutate state
                    }
                    message = "解锁小剧场《$next》"
                    state.copy(
                        inventory = inventory.copy(theaterFragments = inventory.theaterFragments - 1, unlockedTheaters = inventory.unlockedTheaters + next),
                        events = addEvent(state.events, "小剧场", message),
                    )
                }
                StudyEntertainmentKind.Game -> {
                    if (inventory.gameTickets <= 0) return@mutate state
                    message = "电影券已使用 · 可观看1部电影"
                    state.copy(inventory = inventory.copy(gameTickets = inventory.gameTickets - 1), events = addEvent(state.events, "娱乐券", message))
                }
                StudyEntertainmentKind.Video -> {
                    if (inventory.videoCards <= 0) return@mutate state
                    val next = videoCatalog.firstOrNull { it !in inventory.unlockedVideos }
                    if (next == null) {
                        message = "视频已经全部解锁"
                        return@mutate state
                    }
                    message = "解锁视频《$next》"
                    state.copy(
                        inventory = inventory.copy(videoCards = inventory.videoCards - 1, unlockedVideos = inventory.unlockedVideos + next),
                        events = addEvent(state.events, "视频收藏", message),
                    )
                }
                StudyEntertainmentKind.Anime -> {
                    if (inventory.animeTickets <= 0) return@mutate state
                    message = "影视剧一季兑换券已使用 · 可兑换一整季"
                    state.copy(inventory = inventory.copy(animeTickets = inventory.animeTickets - 1), events = addEvent(state.events, "娱乐券", message))
                }
            }
        }
        return message
    }

    fun claimAchievement(id: String): String {
        var message = "成就尚未解锁或已经领取"
        mutate { state ->
            val achievement = state.achievements.firstOrNull { it.id == id } ?: return@mutate state
            if (!achievement.unlocked || achievement.claimed) return@mutate state
            message = "领取《${achievement.title}》：单抽券 +${achievement.rewardSingleTickets}，夸夸值 +${achievement.rewardPraisePoints}"
            state.copy(
                achievements = state.achievements.map { if (it.id == id) it.copy(claimed = true) else it },
                inventory = state.inventory.copy(singleTickets = state.inventory.singleTickets + achievement.rewardSingleTickets),
                profile = state.profile.copy(
                    praisePoints = state.profile.praisePoints + achievement.rewardPraisePoints,
                    experience = state.profile.experience + achievement.rewardPraisePoints,
                ),
                events = addEvent(state.events, "领取成就", message),
            )
        }
        return message
    }

    fun claimLevel(level: Int): String {
        var message = "等级奖励尚未达到或已经领取"
        mutate { state ->
            if (level > state.profile.level || level in state.profile.claimedLevels || level !in 1..StudyLevels.thresholds.size) return@mutate state
            val tickets = when {
                level >= 15 -> 5
                level >= 12 -> 2
                level % 3 == 0 -> 1
                else -> 0
            }
            val praise = when {
                level >= 15 -> 3_000
                level >= 10 -> 1_000
                else -> level * 100
            }
            message = buildString {
                append("领取 Lv.$level：夸夸值 +$praise")
                if (tickets > 0) append("，十连抽券 +$tickets")
            }
            state.copy(
                profile = state.profile.copy(
                    claimedLevels = state.profile.claimedLevels + level,
                    praisePoints = state.profile.praisePoints + praise,
                    experience = state.profile.experience + praise,
                ),
                inventory = state.inventory.copy(tenTickets = state.inventory.tenTickets + tickets),
                events = addEvent(state.events, "等级奖励", message),
            )
        }
        return message
    }

    fun refreshShop(today: LocalDate = LocalDate.now()): String {
        var message = "今天已经手动刷新过了"
        mutate { state ->
            if (state.manualShopRefreshDate == today.toString()) return@mutate state
            message = "商店已刷新"
            state.copy(
                shopItems = defaultShop(today.plusDays(1)),
                shopDate = today.toString(),
                manualShopRefreshDate = today.toString(),
                events = addEvent(state.events, "刷新商店", message),
            )
        }
        return message
    }

    fun buyShopItem(id: String): String {
        var message = "购买失败"
        mutate { state ->
            val item = state.shopItems.firstOrNull { it.id == id } ?: return@mutate state
            if (item.purchased) {
                message = "这件商品已经购买过了"
                return@mutate state
            }
            if (state.profile.praisePoints < item.cost) {
                message = "夸夸值不足，还需要 ${item.cost - state.profile.praisePoints}"
                return@mutate state
            }
            var inventory = state.inventory
            inventory = when (item.reward) {
                StudyShopReward.SingleTicket -> inventory.copy(singleTickets = inventory.singleTickets + item.amount)
                StudyShopReward.DouyinTicket -> inventory.copy(douyinTickets = inventory.douyinTickets + item.amount)
                StudyShopReward.GameRoundTicket -> inventory.copy(gameRoundTickets = inventory.gameRoundTickets + item.amount)
                StudyShopReward.TheaterFragment -> inventory.copy(theaterFragments = inventory.theaterFragments + item.amount)
                StudyShopReward.GameTicket -> inventory.copy(gameTickets = inventory.gameTickets + item.amount)
                StudyShopReward.VideoCard -> inventory.copy(videoCards = inventory.videoCards + item.amount)
                StudyShopReward.AnimeTicket -> inventory.copy(animeTickets = inventory.animeTickets + item.amount)
            }
            message = "购买成功：${item.title}"
            state.copy(
                profile = state.profile.copy(praisePoints = state.profile.praisePoints - item.cost),
                inventory = inventory,
                shopItems = state.shopItems.map { if (it.id == id) it.copy(purchased = true) else it },
                events = addEvent(state.events, "神秘商店", message),
            )
        }
        return message
    }

    private fun updateAchievements(state: StudyState): StudyState {
        val claims = state.achievements.associate { it.id to it.claimed }
        val values = listOf(
            StudyAchievement("pomodoro_3", "热身完成", "累计完成3个番茄钟", state.profile.totalPomodoros, 3),
            StudyAchievement("pomodoro_10", "初识陪伴", "累计完成10个番茄钟", state.profile.totalPomodoros, 10),
            StudyAchievement("pomodoro_20", "渐入佳境", "累计完成20个番茄钟", state.profile.totalPomodoros, 20),
            StudyAchievement("pomodoro_50", "专注成林", "累计完成50个番茄钟", state.profile.totalPomodoros, 50, 2, 200),
            StudyAchievement("pomodoro_100", "百次同行", "累计完成100个番茄钟", state.profile.totalPomodoros, 100, 3, 1_000),
            StudyAchievement("task_10", "清单起势", "累计完成10项待办", state.profile.totalTasksCompleted, 10),
            StudyAchievement("task_30", "清单杀手", "累计完成30项待办", state.profile.totalTasksCompleted, 30),
            StudyAchievement("task_50", "步步兑现", "累计完成50项待办", state.profile.totalTasksCompleted, 50, 2, 500),
            StudyAchievement("study_10h", "坐稳书桌", "累计学习10小时", state.profile.totalStudyMinutes, 600),
            StudyAchievement("study_50h", "时光旅人", "累计学习50小时", state.profile.totalStudyMinutes, 3_000, 2, 300),
            StudyAchievement("study_100h", "百小时灯火", "累计学习100小时", state.profile.totalStudyMinutes, 6_000, 2, 1_000),
            StudyAchievement("outfit_1", "第一画卷", "解锁第一套画卷", state.inventory.unlockedScrolls.size, 1),
            StudyAchievement("outfit_3", "画卷收藏家", "解锁3套画卷", state.inventory.unlockedScrolls.size, 3, 2, 500),
            StudyAchievement("draw_20", "好运初现", "累计抽卡20次", state.profile.totalDraws, 20),
            StudyAchievement("video_1", "第一支视频", "首次解锁视频", state.inventory.unlockedVideos.size, 1),
        )
        return state.copy(achievements = values.map { it.copy(claimed = claims[it.id] == true) })
    }

    private fun rollover(state: StudyState, today: LocalDate): StudyState {
        val tasks = state.tasks.filter { task ->
            runCatching { !LocalDate.parse(task.date).isBefore(today.minusDays(90)) }.getOrDefault(true)
        }
        val todayKey = today.toString()
        val dateChanged = state.activeDate != todayKey
        val existingToday = tasks.filter { it.date == todayKey }
        val preserved = tasks.filterNot { it.date == todayKey && it.source == StudyTaskSource.Preset }
        val withDefaults = preserved + defaultTasks(today).map { preset ->
            if (dateChanged) {
                preset
            } else {
                existingToday.firstOrNull { it.title == preset.title }?.copy(source = StudyTaskSource.Preset) ?: preset
            }
        }
        if (!dateChanged && state.shopDate == todayKey && withDefaults == state.tasks) return updateAchievements(state)
        return updateAchievements(
            state.copy(
                activeDate = todayKey,
                tasks = withDefaults,
                tips = if (state.tips.none { it.date == today.toString() }) defaultTips(today) + state.tips else state.tips,
                shopItems = if (state.shopDate == todayKey) state.shopItems else defaultShop(today),
                shopDate = todayKey,
                manualShopRefreshDate = if (state.shopDate == todayKey) state.manualShopRefreshDate else "",
                superMomentAvailable = if (dateChanged) false else state.superMomentAvailable,
                pomodoro = if (state.pomodoro.running && state.pomodoro.endAtEpochMillis <= System.currentTimeMillis()) {
                    state.pomodoro.copy(running = false, remainingSeconds = state.pomodoro.selectedMinutes * 60, endAtEpochMillis = 0L)
                } else state.pomodoro,
            ),
        )
    }

    private fun allTasksCompleteForDate(tasks: List<StudyTask>, date: String): Boolean {
        val dailyTasks = tasks.filter { it.date == date }
        return dailyTasks.isNotEmpty() && dailyTasks.all(StudyTask::completed)
    }

    private fun addEvent(events: List<StudyEvent>, title: String, detail: String): List<StudyEvent> =
        (listOf(StudyEvent(title = title, detail = detail)) + events).take(MAX_EVENTS)

    private fun mutate(transform: (StudyState) -> StudyState) {
        mutableState.update(transform)
        persist(mutableState.value)
    }

    private fun persist(state: StudyState) {
        val encoded = StudyStateCodec.encode(state)
        prefs.edit()
            .putString(KEY_BACKUP, prefs.getString(KEY_STATE, encoded))
            .putString(KEY_STATE, encoded)
            .apply()
    }

    private fun loadAndRepair(): StudyState {
        val primary = prefs.getString(KEY_STATE, null)
        val backup = prefs.getString(KEY_BACKUP, null)
        return sequenceOf(primary, backup)
            .filterNotNull()
            .mapNotNull { runCatching { StudyStateCodec.decode(it) }.getOrNull() }
            .firstOrNull()
            ?.let(::updateAchievements)
            ?: updateAchievements(StudyState())
    }

    private companion object {
        const val PREFS_NAME = "lulu_study_complete"
        const val KEY_STATE = "state"
        const val KEY_BACKUP = "state_backup"
        const val MAX_EVENTS = 200
        val TIME_REGEX = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
    }
}

object PostgraduateExamStores {
    private var mainInternal: PostgraduateExamStore? = null
    val main: PostgraduateExamStore
        get() = checkNotNull(mainInternal) { "PostgraduateExamStores 尚未初始化" }

    fun initialize(context: Context) {
        if (mainInternal == null) mainInternal = PostgraduateExamStore(context.applicationContext)
    }
}
