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
    /**
     * Creating a new theater story is deliberately ONE API call for ONE story.
     * Invalid/partial output is reported for a manual retry rather than
     * quietly making extra paid repair calls.
     */
    suspend fun generateStoryCandidate(
        characterId: String,
        existingTitle: String?,
        existingGuide: String?,
        direction: String,
        theaterWorldBook: String = "",
    ): Result<StarWishPlotCandidate> = runCatching {
        currentCoroutineContext().ensureActive()
        val facts = buildString {
            appendLine("这是剧场 App 的独立故事策划：一次只创作一部新作品，不提供候选清单。可以是长篇、三四章短篇、纯爽文、氛围短片或纯粹体验，不默认主线与人物成长。")
            if (theaterWorldBook.isNotBlank()) appendLine("本书选用世界书（必须遵守）：\n$theaterWorldBook")
            if (!existingTitle.isNullOrBlank()) appendLine("现有故事标题：$existingTitle")
            if (!existingGuide.isNullOrBlank()) appendLine("现有故事地图：\n$existingGuide")
            if (direction.isNotBlank()) appendLine("用户最想看的体验、爽点、张力、篇幅和禁忌（最高创作优先级，必须成为作品中心）：\n$direction")
            else appendLine("用户未指定题材，请自行创造一部有鲜明阅读体验的作品，不默认爱情、长篇或成长线。")
            appendLine("只需要一套认真打磨的完整故事方案，不要输出多套备选。")
        }
        val instruction = """
            你是擅长不同篇幅与题材的创作策划。只写一部真正贴合用户愿望的故事，不要多个备选方案。
            用户想看的爽点、张力、氛围、关系互动、篇幅与禁忌决定创作方向，这些内容必须占据故事的主要场景。
            想看龙傲天、无敌或打脸，就让爽点直接、精彩地兑现，不强制主角挫败或成长；想看三四章短片、暧昧张力或纯氛围体验，就聚焦高密度场景，不擅自扩成长篇虐恋。
            只有用户明确需要长线发展，才规划人物成长、复杂关系、明暗线、伏笔和多个阶段。不要为了凑字段强加剧情。
            experienceFocus 要写出这部小说给读者的主要快感、情绪回报、典型高光场景和哪些无关支线应被压缩，不能只写“爽”“甜”“张力”。
            appearanceDesign 在人物相关的作品里为主要角色各定鲜明、稳定的视觉特征：容貌轮廓、身形/姿态、发肤、声音和习惯动作，并说明通过谁的目光逐步展示。不要只有“俊美、苍白、冷漠”等泛词；不需要固定身体形象的作品可以留空。
            relationshipDynamics 若作品重人物互动，则分别写出双方的欲望、权力、克制、会主动采取的动作、误解和关系能够变化的具体触发场面；不要自行决定所有故事都恋爱，也不要仅写“关系升温”。
            title（书名）、overview（核心体验与具体安排）、highlights（核心看点及高光场面）必须具体、完整。其他字段按需填写，不适用的直接使用空字符串，不要用「不适用」凑数。
            不要生成逐章详细规划。写清每章建议字数，但作品是否漫长、是否有主线由用户愿望决定。
            只输出一个合法JSON对象，不要数组、Markdown或解释：
            {"title":"","worldview":"","hook":"","overview":"","highlights":"","cast":"","characterArcs":"","relationshipCore":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadowing":"","stagePlan":"","endingDirection":"","emotionalArc":"","proseStyle":"","romanceAesthetics":"","experienceFocus":"","appearanceDesign":"","relationshipDynamics":"","wordCount":"1800-3000"}
        """.trimIndent()
        val raw = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = facts,
            instruction = instruction,
            source = "剧场",
            title = "新故事方案",
            maxTokens = 5_800,
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 240_000,
        ).getOrThrow().text

        val candidate = parseCandidates(raw).firstOrNull(::candidateCompleteEnough)
            ?: error("这一套故事方案的核心内容不完整，未自动发起收费补全请求；请手动重新生成。")
        candidate.copy(creativeIntent = direction.trim())
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
            保留旧栏位兼容页面和存档，并增加三个真正指挥写作的体验栏目；全部是按题材选择的工具箱：
            worldview 世界观、overview 核心安排、hook 钩子、highlights 核心看点、cast 人物、characterArcs 人物成长、relationshipArc 感情线、plotSpine 故事脉络、mainLine 明线、hiddenLine 暗线、foreshadows 伏笔、stagePlan 阶段节奏、endingDirection 收束、emotionalArc 情绪、proseStyle 文风、romanceAesthetics 感情描写。
            experienceFocus 阅读体验：确定读者最期待的具体回报、作品的高光场面优先级以及不值得反复写的追杀/受伤/阴谋套路。不得用笼统形容词代替场面。
            appearanceDesign 人物视觉与气质：给重要人物制定稳定可辨的容貌、身形、肌肤、穿着/声音/姿态和不同视角中的吸引力；只选择合适的细节，并让其随着动作和互动自然进入正文，不做身体特征堆砌。
            relationshipDynamics 双向关系动力：若读者重视 CP/主仆/宿敌等关系，分别写清双方想要什么、怕什么、权力不对称在哪里、怎样主动试探或拒绝，设计可落地的对话与近距离场面。关系可以暧昧未定，不预设必然爱情。
            核心看点与作品安排必须强调用户想体验的东西和对应的具体场景。其余栏目只在对这部作品有帮助时才填写；完全不适用的直接返回空字符串""，绝不能编造来填表，也不要填「不适用」冒充规划。
            如果已经有正文，严格以已确立的人物外貌、人物性别与身份、时间、伤势、物品状态为准；不要因生成新的视觉档案重设旧事实。
            写作语气应有层次变化，不要反复用同一比喻、同一神情、同一个人“野狗般”或另一个人“俊美”来顶替真正的描写。
            分批返回每次指定的栏目，优先可执行的场景、情绪、爽点和节奏，不以复杂程度作为质量指标。
            栏目中的连续叙述不要按字数硬换行，不要拆开词语或让标点单独成行；需要分段时用空行，人物和阶段条目使用明确的名称或序号。
        """.trimIndent()
        // Most short-form / experience-led stories use only a few director fields.
        // Prefer one response so users do not wait for four serialized model calls.
        // If a provider cannot return a usable full JSON, keep the proven smaller
        // groups as a compatibility fallback (with partial results preserved).
        val fullTemplate = JSONObject().apply {
            theaterBibleFields.keys.forEach { put(it, "") }
        }
        val fullRaw = generatePlanningText(
            characterId,
            facts,
            instruction + "\n尝试一次输出完整幕后规划，核心看点要具体；其余不适用字段直接空字符串。只返回JSON：" + fullTemplate,
            "$storyTitle · 一次生成幕后规划",
            7_000,
        )
        if (fullRaw.isNotBlank()) {
            val fullBible = runCatching { parseStoryBible(fullRaw, writtenChapters.size) }.getOrNull()
            if (fullBible != null && fullBible.overview.isNotBlank() && fullBible.highlights.isNotBlank()) {
                // Preserve intentionally established visual and relationship identities
                // if a later model response omits those optional fields.
                val preserved = fullBible.copy(
                    experienceFocus = fullBible.experienceFocus.ifBlank { existingBible?.experienceFocus.orEmpty() },
                    appearanceDesign = fullBible.appearanceDesign.ifBlank { existingBible?.appearanceDesign.orEmpty() },
                    relationshipDynamics = fullBible.relationshipDynamics.ifBlank { existingBible?.relationshipDynamics.orEmpty() },
                )
                onProgress?.invoke(preserved)
                return@runCatching preserved
            }
        }

        var bible = StarWishStoryBible(updatedThroughChapter = writtenChapters.size)
        // Fallback: four small groups survive strict JSON / truncated responses.
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
            // Plan up to three adjacent chapters per model call. The chapter
            // outlines are short JSON, so this reduces round trips without
            // compressing the actual novel prose.
            val end = minOf(start + 2, chapterCount)
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
                你是本作品的执行导演。根据用户原始创作要求、故事地图和幕后规划，为第 $start 至第 $end 章分别设计能兑现读者期待的场景，而不是套长篇公式。
                已写正文与硬事实不可无解释改写，最新世界书约束世界设定。用户希望的爽感、张力、互动或氛围必须在章节主体发生，不能只是埋伏笔与铺垫。
                每章应有“可阅读的核心场面”而不是流水账，明确人物是谁先行动、谁回应、肢体距离、反转点和最终情绪回报。允许整章聚焦一场精心设计的双人戏，不必另加追兵或阴谋。
                如果本书重人物/CP，spotlight 应围绕人物魅力、视线与权力拉扯；sceneBeats 应包含双方主动行为、环境与感官细节如何让紧张递进；relationshipBeat 应写明这场互动之后谁对谁的理解发生了什么改变，不能用“关系升温”混过去。
                如果作品不需要恋爱，就把 relationshipBeat 留空或写符合题材的战友情、信任和对立，不允许强行恋爱。不要连续三章用同一个“被追杀、受伤、保护”替代高光。
                outline 写出适量的具体事件与必要前因后果；spotlight 指定本章最值得细写的高光及其读者回报；sceneBeats 写出1至3个具体、有起伏的场景节拍（人物动作、回应、心理暗流和变化）；relationshipBeat 记录确实存在的互动推进。不要求每章都带恋爱、打斗或反转。
                三四章短篇要在有限篇幅内实现核心场面与收束，不制造无意义的长线；长篇则可用多阶段结构。若用户只要无主线体验，可用连贯的场面与情绪组织本章。
                本次规划第 $start 至第 $end 章，共 $batchCount 章。每章都有独立标题与具体情节，不允许只写「同上」或概括整段。
                按章节顺序返回JSON数组，每项格式：
                {"number":$start,"title":"","outline":"","spotlight":"","sceneBeats":"","relationshipBeat":""}
                不要混入已写完的章节、额外章节或解释。不要只输出格式模板。
            """.trimIndent()

            val raw = generatePlanningText(
                characterId = characterId,
                facts = facts,
                instruction = instruction,
                title = "《" + storyTitle + "》第" + start + "-" + end + "章规划",
                maxTokens = (batchCount * 1_100 + 1_500).coerceIn(3_200, 8_600),
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
            experienceFocus = text(root, "experienceFocus", "阅读体验重心", "核心阅读体验", "爽点执行"),
            appearanceDesign = text(root, "appearanceDesign", "人物视觉档案", "人物外貌", "视觉设计"),
            relationshipDynamics = text(root, "relationshipDynamics", "双向关系动力", "人物关系张力", "关系动力"),
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
                每项格式：{"number":1,"title":"","outline":"","spotlight":"","sceneBeats":"","relationshipBeat":""}
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
            experienceFocus = text(obj, "experienceFocus", "阅读体验重心", "核心阅读体验", "爽点执行"),
            appearanceDesign = text(obj, "appearanceDesign", "人物视觉档案", "人物外貌", "视觉设计"),
            relationshipDynamics = text(obj, "relationshipDynamics", "双向关系动力", "人物关系张力", "关系动力"),
        )
    }

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
        if (start > end || raw.isBlank()) return emptyList()
        val root = parseJsonValue(raw)
        val nestedPlans = theaterPlanningObjects(root) { obj ->
            listOf("outline", "规划", "剧情", "内容", "details", "细纲",
                "chapterPlan", "events", "keyEvents", "beats", "具体事件",
                "spotlight", "sceneBeats").any { obj.has(it) && !obj.isNull(it) }
        }
        val values: List<Any> = if (nestedPlans.isNotEmpty()) nestedPlans else when (root) {
            is JSONArray -> buildList { for (index in 0 until root.length()) root.opt(index)?.let(::add) }
            is JSONObject -> {
                val array = firstArray(root, "chapters", "plans", "chapterPlans", "章节", "章节规划", "data", "result")
                if (array != null) buildList {
                    for (index in 0 until array.length()) array.opt(index)?.let(::add)
                } else {
                    val nested = listOf("chapter", "plan", "章节", "规划", "data", "result")
                        .firstNotNullOfOrNull(root::optJSONObject)
                    listOf(nested ?: root)
                }
            }
            is String -> listOf(root)
            else -> emptyList()
        }

        val plans = linkedMapOf<Int, StarWishChapterPlan>()
        fun add(item: Any, position: Int) {
            val explicitNumber = (item as? JSONObject)?.let {
                it.optInt("number", it.optInt("chapter", it.optInt("index", -1)))
            }
            // Use actual numbers if they are in range, but accept models numbering a batch 1..3
            // even when this batch is for chapters 7..9.
            val number = explicitNumber?.takeIf { it in start..end } ?: position
            if (number !in start..end || number in plans) return
            val candidate = when (item) {
                is JSONObject -> chapterPlanFromObject(item, number)
                is String -> chapterPlanFromText(item, number)
                else -> null
            } ?: return
            // A title-only shell is not a usable plan; never mark it complete.
            val meaningful = listOf(candidate.outline, candidate.spotlight, candidate.sceneBeats)
                .any { it.isNotBlank() && it != "待规划" }
            if (meaningful) plans[number] = candidate.copy(number = number)
        }
        values.forEachIndexed { index, item -> add(item, start + index) }

        // Valid individual objects may be present even if the enclosing array was truncated.
        if (plans.size < end - start + 1) {
            theaterChapterJsonFragments(raw).forEachIndexed { index, item ->
                add(item, start + index)
            }
        }
        // Human-readable numbered sections need no extra paid "JSON repair" call.
        if (plans.size < end - start + 1) {
            theaterChapterMarkdownSections(raw).forEach { (number, text) ->
                if (number in start..end && number !in plans) {
                    chapterPlanFromText(text, number)?.let { plans[number] = it.copy(number = number) }
                }
            }
        }
        if (plans.isEmpty() && start == end) {
            chapterPlanFromText(raw, start)?.let { plans[start] = it.copy(number = start) }
        }
        return plans.values.sortedBy(StarWishChapterPlan::number)
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
        val spotlight = text(item, "spotlight", "本章高光", "核心场面", "体验兑现")
        val sceneBeats = text(item, "sceneBeats", "场面推进", "场景节拍", "具体场面")
        val relationshipBeat = text(item, "relationshipBeat", "关系变化", "情感推进", "关系转折")
        if (structured.isBlank() && spotlight.isBlank() && sceneBeats.isBlank()) return null
        return StarWishChapterPlan(number = number, title = title, outline = structured,
            spotlight = spotlight, sceneBeats = sceneBeats, relationshipBeat = relationshipBeat)
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
