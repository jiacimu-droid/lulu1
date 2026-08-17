package com.jiacimu.lulu.study

import android.content.Context
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import org.json.JSONArray
import org.json.JSONObject

internal data class StarWishPlotCandidate(
    val title: String,
    val worldview: String,
    val hook: String,
    val relationshipCore: String,
    val mainLine: String,
    val hiddenLine: String,
    val foreshadowing: String,
    val emotionalArc: String,
    val proseStyle: String,
    val highlights: String,
    val overview: String,
    val chapters: List<String>,
    val wordCount: String,
) {
    fun detailedGuide(): String = buildString {
        appendLine("【世界观】")
        appendLine(worldview.trim())
        appendLine("\n【故事总纲】")
        appendLine(overview.trim())
        appendLine("\n【关系主线】")
        appendLine(relationshipCore.trim())
        appendLine("\n【明线】")
        appendLine(mainLine.trim())
        appendLine("\n【暗线】")
        appendLine(hiddenLine.trim())
        appendLine("\n【伏笔系统】")
        appendLine(foreshadowing.trim())
        appendLine("\n【情绪曲线】")
        appendLine(emotionalArc.trim())
        appendLine("\n【文风执行】")
        appendLine(proseStyle.trim())
        appendLine("\n【核心钩子】")
        appendLine(hook.trim())
        appendLine("\n【亮点与爽点】")
        appendLine(highlights.trim())
        appendLine("\n【每章建议字数】")
        appendLine(wordCount.ifBlank { "1800-3000" })
        chapters.forEachIndexed { index, chapter ->
            appendLine("\n【第${index + 1}章规划】")
            appendLine(chapter.trim())
        }
    }.trim()
}

