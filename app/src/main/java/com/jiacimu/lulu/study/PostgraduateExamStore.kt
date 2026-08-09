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
        if (snapshot.profile.sleepRewardDate == today.toString()) return Result.success("今天的作息奖励已经判断过了")
        val facts = buildString {
            appendLine("日期：$today")
            appendLine("入睡时间：${sleepTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
            appendLine("起床时间：${wakeTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
            appendLine("睡眠时长：${"%.1f".format(durationHours)}小时")
        }
        return LuluAiServices.gateway.generate(
            characterId = snapshot.profile.selectedCharacterId,
            facts = facts,
            instruction = """
                由当前角色自己判断这次记录是否值得获得早睡奖励和早起奖励，不使用系统预设的固定入睡时间、起床时间或几点前/几点后的硬门槛。
                结合这次实际入睡时间、起床时间、睡眠时长，以及角色已经知道的用户情况来判断。早睡与早起两项必须独立判断，不能因为其中一项不理想就否定另一项。
                第一行严格只写 SLEEP_ALLOW 或 SLEEP_DENY。
                第二行严格只写 WAKE_ALLOW 或 WAKE_DENY。
                第三行起用角色自己的口吻自然回应。
            """.trimIndent(),
            source = "考研",
            title = "作息奖励判断",
            maxTokens = 420,
        ).map { reply ->
            val lines = reply.text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            val sleepAllowed = lines.any { it.equals("SLEEP_ALLOW", ignoreCase = true) }
            val wakeAllowed = lines.any { it.equals("WAKE_ALLOW", ignoreCase = true) }
            val roleText = lines
                .filterNot {
                    it.equals("SLEEP_ALLOW", ignoreCase = true) ||
                        it.equals("SLEEP_DENY", ignoreCase = true) ||
                        it.equals("WAKE_ALLOW", ignoreCase = true) ||
                        it.equals("WAKE_DENY", ignoreCase = true)
                }
                .joinToString("\n")
                .trim()
                .ifBlank { "今天的作息记录我收到了。" }
            val tickets = (if (sleepAllowed) 1 else 0) + (if (wakeAllowed) 1 else 0)
            val rewardText = buildList {
                if (sleepAllowed) add("早睡奖励：十连抽券 +1")
                if (wakeAllowed) add("早起奖励：十连抽券 +1")
            }.joinToString("\n").ifBlank { "本次未发放作息奖励" }
            var result = "$roleText\n$rewardText"
            mutate { current ->
                val state = rollover(current, today)
                if (state.profile.sleepRewardDate == today.toString()) return@mutate state
                result = "$roleText\n$rewardText"
                updateAchievements(
                    state.copy(
                        profile = state.profile.copy(sleepRewardDate = today.toString()),
                        inventory = state.inventory.copy(tenTickets = state.inventory.tenTickets + tickets),
                        events = addEvent(
                            state.events,
                            if (tickets > 0) "作息奖励" else "作息记录",
                            result,
                        ),
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

    fun addTask(
        title: String,
        date: LocalDate = LocalDate.now(),
        source: StudyTaskSource = StudyTaskSource.User,
    ) {
        val clean = title.trim()
        if (clean.isBlank()) return
        mutate { state ->
            state.copy(
                tasks = state.tasks + StudyTask(
                    title = clean,
                    date = date.toString(),
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
            val today = LocalDate.now().toString()
            val firstReward = complete && !task.rewarded && task.date == today
            val tasks = state.tasks.map {
                if (it.id == taskId) it.copy(
                    completed = complete,
                    rewarded = it.rewarded || firstReward,
                ) else it
            }
            val allTodayComplete = allTasksCompleteForDate(tasks, today)
            val unlockAllClear = complete && allTodayComplete && state.superMomentClaimedDate != today
            val detail = if (firstReward) "$task.title · 夸夸值 +$TASK_COMPLETION_PRAISE" else task.title
            var events = addEvent(state.events, if (complete) "待办完成" else "待办取消", detail)
            if (unlockAllClear) {
                events = addEvent(events, "今日待办全清", "全部待办完成 · 十连券 +2（20连）已自动到账")
            }
            updateAchievements(
                state.copy(
                    tasks = tasks,
                    profile = state.profile.copy(
                        praisePoints = state.profile.praisePoints + if (firstReward) TASK_COMPLETION_PRAISE else 0,
                        experience = state.profile.experience + if (firstReward) TASK_COMPLETION_PRAISE else 0,
                        totalTasksCompleted = state.profile.totalTasksCompleted + if (firstReward) 1 else 0,
                        lastStudyDate = if (firstReward) today else state.profile.lastStudyDate,
                    ),
                    inventory = if (unlockAllClear) {
                        state.inventory.copy(tenTickets = state.inventory.tenTickets + 2)
                    } else state.inventory,
                    superMomentAvailable = state.superMomentAvailable || unlockAllClear,
                    superMomentClaimedDate = if (unlockAllClear) today else state.superMomentClaimedDate,
                    events = events,
                ),
            )
        }
    }

    fun reviewVocabulary(count: Int) {
        val safe = count.coerceAtLeast(0)
        if (safe == 0) return
        val date = LocalDate.now().toString()
        mutate { state ->
            updateAchievements(
                state.copy(
                    profile = state.profile.copy(vocabularyReviewed = state.profile.vocabularyReviewed + safe),
                    dailyVocabularyReviewed = state.dailyVocabularyReviewed + (date to ((state.dailyVocabularyReviewed[date] ?: 0) + safe)),
                    events = addEvent(state.events, "词汇复习", "记录复习 $safe 个词"),
                ),
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
            tasks.forEach { appendLine("- ${it.title}") }
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
                "学习 $minutes 分钟，奖励进度 $remainder/$STUDY_REWARD_INTERVAL_MINUTES 分钟"
            }
            updateAchievements(
                state.copy(
                    pendingRewardMinutes = remainder,
                    profile = state.profile.copy(
                        praisePoints = state.profile.praisePoints + praise,
                        experience = state.profile.experience + praise,
                        totalStudyMinutes = state.profile.totalStudyMinutes + minutes,
                        totalPomodoros = state.profile.totalPomodoros + 1,
                        lastStudyDate = date,
                    ),
                    dailyStudyMinutes = state.dailyStudyMinutes + (date to ((state.dailyStudyMinutes[date] ?: 0) + minutes)),
                    dailyPomodoros = state.dailyPomodoros + (date to ((state.dailyPomodoros[date] ?: 0) + 1)),
                    pomodoro = state.pomodoro.copy(
                        running = false,
                        remainingSeconds = state.pomodoro.selectedMinutes * 60,
                        endAtEpochMillis = 0L,
                    ),
                    events = addEvent(state.events, "番茄钟完成", message),
                ),
            )
        }
        LuluRepositories.performance.updateDurations(
            DurationSummary(studyMinutes = mutableState.value.profile.totalStudyMinutes, chatMinutes = 0, callMinutes = 0),
        )
        return message
    }

    fun saveGachaRules(rules: List<StudyGachaRule>): String {
        if (rules.any { it.custom && it.title.trim().isBlank() }) return "保存失败：自定义项目名称不能为空"
        if (rules.any { !it.probabilityPercent.isFinite() || it.probabilityPercent < 0.0 || it.probabilityPercent > 100.0 }) {
            return "保存失败：单项概率必须在 0% 到 100% 之间"
        }
        if (rules.any { it.amountPerDraw !in 1..999 }) return "保存失败：单次获得数量必须在 1 到 999 之间"
        val repaired = repairGachaRules(rules)
        val total = repaired.sumOf(StudyGachaRule::probabilityPercent)
        if (total > 100.000001) return "保存失败：紫色、金色、彩色项目合计概率不能超过 100%"
        val customIds = repaired.filter(StudyGachaRule::custom).mapTo(mutableSetOf(), StudyGachaRule::id)
        mutate { state ->
            state.copy(
                gachaRules = repaired,
                inventory = state.inventory.copy(
                    customRewards = state.inventory.customRewards.filterKeys { it in customIds },
                ),
                events = addEvent(
                    state.events,
                    "抽卡概率设计",
                    "已保存 ${repaired.size} 个非蓝色项目；蓝色画卷使用剩余概率 ${probabilityText((100.0 - total).coerceAtLeast(0.0))}%",
                ),
            )
        }
        return "已保存 · 蓝色画卷 ${probabilityText((100.0 - total).coerceAtLeast(0.0))}%"
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
                val base = if (forcedRare) drawRare(working.gachaRules) else drawOne(working.gachaRules)
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

    private fun drawOne(rules: List<StudyGachaRule>): StudyDrawResult {
        val active = repairGachaRules(rules).filter { it.probabilityPercent > 0.0 }
        val roll = Random.nextDouble(100.0)
        var cursor = 0.0
        active.forEach { rule ->
            cursor += rule.probabilityPercent
            if (roll < cursor.coerceAtMost(100.0)) return rule.toDrawResult()
        }
        val scroll = blueFragmentCatalog.random()
        return StudyDrawResult(
            rewardRuleId = null,
            title = "$scroll · 专属碎片",
            rarity = StudyRarity.Normal,
            amount = 1,
            inventoryChanged = true,
        )
    }

    private fun drawRare(rules: List<StudyGachaRule>): StudyDrawResult {
        val repaired = repairGachaRules(rules)
        val rare = repaired.filter { it.rarity == StudyRarity.Rare && it.probabilityPercent > 0.0 }
        val pool = rare.ifEmpty { repaired.filter { it.probabilityPercent > 0.0 } }
        if (pool.isEmpty()) return drawOne(emptyList())
        val total = pool.sumOf(StudyGachaRule::probabilityPercent)
        var roll = Random.nextDouble(total)
        pool.forEach { rule ->
            if (roll < rule.probabilityPercent) return rule.toDrawResult()
            roll -= rule.probabilityPercent
        }
        return pool.last().toDrawResult()
    }

    private fun StudyGachaRule.toDrawResult(): StudyDrawResult {
        val amount = amountPerDraw.coerceIn(1, 999)
        return StudyDrawResult(
            rewardRuleId = id,
            title = if (amount > 1) "$title ×$amount" else title,
            rarity = rarity,
            amount = amount,
            inventoryChanged = true,
        )
    }

    private fun applyDraw(state: StudyState, initial: StudyDrawResult): Pair<StudyState, StudyDrawResult> {
        var inventory = state.inventory
        var result = initial
        val rewardRuleId = initial.rewardRuleId
        if (rewardRuleId == null) {
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
        } else {
            val rule = repairGachaRules(state.gachaRules).firstOrNull { it.id == rewardRuleId }
            if (rule == null) {
                result = initial.copy(inventoryChanged = false)
            } else {
                val amount = initial.amount.coerceIn(1, 999)
                inventory = when (rule.type) {
                    StudyGachaRewardType.Douyin -> inventory.copy(douyinTickets = inventory.douyinTickets + amount)
                    StudyGachaRewardType.GameRound -> inventory.copy(gameRoundTickets = inventory.gameRoundTickets + amount)
                    StudyGachaRewardType.Theater -> inventory.copy(theaterFragments = inventory.theaterFragments + amount)
                    StudyGachaRewardType.Movie -> inventory.copy(gameTickets = inventory.gameTickets + amount)
                    StudyGachaRewardType.Anime -> inventory.copy(animeTickets = inventory.animeTickets + amount)
                    StudyGachaRewardType.Custom -> inventory.copy(
                        customRewards = inventory.customRewards + (rule.id to ((inventory.customRewards[rule.id] ?: 0) + amount)),
                    )
                }
            }
        }
        val streak = if (result.rarity == StudyRarity.Normal) state.drawsSinceNonNormal + 1 else 0
        return state.copy(inventory = inventory, drawsSinceNonNormal = streak.coerceAtMost(NON_NORMAL_PITY - 1)) to result
    }

    fun claimSuperMoment(): String {
        // 兼容旧调用：全清奖励现在在最后一个待办完成时已经自动入账，这里绝不重复发券。
        var message = "当前没有待展示的全清奖励"
        mutate { state ->
            if (!state.superMomentAvailable) return@mutate state
            message = "今日待办全清：20连奖励已经自动到账"
            state.copy(superMomentAvailable = false)
        }
        return message
    }

    fun dismissSuperMomentCelebration() = mutate { state ->
        if (state.superMomentAvailable) state.copy(superMomentAvailable = false) else state
    }

    fun exchangeSingleTicketsForTen(): String {
        var message = "单抽券不足，需要10张"
        mutate { state ->
            if (state.inventory.singleTickets < 10) return@mutate state
            message = "兑换成功：单抽券 -10，十连券 +1"
            state.copy(
                inventory = state.inventory.copy(
                    singleTickets = state.inventory.singleTickets - 10,
                    tenTickets = state.inventory.tenTickets + 1,
                ),
                events = addEvent(state.events, "抽卡券兑换", message),
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
                StudyEntertainmentKind.Anime -> {
                    if (inventory.animeTickets <= 0) return@mutate state
                    message = "影视剧一季兑换券已使用 · 可兑换一整季"
                    state.copy(inventory = inventory.copy(animeTickets = inventory.animeTickets - 1), events = addEvent(state.events, "娱乐券", message))
                }
            }
        }
        return message
    }

    fun redeemCustomReward(ruleId: String): String {
        var message = "对应收藏数量不足"
        mutate { state ->
            val rule = state.gachaRules.firstOrNull { it.id == ruleId && it.custom } ?: return@mutate state
            val current = state.inventory.customRewards[ruleId] ?: 0
            if (current <= 0) return@mutate state
            message = "已使用：${rule.title}"
            val nextMap = if (current <= 1) state.inventory.customRewards - ruleId
            else state.inventory.customRewards + (ruleId to current - 1)
            state.copy(
                inventory = state.inventory.copy(customRewards = nextMap),
                events = addEvent(state.events, "自定义收藏", message),
            )
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
                StudyShopReward.TenTicket -> inventory.copy(tenTickets = inventory.tenTickets + item.amount)
                StudyShopReward.DouyinTicket -> inventory.copy(douyinTickets = inventory.douyinTickets + item.amount)
                StudyShopReward.GameRoundTicket -> inventory.copy(gameRoundTickets = inventory.gameRoundTickets + item.amount)
                StudyShopReward.TheaterFragment -> inventory.copy(theaterFragments = inventory.theaterFragments + item.amount)
                StudyShopReward.GameTicket -> inventory.copy(gameTickets = inventory.gameTickets + item.amount)
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
        val maxDailyStudyMinutes = state.dailyStudyMinutes.values.maxOrNull() ?: 0
        val maxSevenDayStudyMinutes = maxStudyMinutesInWindow(state.dailyStudyMinutes, 7)
        val longestThreeHourStreak = longestStudyStreak(state.dailyStudyMinutes, 180)
        val values = listOf(
            StudyAchievement("daily_1h", "第一小时", "单日学习达到1小时", maxDailyStudyMinutes, 60, 1, 100),
            StudyAchievement("daily_2h", "坐稳两小时", "单日学习达到2小时", maxDailyStudyMinutes, 120, 2, 200),
            StudyAchievement("daily_3h", "三小时进入状态", "单日学习达到3小时", maxDailyStudyMinutes, 180, 3, 300),
            StudyAchievement("daily_4h", "四小时主场", "单日学习达到4小时", maxDailyStudyMinutes, 240, 4, 400),
            StudyAchievement("daily_5h", "长日初成", "单日学习达到5小时", maxDailyStudyMinutes, 300, 5, 500),
            StudyAchievement("daily_6h", "六小时定力", "单日学习达到6小时", maxDailyStudyMinutes, 360, 8, 800),
            StudyAchievement("daily_7h", "七小时深潜", "单日学习达到7小时", maxDailyStudyMinutes, 420, 12, 1_200),
            StudyAchievement("daily_8h", "八小时全神", "单日学习达到8小时", maxDailyStudyMinutes, 480, 20, 2_000),
            StudyAchievement("week_10h", "七日十小时", "任意连续7天累计学习10小时", maxSevenDayStudyMinutes, 600, 3, 300),
            StudyAchievement("week_15h", "七日十五小时", "任意连续7天累计学习15小时", maxSevenDayStudyMinutes, 900, 5, 500),
            StudyAchievement("week_20h", "七日二十小时", "任意连续7天累计学习20小时", maxSevenDayStudyMinutes, 1_200, 8, 800),
            StudyAchievement("week_25h", "七日二十五小时", "任意连续7天累计学习25小时", maxSevenDayStudyMinutes, 1_500, 10, 1_000),
            StudyAchievement("week_30h", "七日三十小时", "任意连续7天累计学习30小时", maxSevenDayStudyMinutes, 1_800, 15, 1_500),
            StudyAchievement("week_35h", "七日三十五小时", "任意连续7天累计学习35小时", maxSevenDayStudyMinutes, 2_100, 20, 2_000),
            StudyAchievement("week_40h", "七日四十小时", "任意连续7天累计学习40小时", maxSevenDayStudyMinutes, 2_400, 30, 3_000),
            StudyAchievement("streak_3h_3", "三日不断线", "连续3天每天学习至少3小时", longestThreeHourStreak, 3, 2, 200),
            StudyAchievement("streak_3h_7", "一周不断线", "连续7天每天学习至少3小时", longestThreeHourStreak, 7, 5, 500),
            StudyAchievement("streak_3h_14", "十四日不断线", "连续14天每天学习至少3小时", longestThreeHourStreak, 14, 10, 1_000),
            StudyAchievement("streak_3h_30", "三十日成习", "连续30天每天学习至少3小时", longestThreeHourStreak, 30, 20, 2_500),
            StudyAchievement("streak_3h_60", "六十日长燃", "连续60天每天学习至少3小时", longestThreeHourStreak, 60, 35, 5_000),
            StudyAchievement("streak_3h_100", "百日不熄", "连续100天每天学习至少3小时", longestThreeHourStreak, 100, 60, 10_000),
            StudyAchievement("signin_3", "三日见面", "累计签到3天", state.profile.totalSignIns, 3, 1, 100),
            StudyAchievement("signin_7", "签到一周", "累计签到7天", state.profile.totalSignIns, 7, 2, 200),
            StudyAchievement("signin_30", "签到成习", "累计签到30天", state.profile.totalSignIns, 30, 5, 600),
            StudyAchievement("pomodoro_1", "第一次落座", "完成第1个番茄钟", state.profile.totalPomodoros, 1, 1, 100),
            StudyAchievement("pomodoro_10", "十次专注", "累计完成10个番茄钟", state.profile.totalPomodoros, 10, 2, 200),
            StudyAchievement("pomodoro_30", "三十次落座", "累计完成30个番茄钟", state.profile.totalPomodoros, 30, 3, 300),
            StudyAchievement("pomodoro_50", "五十次归位", "累计完成50个番茄钟", state.profile.totalPomodoros, 50, 4, 400),
            StudyAchievement("pomodoro_100", "百次落座", "累计完成100个番茄钟", state.profile.totalPomodoros, 100, 5, 500),
            StudyAchievement("pomodoro_300", "三百次不退场", "累计完成300个番茄钟", state.profile.totalPomodoros, 300, 10, 1_000),
            StudyAchievement("pomodoro_500", "五百次专注", "累计完成500个番茄钟", state.profile.totalPomodoros, 500, 15, 2_000),
            StudyAchievement("pomodoro_1000", "千次钟声", "累计完成1000个番茄钟", state.profile.totalPomodoros, 1_000, 30, 5_000),
            StudyAchievement("pomodoro_2000", "两千次归位", "累计完成2000个番茄钟", state.profile.totalPomodoros, 2_000, 60, 12_000),
            StudyAchievement("task_10", "十件事做完", "累计完成10项待办", state.profile.totalTasksCompleted, 10, 1, 150),
            StudyAchievement("task_30", "清单开始听话", "累计完成30项待办", state.profile.totalTasksCompleted, 30, 2, 250),
            StudyAchievement("task_50", "五十项兑现", "累计完成50项待办", state.profile.totalTasksCompleted, 50, 3, 400),
            StudyAchievement("task_100", "百项兑现", "累计完成100项待办", state.profile.totalTasksCompleted, 100, 5, 800),
            StudyAchievement("task_300", "清单成山", "累计完成300项待办", state.profile.totalTasksCompleted, 300, 10, 1_500),
            StudyAchievement("task_500", "五百次完成", "累计完成500项待办", state.profile.totalTasksCompleted, 500, 15, 3_000),
            StudyAchievement("task_1000", "千项落地", "累计完成1000项待办", state.profile.totalTasksCompleted, 1_000, 30, 6_000),
            StudyAchievement("task_2000", "两千项兑现", "累计完成2000项待办", state.profile.totalTasksCompleted, 2_000, 60, 12_000),
            StudyAchievement("study_1h", "第一盏灯", "累计学习1小时", state.profile.totalStudyMinutes, 60, 1, 100),
            StudyAchievement("study_10h", "十小时起步", "累计学习10小时", state.profile.totalStudyMinutes, 600, 2, 250),
            StudyAchievement("study_25h", "二十五小时路标", "累计学习25小时", state.profile.totalStudyMinutes, 1_500, 3, 400),
            StudyAchievement("study_50h", "五十小时灯火", "累计学习50小时", state.profile.totalStudyMinutes, 3_000, 5, 500),
            StudyAchievement("study_100h", "百小时长路", "累计学习100小时", state.profile.totalStudyMinutes, 6_000, 10, 1_000),
            StudyAchievement("study_300h", "三百小时沉潜", "累计学习300小时", state.profile.totalStudyMinutes, 18_000, 20, 3_000),
            StudyAchievement("study_500h", "五百小时成林", "累计学习500小时", state.profile.totalStudyMinutes, 30_000, 30, 5_000),
            StudyAchievement("study_1000h", "千小时远征", "累计学习1000小时", state.profile.totalStudyMinutes, 60_000, 50, 10_000),
            StudyAchievement("study_1500h", "一千五百小时", "累计学习1500小时", state.profile.totalStudyMinutes, 90_000, 80, 16_000),
            StudyAchievement("study_2000h", "两千小时长征", "累计学习2000小时", state.profile.totalStudyMinutes, 120_000, 100, 20_000),
            StudyAchievement("vocab_500", "五百次重逢", "累计复习500个词", state.profile.vocabularyReviewed, 500, 1, 100),
            StudyAchievement("vocab_1000", "千词起步", "累计复习1000个词", state.profile.vocabularyReviewed, 1_000, 2, 200),
            StudyAchievement("vocab_3000", "三千词痕", "累计复习3000个词", state.profile.vocabularyReviewed, 3_000, 3, 350),
            StudyAchievement("vocab_5000", "五千词痕", "累计复习5000个词", state.profile.vocabularyReviewed, 5_000, 5, 500),
            StudyAchievement("vocab_10000", "万词成路", "累计复习10000个词", state.profile.vocabularyReviewed, 10_000, 10, 1_000),
            StudyAchievement("vocab_30000", "三万次重逢", "累计复习30000个词", state.profile.vocabularyReviewed, 30_000, 20, 3_000),
            StudyAchievement("vocab_50000", "五万词海", "累计复习50000个词", state.profile.vocabularyReviewed, 50_000, 35, 5_000),
            StudyAchievement("draw_10", "十次愿望", "累计抽卡10次", state.profile.totalDraws, 10, 1, 100),
            StudyAchievement("draw_30", "三十次愿望", "累计抽卡30次", state.profile.totalDraws, 30, 2, 200),
            StudyAchievement("draw_100", "百次愿望", "累计抽卡100次", state.profile.totalDraws, 100, 5, 500),
        )
        return state.copy(achievements = values.map { it.copy(claimed = claims[it.id] == true) })
    }

    private fun maxStudyMinutesInWindow(
        dailyStudyMinutes: Map<String, Int>,
        windowDays: Int,
    ): Int {
        val dates = dailyStudyMinutes.keys.mapNotNull { key ->
            runCatching { LocalDate.parse(key) }.getOrNull()
        }.sorted()
        if (dates.isEmpty() || windowDays <= 0) return 0
        var start = dates.first()
        val last = dates.last()
        var best = 0
        while (!start.isAfter(last)) {
            var total = 0
            repeat(windowDays) { offset ->
                total += dailyStudyMinutes[start.plusDays(offset.toLong()).toString()] ?: 0
            }
            best = maxOf(best, total)
            start = start.plusDays(1)
        }
        return best
    }

    private fun longestStudyStreak(
        dailyStudyMinutes: Map<String, Int>,
        minimumDailyMinutes: Int,
    ): Int {
        val dates = dailyStudyMinutes
            .filterValues { it >= minimumDailyMinutes }
            .keys
            .mapNotNull { key -> runCatching { LocalDate.parse(key) }.getOrNull() }
            .distinct()
            .sorted()
        if (dates.isEmpty()) return 0
        var longest = 1
        var current = 1
        for (index in 1 until dates.size) {
            current = if (dates[index] == dates[index - 1].plusDays(1)) current + 1 else 1
            longest = maxOf(longest, current)
        }
        return longest
    }

    private fun usesLegacyShopPricing(items: List<StudyShopItem>): Boolean =
        items.any { item -> item.cost != item.reward.shopCost() }

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
        val legacyShop = usesLegacyShopPricing(state.shopItems)
        if (!dateChanged && state.shopDate == todayKey && withDefaults == state.tasks && !legacyShop) return updateAchievements(state)
        return updateAchievements(
            state.copy(
                activeDate = todayKey,
                tasks = withDefaults,
                tips = if (state.tips.none { it.date == today.toString() }) defaultTips(today) + state.tips else state.tips,
                shopItems = if (state.shopDate == todayKey && !legacyShop) state.shopItems else defaultShop(today),
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

    private fun probabilityText(value: Double): String {
        val rounded = kotlin.math.round(value * 1000.0) / 1000.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString().trimEnd('0').trimEnd('.')
    }

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
