package com.jiacimu.lulu.study

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.max
import kotlin.random.Random

/**
 * Complete independently implemented study domain store.
 *
 * It preserves the old product capabilities while keeping Lulu1's new UI and data model.
 * All state-changing actions persist immediately with a backup snapshot.
 */
class PostgraduateExamStore internal constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(loadAndRepair())
    val state: StateFlow<StudyState> = mutableState.asStateFlow()

    init {
        syncDate()
    }

    fun syncDate(now: LocalDate = LocalDate.now()) {
        mutate { current -> rollover(current, now) }
    }

    fun selectCharacter(characterId: String) {
        if (characterId.isBlank()) return
        mutate { it.copy(profile = it.profile.copy(selectedCharacterId = characterId)) }
    }

    fun signIn(today: LocalDate = LocalDate.now()): String {
        var message = "今天已经签到过了"
        mutate { current ->
            val synced = rollover(current, today)
            if (synced.profile.lastSignInDate == today.toString()) return@mutate synced
            val yesterday = today.minusDays(1).toString()
            val streak = if (synced.profile.lastSignInDate == yesterday) synced.profile.streakDays + 1 else 1
            val praise = 8 + (streak.coerceAtMost(7) - 1) * 2
            message = "签到成功：夸夸值 +$praise，连续 $streak 天"
            updateAchievements(
                synced.copy(
                    profile = synced.profile.copy(
                        praisePoints = synced.profile.praisePoints + praise,
                        experience = synced.profile.experience + 5,
                        streakDays = streak,
                        lastSignInDate = today.toString(),
                        totalSignIns = synced.profile.totalSignIns + 1,
                    ),
                    events = addEvent(synced.events, "每日签到", message),
                ),
            )
        }
        return message
    }

    /**
     * Sleep times are only reference data. The selected role decides ALLOW/DENY;
     * there is deliberately no system-side time-window veto.
     */
    suspend fun evaluateSleepReward(
        sleepTime: LocalTime,
        wakeTime: LocalTime,
        durationHours: Double,
        today: LocalDate = LocalDate.now(),
    ): Result<String> {
        val snapshot = rollover(mutableState.value, today)
        if (snapshot.profile.sleepRewardDate == today.toString()) {
            return Result.success("今天的睡眠奖励已经判断过了")
        }
        val characterId = snapshot.profile.selectedCharacterId
        val facts = buildString {
            appendLine("日期：$today")
            appendLine("入睡时间：${sleepTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
            appendLine("起床时间：${wakeTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
            appendLine("睡眠时长：${"%.1f".format(durationHours)}小时")
            appendLine("参考基线：约23:30入睡、约07:30起床、约7.5小时睡眠。")
            appendLine("重要：参考基线不是硬门槛，程序不会因为时间偏离而否决奖励。")
        }
        return LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = facts,
            instruction = "你拥有最终判断权。结合用户实际情况、角色人设、关系边界和近期记忆，决定本次是否给予睡眠夸夸奖励。第一行必须严格写 ALLOW 或 DENY；第二行起用角色自己的口吻说明理由。不得因为参考基线机械否决，也不得默认夸奖。",
            source = "考研",
            title = "睡眠习惯奖励判断",
            maxTokens = 360,
        ).map { reply ->
            val allow = reply.text.lineSequence().firstOrNull()?.trim()?.uppercase() == "ALLOW"
            val roleText = reply.text.lineSequence().drop(1).joinToString("\n").trim().ifBlank { reply.text.trim() }
            var resultMessage = roleText
            mutate { current ->
                val synced = rollover(current, today)
                val praise = if (allow) 20 else 0
                resultMessage = if (allow) "$roleText\n夸夸值 +$praise" else roleText
                updateAchievements(
                    synced.copy(
                        profile = synced.profile.copy(
                            praisePoints = synced.profile.praisePoints + praise,
                            experience = synced.profile.experience + if (allow) 8 else 0,
                            sleepRewardDate = today.toString(),
                        ),
                        events = addEvent(
                            synced.events,
                            if (allow) "睡眠奖励通过" else "睡眠奖励未通过",
                            resultMessage,
                        ),
                    ),
                )
            }
            resultMessage
        }
    }

    fun applyInactivityPenalty(today: LocalDate = LocalDate.now()): String {
        var message = "今天不需要执行未学习惩罚"
        mutate { current ->
            val synced = rollover(current, today)
            val lastStudy = synced.profile.lastStudyDate.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            if (lastStudy == null || !lastStudy.isBefore(today) || synced.profile.inactivityPenaltyDate == today.toString()) {
                return@mutate synced
            }
            val missedDays = ChronoUnit.DAYS.between(lastStudy, today).toInt().coerceAtLeast(1)
            val penalty = (missedDays * 5).coerceAtMost(30)
            message = "连续 $missedDays 天未记录学习，夸夸值 -$penalty"
            synced.copy(
                profile = synced.profile.copy(
                    praisePoints = (synced.profile.praisePoints - penalty).coerceAtLeast(0),
                    inactivityPenaltyDate = today.toString(),
                ),
                events = addEvent(synced.events, "未学习惩罚", message),
            )
        }
        return message
    }

    fun addTask(
        title: String,
        pomodoroTarget: Int = 1,
        date: LocalDate = LocalDate.now(),
        source: StudyTaskSource = StudyTaskSource.User,
    ) {
        val clean = title.trim()
        if (clean.isBlank()) return
        mutate { current ->
            val synced = rollover(current, LocalDate.now())
            synced.copy(
                tasks = synced.tasks + StudyTask(
                    title = clean,
                    date = date.toString(),
                    pomodoroTarget = pomodoroTarget.coerceIn(1, 12),
                    source = source,
                ),
                events = addEvent(synced.events, if (date == LocalDate.now()) "添加今日待办" else "添加明日待办", clean),
            )
        }
    }

    fun deleteTask(taskId: String) = mutate { current -> current.copy(tasks = current.tasks.filterNot { it.id == taskId }) }

    fun toggleTask(taskId: String) {
        mutate { current ->
            val task = current.tasks.firstOrNull { it.id == taskId } ?: return@mutate current
            val newlyCompleted = !task.completed
            val updated = current.tasks.map {
                if (it.id == taskId) it.copy(
                    completed = newlyCompleted,
                    pomodoroCompleted = if (newlyCompleted) max(it.pomodoroCompleted, it.pomodoroTarget) else it.pomodoroCompleted,
                ) else it
            }
            updateAchievements(
                current.copy(
                    tasks = updated,
                    profile = current.profile.copy(
                        praisePoints = (current.profile.praisePoints + if (newlyCompleted) 3 else -3).coerceAtLeast(0),
                        experience = (current.profile.experience + if (newlyCompleted) 5 else -5).coerceAtLeast(0),
                        totalTasksCompleted = (current.profile.totalTasksCompleted + if (newlyCompleted) 1 else -1).coerceAtLeast(0),
                    ),
                    events = addEvent(current.events, if (newlyCompleted) "完成任务" else "取消完成", task.title),
                ),
            )
        }
    }

    fun reviewVocabulary(count: Int) {
        val safe = count.coerceAtLeast(0)
        if (safe == 0) return
        mutate { current ->
            updateAchievements(
                current.copy(
                    profile = current.profile.copy(
                        vocabularyReviewed = current.profile.vocabularyReviewed + safe,
                        experience = current.profile.experience + safe / 10,
                    ),
                    events = addEvent(current.events, "词汇复习", "记录复习 $safe 个词"),
                ),
            )
        }
    }

    fun addPlanItem(range: StudyPlanRange, title: String, note: String = "") {
        val clean = title.trim()
        if (clean.isBlank()) return
        mutate { it.copy(planItems = it.planItems + StudyPlanItem(range = range, title = clean, note = note.trim())) }
    }

    fun togglePlanItem(id: String) = mutate { current ->
        current.copy(planItems = current.planItems.map { if (it.id == id) it.copy(completed = !it.completed) else it })
    }

    fun deletePlanItem(id: String) = mutate { it.copy(planItems = it.planItems.filterNot { item -> item.id == id }) }

    fun addTip(text: String, date: LocalDate = LocalDate.now()) {
        val clean = text.trim()
        if (clean.isBlank()) return
        mutate { it.copy(tips = listOf(StudyTip(text = clean, date = date.toString())) + it.tips) }
    }

    suspend fun generateTodaySchedule(
        now: LocalTime = LocalTime.now(),
        today: LocalDate = LocalDate.now(),
    ): Result<List<StudyScheduleBlock>> {
        val snapshot = rollover(mutableState.value, today)
        val tasks = snapshot.tasks.filter { it.date == today.toString() && !it.completed }
        val plans = snapshot.planItems.filterNot { it.completed }
        val facts = buildString {
            appendLine("当前日期：$today")
            appendLine("当前时间：${now.format(DateTimeFormatter.ofPattern("HH:mm"))}")
            appendLine("今日未完成任务：")
            tasks.forEach { appendLine("- ${it.title}，预计${it.pomodoroTarget}个番茄钟") }
            appendLine("周月计划参考：")
            plans.take(12).forEach { appendLine("- ${it.range.name}：${it.title}${it.note.takeIf(String::isNotBlank)?.let { note -> "（$note）" }.orEmpty()}") }
            appendLine("必须预留小缓冲，不得把时间排满；已完成或已删除任务不得重新加入。")
        }
        return LuluAiServices.gateway.generate(
            characterId = snapshot.profile.selectedCharacterId,
            facts = facts,
            instruction = "生成从当前时间开始的现实可执行考研日程。只返回 JSON 数组，每项格式 {\"start\":\"HH:mm\",\"end\":\"HH:mm\",\"title\":\"任务\"}。不得输出JSON之外的文字。",
            source = "考研",
            title = "AI生成今日计划",
            temperature = 0.35,
            maxTokens = 1200,
        ).mapCatching { reply ->
            val blocks = parseSchedule(reply.text, today)
            check(blocks.isNotEmpty()) { "主模型没有返回可读取的时间表" }
            mutate { current ->
                current.copy(
                    schedules = current.schedules.filterNot { it.date == today.toString() } + blocks,
                    events = addEvent(current.events, "生成今日计划", "生成 ${blocks.size} 个时间块"),
                )
            }
            blocks
        }
    }

    fun toggleSchedule(id: String) = mutate { current ->
        current.copy(schedules = current.schedules.map { if (it.id == id) it.copy(completed = !it.completed) else it })
    }

    fun clearSchedule(date: LocalDate = LocalDate.now()) = mutate { current ->
        current.copy(schedules = current.schedules.filterNot { it.date == date.toString() })
    }

    fun setPomodoroDuration(minutes: Int) {
        val safe = minutes.coerceIn(5, 180)
        mutate { current ->
            if (current.pomodoro.running) current else current.copy(
                pomodoro = current.pomodoro.copy(
                    selectedMinutes = safe,
                    remainingSeconds = safe * 60,
                    endAtEpochMillis = 0,
                ),
            )
        }
    }

    fun togglePomodoroVoice() = mutate { current ->
        current.copy(pomodoro = current.pomodoro.copy(voiceEnabled = !current.pomodoro.voiceEnabled))
    }

    fun togglePomodoro(nowMillis: Long = System.currentTimeMillis()) = mutate { current ->
        val timer = current.pomodoro
        if (timer.running) {
            val remaining = ((timer.endAtEpochMillis - nowMillis) / 1000L).toInt().coerceAtLeast(0)
            current.copy(pomodoro = timer.copy(running = false, remainingSeconds = remaining, endAtEpochMillis = 0))
        } else {
            val seconds = timer.remainingSeconds.takeIf { it > 0 } ?: timer.selectedMinutes * 60
            current.copy(pomodoro = timer.copy(running = true, remainingSeconds = seconds, endAtEpochMillis = nowMillis + seconds * 1000L))
        }
    }

    fun syncPomodoroClock(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val timer = mutableState.value.pomodoro
        if (!timer.running) return false
        val remaining = ((timer.endAtEpochMillis - nowMillis) / 1000L).toInt()
        return if (remaining <= 0) {
            completePomodoro()
            true
        } else {
            mutate { current -> current.copy(pomodoro = current.pomodoro.copy(remainingSeconds = remaining)) }
            false
        }
    }

    fun resetPomodoro() = mutate { current ->
        current.copy(pomodoro = current.pomodoro.copy(
            running = false,
            remainingSeconds = current.pomodoro.selectedMinutes * 60,
            endAtEpochMillis = 0,
        ))
    }

    fun completePomodoro(today: LocalDate = LocalDate.now()): String {
        var message = ""
        mutate { current ->
            val synced = rollover(current, today)
            val minutes = synced.pomodoro.selectedMinutes
            val todayString = today.toString()
            val firstPending = synced.tasks.indexOfFirst { it.date == todayString && !it.completed }
            var completedTaskNow = false
            val tasks = synced.tasks.mapIndexed { index, task ->
                if (index == firstPending) {
                    val progress = (task.pomodoroCompleted + 1).coerceAtMost(task.pomodoroTarget)
                    completedTaskNow = !task.completed && progress >= task.pomodoroTarget
                    task.copy(pomodoroCompleted = progress, completed = progress >= task.pomodoroTarget)
                } else task
            }
            val previousStudyDate = synced.profile.lastStudyDate.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            val streak = when (previousStudyDate) {
                today -> synced.profile.streakDays
                today.minusDays(1) -> synced.profile.streakDays + 1
                else -> 1
            }
            val totalPomodoros = synced.profile.totalPomodoros + 1
            val rewardPraise = (minutes / 10).coerceAtLeast(1)
            val rewardExperience = (minutes / 5).coerceAtLeast(1)
            message = "完成 $minutes 分钟专注：夸夸值 +$rewardPraise"
            val next = synced.copy(
                tasks = tasks,
                profile = synced.profile.copy(
                    praisePoints = synced.profile.praisePoints + rewardPraise,
                    experience = synced.profile.experience + rewardExperience,
                    streakDays = streak,
                    lastStudyDate = todayString,
                    totalStudyMinutes = synced.profile.totalStudyMinutes + minutes,
                    totalPomodoros = totalPomodoros,
                    totalTasksCompleted = synced.profile.totalTasksCompleted + if (completedTaskNow) 1 else 0,
                ),
                inventory = synced.inventory.copy(
                    mysteryBoxes = synced.inventory.mysteryBoxes + if (totalPomodoros % 5 == 0) 1 else 0,
                ),
                superMomentAvailable = synced.superMomentAvailable || totalPomodoros % 10 == 0,
                pomodoro = synced.pomodoro.copy(
                    running = false,
                    remainingSeconds = synced.pomodoro.selectedMinutes * 60,
                    endAtEpochMillis = 0,
                ),
                events = addEvent(synced.events, "番茄钟完成", message),
            )
            updateAchievements(next)
        }
        val total = mutableState.value.profile.totalStudyMinutes
        LuluRepositories.performance.updateDurations(
            com.jiacimu.lulu.core.DurationSummary(studyMinutes = total, chatMinutes = 0, callMinutes = 0),
        )
        return message
    }

    fun drawSingle(): List<StudyDrawResult> = consumeDraw(count = 1, ten = false)
    fun drawTen(): List<StudyDrawResult> = consumeDraw(count = 10, ten = true)

    fun drawSafePurple(today: LocalDate = LocalDate.now()): List<StudyDrawResult> {
        var results = emptyList<StudyDrawResult>()
        mutate { current ->
            val synced = rollover(current, today)
            if (synced.safeDrawUsedDate == today.toString() || synced.inventory.safePurpleTickets <= 0) return@mutate synced
            val result = applyDrawReward(synced, forcedKind = StudyDrawKind.PurpleFragment)
            results = listOf(result.result)
            updateAchievements(
                result.state.copy(
                    inventory = result.state.inventory.copy(safePurpleTickets = result.state.inventory.safePurpleTickets - 1),
                    safeDrawUsedDate = today.toString(),
                    profile = result.state.profile.copy(totalDraws = result.state.profile.totalDraws + 1),
                    events = addEvent(result.state.events, "今日安全抽", result.result.title),
                ),
            )
        }
        return results
    }

    fun openMysteryBox(): String {
        var message = "没有可打开的神秘盒子"
        mutate { current ->
            if (current.inventory.mysteryBoxes <= 0) return@mutate current
            val praise = Random.nextInt(6, 21)
            val universal = if (Random.nextDouble() < 0.35) 1 else 0
            message = buildString {
                append("夸夸值 +$praise")
                if (universal > 0) append("，万能蓝碎片 +1")
            }
            current.copy(
                profile = current.profile.copy(praisePoints = current.profile.praisePoints + praise),
                inventory = current.inventory.copy(
                    mysteryBoxes = current.inventory.mysteryBoxes - 1,
                    universalBlueFragments = current.inventory.universalBlueFragments + universal,
                ),
                events = addEvent(current.events, "打开神秘盒子", message),
            )
        }
        return message
    }

    fun claimSuperMoment(choice: StudySuperChoice, today: LocalDate = LocalDate.now()): String {
        var message = "当前没有可领取的 Super Moment"
        mutate { current ->
            if (!current.superMomentAvailable || current.superMomentClaimedDate == today.toString()) return@mutate current
            var profile = current.profile
            var inventory = current.inventory
            message = when (choice) {
                StudySuperChoice.DoublePraise -> {
                    profile = profile.copy(praisePoints = profile.praisePoints + 30)
                    "Super Moment：夸夸值 +30"
                }
                StudySuperChoice.MysteryBoxes -> {
                    inventory = inventory.copy(mysteryBoxes = inventory.mysteryBoxes + 2)
                    "Super Moment：神秘盒子 +2"
                }
                StudySuperChoice.DrawTickets -> {
                    inventory = inventory.copy(singleTickets = inventory.singleTickets + 3)
                    "Super Moment：单抽券 +3"
                }
            }
            current.copy(
                profile = profile,
                inventory = inventory,
                superMomentAvailable = false,
                superMomentClaimedDate = today.toString(),
                events = addEvent(current.events, "Super Moment", message),
            )
        }
        return message
    }

    fun applyUniversalBlue(target: String? = null): String {
        var message = "没有可用的万能蓝碎片"
        mutate { current ->
            if (current.inventory.universalBlueFragments <= 0) return@mutate current
            val key = target?.takeIf { it in blueFragmentCatalog }
                ?: blueFragmentCatalog.minByOrNull { current.inventory.blueFragments[it] ?: 0 }
                ?: return@mutate current
            val amount = current.inventory.blueFragments[key] ?: 0
            if (amount >= BLUE_FRAGMENT_MAX) {
                message = "这个收藏已经补满"
                return@mutate current
            }
            val blue = current.inventory.blueFragments + (key to amount + 1)
            message = "万能蓝碎片已用于《$key》：${amount + 1}/$BLUE_FRAGMENT_MAX"
            maybeUnlockScroll(
                current.copy(
                    inventory = current.inventory.copy(
                        universalBlueFragments = current.inventory.universalBlueFragments - 1,
                        blueFragments = blue,
                    ),
                    events = addEvent(current.events, "使用万能碎片", message),
                ),
            )
        }
        return message
    }

    fun redeemEntertainment(kind: StudyEntertainmentKind): String {
        var message = "还需要 1 个对应碎片"
        mutate { current ->
            val amount = current.inventory.entertainmentFragments[kind] ?: 0
            if (amount <= 0) return@mutate current
            val remaining = current.inventory.entertainmentFragments + (kind to amount - 1)
            when (kind) {
                StudyEntertainmentKind.Douyin -> {
                    val next = videoCatalog.firstOrNull { it !in current.inventory.unlockedVideos }
                    if (next == null) {
                        message = "短视频收藏已经全部解锁，碎片已保留"
                        return@mutate current
                    }
                    message = "解锁短视频《$next》"
                    current.copy(
                        inventory = current.inventory.copy(
                            entertainmentFragments = remaining,
                            unlockedVideos = current.inventory.unlockedVideos + next,
                        ),
                        events = addEvent(current.events, "短视频解锁", next),
                    )
                }
                StudyEntertainmentKind.SideStory -> {
                    val next = theaterCatalog.firstOrNull { it !in current.inventory.unlockedTheaters }
                    if (next == null) {
                        message = "小剧场已经全部解锁，碎片已保留"
                        return@mutate current
                    }
                    message = "解锁小剧场《$next》"
                    current.copy(
                        inventory = current.inventory.copy(
                            entertainmentFragments = remaining,
                            unlockedTheaters = current.inventory.unlockedTheaters + next,
                        ),
                        events = addEvent(current.events, "小剧场解锁", next),
                    )
                }
            }
        }
        return message
    }

    fun claimAchievement(id: String): String {
        var message = "成就尚未解锁或已经领取"
        mutate { current ->
            val achievement = current.achievements.firstOrNull { it.id == id } ?: return@mutate current
            if (!achievement.unlocked || achievement.claimed) return@mutate current
            message = "领取《${achievement.title}》：单抽券 +${achievement.rewardSingleTickets}，夸夸值 +${achievement.rewardPraisePoints}"
            current.copy(
                achievements = current.achievements.map { if (it.id == id) it.copy(claimed = true) else it },
                inventory = current.inventory.copy(singleTickets = current.inventory.singleTickets + achievement.rewardSingleTickets),
                profile = current.profile.copy(praisePoints = current.profile.praisePoints + achievement.rewardPraisePoints),
                events = addEvent(current.events, "领取成就", message),
            )
        }
        return message
    }

    fun claimLevel(level: Int): String {
        var message = "等级奖励尚未达到或已经领取"
        mutate { current ->
            if (level > current.profile.level || level in current.profile.claimedLevels || level !in 1..StudyLevels.thresholds.size) return@mutate current
            val tickets = 1 + level / 5
            val praise = 10 + level * 2
            message = "领取 Lv.$level：单抽券 +$tickets，夸夸值 +$praise"
            current.copy(
                profile = current.profile.copy(
                    claimedLevels = current.profile.claimedLevels + level,
                    praisePoints = current.profile.praisePoints + praise,
                ),
                inventory = current.inventory.copy(singleTickets = current.inventory.singleTickets + tickets),
                events = addEvent(current.events, "等级奖励", message),
            )
        }
        return message
    }

    fun refreshShop(today: LocalDate = LocalDate.now()): String {
        var message = "商店已刷新"
        mutate { current ->
            val cost = if (current.shopDate == today.toString()) 10 + current.shopRefreshCount * 5 else 0
            if (current.profile.praisePoints < cost) {
                message = "夸夸值不足，刷新需要 $cost"
                return@mutate current
            }
            current.copy(
                profile = current.profile.copy(praisePoints = current.profile.praisePoints - cost),
                shopItems = defaultShop(today.plusDays(current.shopRefreshCount.toLong() + 1)),
                shopDate = today.toString(),
                shopRefreshCount = current.shopRefreshCount + 1,
                events = addEvent(current.events, "刷新商店", if (cost == 0) "每日自动刷新" else "消耗夸夸值 $cost"),
            )
        }
        return message
    }

    fun buyShopItem(id: String): String {
        var message = "购买失败"
        mutate { current ->
            val item = current.shopItems.firstOrNull { it.id == id } ?: return@mutate current
            if (item.remaining <= 0) {
                message = "商品已经售罄"
                return@mutate current
            }
            if (current.profile.praisePoints < item.cost) {
                message = "夸夸值不足，还需要 ${item.cost - current.profile.praisePoints}"
                return@mutate current
            }
            var inventory = current.inventory
            var profile = current.profile.copy(praisePoints = current.profile.praisePoints - item.cost)
            when (item.reward) {
                StudyShopReward.SingleTicket -> inventory = inventory.copy(singleTickets = inventory.singleTickets + item.amount)
                StudyShopReward.TenTicket -> inventory = inventory.copy(tenTickets = inventory.tenTickets + item.amount)
                StudyShopReward.SafePurpleTicket -> inventory = inventory.copy(safePurpleTickets = inventory.safePurpleTickets + item.amount)
                StudyShopReward.MysteryBox -> inventory = inventory.copy(mysteryBoxes = inventory.mysteryBoxes + item.amount)
                StudyShopReward.UniversalBlueFragment -> inventory = inventory.copy(universalBlueFragments = inventory.universalBlueFragments + item.amount)
                StudyShopReward.PraisePoints -> profile = profile.copy(praisePoints = profile.praisePoints + item.amount)
            }
            message = "购买成功：${item.title} ×${item.amount}"
            current.copy(
                profile = profile,
                inventory = inventory,
                shopItems = current.shopItems.map { if (it.id == id) it.copy(purchased = it.purchased + 1) else it },
                events = addEvent(current.events, "商店购买", message),
            )
        }
        return message
    }

    private fun consumeDraw(count: Int, ten: Boolean): List<StudyDrawResult> {
        var results = emptyList<StudyDrawResult>()
        mutate { current ->
            val ticketAvailable = if (ten) current.inventory.tenTickets > 0 else current.inventory.singleTickets > 0
            val praiseCost = if (ten) TEN_DRAW_PRAISE_COST else SINGLE_DRAW_PRAISE_COST
            if (!ticketAvailable && current.profile.praisePoints < praiseCost) return@mutate current
            var working = if (ticketAvailable) {
                current.copy(
                    inventory = if (ten) current.inventory.copy(tenTickets = current.inventory.tenTickets - 1)
                    else current.inventory.copy(singleTickets = current.inventory.singleTickets - 1),
                )
            } else {
                current.copy(profile = current.profile.copy(praisePoints = current.profile.praisePoints - praiseCost))
            }
            val rolled = mutableListOf<StudyDrawResult>()
            repeat(count) {
                val forcePurple = working.drawsSinceNonNormal >= NON_NORMAL_PITY - 1
                val applied = applyDrawReward(working, if (forcePurple) StudyDrawKind.PurpleFragment else null)
                working = applied.state.copy(
                    profile = applied.state.profile.copy(totalDraws = applied.state.profile.totalDraws + 1),
                )
                rolled += applied.result
            }
            results = rolled
            updateAchievements(
                working.copy(events = addEvent(working.events, if (ten) "十连抽" else "单抽", rolled.joinToString("、") { it.title })),
            )
        }
        return results
    }

    private data class AppliedDraw(val state: StudyState, val result: StudyDrawResult)

    private fun applyDrawReward(state: StudyState, forcedKind: StudyDrawKind?): AppliedDraw {
        val kind = forcedKind ?: rollDrawKind()
        var inventory = state.inventory
        var changed = true
        val title: String
        val rarity: String
        when (kind) {
            StudyDrawKind.TheaterFragment -> {
                val amount = inventory.entertainmentFragments[StudyEntertainmentKind.SideStory] ?: 0
                inventory = inventory.copy(entertainmentFragments = inventory.entertainmentFragments + (StudyEntertainmentKind.SideStory to amount + 1))
                title = "番外小剧场碎片"
                rarity = "1%"
            }
            StudyDrawKind.VideoFragment -> {
                val amount = inventory.entertainmentFragments[StudyEntertainmentKind.Douyin] ?: 0
                inventory = inventory.copy(entertainmentFragments = inventory.entertainmentFragments + (StudyEntertainmentKind.Douyin to amount + 1))
                title = "抖音视频碎片"
                rarity = "5%"
            }
            StudyDrawKind.PurpleFragment -> {
                inventory = inventory.copy(purpleFragments = inventory.purpleFragments + 1)
                title = "紫色碎片"
                rarity = "6%"
            }
            StudyDrawKind.BlueFragment -> {
                val key = blueFragmentCatalog.random()
                val old = inventory.blueFragments[key] ?: 0
                changed = old < BLUE_FRAGMENT_MAX
                inventory = inventory.copy(
                    blueFragments = if (changed) inventory.blueFragments + (key to old + 1) else inventory.blueFragments,
                )
                title = if (changed) "蓝色碎片 · $key (${old + 1}/$BLUE_FRAGMENT_MAX)" else "蓝色碎片 · $key（已满，仍展示本次抽中）"
                rarity = "88%"
            }
        }
        val result = StudyDrawResult(kind = kind, title = title, rarityLabel = rarity, inventoryChanged = changed)
        val nonNormal = kind != StudyDrawKind.BlueFragment
        return AppliedDraw(
            maybeUnlockScroll(
                state.copy(
                    inventory = inventory,
                    drawsSinceNonNormal = if (nonNormal) 0 else state.drawsSinceNonNormal + 1,
                ),
            ),
            result,
        )
    }

    private fun rollDrawKind(): StudyDrawKind {
        val value = Random.nextDouble()
        return when {
            value < 0.01 -> StudyDrawKind.TheaterFragment
            value < 0.06 -> StudyDrawKind.VideoFragment
            value < 0.12 -> StudyDrawKind.PurpleFragment
            else -> StudyDrawKind.BlueFragment
        }
    }

    private fun maybeUnlockScroll(state: StudyState): StudyState {
        val complete = blueFragmentCatalog.all { (state.inventory.blueFragments[it] ?: 0) >= BLUE_FRAGMENT_MAX }
        if (!complete || "月光学习画卷" in state.inventory.unlockedScrolls) return state
        return state.copy(
            inventory = state.inventory.copy(unlockedScrolls = state.inventory.unlockedScrolls + "月光学习画卷"),
            events = addEvent(state.events, "画卷解锁", "集齐全部蓝色碎片，解锁《月光学习画卷》"),
        )
    }

    private fun updateAchievements(state: StudyState): StudyState {
        val previousClaims = state.achievements.associate { it.id to it.claimed }
        val values = listOf(
            StudyAchievement("first_pomodoro", "第一次专注", "完成1个番茄钟", state.profile.totalPomodoros, 1),
            StudyAchievement("pomodoro_5", "进入状态", "累计完成5个番茄钟", state.profile.totalPomodoros, 5),
            StudyAchievement("pomodoro_20", "专注习惯", "累计完成20个番茄钟", state.profile.totalPomodoros, 20),
            StudyAchievement("pomodoro_50", "稳定推进", "累计完成50个番茄钟", state.profile.totalPomodoros, 50),
            StudyAchievement("pomodoro_100", "百次专注", "累计完成100个番茄钟", state.profile.totalPomodoros, 100, 2, 40),
            StudyAchievement("study_60", "学习一小时", "累计学习60分钟", state.profile.totalStudyMinutes, 60),
            StudyAchievement("study_300", "五小时里程碑", "累计学习300分钟", state.profile.totalStudyMinutes, 300),
            StudyAchievement("study_1000", "千分钟", "累计学习1000分钟", state.profile.totalStudyMinutes, 1000, 2, 50),
            StudyAchievement("vocab_50", "词汇热身", "累计复习50词", state.profile.vocabularyReviewed, 50),
            StudyAchievement("vocab_200", "词汇积累", "累计复习200词", state.profile.vocabularyReviewed, 200),
            StudyAchievement("vocab_1000", "词汇长跑", "累计复习1000词", state.profile.vocabularyReviewed, 1000, 2, 40),
            StudyAchievement("task_1", "今日开张", "累计完成1项任务", state.profile.totalTasksCompleted, 1),
            StudyAchievement("task_10", "清单推进", "累计完成10项任务", state.profile.totalTasksCompleted, 10),
            StudyAchievement("task_50", "清单终结者", "累计完成50项任务", state.profile.totalTasksCompleted, 50, 2, 40),
            StudyAchievement("sign_7", "连续签到", "累计签到7次", state.profile.totalSignIns, 7),
            StudyAchievement("draw_10", "月光初见", "累计抽取10次", state.profile.totalDraws, 10),
            StudyAchievement("draw_100", "收藏家", "累计抽取100次", state.profile.totalDraws, 100, 2, 50),
            StudyAchievement("praise_100", "值得夸夸", "拥有100夸夸值", state.profile.praisePoints, 100),
            StudyAchievement("level_5", "成长中的陪伴", "达到5级", state.profile.level, 5),
            StudyAchievement("level_10", "十级里程碑", "达到10级", state.profile.level, 10, 2, 50),
        )
        return state.copy(achievements = values.map { it.copy(claimed = previousClaims[it.id] == true) })
    }

    private fun rollover(state: StudyState, today: LocalDate): StudyState {
        if (state.activeDate == today.toString() && state.shopDate == today.toString()) return updateAchievements(state)
        val oldDate = runCatching { LocalDate.parse(state.activeDate) }.getOrDefault(today)
        val keepFrom = today.minusDays(90)
        val tasks = state.tasks.filter { runCatching { !LocalDate.parse(it.date).isBefore(keepFrom) }.getOrDefault(true) }
        val schedules = state.schedules.filter { runCatching { !LocalDate.parse(it.date).isBefore(keepFrom) }.getOrDefault(true) }
        val withDefaults = if (tasks.none { it.date == today.toString() }) tasks + defaultTasks(today) else tasks
        return updateAchievements(
            state.copy(
                activeDate = today.toString(),
                tasks = withDefaults,
                schedules = schedules,
                tips = if (state.tips.none { it.date == today.toString() }) defaultTips(today) + state.tips else state.tips,
                shopItems = if (state.shopDate != today.toString()) defaultShop(today) else state.shopItems,
                shopDate = today.toString(),
                shopRefreshCount = if (state.shopDate != today.toString()) 0 else state.shopRefreshCount,
                pomodoro = if (state.pomodoro.running && state.pomodoro.endAtEpochMillis <= System.currentTimeMillis()) {
                    state.pomodoro.copy(running = false, remainingSeconds = state.pomodoro.selectedMinutes * 60, endAtEpochMillis = 0)
                } else state.pomodoro,
                events = if (oldDate != today) addEvent(state.events, "日期切换", "学习数据已切换到 $today") else state.events,
            ),
        )
    }

    private fun parseSchedule(text: String, date: LocalDate): List<StudyScheduleBlock> {
        val clean = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val array = JSONArray(clean)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val start = item.optString("start").takeIf { TIME_REGEX.matches(it) } ?: continue
                val end = item.optString("end").takeIf { TIME_REGEX.matches(it) } ?: continue
                val title = item.optString("title").trim().takeIf { it.isNotBlank() } ?: continue
                add(StudyScheduleBlock(date = date.toString(), start = start, end = end, title = title))
            }
        }.sortedBy { it.start }
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
            .mapNotNull { raw -> runCatching { StudyStateCodec.decode(raw) }.getOrNull() }
            .firstOrNull()
            ?.let(::updateAchievements)
            ?: updateAchievements(StudyState())
    }

    private companion object {
        const val PREFS_NAME = "lulu_study_complete"
        const val KEY_STATE = "state"
        const val KEY_BACKUP = "state_backup"
        const val MAX_EVENTS = 200
        const val BLUE_FRAGMENT_MAX = 5
        const val NON_NORMAL_PITY = 30
        const val SINGLE_DRAW_PRAISE_COST = 20
        const val TEN_DRAW_PRAISE_COST = 180
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
