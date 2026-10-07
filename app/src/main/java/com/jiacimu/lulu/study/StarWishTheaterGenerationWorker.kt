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
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import kotlinx.coroutines.CancellationException
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
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    val active: Boolean get() = status == StarWishTheaterTaskStatus.QUEUED || status == StarWishTheaterTaskStatus.RUNNING
}

internal class StarWishTheaterGenerationManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val tasks: StateFlow<Map<String, StarWishTheaterTask>> = mutable.asStateFlow()

    fun enqueue(theater: String, influence: String): Result<Unit> = runCatching {
        val cleanTheater = theater.trim()
        require(cleanTheater.isNotBlank()) { "故事名称不能为空" }
        val existing = tasks.value[cleanTheater]
        val stale = existing?.active == true && System.currentTimeMillis() - existing.updatedAtMillis > STALE_TASK_MILLIS
        check(existing?.active != true || stale) { "这一章已经在生成中" }
        StarWishStores.initialize(appContext)
        LuluAiServices.initialize(appContext)
        val chapterNumber = StarWishStores.main.state.value.theaterChapters[cleanTheater].orEmpty().size + 1
        setTask(StarWishTheaterTask(cleanTheater, chapterNumber, StarWishTheaterTaskStatus.QUEUED, "等待模型开始续写"))
        val request = OneTimeWorkRequestBuilder<StarWishTheaterGenerationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(Data.Builder()
                .putString(KEY_THEATER, cleanTheater)
                .putString(KEY_INFLUENCE, influence.trim().take(3_000))
                .build())
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            workName(cleanTheater),
            if (stale) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
        Unit
    }

    fun cancel(theater: String) {
        val cleanTheater = theater.trim()
        if (cleanTheater.isBlank()) return
        WorkManager.getInstance(appContext).cancelUniqueWork(workName(cleanTheater))
        mutable.value = mutable.value - cleanTheater
        persist()
    }

    internal fun running(theater: String, chapterNumber: Int) = setTask(
        StarWishTheaterTask(theater, chapterNumber, StarWishTheaterTaskStatus.RUNNING, "正在生成第 $chapterNumber 章；退出页面也会继续"),
    )

    internal fun succeeded(theater: String, chapterNumber: Int) = setTask(
        StarWishTheaterTask(theater, chapterNumber, StarWishTheaterTaskStatus.SUCCEEDED, "第 $chapterNumber 章已生成"),
    )

    internal fun failed(theater: String, chapterNumber: Int, message: String) = setTask(
        StarWishTheaterTask(theater, chapterNumber, StarWishTheaterTaskStatus.FAILED, message.ifBlank { "章节生成失败" }),
    )

    internal fun queuedAgain(theater: String, chapterNumber: Int) = setTask(
        StarWishTheaterTask(theater, chapterNumber, StarWishTheaterTaskStatus.QUEUED, "任务暂时中断，等待系统继续"),
    )

    private fun setTask(task: StarWishTheaterTask) {
        mutable.value = mutable.value + (task.theater to task)
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY_TASKS, JSONArray().apply {
            mutable.value.values.forEach { task ->
                put(JSONObject()
                    .put("theater", task.theater).put("chapter", task.chapterNumber)
                    .put("status", task.status.name).put("message", task.message).put("updatedAt", task.updatedAtMillis))
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
                    updatedAtMillis = item.optLong("updatedAt", System.currentTimeMillis()),
                ))
            }
        }
    }.getOrDefault(emptyMap())

    companion object {
        private const val PREFS_NAME = "lulu_star_wish_theater_tasks"
        private const val KEY_TASKS = "tasks_v1"
        private const val STALE_TASK_MILLIS = 20 * 60 * 1_000L
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
    val updatedAtMillis: Long = System.currentTimeMillis(),
) {
    val active: Boolean get() = status == StarWishTheaterTaskStatus.QUEUED || status == StarWishTheaterTaskStatus.RUNNING
}

