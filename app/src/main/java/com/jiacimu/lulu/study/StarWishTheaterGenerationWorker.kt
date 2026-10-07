package com.jiacimu.lulu.study

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal enum class StarWishTheaterTaskStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }

internal data class StarWishTheaterTask(
    val theater: String,
    val chapterNumber: Int,
    val status: StarWishTheaterTaskStatus,
    val message: String = "",
    val requestId: String = "",
    val influence: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    val active: Boolean get() = status == StarWishTheaterTaskStatus.QUEUED || status == StarWishTheaterTaskStatus.RUNNING
}

internal class StarWishTheaterGenerationManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val tasks: StateFlow<Map<String, StarWishTheaterTask>> = mutable.asStateFlow()

    @Synchronized fun enqueue(theater: String, influence: String): Result<Unit> = runCatching {
        val cleanTheater = theater.trim()
        require(cleanTheater.isNotBlank()) { "故事名称不能为空" }
        val existing = tasks.value[cleanTheater]
        val stale = existing?.active == true && System.currentTimeMillis() - existing.updatedAtMillis > STALE_TASK_MILLIS
        check(existing?.active != true || stale) { "这一章已经在生成中" }
        StarWishStores.initialize(appContext)
        LuluAiServices.initialize(appContext)
        require(influence.trim().length <= 3_000) { "剧情要求最多3000字，请精简后重试" }
        val requestId = UUID.randomUUID().toString()
        val chapterNumber = StarWishStores.main.state.value.theaterChapters[cleanTheater].orEmpty().size + 1
        check(chapterNumber <= StarWishRules.MAX_CHAPTERS_PER_THEATER) { "已达到本书章节上限" }
        setTask(StarWishTheaterTask(cleanTheater, chapterNumber, StarWishTheaterTaskStatus.QUEUED, "等待模型开始续写", requestId, influence.trim()))
        val request = OneTimeWorkRequestBuilder<StarWishTheaterGenerationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder()
                .putString(KEY_THEATER, cleanTheater)
                .putString(KEY_INFLUENCE, influence.trim())
                .putString(KEY_REQUEST_ID, requestId)
                .build())
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            workName(cleanTheater),
            if (stale) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        Unit
    }

    @Synchronized fun cancel(theater: String) {
        val cleanTheater = theater.trim()
        if (cleanTheater.isBlank()) return
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(cleanTheater))
        mutable.value = mutable.value - cleanTheater
        persist()
    }

    @Synchronized internal fun owns(theater: String, requestId: String) = tasks.value[theater]?.let {
        it.requestId == requestId && it.active
    } == true

    @Synchronized internal fun commitIfCurrent(theater: String, requestId: String, action: () -> Unit) {
        check(owns(theater, requestId)) { "本次续写已取消或被替换" }
        action()
    }

    @Synchronized internal fun mark(theater: String, requestId: String, status: StarWishTheaterTaskStatus, message: String) {
        if (!owns(theater, requestId)) return
        val old = tasks.value.getValue(theater)
        setTask(old.copy(status = status, message = message, updatedAtMillis = System.currentTimeMillis()))
    }

    private fun setTask(task: StarWishTheaterTask) {
        mutable.value = mutable.value + (task.theater to task)
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY_TASKS, JSONArray().apply {
            mutable.value.values.forEach { task ->
                put(JSONObject()
                    .put("theater", task.theater).put("chapter", task.chapterNumber)
                    .put("requestId", task.requestId).put("influence", task.influence).put("status", task.status.name).put("message", task.message).put("updatedAt", task.updatedAtMillis))
            }
        }.toString()).apply()
    }

    private fun load(): Map<String, StarWishTheaterTask> = runCatching {
        val array = JSONArray(prefs.getString(KEY_TASKS, "[]").orEmpty())
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val theater = item.optString("theater").trim()
                if (theater.isBlank()) continue
                val status = runCatching { StarWishTheaterTaskStatus.valueOf(item.optString("status")) }
                    .getOrDefault(StarWishTheaterTaskStatus.FAILED)
                put(theater, StarWishTheaterTask(
                    theater = theater,
                    chapterNumber = item.optInt("chapter", 1),
                    status = status,
                    message = item.optString("message"),
                    requestId = item.optString("requestId"),
                    influence = item.optString("influence"),
                    updatedAtMillis = item.optLong("updatedAt", System.currentTimeMillis()),
                ))
            }
        }
    }.getOrDefault(emptyMap())

    companion object {
        private const val PREFS_NAME = "lulu_star_wish_theater_tasks"
        private const val KEY_TASKS = "tasks_v1"
        private const val STALE_TASK_MILLIS = 20 * 60 * 1_000L
        internal const val KEY_REQUEST_ID = "request_id"
        internal const val KEY_THEATER = "theater"
        internal const val KEY_INFLUENCE = "influence"
        @Volatile private var instance: StarWishTheaterGenerationManager? = null

        fun get(context: Context): StarWishTheaterGenerationManager = instance ?: synchronized(this) {
            instance ?: StarWishTheaterGenerationManager(context.applicationContext).also { instance = it }
        }

        private fun workName(theater: String): String = "starwish-theater-${theater.hashCode()}"
    }
}