internal class StarWishCustomTheaterLibrary private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lulu_starwish_custom_theaters", Context.MODE_PRIVATE)

    fun all(): List<StarWishTheaterSeed> {
        val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                if (title.isNotBlank()) add(StarWishTheaterSeed(title, item.optString("prompt")))
            }
        }
    }

    fun add(seed: StarWishTheaterSeed) {
        val items = (all().filterNot { it.title == seed.title } + seed).takeLast(60)
        val array = JSONArray().apply {
            items.forEach { put(JSONObject().put("title", it.title).put("prompt", it.prompt)) }
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    fun delete(title: String) {
        val array = JSONArray().apply {
            all().filterNot { it.title == title }.forEach {
                put(JSONObject().put("title", it.title).put("prompt", it.prompt))
            }
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    companion object {
        @Volatile private var instance: StarWishCustomTheaterLibrary? = null
        fun get(context: Context): StarWishCustomTheaterLibrary = instance ?: synchronized(this) {
            instance ?: StarWishCustomTheaterLibrary(context).also { instance = it }
        }
    }
}

internal object StarWishPlotPlanner {
    suspend fun generate(
        characterId: String,
        existingTitle: String?,
        existingGuide: String?,
        direction: String,
    ): Result<List<StarWishPlotCandidate>> = runCatching {
        val facts = buildString {
            appendLine("这是心愿馆小剧场的长篇小说策划任务。")
            if (!existingTitle.isNullOrBlank()) appendLine("正在重新规划已有小说：《$existingTitle》")
            if (!existingGuide.isNullOrBlank()) appendLine("当前世界观与剧情规划：\n$existingGuide")
            if (direction.isNotBlank()) appendLine("用户最高优先级偏好：$direction")
        }
        val instruction = """
            你是成熟的长篇类型小说总策划。一次设计3套差异明显、能够真正展开成小说的小剧场方案。
            这是完全独立的小剧场小说，不得引用真实角色人设、关系、聊天、记忆或共同时间线。只依据本次提供的题材、已有大纲和用户偏好创作；用户偏好高于原有大纲，大纲是导航，不是铁轨。
            三套方案的核心驱动力必须不同，可使用恋爱攻略、被攻略、系统任务、轻喜剧、悬疑、权谋、冒险、循环、末日经营、治愈、甜中带刀等方向，但不能三套都依赖同一种反派。

            每套必须详细包含：
            1. 世界规则、人物处境和一眼想读的开篇钩子。
            2. 明线目标、暗线真相、关系主线、阶段冲突和最终选择。
            3. 至少4个伏笔，逐项写清埋设章节、表面含义、真实含义、回收章节和回收效果。
            4. 读者情绪曲线：好奇、心动或爽点、压迫或误会、中段高潮、反转、终局释放与余韵。
            5. 6章详细规划，每章必须包含具体事件、人物主动选择、关系变化、伏笔埋设或回收、情绪目标和结尾钩子；每章不少于100字。
            6. 文风必须是可执行技法：镜头距离、五感密度、对白节奏、心理描写比例、留白和意象。不要只写“唯美”“细腻”。

            只输出JSON，不要Markdown，不要解释。顶层必须是恰好3个对象的数组，字段如下：
            [{"title":"","worldview":"","hook":"","relationshipCore":"","mainLine":"","hiddenLine":"","foreshadowing":"","emotionalArc":"","proseStyle":"","highlights":"","overview":"","wordCount":"1800-3000","chapters":["","","","","",""]}]
        """.trimIndent()
        val reply = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = facts,
            instruction = instruction,
            source = "心愿馆",
            title = if (existingTitle.isNullOrBlank()) "三套剧情规划" else "《$existingTitle》的三套剧情规划",
            temperature = 0.9,
            maxTokens = 7600,
            contextMode = CompanionContextMode.Isolated,
        ).getOrThrow().text
        parseJson(reply).ifEmpty { parseLoose(reply) }.take(3).takeIf { it.isNotEmpty() }
            ?: error("剧情已经生成，但格式仍无法识别。")
    }

    private fun parseJson(raw: String): List<StarWishPlotCandidate> = runCatching {
        var clean = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            .replace('“', '"').replace('”', '"')
            .replace(Regex(",\\s*([}\\]])"), "$1")
        val start = clean.indexOf('[')
        val end = clean.lastIndexOf(']')
        if (start >= 0 && end > start) clean = clean.substring(start, end + 1)
        val array = JSONArray(clean)
        buildList {
            for (index in 0 until array.length()) candidate(array.optJSONObject(index))?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun candidate(obj: JSONObject?): StarWishPlotCandidate? {
        if (obj == null) return null
        fun text(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            obj.optString(key).trim().takeIf(String::isNotBlank)
        }.orEmpty()
        val chaptersArray = obj.optJSONArray("chapters") ?: obj.optJSONArray("章节")
        val chapters = buildList {
            if (chaptersArray != null) for (index in 0 until chaptersArray.length()) {
                chaptersArray.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val title = text("title", "标题")
        val overview = text("overview", "总纲", "总览")
        if (title.isBlank() || overview.isBlank() || chapters.size < 4) return null
        return StarWishPlotCandidate(
            title = title,
            worldview = text("worldview", "世界观", "世界设定"),
            hook = text("hook", "钩子"),
            relationshipCore = text("relationshipCore", "关系主线"),
            mainLine = text("mainLine", "明线"),
            hiddenLine = text("hiddenLine", "暗线"),
            foreshadowing = text("foreshadowing", "伏笔", "伏笔系统"),
            emotionalArc = text("emotionalArc", "情绪曲线"),
            proseStyle = text("proseStyle", "文风", "叙事风格"),
            highlights = text("highlights", "亮点", "爽点"),
            overview = overview,
            chapters = chapters,
            wordCount = text("wordCount", "字数").ifBlank { "1800-3000" },
        )
    }

    private fun parseLoose(raw: String): List<StarWishPlotCandidate> {
        val blocks = raw.split(Regex("(?:^|\\n)\\s*(?:={2,}|#{1,4})?\\s*方案\\s*[一二三123]", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)))
            .map(String::trim).filter(String::isNotBlank)
        return blocks.mapNotNull { block ->
            fun value(label: String): String = Regex("(?:^|\\n)\\s*${Regex.escape(label)}\\s*[：:]\\s*([\\s\\S]*?)(?=\\n\\s*(?:标题|世界观|钩子|关系主线|明线|暗线|伏笔系统|情绪曲线|文风|亮点|总纲|总览|字数|第\\d+章)\\s*[：:]|$)", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val title = value("标题")
            val overview = value("总纲").ifBlank { value("总览") }
            val chapters = (1..12).map { value("第${it}章") }.filter(String::isNotBlank)
            if (title.isBlank() || overview.isBlank() || chapters.size < 4) null else StarWishPlotCandidate(
                title, value("世界观"), value("钩子"), value("关系主线"), value("明线"), value("暗线"),
                value("伏笔系统"), value("情绪曲线"), value("文风"), value("亮点"), overview, chapters,
                value("字数").ifBlank { "1800-3000" },
            )
        }
    }
}
