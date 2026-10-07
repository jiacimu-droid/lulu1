package com.jiacimu.lulu.study

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object StarWishTheaterPlanningEngine {
    suspend fun generateStoryCandidates(
        characterId: String,
        existingTitle: String?,
        existingGuide: String?,
        direction: String,
    ): Result<List<StarWishPlotCandidate>> = runCatching {
        val facts = buildString {
            appendLine("这是剧场 App 的独立小说策划任务。")
            if (!existingTitle.isNullOrBlank()) appendLine("现有故事标题：$existingTitle")
            if (!existingGuide.isNullOrBlank()) appendLine("现有总大纲：\n$existingGuide")
            if (direction.isNotBlank()) {
                appendLine("用户提供的题材或大纲（最高优先级）：\n$direction")
                appendLine("如果这里已经是一份明确大纲，必须保留它的世界观、人物关系、核心事件与结局，只补齐结构，不要擅自换成另一个故事。")
            }
        }
        val instruction = """
            你是成熟的长篇类型小说总策划。请给出最多3套可直接开写的方案。
            这是完全独立的剧场小说，不得引用真实角色人设、聊天、记忆、共同时间线、用户资料或世界书。
            若用户已经给出完整或半完整大纲，所有方案都必须忠实沿用该大纲的核心设定与主要剧情，只允许在章节节奏、叙事重心和表现方式上做小幅差异。

            每套方案必须包含：世界规则与人物处境、开篇钩子、故事总纲、关系主线、明线、暗线、伏笔系统、情绪曲线、可执行文风、亮点，以及6章逐章规划。
            每章规划必须写出具体事件、人物主动选择、关系变化、明线或暗线推进、伏笔埋设或回收、情绪目标与结尾钩子。

            只输出JSON，不要Markdown，不要解释。优先使用这个结构：
            [{"title":"","worldview":"","hook":"","relationshipCore":"","mainLine":"","hiddenLine":"","foreshadowing":"","emotionalArc":"","proseStyle":"","highlights":"","overview":"","wordCount":"1800-3000","chapters":[{"title":"","outline":""}]}]
        """.trimIndent()

        val reply = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = facts,
            instruction = instruction,
            source = "剧场",
            title = if (existingTitle.isNullOrBlank()) "新故事剧情规划" else "故事剧情规划",
            maxTokens = 7_600,
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 240_000,
        ).getOrThrow().text

        val parsed = parseCandidates(reply)
        val repaired = if (parsed.isEmpty()) {
            repairStoryPayload(characterId, reply).getOrNull()?.let(::parseCandidates).orEmpty()
        } else {
            parsed
        }
        repaired.take(3).takeIf { it.isNotEmpty() }
            ?: error("剧情已经生成，但格式仍无法识别。已自动尝试格式修复。")
    }

    suspend fun generateChapterPlans(
        characterId: String,
        storyTitle: String,
        storyGuide: String,
        chapterCount: Int,
    ): Result<List<StarWishChapterPlan>> = runCatching {
        require(storyGuide.isNotBlank()) { "总大纲不能为空" }
        require(chapterCount in 1..StarWishRules.MAX_CHAPTERS_PER_THEATER) { "章节数量不正确" }

        val collected = mutableListOf<StarWishChapterPlan>()
        var start = 1
        while (start <= chapterCount) {
            val end = minOf(start + 8, chapterCount)
            val batchCount = end - start + 1
            val previous = collected.takeLast(4).joinToString("\n") { plan ->
                "第" + plan.number + "章 " + plan.title + "：" + plan.outline.take(500)
            }
            val facts = buildString {
                appendLine("独立剧场故事：《$storyTitle》")
                appendLine("固定总大纲（不得重写、替换或改变故事基调）：\n$storyGuide")
                appendLine("全书计划共 $chapterCount 章。")
                appendLine("本批只规划第 $start 至第 $end 章。")
                if (previous.isNotBlank()) appendLine("前几章规划，仅用于连续性：\n$previous")
            }
            val instruction = """
                你是小说作者兼剧情导演。根据固定总大纲，为指定章节生成真正可执行的逐章写作框架。
                不要重写总大纲，不要改世界观、故事基调、核心关系或最终方向；你只负责把全局大纲拆成具体章节。

                每一章的 outline 必须明确写出：
                1. 本章在全书中的阶段功能；
                2. 3—6个按因果顺序发生的具体事件；
                3. 主要人物各自主动做出的选择与后果；
                4. 关系变化；
                5. 明线推进；
                6. 暗线推进；
                7. 本章埋设、误导、强化或回收的伏笔，并注明预计回收章节；
                8. 情绪目标与节奏；
                9. 章节结尾钩子以及下一章必须承接的状态。

                本批必须恰好输出 $batchCount 个对象，对应第 $start 至第 $end 章。只输出JSON数组，不要Markdown：
                [{"number":1,"title":"","outline":""}]
            """.trimIndent()

            val raw = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = facts,
                instruction = instruction,
                source = "剧场",
                title = "《" + storyTitle + "》第" + start + "-" + end + "章规划",
                maxTokens = (batchCount * 650 + 1_200).coerceIn(2_400, 7_600),
                connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
                contextMode = CompanionContextMode.Isolated,
                readTimeoutMillis = 240_000,
            ).getOrThrow().text

            var batch = parseChapterPlans(raw, start, end)
            if (batch.size != batchCount) {
                val fixed = repairChapterPayload(
                    characterId = characterId,
                    raw = raw,
                    storyTitle = storyTitle,
                    start = start,
                    end = end,
                ).getOrNull()
                if (fixed != null) batch = parseChapterPlans(fixed, start, end)
            }
            check(batch.size == batchCount) {
                "第 $start-$end 章已经生成，但章节规划格式不完整。"
            }
            collected += batch
            start = end + 1
        }

        collected.mapIndexed { index, plan -> plan.copy(number = index + 1) }
    }

    private suspend fun repairStoryPayload(characterId: String, raw: String): Result<String> = runCatching {
        LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = "下面是已经生成好的剧情内容。只修格式，不要换故事：\n" + raw.take(28_000),
            instruction = """
                把提供的内容整理成合法JSON。不要删剧情，不要改设定，不要重新创作。
                允许1到3套方案。顶层必须是JSON数组。每套字段：
                {"title":"","worldview":"","hook":"","relationshipCore":"","mainLine":"","hiddenLine":"","foreshadowing":"","emotionalArc":"","proseStyle":"","highlights":"","overview":"","wordCount":"1800-3000","chapters":[{"title":"","outline":""}]}
                chapters 既可以保留原有章节标题，也必须把每章原有规划完整放进 outline。只输出JSON。
            """.trimIndent(),
            source = "剧场",
            title = "修复剧情规划格式",
            maxTokens = 7_600,
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 240_000,
        ).getOrThrow().text
    }

    private suspend fun repairChapterPayload(
        characterId: String,
        raw: String,
        storyTitle: String,
        start: Int,
        end: Int,
    ): Result<String> = runCatching {
        val count = end - start + 1
        LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = "《" + storyTitle + "》已生成的第" + start + "-" + end + "章规划原文：\n" + raw.take(24_000),
            instruction = """
                只做格式修复。保留原剧情规划，整理成合法JSON数组。
                必须恰好有 $count 个对象，依次对应第 $start 至第 $end 章；若原输出只因格式混乱漏掉对象边界，请恢复出来。
                每项格式：{"number":1,"title":"","outline":""}
                outline 必须保留具体事件、人物选择、关系变化、明暗线、伏笔、情绪目标和结尾钩子。只输出JSON。
            """.trimIndent(),
            source = "剧场",
            title = "修复章节规划格式",
            maxTokens = (count * 650 + 800).coerceIn(1_800, 7_600),
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 240_000,
        ).getOrThrow().text
    }

    private fun parseCandidates(raw: String): List<StarWishPlotCandidate> {
        val root = parseJsonValue(raw) ?: return emptyList()
        val objects = when (root) {
            is JSONArray -> buildList {
                for (index in 0 until root.length()) root.optJSONObject(index)?.let(::add)
            }
            is JSONObject -> {
                val nested = firstArray(root, "candidates", "plans", "stories", "方案", "方案列表")
                if (nested != null) {
                    buildList {
                        for (index in 0 until nested.length()) nested.optJSONObject(index)?.let(::add)
                    }
                } else {
                    listOf(root)
                }
            }
            else -> emptyList()
        }
        return objects.mapNotNull(::candidate)
    }

    private fun candidate(obj: JSONObject): StarWishPlotCandidate? {
        val title = text(obj, "title", "标题", "name", "书名")
        if (title.isBlank()) return null
        val chapters = chapterTexts(obj)
        if (chapters.isEmpty()) return null

        val worldview = text(obj, "worldview", "世界观", "世界设定")
        val hook = text(obj, "hook", "钩子", "开篇钩子")
        val relationship = text(obj, "relationshipCore", "关系主线", "关系线")
        val mainLine = text(obj, "mainLine", "明线", "主线")
        val hiddenLine = text(obj, "hiddenLine", "暗线")
        val overview = text(obj, "overview", "总纲", "故事总纲", "总览")
            .ifBlank { mainLine.ifBlank { hook.ifBlank { worldview } } }

        return StarWishPlotCandidate(
            title = title,
            worldview = worldview,
            hook = hook,
            relationshipCore = relationship,
            mainLine = mainLine,
            hiddenLine = hiddenLine,
            foreshadowing = text(obj, "foreshadowing", "伏笔", "伏笔系统"),
            emotionalArc = text(obj, "emotionalArc", "情绪曲线", "情感曲线"),
            proseStyle = text(obj, "proseStyle", "文风", "叙事风格"),
            highlights = text(obj, "highlights", "亮点", "爽点"),
            overview = overview,
            chapters = chapters,
            wordCount = text(obj, "wordCount", "字数", "每章字数").ifBlank { "1800-3000" },
        )
    }

    private fun chapterTexts(obj: JSONObject): List<String> {
        val value = firstValue(obj, "chapters", "章节", "chapterPlans", "章节规划") ?: return emptyList()
        return when (value) {
            is JSONArray -> buildList {
                for (index in 0 until value.length()) {
                    when (val item = value.opt(index)) {
                        is JSONObject -> chapterObjectToText(item, index + 1).takeIf(String::isNotBlank)?.let(::add)
                        is String -> item.trim().takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }
            is JSONObject -> buildList {
                val keys = value.keys()
                var index = 1
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val item = value.opt(key)) {
                        is JSONObject -> chapterObjectToText(item, index).takeIf(String::isNotBlank)?.let(::add)
                        is String -> item.trim().takeIf(String::isNotBlank)?.let(::add)
                    }
                    index += 1
                }
            }
            is String -> listOf(value.trim()).filter(String::isNotBlank)
            else -> emptyList()
        }
    }

    private fun chapterObjectToText(obj: JSONObject, number: Int): String {
        val title = text(obj, "title", "标题", "name", "章节标题").ifBlank { "第 $number 章" }
        val direct = text(obj, "outline", "规划", "剧情", "内容", "summary", "摘要", "details", "细纲")
        if (direct.isNotBlank()) return title + "\n" + direct

        val fields = listOf(
            "阶段功能" to text(obj, "stage", "阶段功能", "function"),
            "具体事件" to text(obj, "events", "具体事件", "事件"),
            "人物选择" to text(obj, "choices", "人物选择", "主动选择"),
            "关系变化" to text(obj, "relationship", "关系变化"),
            "明线推进" to text(obj, "mainLine", "明线推进", "明线"),
            "暗线推进" to text(obj, "hiddenLine", "暗线推进", "暗线"),
            "伏笔" to text(obj, "foreshadowing", "伏笔"),
            "情绪目标" to text(obj, "emotion", "情绪目标", "情绪"),
            "结尾钩子" to text(obj, "hook", "结尾钩子", "钩子"),
        )
        val detail = fields
            .filter { it.second.isNotBlank() }
            .joinToString("\n") { it.first + "：" + it.second }
        return if (detail.isBlank()) title else title + "\n" + detail
    }

    private fun parseChapterPlans(raw: String, start: Int, end: Int): List<StarWishChapterPlan> {
        val root = parseJsonValue(raw) ?: return emptyList()
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> firstArray(root, "chapters", "plans", "chapterPlans", "章节", "章节规划")
            else -> null
        } ?: return emptyList()

        val expectedCount = end - start + 1
        val parsed = buildList {
            for (index in 0 until array.length()) {
                val number = start + index
                when (val item = array.opt(index)) {
                    is JSONObject -> {
                        val title = text(item, "title", "标题", "name", "章节标题").ifBlank { "第 $number 章" }
                        val withTitle = chapterObjectToText(item, number)
                        val outline = withTitle.removePrefix(title).trim()
                            .ifBlank { text(item, "outline", "规划", "剧情", "内容", "summary", "摘要") }
                        if (outline.isNotBlank()) {
                            add(StarWishChapterPlan(number = number, title = title, outline = outline))
                        }
                    }
                    is String -> {
                        val clean = item.trim()
                        if (clean.isNotBlank()) {
                            val firstLine = clean.lineSequence().firstOrNull().orEmpty().take(40)
                            add(
                                StarWishChapterPlan(
                                    number = number,
                                    title = firstLine.takeIf { it.length in 2..40 } ?: "第 $number 章",
                                    outline = clean,
                                ),
                            )
                        }
                    }
                }
            }
        }

        return parsed.take(expectedCount).mapIndexed { index, plan ->
            plan.copy(number = start + index)
        }
    }

    private fun parseJsonValue(raw: String): Any? = runCatching {
        var clean = raw.trim()
            .replace('“', '"')
            .replace('”', '"')

        val arrayStart = clean.indexOf('[')
        val objectStart = clean.indexOf('{')
        val start = listOf(arrayStart, objectStart).filter { it >= 0 }.minOrNull()
            ?: return@runCatching null
        val end = maxOf(clean.lastIndexOf(']'), clean.lastIndexOf('}'))
        if (end > start) clean = clean.substring(start, end + 1)
        JSONTokener(clean).nextValue()
    }.getOrNull()

    private fun firstArray(obj: JSONObject, vararg keys: String): JSONArray? =
        keys.firstNotNullOfOrNull { key -> obj.optJSONArray(key) }

    private fun firstValue(obj: JSONObject, vararg keys: String): Any? {
        keys.forEach { key ->
            if (obj.has(key) && !obj.isNull(key)) return obj.opt(key)
        }
        return null
    }

    private fun text(obj: JSONObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            obj.optString(key).trim().takeIf(String::isNotBlank)
        }.orEmpty()
}