internal data class StarWishPlanTask(
    val theater: String,
    val chapterCount: Int,
    val status: StarWishTheaterTaskStatus,
    val message: String = "",
    val requestId: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    val active: Boolean get() = status == StarWishTheaterTaskStatus.QUEUED || status == StarWishTheaterTaskStatus.RUNNING
}

internal class StarWishPlanGenerationManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val tasks: StateFlow<Map<String, StarWishPlanTask>> = mutable.asStateFlow()

    @Synchronized fun enqueue(theater: String, characterId: String, chapterCount: Int): Result<Unit> = runCatching {
        val cleanTheater = theater.trim()
        require(cleanTheater.isNotBlank()) { "故事名称不能为空" }
        require(chapterCount in 1..StarWishRules.MAX_CHAPTERS_PER_THEATER) { "请先用 +3章 确定章节数量" }
        val existing = tasks.value[cleanTheater]
        val stale = existing?.active == true && System.currentTimeMillis() - existing.updatedAtMillis > STALE_TASK_MILLIS
        check(existing?.active != true || stale) { "章节规划已经在生成中" }

        StarWishStores.initialize(appContext)
        LuluAiServices.initialize(appContext)
        val requestId = UUID.randomUUID().toString()
        setTask(StarWishPlanTask(cleanTheater, chapterCount, StarWishTheaterTaskStatus.QUEUED, "等待模型开始规划", requestId))

        val request = OneTimeWorkRequestBuilder<StarWishPlanGenerationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                Data.Builder()
                    .putString(KEY_THEATER, cleanTheater)
                    .putString(KEY_CHARACTER_ID, characterId.trim())
                    .putInt(KEY_CHAPTER_COUNT, chapterCount)
                    .putString(KEY_REQUEST_ID, requestId)
                    .build(),
            )
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            workName(cleanTheater),
            if (stale) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        Unit
    }

    @Synchronized fun cancel(theater: String) {
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(theater))
        mutable.value = mutable.value - theater
        persist()
    }
    @Synchronized internal fun owns(theater: String, requestId: String) = tasks.value[theater]?.let {
        it.requestId == requestId && it.active
    } == true
    @Synchronized internal fun commitIfCurrent(theater: String, requestId: String, action: () -> Unit) {
        check(owns(theater, requestId)) { "规划已取消或被替换" }
        action()
    }
    @Synchronized internal fun mark(theater: String, requestId: String, status: StarWishTheaterTaskStatus, message: String) {
        if (!owns(theater, requestId)) return
        setTask(tasks.value.getValue(theater).copy(status = status, message = message, updatedAtMillis = System.currentTimeMillis()))
    }

    private fun setTask(task: StarWishPlanTask) {
        mutable.value = mutable.value + (task.theater to task)
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY_TASKS, JSONArray().apply {
            mutable.value.values.forEach { task ->
                put(
                    JSONObject()
                        .put("theater", task.theater)
                        .put("chapterCount", task.chapterCount).put("requestId", task.requestId)
                        .put("status", task.status.name)
                        .put("message", task.message)
                        .put("updatedAt", task.updatedAtMillis),
                )
            }
        }.toString()).apply()
    }

    private fun load(): Map<String, StarWishPlanTask> = runCatching {
        val array = JSONArray(prefs.getString(KEY_TASKS, "[]").orEmpty())
        buildMap {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val theater = item.optString("theater").trim()
                if (theater.isBlank()) continue
                val status = runCatching { StarWishTheaterTaskStatus.valueOf(item.optString("status")) }
                    .getOrDefault(StarWishTheaterTaskStatus.FAILED)
                put(
                    theater,
                    StarWishPlanTask(
                        theater = theater,
                        requestId = item.optString("requestId"),
                        chapterCount = item.optInt("chapterCount", 0),
                        status = status,
                        message = item.optString("message"),
                        updatedAtMillis = item.optLong("updatedAt", System.currentTimeMillis()),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())

    companion object {
        private const val PREFS_NAME = "lulu_star_wish_plan_tasks"
        private const val KEY_TASKS = "tasks_v1"
        private const val STALE_TASK_MILLIS = 30 * 60 * 1_000L
        internal const val KEY_REQUEST_ID = "request_id"
        internal const val KEY_THEATER = "theater"
        internal const val KEY_CHARACTER_ID = "characterId"
        internal const val KEY_CHAPTER_COUNT = "chapterCount"

        @Volatile private var instance: StarWishPlanGenerationManager? = null

        fun get(context: Context): StarWishPlanGenerationManager = instance ?: synchronized(this) {
            instance ?: StarWishPlanGenerationManager(context.applicationContext).also { instance = it }
        }

        private fun workName(theater: String): String = "starwish-plan-" + theater.hashCode()
    }
}

internal class StarWishPlanGenerationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val theater = inputData.getString(StarWishPlanGenerationManager.KEY_THEATER).orEmpty().trim()
        val characterId = inputData.getString(StarWishPlanGenerationManager.KEY_CHARACTER_ID).orEmpty().trim()
        val chapterCount = inputData.getInt(StarWishPlanGenerationManager.KEY_CHAPTER_COUNT, 0)
        if (theater.isBlank() || chapterCount <= 0) return Result.failure()

        val requestId = inputData.getString(StarWishPlanGenerationManager.KEY_REQUEST_ID).orEmpty()
        val manager = StarWishPlanGenerationManager.get(applicationContext)
        if (!manager.owns(theater, requestId)) return Result.success()
        StarWishStores.initialize(applicationContext)
        LuluAiServices.initialize(applicationContext)
        LuluRepositories.worldBook.initialize(applicationContext)
        manager.mark(theater, requestId, StarWishTheaterTaskStatus.RUNNING, "正在生成幕后规划")

        return try {
            val store = StarWishStores.main
            val planSnapshot = store.state.value
            var expected = planSnapshot
            fun saveProgress(action: () -> Unit) {
                check(!isStopped) { "规划已停止" }
                manager.commitIfCurrent(theater, requestId) {
                    store.updateTheaterIfUnchanged(theater, expected, action)
                    expected = store.state.value
                }
            }
            val guide = planSnapshot.theaterGuides[theater].orEmpty().trim()
            check(guide.isNotBlank()) { "总大纲不能为空" }
            val writtenChapters = planSnapshot.theaterChapters[theater].orEmpty()
            val existingPlans = planSnapshot.theaterPlans[theater].orEmpty().ifEmpty {
                starWishPlansFromLegacyGuide(guide)
            }
            val ledger = planSnapshot.theaterLedgers[theater]
            val theaterWorldBook = selectedWorldBookPrompt(planSnapshot.theaterWorldBookIds[theater].orEmpty())
            val existingBible = planSnapshot.theaterBibles[theater]
            val bible = StarWishTheaterPlanningEngine.generateStoryBible(
                characterId = characterId,
                storyTitle = theater,
                storyGuide = guide,
                chapterCount = chapterCount,
                writtenChapters = writtenChapters,
                existingBible = existingBible,
                ledger = ledger,
                theaterWorldBook = theaterWorldBook,
            ).getOrThrow()
            saveProgress { store.setBible(theater, checkNotNull(bible)) }

            val plans = StarWishTheaterPlanningEngine.generateChapterPlans(
                characterId = characterId,
                storyTitle = theater,
                storyGuide = guide,
                chapterCount = chapterCount,
                writtenChapters = writtenChapters,
                existingPlans = existingPlans,
                storyBible = bible,
                ledger = ledger,
                theaterWorldBook = theaterWorldBook,
                onProgress = { partialPlans ->
                    saveProgress { store.setStoryPlan(theater, guide, partialPlans) }
                    val done = partialPlans.count { it.outline.isNotBlank() && it.outline != "待规划" }
                    manager.mark(theater, requestId, StarWishTheaterTaskStatus.RUNNING, "逐章规划 $done/$chapterCount，已完成部分已保存")
                },
            ).getOrThrow()

            saveProgress { store.setStoryPlan(theater, guide, plans) }
            manager.mark(theater, requestId, StarWishTheaterTaskStatus.SUCCEEDED, "幕后规划与 $chapterCount 章剧情规划已保存")
            Result.success()
        } catch (cancelled: CancellationException) {
            manager.mark(theater, requestId, StarWishTheaterTaskStatus.QUEUED, "任务暂时中断，等待系统继续")
            throw cancelled
        } catch (error: Throwable) {
            manager.mark(theater, requestId, StarWishTheaterTaskStatus.FAILED, error.message ?: "章节规划生成失败")
            Result.failure()
        }
    }
}