internal class StarWishPlanGenerationManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val tasks: StateFlow<Map<String, StarWishPlanTask>> = mutable.asStateFlow()

    fun enqueue(theater: String, characterId: String, chapterCount: Int): Result<Unit> = runCatching {
        val cleanTheater = theater.trim()
        require(cleanTheater.isNotBlank()) { "故事名称不能为空" }
        require(chapterCount in 1..StarWishRules.MAX_CHAPTERS_PER_THEATER) { "请先用 +3章 确定章节数量" }
        val existing = tasks.value[cleanTheater]
        val stale = existing?.active == true && System.currentTimeMillis() - existing.updatedAtMillis > STALE_TASK_MILLIS
        check(existing?.active != true || stale) { "章节规划已经在生成中" }

        StarWishStores.initialize(appContext)
        LuluAiServices.initialize(appContext)
        setTask(StarWishPlanTask(cleanTheater, chapterCount, StarWishTheaterTaskStatus.QUEUED, "等待模型开始规划"))

        val request = OneTimeWorkRequestBuilder<StarWishPlanGenerationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                Data.Builder()
                    .putString(KEY_THEATER, cleanTheater)
                    .putString(KEY_CHARACTER_ID, characterId.trim())
                    .putInt(KEY_CHAPTER_COUNT, chapterCount)
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

    internal fun running(theater: String, chapterCount: Int) = setTask(
        StarWishPlanTask(theater, chapterCount, StarWishTheaterTaskStatus.RUNNING, "正在按总大纲规划 $chapterCount 章；退出页面也会继续"),
    )

    internal fun succeeded(theater: String, chapterCount: Int) = setTask(
        StarWishPlanTask(theater, chapterCount, StarWishTheaterTaskStatus.SUCCEEDED, "$chapterCount 章章节规划已生成"),
    )

    internal fun failed(theater: String, chapterCount: Int, message: String) = setTask(
        StarWishPlanTask(theater, chapterCount, StarWishTheaterTaskStatus.FAILED, message.ifBlank { "章节规划生成失败" }),
    )

    internal fun queuedAgain(theater: String, chapterCount: Int) = setTask(
        StarWishPlanTask(theater, chapterCount, StarWishTheaterTaskStatus.QUEUED, "任务暂时中断，等待系统继续"),
    )

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
                        .put("chapterCount", task.chapterCount)
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

        val manager = StarWishPlanGenerationManager.get(applicationContext)
        StarWishStores.initialize(applicationContext)
        LuluAiServices.initialize(applicationContext)
        manager.running(theater, chapterCount)

        return try {
            val store = StarWishStores.main
            val planSnapshot = store.state.value
            val guide = planSnapshot.theaterGuides[theater].orEmpty().trim()
            check(guide.isNotBlank()) { "总大纲不能为空" }
            val writtenChapters = planSnapshot.theaterChapters[theater].orEmpty()
            val existingPlans = planSnapshot.theaterPlans[theater].orEmpty().ifEmpty {
                starWishPlansFromLegacyGuide(guide)
            }

            val plans = StarWishTheaterPlanningEngine.generateChapterPlans(
                characterId = characterId,
                storyTitle = theater,
                storyGuide = guide,
                chapterCount = chapterCount,
                writtenChapters = writtenChapters,
                existingPlans = existingPlans,
            ).getOrThrow()

            store.setStoryPlan(theater, guide, plans)
            manager.succeeded(theater, chapterCount)
            Result.success()
        } catch (cancelled: CancellationException) {
            manager.queuedAgain(theater, chapterCount)
            throw cancelled
        } catch (error: Throwable) {
            manager.failed(theater, chapterCount, error.message ?: "章节规划生成失败")
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
        val manager = StarWishTheaterGenerationManager.get(applicationContext)
        StarWishStores.initialize(applicationContext)
        LuluAiServices.initialize(applicationContext)
        val store = StarWishStores.main
        val snapshot = store.state.value
        val chapters = snapshot.theaterChapters[theater].orEmpty()
        val chapterNumber = chapters.size + 1
        manager.running(theater, chapterNumber)
        return try {
            var guide = snapshot.theaterGuides[theater].orEmpty().trim()
            var plans = snapshot.theaterPlans[theater].orEmpty().ifEmpty { starWishPlansFromLegacyGuide(guide) }

            if (chapters.isNotEmpty() && plans.isEmpty() && !hasFullStoryMap(guide)) {
                recoverStoryMap(theater, guide, chapters)?.takeIf(String::isNotBlank)?.let { recovered ->
                    guide = recovered
                    store.setStoryPlan(theater, guide, plans)
                }
            }

            var ledger = snapshot.theaterLedgers[theater] ?: StarWishStoryLedger()
            if (chapters.isNotEmpty() && ledger.updatedThroughChapter != chapters.size) {
                rebuildLedger(theater, guide, plans, chapters)?.let { rebuilt ->
                    ledger = rebuilt
                    store.setLedger(theater, rebuilt)
                }
            }

            var currentPlan = plans.firstOrNull { it.number == chapterNumber }
            if (currentPlan == null && guide.isNotBlank()) {
                val plannedThroughCurrent = StarWishTheaterPlanningEngine.generateChapterPlans(
                    characterId = ISOLATED_CHARACTER_ID,
                    storyTitle = theater,
                    storyGuide = guide,
                    chapterCount = chapterNumber,
                    writtenChapters = chapters,
                    existingPlans = plans,
                ).getOrNull()
                val generatedCurrent = plannedThroughCurrent?.firstOrNull { it.number == chapterNumber }
                if (generatedCurrent != null && plannedThroughCurrent != null) {
                    plans = plannedThroughCurrent.sortedBy { it.number }
                    currentPlan = generatedCurrent
                    store.setStoryPlan(theater, guide, plans)
                }
            }

            val recentChapters = chapters.takeLast(3).joinToString("\n\n") { chapter ->
                "${chapter.title}\n${chapter.content.takeLast(2_400)}"
            }
            val chapterFacts = buildString {
                appendLine("独立剧场故事：《$theater》")
                appendLine("故事总地图：\n${guide.ifBlank { "尚未填写总地图" }}")
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
                这是完全独立的小剧场，不得引用任何真实角色设定、聊天、记忆、共同时间线、用户资料或世界书。
                用户要求优先级最高；故事地图和逐章规划是导航。新章必须发生在上一章最后一句之后，禁止重演已经完成的动作、对白、发现或决定。
                严格继承连续性档案中的人物位置、身体状态、情绪、关系、已知信息、物品、明暗线与伏笔。若用户改变剧情方向，应自然改道，并保留可回收的旧伏笔。
                使用环境、五感、空间距离、动作余韵、神态、心理变化、潜台词和留白；不能流水账，也不能用直白结论代替描写。
                每章至少推进明线、暗线、关系线中的两条，结尾留下自然钩子。不要输出提纲、解释、标题或系统提示。
            """.trimIndent()

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
            store.addChapter(chapter)
            updateLedger(theater, guide, plans, ledger, chapter)?.let { store.setLedger(theater, it) }
            manager.succeeded(theater, chapterNumber)
            Result.success()
        } catch (cancelled: CancellationException) {
            manager.queuedAgain(theater, chapterNumber)
            throw cancelled
        } catch (error: Throwable) {
            manager.failed(theater, chapterNumber, error.message ?: "章节生成失败")
            Result.failure()
        }
    }

    private fun hasFullStoryMap(guide: String): Boolean {
        if (guide.isBlank()) return false
        val markers = listOf("【故事总纲】", "【关系主线】", "【明线】", "【暗线】", "【伏笔系统】")
        return markers.count(guide::contains) >= 3
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
                为这部已经开始写作的小说补回一份“故事总地图”。已写正文是最高事实，不得改写、否定或让人物倒退。
                在已有设定和正文基础上整理并补齐未来可继续执行的总体剧情，必须包含：
                【世界观】【故事总纲】【关系主线】【明线】【暗线】【伏笔系统】【情绪曲线】【文风执行】【核心钩子】。
                对尚未揭晓的部分可以做最保守、最连贯的补全，但不要凭空换题材、换人物关系或推翻前文。
                只输出这份总地图正文，不要解释。
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
                {"summary":"截至本章的紧凑剧情摘要","characters":"人物位置、身体、情绪、目标、已知信息","worldState":"时间、地点、环境和世界规则的当前状态","relationships":"人物关系与本章变化","openThreads":"正在推进但未完成的明线与暗线","foreshadows":"已埋、已回收和待回收伏笔","keyItems":"关键物品、归属和状态","updatedThroughChapter":${chapter.chapter}}
                每个文本字段保留真正影响后续写作的具体事实，删除已经失效的状态，控制整份档案在1800字以内。
            """.trimIndent(),
            source = "剧场",
            title = "$theater · 连续性档案",
            maxTokens = 1_500,
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 180_000,
        ).getOrThrow().text
        parseLedger(raw, chapter.chapter)
    }.getOrNull()

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
                只输出JSON：{"summary":"","characters":"","worldState":"","relationships":"","openThreads":"","foreshadows":"","keyItems":"","updatedThroughChapter":${chapters.size}}
                重点保留人物位置与状态、关系变化、已知信息、关键物品、未完明暗线和待回收伏笔，整份控制在1800字以内。
            """.trimIndent(),
            source = "剧场",
            title = "$theater · 重建连续性档案",
            maxTokens = 1_500,
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 180_000,
        ).getOrThrow().text
        parseLedger(raw, chapters.size)
    }.getOrNull()

    private fun parseLedger(raw: String, chapterNumber: Int): StarWishStoryLedger {
        var clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start >= 0 && end > start) clean = clean.substring(start, end + 1)
        val item = JSONObject(clean)
        return StarWishStoryLedger(
            summary = item.optString("summary"), characters = item.optString("characters"),
            worldState = item.optString("worldState"), relationships = item.optString("relationships"),
            openThreads = item.optString("openThreads"), foreshadows = item.optString("foreshadows"),
            keyItems = item.optString("keyItems"),
            updatedThroughChapter = item.optInt("updatedThroughChapter", chapterNumber).coerceAtLeast(chapterNumber),
        )
    }

    private companion object {
        const val ISOLATED_CHARACTER_ID = "__starwish_theater_isolated__"
    }
}

internal fun StarWishStoryLedger.promptText(): String = buildString {
    if (summary.isNotBlank()) appendLine("剧情摘要：$summary")
    if (characters.isNotBlank()) appendLine("人物状态：$characters")
    if (worldState.isNotBlank()) appendLine("世界状态：$worldState")
    if (relationships.isNotBlank()) appendLine("关系变化：$relationships")
    if (openThreads.isNotBlank()) appendLine("未完线索：$openThreads")
    if (foreshadows.isNotBlank()) appendLine("伏笔：$foreshadows")
    if (keyItems.isNotBlank()) appendLine("关键物品：$keyItems")
}.trim()
