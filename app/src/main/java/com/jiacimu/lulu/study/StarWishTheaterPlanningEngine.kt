package com.jiacimu.lulu.study

import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object StarWishTheaterPlanningEngine {
    suspend fun generateStoryCandidates(
        characterId: String,
        existingTitle: String?,
        existingGuide: String?,
        direction: String,
        theaterWorldBook: String = "",
        onCandidates: ((List<StarWishPlotCandidate>) -> Unit)? = null,
    ): Result<List<StarWishPlotCandidate>> = runCatching {
        val accepted = mutableListOf<StarWishPlotCandidate>()
        for (variant in 1..3) {
            currentCoroutineContext().ensureActive()
            val previous = accepted.joinToString("\n") { "《${it.title}》：${it.hook.take(180)}" }
            val facts = buildString {
                appendLine("这是剧场 App 的独立创作策划任务，作品可以是长篇、三四章短篇、纯爽文、氛围短片或单一体验，不默认主线和人物成长。")
                if (theaterWorldBook.isNotBlank()) appendLine("本书选用世界书（必须遵守）：\n$theaterWorldBook")
                if (!existingTitle.isNullOrBlank()) appendLine("现有故事标题：$existingTitle")
                if (!existingGuide.isNullOrBlank()) appendLine("现有故事地图：\n$existingGuide")
                if (direction.isNotBlank()) appendLine("用户最想看的体验、爽点、张力、篇幅和禁忌（最高创作优先级；必须成为作品的中心）：\n$direction")
                else appendLine("用户没有指定题材，请构思能兑现鲜明阅读体验的作品，不默认长篇或爱情线。")
                appendLine("现在只生成第 $variant 套方案，共3套。认真完成核心内容；不需要的可选栏目可以留空。")
                if (previous.isNotBlank()) appendLine("已经生成的方案如下，本套在场景、切入点或兑现核心看点的方式上要有差异，但不能偏离用户想看的体验：\n$previous")
                appendLine("用户只给一句题材也完全足够。需要人物或世界背景时主动创造；不需要时不得强加男女主、恋爱、反派或宏大设定。")
            }
            val instruction = """
                你是擅长各种篇幅、题材和体验的创作策划。只输出一套方案。
                用户明确想看的爽点、张力、氛围、关系互动、篇幅以及不想看的套路，是作品唯一的创作方向；应占据主要场景与阅读体验，绝不能沦为长篇剧情的一点装饰。
                想看龙傲天、打脸、无敌或强者臣服，就集中安排足够直接、精彩的爽感兑现，不强迫主角挫败、赎罪或成长；想看三四章的张力短片、无主线体验、氛围或纯互动，就聚焦高密度场景，不擅自写成漫长爱恨情仇。
                只有用户明确需要长线发展时，才设计人物成长、关系弧、明暗线、伏笔与复杂阶段；复杂不等于好看。三套方案都要切合同一用户愿望，差异体现在场景和表现手法，不能为了差异而跑题。
                必填核心字段：title（作品名）、overview（这部作品实际要呈现的体验与安排）、highlights（最重要的看点和具体高光场景）。
                其余字段都是可选工具：worldview 世界前提、hook 开篇钩子、cast 出场人物、proseStyle 文风、wordCount 每章建议字数，以及 characterArcs 成长、relationshipCore 感情线、plotSpine 长线脉络、mainLine 明线、hiddenLine 暗线、foreshadowing 伏笔、stagePlan 阶段节奏、endingDirection 收束方向、emotionalArc 情绪曲线、romanceAesthetics 感情描写。
                根据用户要求选择适用的字段，不需要的直接输出空字符串""；严禁为了填表凑出成长、暗线、伏笔或感情线。overview 可以是场景/体验规划，不一定是完整起承转合。
                不生成逐章规划，三四章短篇应该能自然收束，不必延长。
                只输出一个合法JSON对象，不要Markdown、解释或数组：
                {"title":"","worldview":"","hook":"","overview":"","highlights":"","cast":"","characterArcs":"","relationshipCore":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadowing":"","stagePlan":"","endingDirection":"","emotionalArc":"","proseStyle":"","romanceAesthetics":"","wordCount":"1800-3000"}
            """.trimIndent()

            val raw = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = facts,
                instruction = instruction,
                source = "剧场",
                title = "新故事方案 $variant/3",
                maxTokens = 5_800,
                connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
                contextMode = CompanionContextMode.Isolated,
                readTimeoutMillis = 240_000,
            ).getOrThrow().text

            var candidate = parseCandidates(raw).firstOrNull()
            if (candidate == null || !candidateCompleteEnough(candidate)) {
                val fixed = completeSingleStoryPayload(
                    characterId = characterId,
                    raw = raw,
                    direction = direction,
                    variant = variant,
                    theaterWorldBook = theaterWorldBook,
                ).getOrNull()
                if (!fixed.isNullOrBlank()) candidate = parseCandidates(fixed).firstOrNull()
            }

            if (candidate == null || !candidateCompleteEnough(candidate)) {
                error("第${variant}套方案生成不完整。已经自动补全过一次；直接重新生成即可，不需要补男女主名字。")
            }
            accepted += candidate.copy(creativeIntent = direction.trim())
            onCandidates?.invoke(accepted.toList())
        }
        accepted
    }.onFailure { if (it is CancellationException) throw it }

    suspend fun generateStoryBible(
        characterId: String,
        storyTitle: String,
        storyGuide: String,
        chapterCount: Int,
        writtenChapters: List<StarWishTheaterChapter>,
        existingBible: StarWishStoryBible? = null,
        ledger: StarWishStoryLedger? = null,
        theaterWorldBook: String = "",
        onProgress: ((StarWishStoryBible) -> Unit)? = null,
    ): Result<StarWishStoryBible> = runCatching {
        require(storyGuide.isNotBlank()) { "故事地图不能为空" }
        val writtenEvidence = writtenChapters.takeLast(8).joinToString("\n\n") { chapter ->
            "第${chapter.chapter}章 ${chapter.title}\n${chapter.content.takeLast(2_400)}"
        }
        val facts = buildString {
            appendLine("独立剧场故事：《$storyTitle》")
            appendLine("故事地图（核心方向与看点，不是逐章细纲）：\n$storyGuide")
            appendLine("计划总章数：$chapterCount；已经写完：${writtenChapters.size}章。")
            if (theaterWorldBook.isNotBlank()) {
                appendLine("本剧场专属世界书（已开启条目，必须遵守）：\n$theaterWorldBook")
            }
            existingBible?.promptText()?.takeIf { it.isNotBlank() && writtenChapters.isNotEmpty() }?.let {
                appendLine("旧幕后规划只作参考：如果未发生的长线、感情或伏笔与最新故事地图及用户创作要求不符，必须舍弃：\n$it")
            }
            ledger?.promptText()?.takeIf(String::isNotBlank)?.let {
                appendLine("正文确认的当前状态与硬事实，优先级最高：\n$it")
            }
            if (writtenEvidence.isNotBlank()) appendLine("最近已写正文证据：\n$writtenEvidence")
        }
        val instruction = """
            你是这部作品的幕后体验导演，而非默认长篇小说策划。用户原始创作要求和故事地图中的核心看点决定一切：爽点就要直接兑现，三四章短篇就要快速建立与释放张力，纯氛围、互动或片段体验无需主线、反派、成长或长篇爱情。只有明确需要复杂长线时才规划明暗线、伏笔和成长。
            已写正文与连续性档案中的生死、身份、关系、重要伤势、物品归属、地点和已发生事实不可无解释改写。旧幕后规划中未发生的内容不能压倒用户新要求。
            保留16栏格式用于页面和存档兼容，但它们只是可选工具箱：
            worldview 世界观、overview 核心安排、hook 钩子、highlights 核心看点、cast 人物、characterArcs 人物成长、relationshipArc 感情线、plotSpine 故事脉络、mainLine 明线、hiddenLine 暗线、foreshadows 伏笔、stagePlan 阶段节奏、endingDirection 收束、emotionalArc 情绪、proseStyle 文风、romanceAesthetics 感情描写。
            核心看点与作品安排必须强调用户想体验的东西和对应的具体场景。其余栏目只在对这部作品有帮助时才填写；完全不适用的直接返回空字符串""，绝不能编造来填表，也不要填「不适用」冒充规划。
            分批返回每次指定的栏目，优先可执行的场景、情绪、爽点和节奏，不以复杂程度作为质量指标。
        """.trimIndent()
        var bible = StarWishStoryBible(updatedThroughChapter = writtenChapters.size)
        // Smaller fixed groups avoid one giant JSON document failing or being truncated.
        for (group in theaterBibleFields.entries.chunked(4)) {
            currentCoroutineContext().ensureActive()
            val template = JSONObject().apply { group.forEach { put(it.key, "") } }
            val raw = generatePlanningText(
                characterId, facts + "\n已经填入本轮框架的内容：\n" + bible.promptText(),
                instruction + "\n本次只填写这些栏目：" + group.joinToString { "${it.key}（${it.value}）" } +
                    "。适用的栏目写出具体可执行内容；不需要的栏目直接留空，不解释、不凑数。不要求固定字数。\n只返回本次模板：" + template,
                "$storyTitle · " + group.joinToString { it.value }, 3_200,
            )
            val partial = runCatching { parseStoryBible(raw, writtenChapters.size) }.getOrNull()
            if (partial != null) {
                bible = partial.withMissingFieldsFrom(bible)
                onProgress?.invoke(bible)
            }
            // Deliberate empty optional slots are not errors and must never be filled
            // by another API call inventing unwanted character arcs or subplots.
        }
        check(storyBibleCompleteEnough(bible)) { "幕后规划关键栏目尚未填写，已填内容已经保存" }
        bible
    }.onFailure { if (it is CancellationException) throw it }

    suspend fun generateChapterPlans(
        characterId: String,
        storyTitle: String,
        storyGuide: String,
        chapterCount: Int,
        writtenChapters: List<StarWishTheaterChapter> = emptyList(),
        existingPlans: List<StarWishChapterPlan> = emptyList(),
        storyBible: StarWishStoryBible? = null,
        ledger: StarWishStoryLedger? = null,
        theaterWorldBook: String = "",
        onProgress: ((List<StarWishChapterPlan>) -> Unit)? = null,
    ): Result<List<StarWishChapterPlan>> = runCatching {
        require(storyGuide.isNotBlank()) { "总大纲不能为空" }
        require(chapterCount in 1..StarWishRules.MAX_CHAPTERS_PER_THEATER) { "章节数量不正确" }
        require(storyBible != null && storyBibleCompleteEnough(storyBible)) {
            "必须先成功生成幕后规划，才能生成逐章规划"
        }

        val lockedCount = minOf(writtenChapters.size, chapterCount)
        val collected = (1..lockedCount).map { number ->
            existingPlans.firstOrNull { it.number == number }
                ?: writtenChapters.getOrNull(number - 1)?.let { chapter ->
                    StarWishChapterPlan(
                        number = number,
                        title = chapter.title.ifBlank { "第 $number 章" },
                        outline = "本章正文已经完成；以现有正文为准，不参与重新规划。",
                    )
                }
                ?: StarWishChapterPlan(number = number, title = "第 $number 章", outline = "本章已完成")
        }.toMutableList()
        val lockedContext = writtenChapters.takeLast(3).joinToString("\n\n") { chapter ->
            "第${chapter.chapter}章 ${chapter.title}\n${chapter.content.takeLast(2_200)}"
        }
        var start = lockedCount + 1
        while (start <= chapterCount) {
            val end = start
            val batchCount = end - start + 1
            val previous = collected.takeLast(4).joinToString("\n") { plan ->
                "第" + plan.number + "章 " + plan.title + "：" + plan.outline.take(500)
            }
            val facts = buildString {
                appendLine("独立剧场故事：《$storyTitle》")
                appendLine("故事地图（保留核心方向，未来设定冲突须按最新世界书调整）：\n$storyGuide")
                if (theaterWorldBook.isNotBlank()) {
                    appendLine("本剧场专属世界书（已开启条目，必须遵守）：\n$theaterWorldBook")
                }
                storyBible?.promptText()?.takeIf(String::isNotBlank)?.let {
                    appendLine("幕后长期规划，逐章规划必须从这里落地：\n$it")
                }
                ledger?.promptText()?.takeIf(String::isNotBlank)?.let {
                    appendLine("正文当前状态与硬事实，绝对不得违背：\n$it")
                }
                appendLine("全书计划共 $chapterCount 章。")
                appendLine("前 $lockedCount 章已经写成正文，绝对不能重新规划或改写；只规划尚未写出的章节。")
                appendLine("本批只规划第 $start 至第 $end 章。")
                if (lockedContext.isNotBlank()) appendLine("最近已写正文，只用于保证后续连续：\n$lockedContext")
                if (previous.isNotBlank()) appendLine("前几章规划，仅用于连续性：\n$previous")
            }
            val instruction = """
                你是本作品的执行导演。根据用户原始创作要求、故事地图和幕后规划，设计第 $start 章真正能兑现读者期待的场景，而不是套长篇公式。
                已写正文与硬事实不可无解释改写，最新世界书约束世界设定。用户希望的爽感、张力、互动或氛围必须在章节主体发生，不能只是埋伏笔与铺垫。
                outline 写出适量的具体场景、动作和人物反应、看点如何兑现、阅读情绪及与下章的必要承接。事件数不固定；关系变化、明暗线推进、伏笔和结尾悬念仅在作品真正需要时出现。
                三四章短篇要在有限篇幅内实现核心场面与收束，不制造无意义的长线；长篇则可用多阶段结构。若用户只要无主线体验，可用连贯的场面与情绪组织本章。
                本次仅规划第 $start 章，优先返回JSON：
                {"number":$start,"title":"","outline":""}
                也允许数组、中文字段或实质性的分节；不要只输出格式模板。
            """.trimIndent()

            val raw = generatePlanningText(
                characterId = characterId,
                facts = facts,
                instruction = instruction,
                title = "《" + storyTitle + "》第" + start + "-" + end + "章规划",
                maxTokens = (batchCount * 720 + 1_500).coerceIn(2_800, 8_600),
            )

            var batch = parseChapterPlans(raw, start, end)
            if (batch.size != batchCount) {
                val fixed = repairChapterPayload(
                    characterId = characterId,
                    raw = raw,
                    storyTitle = storyTitle,
                    start = start,
                    end = end,
                    originalFacts = facts,
                ).getOrNull()
                if (fixed != null) batch = parseChapterPlans(fixed, start, end)
            }
            check(batch.size == batchCount) {
                "第 $start-$end 章未取得可用的章节规划，请重试。"
            }
            collected += batch
            val progressPlans = (1..chapterCount).map { number ->
                collected.firstOrNull { it.number == number }
                    ?: existingPlans.firstOrNull { it.number == number }
                    ?: StarWishChapterPlan(number = number, title = "第 $number 章", outline = "待规划")
            }
            onProgress?.invoke(progressPlans)
            start = end + 1
        }

        collected.mapIndexed { index, plan -> plan.copy(number = index + 1) }
    }

    private suspend fun generatePlanningText(
        characterId: String,
        facts: String,
        instruction: String,
        title: String,
        maxTokens: Int,
    ): String {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val retryInstruction = if (attempt == 0) instruction else buildString {
                appendLine(instruction)
                appendLine()
                appendLine("这是第 ${attempt + 1} 次尝试。上一次接口成功返回但没有可读取正文。")
                appendLine("不要只进行内部思考，不要停在 reasoning/analysis；必须在最终 answer/content 中实际输出要求的 JSON。")
                appendLine("不要输出空字符串、工具调用、占位符或省略号。")
            }
            val result = LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = facts,
                instruction = retryInstruction,
                source = "剧场",
                title = if (attempt == 0) title else "$title · 空响应重试${attempt + 1}",
                maxTokens = if (attempt == 0) maxTokens else (maxTokens + attempt * 1_200).coerceAtMost(10_000),
                connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
                contextMode = CompanionContextMode.Isolated,
                readTimeoutMillis = 240_000,
            )
            val reply = result.getOrNull()
            if (reply != null && reply.text.isNotBlank()) return reply.text
            val error = result.exceptionOrNull()
            lastError = error
            if (error != null && !isUnreadablePlanningResponse(error)) throw error
        }
        throw IllegalStateException(
            "剧情规划模型连续3次没有返回正文。不是章节内容的问题；请检查当前剧场模型/中转站是否把最终 answer/content 返回为空。",
            lastError,
        )
    }

    private fun isUnreadablePlanningResponse(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("没有返回可读取") ||
            message.contains("流式响应没有返回") ||
            message.contains("接口返回了空内容") ||
            message.contains("没有返回正文")
    }

    internal fun parseStoryBible(raw: String, writtenCount: Int): StarWishStoryBible {
        val root = theaterPlanningObjects(parseJsonValue(raw)) { objectValue ->
            theaterBibleFields.keys.any { theaterPlanningText(objectValue, it).isNotBlank() }
        }.firstOrNull() ?: theaterPlanningSections(raw).takeIf { it.length() > 0 }
            ?: error("幕后规划未返回可识别的内容")
        return StarWishStoryBible(
            worldview = text(root, "worldview", "世界观", "世界前提"),
            overview = text(root, "overview", "故事总纲", "总纲", "故事核心"),
            hook = text(root, "hook", "核心钩子", "开篇钩子"),
            highlights = text(root, "highlights", "核心看点", "亮点"),
            emotionalArc = text(root, "emotionalArc", "情绪曲线", "情感曲线"),
            proseStyle = text(root, "proseStyle", "文风", "文风执行"),
            cast = text(root, "cast", "人物", "人物设定", "人物卡"),
            characterArcs = text(root, "characterArcs", "人物成长", "成长弧"),
            relationshipArc = text(root, "relationshipArc", "relationshipCore", "感情线", "关系线"),
            plotSpine = text(root, "plotSpine", "故事脉络", "剧情脉络"),
            mainLine = text(root, "mainLine", "明线", "主线"),
            hiddenLine = text(root, "hiddenLine", "暗线"),
            foreshadows = text(root, "foreshadows", "foreshadowing", "伏笔", "伏笔系统"),
            stagePlan = text(root, "stagePlan", "阶段规划", "阶段高潮"),
            endingDirection = text(root, "endingDirection", "结局方向", "结局"),
            romanceAesthetics = text(root, "romanceAesthetics", "感情描写", "审美执行"),
            updatedThroughChapter = root.optInt("updatedThroughChapter", writtenCount).coerceAtLeast(writtenCount),
        )
    }

    internal fun storyBibleCompleteEnough(bible: StarWishStoryBible): Boolean =
        // An experience-led short work need not have a main plot, romance or arcs.
        listOf(bible.overview, bible.highlights, bible.hook).any(String::isNotBlank)

    private suspend fun completeSingleStoryPayload(
        characterId: String,
        raw: String,
        direction: String,
        variant: Int,
        theaterWorldBook: String = "",
    ): Result<String> = runCatching {
        LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                if (theaterWorldBook.isNotBlank()) appendLine("本书选用世界书（必须遵守）：\n$theaterWorldBook")
                if (direction.isNotBlank()) appendLine("用户原始题材：$direction")
                appendLine("第${variant}套第一次输出如下。它可能JSON格式有问题，也可能缺字段：")
                appendLine(raw.take(26_000))
            },
            instruction = """
                只修复格式和必要核心内容，保持用户最想体验的爽点、张力、氛围或篇幅不变。
                title、overview、highlights 要真实具体；其他字段仅在故事需要时填，不得为了凑齐栏目擅自加入人物成长、长篇感情或伏笔。需要人物时自行命名，不强制男女主。
                只输出一个合法JSON对象：
                {"title":"","worldview":"","hook":"","overview":"","highlights":"","cast":"","characterArcs":"","relationshipCore":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadowing":"","stagePlan":"","endingDirection":"","emotionalArc":"","proseStyle":"","romanceAesthetics":"","wordCount":"1800-3000"}
            """.trimIndent(),
            source = "剧场",
            title = "补全第${variant}套剧情方案",
            maxTokens = 5_800,
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 240_000,
        ).getOrThrow().text
    }

    private suspend fun completeStoryPayload(
        characterId: String,
        raw: String,
        existingTitle: String?,
        existingGuide: String?,
        direction: String,
    ): Result<String> = runCatching {
        LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                if (!existingTitle.isNullOrBlank()) appendLine("已有标题：$existingTitle")
                if (!existingGuide.isNullOrBlank()) appendLine("已有故事地图：\n$existingGuide")
                if (direction.isNotBlank()) appendLine("用户题材/要求：\n$direction")
                appendLine("第一次模型输出如下。它可能格式混乱，也可能只有第一套完整、后二套缩水；请保留可用创意并补齐：\n${raw.take(28_000)}")
            },
            instruction = """
                把第一次输出整理并补全成恰好3套完整、独立、同等详细的小说方案。
                这不仅是格式修复：如果方案2或方案3缺字段、只有一小段、只写“与方案1不同之处”，必须把它扩写成与方案1同等完整的独立方案。
                如果用户只提供题材而没有男女主姓名/职业/身份，直接自行创造，绝对不要把缺名字当成无法规划的理由。
                不要改变用户明确指定的题材核心。

                每套必须全部包含：
                title, worldview, hook, overview, cast, characterArcs, relationshipCore, plotSpine,
                mainLine, hiddenLine, foreshadowing, stagePlan, endingDirection, emotionalArc,
                proseStyle, romanceAesthetics, highlights, wordCount。
                三套的信息量必须接近，不能只有第一套详细。

                只输出合法JSON数组，顶层恰好3个对象，不要Markdown，不要解释：
                [
                  {"title":"","worldview":"","hook":"","overview":"","cast":"","characterArcs":"","relationshipCore":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadowing":"","stagePlan":"","endingDirection":"","emotionalArc":"","proseStyle":"","romanceAesthetics":"","highlights":"","wordCount":"1800-3000"},
                  {"title":"","worldview":"","hook":"","overview":"","cast":"","characterArcs":"","relationshipCore":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadowing":"","stagePlan":"","endingDirection":"","emotionalArc":"","proseStyle":"","romanceAesthetics":"","highlights":"","wordCount":"1800-3000"},
                  {"title":"","worldview":"","hook":"","overview":"","cast":"","characterArcs":"","relationshipCore":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadowing":"","stagePlan":"","endingDirection":"","emotionalArc":"","proseStyle":"","romanceAesthetics":"","highlights":"","wordCount":"1800-3000"}
                ]
            """.trimIndent(),
            source = "剧场",
            title = "补全三套剧情方案",
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
        originalFacts: String,
    ): Result<String> = runCatching {
        val count = end - start + 1
        LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = originalFacts + "\n《" + storyTitle + "》已生成的第" + start + "-" + end + "章规划原文：\n" + raw.take(24_000),
            instruction = """
                只做格式修复。保留原剧情规划，整理成合法JSON数组。
                必须恰好有 $count 个对象，依次对应第 $start 至第 $end 章；若原输出只因格式混乱漏掉对象边界，请恢复出来。
                每项格式：{"number":1,"title":"","outline":""}
                outline 必须保留原规划真正出现的场景、体验、行动和情绪；不可增添原本没有的明暗线、感情线或伏笔。只输出JSON。
            """.trimIndent(),
            source = "剧场",
            title = "修复章节规划格式",
            maxTokens = (count * 650 + 800).coerceIn(1_800, 7_600),
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 240_000,
        ).getOrThrow().text
    }

    internal fun parseCandidates(raw: String): List<StarWishPlotCandidate> =
        theaterPlanningObjects(parseJsonValue(raw), ::looksLikeCandidate)
            .ifEmpty { listOfNotNull(theaterPlanningSections(raw).takeIf { looksLikeCandidate(it) }) }
            .mapIndexedNotNull { index, obj -> candidate(obj, index + 1) }

    private fun looksLikeCandidate(obj: JSONObject): Boolean =
        listOf("overview", "故事总纲", "mainLine", "明线", "worldview", "世界观", "hook", "钩子", "cast", "人物")
            .any(obj::has)

    private fun candidate(obj: JSONObject, fallbackIndex: Int): StarWishPlotCandidate? {
        val worldview = text(obj, "worldview", "世界观", "世界设定", "世界前提")
        val hook = text(obj, "hook", "钩子", "开篇钩子", "核心钩子")
        val relationship = text(obj, "relationshipCore", "relationshipArc", "关系主线", "关系线", "长期感情线", "感情线")
        val mainLine = text(obj, "mainLine", "明线", "主线目标")
        val hiddenLine = text(obj, "hiddenLine", "暗线", "暗线真相")
        val overview = text(obj, "overview", "总纲", "故事总纲", "总览", "故事核心")
            .ifBlank { mainLine.ifBlank { hook.ifBlank { worldview } } }
        val title = text(obj, "title", "标题", "name", "书名")
            .ifBlank { "候选故事 $fallbackIndex" }

        if (listOf(worldview, hook, relationship, mainLine, hiddenLine, overview).all(String::isBlank)) return null

        return StarWishPlotCandidate(
            title = title,
            worldview = worldview,
            hook = hook,
            relationshipCore = relationship,
            mainLine = mainLine,
            hiddenLine = hiddenLine,
            foreshadowing = text(obj, "foreshadowing", "foreshadows", "伏笔", "伏笔系统", "伏笔明细"),
            emotionalArc = text(obj, "emotionalArc", "情绪曲线", "情感曲线"),
            proseStyle = text(obj, "proseStyle", "文风", "叙事风格", "文风执行"),
            highlights = text(obj, "highlights", "亮点", "爽点", "核心看点"),
            overview = overview,
            chapters = chapterTexts(obj),
            wordCount = text(obj, "wordCount", "字数", "每章字数").ifBlank { "1800-3000" },
            cast = text(obj, "cast", "人物", "人物设定", "人物卡"),
            characterArcs = text(obj, "characterArcs", "人物成长", "成长弧", "人物成长弧"),
            plotSpine = text(obj, "plotSpine", "故事脉络", "剧情脉络", "长线脉络"),
            stagePlan = text(obj, "stagePlan", "阶段规划", "阶段高潮", "阶段节奏"),
            endingDirection = text(obj, "endingDirection", "结局方向", "结局"),
            romanceAesthetics = text(obj, "romanceAesthetics", "感情描写", "审美执行", "感情戏与人物描写"),
        )
    }

    private fun threeCompleteCandidates(items: List<StarWishPlotCandidate>): Boolean =
        items.size == 3 && items.take(3).all(::candidateCompleteEnough)

    private fun candidateCompleteEnough(item: StarWishPlotCandidate): Boolean =
        item.title.isNotBlank() && item.overview.isNotBlank() && item.highlights.isNotBlank()

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

    internal fun parseChapterPlans(raw: String, start: Int, end: Int): List<StarWishChapterPlan> {
        val expectedCount = end - start + 1
        val root = parseJsonValue(raw)

        val nestedPlans = theaterPlanningObjects(root) { obj ->
            listOf("outline", "规划", "剧情", "内容", "details", "细纲", "chapterPlan", "events", "keyEvents", "beats", "具体事件")
                .any { obj.has(it) && !obj.isNull(it) }
        }
        val values: List<Any> = if (nestedPlans.isNotEmpty()) nestedPlans else when (root) {
            is JSONArray -> buildList {
                for (index in 0 until root.length()) root.opt(index)?.let(::add)
            }
            is JSONObject -> {
                val nestedArray = firstArray(root, "chapters", "plans", "chapterPlans", "章节", "章节规划", "data", "result")
                when {
                    nestedArray != null -> buildList {
                        for (index in 0 until nestedArray.length()) nestedArray.opt(index)?.let(::add)
                    }
                    else -> {
                        val nestedObject = listOf("chapter", "plan", "章节", "规划", "data", "result")
                            .firstNotNullOfOrNull { key -> root.optJSONObject(key) }
                        listOf(nestedObject ?: root)
                    }
                }
            }
            is String -> listOf(root)
            else -> emptyList()
        }

        val parsed = values.mapIndexedNotNull { index, item ->
            val number = start + index
            when (item) {
                is JSONObject -> chapterPlanFromObject(item, number)
                is String -> chapterPlanFromText(item, number)
                else -> null
            }
        }.take(expectedCount)

        if (parsed.size == expectedCount) {
            return parsed.mapIndexed { index, plan -> plan.copy(number = start + index) }
        }

        // Planning now runs one chapter at a time. Do not discard useful content just
        // because a provider used a different schema or slightly malformed JSON.
        if (expectedCount == 1) {
            chapterPlanFromText(raw, start)?.let { return listOf(it.copy(number = start)) }
        }
        return parsed.mapIndexed { index, plan -> plan.copy(number = start + index) }
    }

    private fun chapterPlanFromObject(item: JSONObject, number: Int): StarWishChapterPlan? {
        val title = text(item, "title", "标题", "name", "章节标题", "chapterTitle")
            .ifBlank { "第 $number 章" }
        val direct = text(
            item,
            "outline", "规划", "剧情", "内容", "summary", "摘要", "details", "细纲",
            "plot", "chapterPlan", "story", "正文规划",
        )
        val structured = if (direct.isNotBlank()) direct else {
            val preferred = listOf(
                "stage" to "阶段功能",
                "function" to "阶段功能",
                "阶段功能" to "阶段功能",
                "events" to "具体事件",
                "keyEvents" to "具体事件",
                "beats" to "具体事件",
                "事件" to "具体事件",
                "具体事件" to "具体事件",
                "choices" to "人物选择",
                "人物选择" to "人物选择",
                "relationship" to "关系变化",
                "关系变化" to "关系变化",
                "mainLine" to "明线推进",
                "明线" to "明线推进",
                "hiddenLine" to "暗线推进",
                "暗线" to "暗线推进",
                "foreshadowing" to "伏笔",
                "foreshadows" to "伏笔",
                "伏笔" to "伏笔",
                "emotion" to "情绪目标",
                "情绪" to "情绪目标",
                "hook" to "结尾钩子",
                "endingHook" to "结尾钩子",
                "结尾钩子" to "结尾钩子",
                "nextState" to "下一章状态",
            )
            val seen = mutableSetOf<String>()
            val lines = mutableListOf<String>()
            preferred.forEach { (key, label) ->
                if (label in seen || !item.has(key) || item.isNull(key)) return@forEach
                val value = item.opt(key)?.toString()?.trim().orEmpty()
                if (value.isNotBlank() && value != "[]" && value != "{}") {
                    lines += "$label：$value"
                    seen += label
                }
            }
            if (lines.isNotEmpty()) {
                lines.joinToString("\n")
            } else {
                buildList {
                    val keys = item.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key in setOf("number", "chapter", "index", "title", "标题", "name", "章节标题")) continue
                        val value = item.opt(key)?.toString()?.trim().orEmpty()
                        if (value.isNotBlank() && value != "[]" && value != "{}") add("$key：$value")
                    }
                }.joinToString("\n")
            }
        }
        if (structured.isBlank()) return null
        return StarWishChapterPlan(number = number, title = title, outline = structured)
    }

    private fun chapterPlanFromText(raw: String, number: Int): StarWishChapterPlan? {
        val fence = 96.toChar().toString().repeat(3)
        val clean = raw.trim()
            .removePrefix(fence + "json")
            .removePrefix(fence + "JSON")
            .removePrefix(fence)
            .removeSuffix(fence)
            .trim()
        if (clean.length < 16) return null
        if ((clean.startsWith('{') || clean.startsWith('[')) && theaterPlanningValue(clean) == null) return null
        val firstLine = clean.lineSequence().firstOrNull().orEmpty()
            .replace(Regex("^#+\\s*"), "")
            .take(40)
            .trim()
        val title = firstLine
            .takeIf { it.length in 2..40 && !it.startsWith("{") && !it.startsWith("[") }
            ?: "第 $number 章"
        return StarWishChapterPlan(number = number, title = title, outline = clean)
    }

    private fun parseJsonValue(raw: String): Any? = theaterPlanningValue(raw)

    private fun firstArray(obj: JSONObject, vararg keys: String): JSONArray? =
        keys.firstNotNullOfOrNull { key -> obj.optJSONArray(key) }

    private fun firstValue(obj: JSONObject, vararg keys: String): Any? {
        keys.forEach { key ->
            if (obj.has(key) && !obj.isNull(key)) return obj.opt(key)
        }
        return null
    }

    private fun text(obj: JSONObject, vararg keys: String): String = theaterPlanningText(obj, *keys)
}