internal class StarWishTheaterGenerationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val theater = inputData.getString(StarWishTheaterGenerationManager.KEY_THEATER).orEmpty().trim()
        val influence = inputData.getString(StarWishTheaterGenerationManager.KEY_INFLUENCE).orEmpty().trim()
        if (theater.isBlank()) return Result.failure()
        val requestId = inputData.getString(StarWishTheaterGenerationManager.KEY_REQUEST_ID).orEmpty()
        val manager = StarWishTheaterGenerationManager.get(applicationContext)
        if (!manager.owns(theater, requestId)) return Result.success()
        StarWishStores.initialize(applicationContext)
        LuluAiServices.initialize(applicationContext)
        LuluRepositories.worldBook.initialize(applicationContext)
        val store = StarWishStores.main
        val snapshot = store.state.value
        var expected = snapshot
        fun saveProgress(action: () -> Unit) {
            check(!isStopped) { "续写已停止" }
            manager.commitIfCurrent(theater, requestId) {
                store.updateTheaterIfUnchanged(theater, expected, action)
                expected = store.state.value
            }
        }
        val chapters = snapshot.theaterChapters[theater].orEmpty()
        val requestedChapter = manager.tasks.value[theater]?.chapterNumber ?: return Result.success()
        if (chapters.size >= requestedChapter) {
            manager.mark(theater, requestId, StarWishTheaterTaskStatus.SUCCEEDED, "第 $requestedChapter 章已保存")
            return Result.success()
        }
        val theaterWorldBook = selectedWorldBookPrompt(snapshot.theaterWorldBookIds[theater].orEmpty())
        val chapterNumber = chapters.size + 1
        manager.mark(theater, requestId, StarWishTheaterTaskStatus.RUNNING, "正在生成第 $chapterNumber 章；退出页面也会继续")
        return try {
            val builtInSeed = StarWishRules.theaters.firstOrNull { it.title == theater }
            var guide = snapshot.theaterGuides[theater].orEmpty().trim()
                .ifBlank { builtInSeed?.prompt.orEmpty().trim() }
            var plans = snapshot.theaterPlans[theater].orEmpty().ifEmpty { starWishPlansFromLegacyGuide(guide) }
            if (snapshot.theaterGuides[theater].isNullOrBlank() && guide.isNotBlank()) {
                saveProgress { store.setStoryPlan(theater, guide, plans) }
            }

            val needsBuiltInLongRangeBootstrap = false // Full-book replanning is an explicit planner action.
            val planningHorizon = if (builtInSeed != null) {
                maxOf(
                    plans.maxOfOrNull { it.number } ?: 0,
                    chapters.size + BUILTIN_PLAN_AHEAD_CHAPTERS,
                    BUILTIN_MIN_PLANNING_HORIZON,
                ).coerceAtMost(StarWishRules.MAX_CHAPTERS_PER_THEATER)
            } else {
                maxOf(plans.maxOfOrNull { it.number } ?: 0, chapterNumber)
            }

            if (chapters.isNotEmpty() && plans.isEmpty() && !hasFullStoryMap(guide)) {
                recoverStoryMap(theater, guide, chapters)?.takeIf(String::isNotBlank)?.let { recovered ->
                    guide = recovered
                    saveProgress { store.setStoryPlan(theater, guide, plans) }
                }
            }

            var ledger = snapshot.theaterLedgers[theater] ?: StarWishStoryLedger()
            if (chapters.isNotEmpty() && ledger.updatedThroughChapter != chapters.size) {
                ledger = rebuildLedger(theater, guide, plans, chapters)
                    ?: error("连续性档案重建失败，尚未续写。请重试。")
            }

            manager.mark(theater, requestId, StarWishTheaterTaskStatus.RUNNING, "正在准备本章规划与连续性")
            var bible = snapshot.theaterBibles[theater]
            if ((bible == null || needsBuiltInLongRangeBootstrap) && guide.isNotBlank()) {
                val refreshed = StarWishTheaterPlanningEngine.generateStoryBible(
                    characterId = ISOLATED_CHARACTER_ID,
                    storyTitle = theater,
                    storyGuide = guide,
                    chapterCount = planningHorizon,
                    writtenChapters = chapters,
                    existingBible = bible,
                    ledger = ledger,
                    theaterWorldBook = theaterWorldBook,
                )
                bible = if (bible == null) refreshed.getOrThrow() else refreshed.getOrNull() ?: bible
                saveProgress { store.setBible(theater, checkNotNull(bible)) }
            }

            var currentPlan = plans.firstOrNull { it.number == chapterNumber }
            if ((currentPlan == null || currentPlan.outline.isBlank() || currentPlan.outline == "待规划") && guide.isNotBlank()) {
                val planTarget = chapterNumber
                val plannedThroughCurrent = StarWishTheaterPlanningEngine.generateChapterPlans(
                    characterId = ISOLATED_CHARACTER_ID,
                    storyTitle = theater,
                    storyGuide = guide,
                    chapterCount = planTarget,
                    writtenChapters = chapters,
                    existingPlans = plans,
                    storyBible = bible,
                    ledger = ledger,
                    theaterWorldBook = theaterWorldBook,
                    onProgress = { partialPlans ->
                        saveProgress { store.setStoryPlan(theater, guide, partialPlans) }
                    },
                ).getOrThrow()
                val generatedCurrent = plannedThroughCurrent?.firstOrNull { it.number == chapterNumber }
                if (generatedCurrent != null && plannedThroughCurrent != null) {
                    plans = plannedThroughCurrent.sortedBy { it.number }
                    currentPlan = generatedCurrent
                    saveProgress { store.setStoryPlan(theater, guide, plans) }
                }
            }

            val recentChapters = chapters.takeLast(3).joinToString("\n\n") { chapter ->
                "${chapter.title}\n${chapter.content.takeLast(2_400)}"
            }
            val chapterFacts = buildString {
                appendLine("独立剧场故事：《$theater》")
                appendLine("故事地图：\n${guide.ifBlank { "尚未填写故事地图" }}")
                if (theaterWorldBook.isNotBlank()) {
                    appendLine("这本剧场选用的世界书（来自世界书 App，必须遵守）：\n$theaterWorldBook")
                }
                bible?.promptText()?.takeIf(String::isNotBlank)?.let {
                    appendLine("幕后长期规划（负责人物弧、明暗线、伏笔和长线节奏）：\n$it")
                }
                if (plans.isNotEmpty()) {
                    appendLine("本章附近的逐章规划：")
                    plans.filter { it.number in (chapterNumber - 2).coerceAtLeast(1)..(chapterNumber + 8) }
                        .forEach { plan -> appendLine("- 第${plan.number}章 ${plan.title}：${plan.outline}") }
                }
                if (currentPlan != null) appendLine("本章必须重点执行：${currentPlan.title}｜${currentPlan.outline}")
                if (ledger.updatedThroughChapter > 0) appendLine("截至第${ledger.updatedThroughChapter}章的连续性档案：\n${ledger.promptText()}")
                if (recentChapters.isNotBlank()) appendLine("最近章节原文：\n$recentChapters")
                chapters.lastOrNull()?.content?.takeLast(1_500)?.let { appendLine("上一章结尾连续性锚点：\n$it") }
                if (influence.isNotBlank()) appendLine("用户对本章的最高优先级要求：$influence")
            }
            val chapterInstruction = """
                续写第 $chapterNumber 章完整中文小说正文，约1800—3200字，只输出正文。
                这是完全独立的小剧场，不得引用任何真实角色设定、聊天、记忆、共同时间线、用户资料或未被本书选中的世界书。若本次素材中提供了“这本剧场选用的世界书”，它来自原世界书 App，是这本小说明确启用的权威规则，必须遵守。
                用户要求优先级最高；故事地图、已选世界书、幕后规划和逐章规划负责“精彩且符合设定”，连续性档案与硬事实负责“不能写崩”。新章必须发生在上一章最后一句之后，禁止重演已经完成的动作、对白、发现或决定。
                连续性档案里的硬事实是绝对约束：已经死亡的人不能无解释复活，亲属/身份/性别/婚恋关系不能莫名改变，伤势、物品归属、人物已知信息、阵营和地点不能回滚。若规划与正文事实冲突，以正文事实为准。
                人物必须有自己的欲望、判断和主动选择，事件要有因果，至少推进明线、暗线、关系线中的两条，并让伏笔有埋设、强化或回收。
                感情戏要有让读者心动的画面感：自然描写眼神、手指、腕骨、锁骨、肩颈、衣料、声音、呼吸、距离、光影和动作停顿，用潜台词与身体距离制造张力；不要机械堆砌身体部位。
                使用环境、五感、空间距离、动作余韵、神态、心理变化、潜台词和留白；不能流水账，也不能用直白结论代替描写。结尾留下自然钩子。不要输出提纲、解释、标题或系统提示。
            """.trimIndent()

            manager.mark(theater, requestId, StarWishTheaterTaskStatus.RUNNING, "正在写第 $chapterNumber 章正文")
            var reply = ""
            var lastGenerationError: Throwable? = null
            for (attempt in 1..2) {
                val result = LuluAiServices.gateway.generate(
                    characterId = ISOLATED_CHARACTER_ID,
                    facts = chapterFacts,
                    instruction = chapterInstruction,
                    source = "剧场",
                    title = "$theater · 第${chapterNumber}章",
                    maxTokens = 4_600,
                    connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
                    contextMode = CompanionContextMode.Isolated,
                    readTimeoutMillis = 240_000,
                )
                result.onSuccess { generated -> reply = generated.text.trim() }
                result.onFailure { error -> lastGenerationError = error }
                if (reply.isNotBlank()) break
                val retryableEmpty = lastGenerationError?.message?.contains("没有返回可读取", ignoreCase = true) == true ||
                    lastGenerationError?.message?.contains("空章节", ignoreCase = true) == true
                if (!retryableEmpty) break
            }
            if (reply.isBlank()) throw lastGenerationError ?: IllegalStateException("模型连续两次没有返回章节正文")

            val chapter = StarWishTheaterChapter(
                theater = theater,
                chapter = chapterNumber,
                title = currentPlan?.title?.trim().orEmpty().ifBlank { "第 $chapterNumber 章" },
                content = reply,
                userInfluence = influence,
            )
            coroutineContext.ensureActive()
            manager.commitIfCurrent(theater, requestId) { store.appendGeneratedChapter(chapter, expected) }
            // The chapter is already durable. A failed auxiliary request must not hide it or regenerate it.
            val updated = updateLedger(theater, guide, plans, ledger, chapter)
            coroutineContext.ensureActive()
            manager.commitIfCurrent(theater, requestId) {
                if (updated != null) store.setGeneratedLedger(theater, updated, expected, chapter)
            }
            manager.mark(theater, requestId, StarWishTheaterTaskStatus.SUCCEEDED,
                if (updated == null) "第 $chapterNumber 章已保存；连续性档案未更新，下次续写会先重建" else "第 $chapterNumber 章已生成")
            Result.success()
        } catch (cancelled: CancellationException) {
            manager.mark(theater, requestId, StarWishTheaterTaskStatus.QUEUED, "任务暂时中断，等待系统继续")
            throw cancelled
        } catch (error: Throwable) {
            manager.mark(theater, requestId, StarWishTheaterTaskStatus.FAILED, error.message ?: "章节生成失败")
            Result.failure()
        }
    }

    private fun hasFullStoryMap(guide: String): Boolean {
        if (guide.isBlank()) return false
        val newMarkers = listOf("【故事核心】", "【核心看点】", "【世界前提】", "【关系底色】")
        if (newMarkers.count(guide::contains) >= 2) return true
        val legacyMarkers = listOf("【故事总纲】", "【关系主线】", "【明线】", "【暗线】", "【伏笔系统】")
        return legacyMarkers.count(guide::contains) >= 3
    }

    private suspend fun recoverStoryMap(
        theater: String,
        currentGuide: String,
        chapters: List<StarWishTheaterChapter>,
    ): String? = runCatching {
        val seedPrompt = (
            StarWishCustomTheaterLibrary.get(applicationContext).all() + StarWishRules.theaters
        ).firstOrNull { it.title == theater }?.prompt.orEmpty()
        val evidence = chapters.takeLast(4).joinToString("\n\n") { chapter ->
            "第${chapter.chapter}章 ${chapter.title}\n${chapter.content.takeLast(2_800)}"
        }
        LuluAiServices.gateway.generate(
            characterId = ISOLATED_CHARACTER_ID,
            facts = buildString {
                appendLine("独立剧场故事：《$theater》")
                if (currentGuide.isNotBlank()) appendLine("旧总地图/残留设定：\n$currentGuide")
                if (seedPrompt.isNotBlank() && seedPrompt != currentGuide) appendLine("故事最初设定：\n$seedPrompt")
                if (evidence.isNotBlank()) appendLine("已经真实写出的章节证据：\n$evidence")
            },
            instruction = """
                为这部已经开始写作的小说补回一份简洁“故事地图”。已写正文是最高事实，不得改写、否定或让人物倒退。
                故事地图只负责读者层面的核心方向，不承担人物卡、逐章细纲、明暗线细节或伏笔明细；这些由幕后规划负责。
                只整理：【故事核心】【核心看点】【世界前提】【关系底色】【开篇/当前钩子】【基调与文风】。
                对尚未揭晓的部分只做最保守的补全，不要凭空换题材、换人物关系或推翻前文。只输出故事地图正文，不要解释。
            """.trimIndent(),
            source = "剧场",
            title = "$theater · 补回故事总地图",
            maxTokens = 3_400,
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 240_000,
        ).getOrThrow().text.trim().takeIf(String::isNotBlank)
    }.getOrNull()

    private suspend fun updateLedger(
        theater: String,
        guide: String,
        plans: List<StarWishChapterPlan>,
        previous: StarWishStoryLedger,
        chapter: StarWishTheaterChapter,
    ): StarWishStoryLedger? = runCatching {
        val raw = LuluAiServices.gateway.generate(
            characterId = ISOLATED_CHARACTER_ID,
            facts = buildString {
                appendLine("小说：《$theater》；刚完成第${chapter.chapter}章。")
                appendLine("总地图：\n$guide")
                if (plans.isNotEmpty()) appendLine("后续规划：\n${plans.filter { it.number > chapter.chapter }.joinToString("\n") { "第${it.number}章 ${it.title}：${it.outline}" }}")
                if (previous.updatedThroughChapter > 0) appendLine("旧连续性档案：\n${previous.promptText()}")
                appendLine("新章节正文：\n${chapter.content}")
            },
            instruction = """
                更新这部独立小说的连续性档案。只记录正文已经确认的事实，不得猜测，不得引用任何聊天或角色资料。
                只输出一个JSON对象，不要Markdown：
                {"summary":"截至本章的紧凑剧情摘要","characters":"人物位置、身体、情绪、目标、已知信息","worldState":"时间、地点、环境和世界规则的当前状态","relationships":"人物关系与本章变化","openThreads":"正在推进但未完成的明线与暗线","foreshadows":"已埋、已回收和待回收伏笔","keyItems":"关键物品、归属和状态","hardFacts":"不可随意改变的已确认事实：生死、亲属、身份、性别、婚恋、阵营、重要伤势、关键秘密知情情况、物品归属、已发生关键事件","updatedThroughChapter":${chapter.chapter}}
                hardFacts 必须继承旧档案中仍成立的硬事实，只能被新正文明确推翻/揭示反转时更新，不能自行猜测或回滚。其他字段保留真正影响后续写作的当前事实，删除已经失效的临时状态，整份控制在2200字以内。
            """.trimIndent(),
            source = "剧场",
            title = "$theater · 连续性档案",
            maxTokens = 1_500,
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 180_000,
        ).getOrThrow().text
        parseLedger(raw, chapter.chapter)
    }.getOrElse { if (it is CancellationException) throw it else null }

    private suspend fun rebuildLedger(
        theater: String,
        guide: String,
        plans: List<StarWishChapterPlan>,
        chapters: List<StarWishTheaterChapter>,
    ): StarWishStoryLedger? = runCatching {
        val selected = (chapters.take(3) + chapters.takeLast(12)).distinctBy { it.id }
        val raw = LuluAiServices.gateway.generate(
            characterId = ISOLATED_CHARACTER_ID,
            facts = buildString {
                appendLine("独立小说：《$theater》；当前保留到第${chapters.size}章。")
                appendLine("故事总地图：\n$guide")
                if (plans.isNotEmpty()) {
                    val relevantPlans = (plans.take(3) + plans.filter { it.number in (chapters.size - 2).coerceAtLeast(1)..(chapters.size + 8) }).distinctBy { it.id }
                    appendLine("相关逐章规划：\n${relevantPlans.joinToString("\n") { "第${it.number}章 ${it.title}：${it.outline}" }}")
                }
                appendLine("保留章节摘录：")
                selected.forEach { chapter -> appendLine("\n${chapter.title}\n${chapter.content.take(1_800)}") }
            },
            instruction = """
                重新建立这部小说截至当前章节的连续性档案。只能依据提供的故事内容，不得调用聊天、角色资料或其他世界信息。
                只输出JSON：{"summary":"","characters":"","worldState":"","relationships":"","openThreads":"","foreshadows":"","keyItems":"","hardFacts":"","updatedThroughChapter":${chapters.size}}
                重点保留人物位置与状态、关系变化、已知信息、关键物品、未完明暗线和待回收伏笔。hardFacts 专门整理生死、亲属、身份、性别、婚恋、阵营、重要伤势、关键秘密知情情况、物品归属和已经发生的关键事件，后续不得无解释违背。整份控制在2200字以内。
            """.trimIndent(),
            source = "剧场",
            title = "$theater · 重建连续性档案",
            maxTokens = 1_500,
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 180_000,
        ).getOrThrow().text
        parseLedger(raw, chapters.size)
    }.getOrElse { if (it is CancellationException) throw it else null }

    private fun parseLedger(raw: String, chapterNumber: Int): StarWishStoryLedger {
        var clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start >= 0 && end > start) clean = clean.substring(start, end + 1)
        val item = JSONObject(clean)
        check(listOf("summary", "characters", "worldState").any { item.optString(it).isNotBlank() }) { "连续性档案为空" }
        return StarWishStoryLedger(
            summary = item.optString("summary"), characters = item.optString("characters"),
            worldState = item.optString("worldState"), relationships = item.optString("relationships"),
            openThreads = item.optString("openThreads"), foreshadows = item.optString("foreshadows"),
            keyItems = item.optString("keyItems"), hardFacts = item.optString("hardFacts"),
            updatedThroughChapter = chapterNumber,
        )
    }

    private companion object {
        const val ISOLATED_CHARACTER_ID = "__starwish_theater_isolated__"
        const val BUILTIN_MIN_PLANNING_HORIZON = 12
        const val BUILTIN_PLAN_AHEAD_CHAPTERS = 8
    }
}

private fun selectedWorldBookPrompt(ids: Set<String>): String {
    if (ids.isEmpty()) return ""
    return LuluRepositories.worldBook.snapshot()
        .filter { it.id in ids }
        .joinToString("\n") { entry -> "- ${entry.title}：${entry.content}" }
        .trim()
}
internal fun StarWishStoryLedger.promptText(): String = buildString {
    if (summary.isNotBlank()) appendLine("剧情摘要：$summary")
    if (characters.isNotBlank()) appendLine("人物状态：$characters")
    if (worldState.isNotBlank()) appendLine("世界状态：$worldState")
    if (relationships.isNotBlank()) appendLine("关系变化：$relationships")
    if (openThreads.isNotBlank()) appendLine("未完线索：$openThreads")
    if (foreshadows.isNotBlank()) appendLine("伏笔：$foreshadows")
    if (keyItems.isNotBlank()) appendLine("关键物品：$keyItems")
    if (hardFacts.isNotBlank()) appendLine("硬事实（绝对不能无解释违背）：$hardFacts")
}.trim()
