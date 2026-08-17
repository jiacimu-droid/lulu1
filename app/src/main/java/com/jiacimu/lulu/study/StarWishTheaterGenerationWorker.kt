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
        require(cleanTheater.isNotBlank()) { "小剧场名称不能为空" }
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
            val guide = snapshot.theaterGuides[theater].orEmpty()
            val plans = snapshot.theaterPlans[theater].orEmpty().ifEmpty { starWishPlansFromLegacyGuide(guide) }
            var ledger = snapshot.theaterLedgers[theater] ?: StarWishStoryLedger()
            if (chapters.isNotEmpty() && ledger.updatedThroughChapter != chapters.size) {
                rebuildLedger(theater, guide, plans, chapters)?.let { rebuilt ->
                    ledger = rebuilt
                    store.setLedger(theater, rebuilt)
                }
            }
            val currentPlan = plans.firstOrNull { it.number == chapterNumber }
            val recentChapters = chapters.takeLast(3).joinToString("\n\n") { chapter ->
                "${chapter.title}\n${chapter.content.takeLast(2_400)}"
            }
            val reply = LuluAiServices.gateway.generate(
                characterId = ISOLATED_CHARACTER_ID,
                facts = buildString {
                    appendLine("独立小剧场：《$theater》")
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
                },
                instruction = """
                    续写第 $chapterNumber 章完整中文小说正文，约1800—3200字，只输出正文。
                    这是完全独立的小剧场，不得引用任何真实角色设定、聊天、记忆、共同时间线、用户资料或世界书。
                    用户要求优先级最高；故事地图和逐章规划是导航。新章必须发生在上一章最后一句之后，禁止重演已经完成的动作、对白、发现或决定。
                    严格继承连续性档案中的人物位置、身体状态、情绪、关系、已知信息、物品、明暗线与伏笔。若用户改变剧情方向，应自然改道，并保留可回收的旧伏笔。
                    使用环境、五感、空间距离、动作余韵、神态、心理变化、潜台词和留白；不能流水账，也不能用直白结论代替描写。
                    每章至少推进明线、暗线、关系线中的两条，结尾留下自然钩子。不要输出提纲、解释、标题或系统提示。
                """.trimIndent(),
                source = "心愿馆",
                title = "$theater · 第${chapterNumber}章",
                temperature = 0.82,
                maxTokens = 4_600,
                connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
                contextMode = CompanionContextMode.Isolated,
                readTimeoutMillis = 240_000,
            ).getOrThrow().text.trim()
            check(reply.isNotBlank()) { "模型返回了空章节" }
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
            source = "心愿馆",
            title = "$theater · 连续性档案",
            temperature = 0.18,
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
            source = "心愿馆",
            title = "$theater · 重建连续性档案",
            temperature = 0.16,
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
