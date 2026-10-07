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
        val accepted = mutableListOf<StarWishPlotCandidate>()
        for (variant in 1..3) {
            val previous = accepted.joinToString("\n") { "《${it.title}》：${it.hook.take(180)}" }
            val facts = buildString {
                appendLine("这是剧场 App 的独立长篇小说策划任务。")
                if (!existingTitle.isNullOrBlank()) appendLine("现有故事标题：$existingTitle")
                if (!existingGuide.isNullOrBlank()) appendLine("现有故事地图：\n$existingGuide")
                if (direction.isNotBlank()) appendLine("用户提供的题材/一句话主题（最高优先级）：\n$direction")
                else appendLine("用户没有指定题材，请主动构思适合长篇发展的故事。")
                appendLine("现在只生成第 $variant 套方案，共3套。必须完整写完这一套，不能省略字段。")
                if (previous.isNotBlank()) appendLine("已经生成的方案如下，本套在人物动机、冲突、暗线、感情推进或结局路径上必须明显不同：\n$previous")
                appendLine("用户只给一句题材也完全足够。若没有男女主姓名、职业、身份、世界背景，请你主动创造并命名，不得因为缺名字而省略任何部分。")
            }
            val instruction = """
                你是成熟的长篇类型小说总策划。只输出一套完整方案。
                目标只有两个：精彩、连贯。人物必须鲜明、有欲望、有主动选择；剧情必须有因果、冲突、转折、伏笔与回收；感情线要能支撑长篇推进。

                这一套必须完整包含：
                title：故事名。
                worldview：世界观、时代/行业/环境规则与人物处境。
                hook：最强开篇钩子。
                overview：从开篇到结局方向的故事总纲。
                highlights：核心看点、爽点、虐点、反转卖点。
                cast：主要人物与人设，至少男女主；姓名、身份、外貌气质、欲望、恐惧、底线、秘密、行为方式。
                characterArcs：主要人物成长/变化弧，以及变化由什么事件推动。
                relationshipCore：男女主和关键关系的长期感情线。
                plotSpine：整本书的长线故事脉络、阶段目标、关键转折和高潮。
                mainLine：明线目标、阻力与推进。
                hiddenLine：暗线真相、幕后因果和揭露节奏。
                foreshadowing：伏笔系统，至少4项，写明表层含义、真实含义、埋设阶段、预计回收阶段。
                stagePlan：阶段节奏，安排冲突、甜点、低谷、反转、高潮与喘息，避免故事几章就耗尽。
                endingDirection：结局方向与必须兑现的核心承诺。
                emotionalArc：读者情绪曲线。
                proseStyle：可执行文风，包括镜头、五感、对白、心理、留白、意象。
                romanceAesthetics：感情戏与人物吸引力描写原则；自然运用眼神、手、腕骨、锁骨、肩颈、衣料、声音、呼吸、距离、光影、动作停顿和潜台词制造心动感，不机械堆砌身体部位。
                wordCount：每章建议字数。

                不要生成逐章规划，章节数量由用户之后建立空白章节。
                只输出一个合法JSON对象，不要Markdown，不要解释，不要外层数组：
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
                ).getOrNull()
                if (!fixed.isNullOrBlank()) candidate = parseCandidates(fixed).firstOrNull()
            }

            if (candidate == null || !candidateCompleteEnough(candidate)) {
                error("第${variant}套方案生成不完整。已经自动补全过一次；直接重新生成即可，不需要补男女主名字。")
            }
            accepted += candidate
        }
        accepted
    }

    suspend fun generateStoryBible(
        characterId: String,
        storyTitle: String,
        storyGuide: String,
        chapterCount: Int,
        writtenChapters: List<StarWishTheaterChapter>,
        existingBible: StarWishStoryBible? = null,
        ledger: StarWishStoryLedger? = null,
        theaterWorldBook: String = "",
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
            existingBible?.promptText()?.takeIf(String::isNotBlank)?.let {
                appendLine("旧幕后规划，仅供继承仍然有效的长期结构：\n$it")
            }
            ledger?.promptText()?.takeIf(String::isNotBlank)?.let {
                appendLine("正文确认的当前状态与硬事实，优先级最高：\n$it")
            }
            if (writtenEvidence.isNotBlank()) appendLine("最近已写正文证据：\n$writtenEvidence")
        }
        val instruction = """
            你是这部长篇小说的幕后总导演。请生成/刷新一份“幕后规划”，它不是逐章规划，而是长期稳定的故事圣经。
            已经写出的正文和连续性档案中的硬事实是最高事实：人物死亡、生死状态、亲属关系、身份、性别、婚姻/恋爱关系、阵营、已知秘密、伤势、物品归属、地点与已经发生的关键事件绝对不能被未来规划改写。
            可以为了更精彩而重新设计尚未发生的未来剧情，但必须自然承接已经写出的内容，不能让人物性格和关系无理由跳变。

            幕后规划与新建故事方案必须使用同一套模板。完整保留并刷新：
            worldview 世界观、overview 故事总纲、hook 核心钩子、highlights 核心看点、
            emotionalArc 情绪曲线、proseStyle 文风执行、cast 人物与人设、characterArcs 人物成长弧、
            relationshipArc 长期感情线、plotSpine 故事脉络/主线、mainLine 明线、hiddenLine 暗线、
            foreshadows 伏笔系统、stagePlan 阶段高潮与节奏、endingDirection 结局方向、
            romanceAesthetics 感情戏与人物吸引力的描写审美。
            已经写出的正文优先级最高；可以调整尚未发生的未来，但不得为了新规划推翻已确认事实。

            目标只有两个：精彩、连贯。未来规划要有主动人物、因果链、伏笔与回收、关系变化和真正推进的事件。
            只输出合法JSON对象，不要Markdown：
            {"worldview":"","overview":"","hook":"","highlights":"","emotionalArc":"","proseStyle":"","cast":"","characterArcs":"","relationshipArc":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadows":"","stagePlan":"","endingDirection":"","romanceAesthetics":"","updatedThroughChapter":0}
        """.trimIndent()
        val raw = generatePlanningText(
            characterId = characterId,
            facts = facts,
            instruction = instruction,
            title = "$storyTitle · 幕后规划",
            maxTokens = 6_400,
        )
        var bible = runCatching { parseStoryBible(raw, writtenChapters.size) }.getOrNull()
        if (bible == null || !storyBibleCompleteEnough(bible)) {
            val fixed = repairStoryBiblePayload(
                characterId = characterId,
                facts = facts,
                raw = raw,
                storyTitle = storyTitle,
                writtenCount = writtenChapters.size,
            ).getOrThrow()
            val repaired = parseStoryBible(fixed, writtenChapters.size)
            bible = repaired.withMissingFieldsFrom(bible)
        }
        check(storyBibleCompleteEnough(bible)) { "幕后规划生成不完整，已自动补全一次但仍缺少关键长线内容" }
        bible
    }

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
        require(storyBible != null && storyBible.promptText().isNotBlank()) {
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
                appendLine("故事地图（核心方向与看点，不得推翻）：\n$storyGuide")
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
                你是小说作者兼剧情导演。根据故事地图、幕后长期规划和正文已确认事实，为指定章节生成真正可执行的逐章写作框架。
                已写正文与硬事实优先级最高；不得让死人复活、亲属关系变动、身份/伤势/物品/已知信息回滚，除非正文明确给出合理反转依据。
                不要重写故事核心；“重新生成”的目标是让尚未发生的后续更有吸引力、更有因果、更想让人继续读，而不是推翻前文。
                女主与其他主要人物一样可以正常写出明确的想法、选择和后果。用户若在“影响下一章”里改变女主行为，以用户最新输入和已生成正文为准，后续规划自然改道即可。

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

                本次只规划第 $start 章。优先输出一个JSON对象，不要Markdown：
                {"number":$start,"title":"","outline":""}
                如果你更习惯输出数组、中文字段或结构化小节也可以；只要把这一章的实质规划完整写出来即可，系统会兼容解析。
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
                ).getOrNull()
                if (fixed != null) batch = parseChapterPlans(fixed, start, end)
            }
            check(batch.size == batchCount) {
                "第 $start-$end 章已经生成，但章节规划格式不完整。"
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
            theaterPlanningText(objectValue, "worldview").isNotBlank() || theaterPlanningText(objectValue, "cast").isNotBlank()
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

    private fun storyBibleCompleteEnough(bible: StarWishStoryBible): Boolean {
        val required = listOf(
            bible.worldview,
            bible.overview,
            bible.hook,
            bible.highlights,
            bible.emotionalArc,
            bible.proseStyle,
            bible.cast,
            bible.characterArcs,
            bible.relationshipArc,
            bible.plotSpine,
            bible.mainLine,
            bible.hiddenLine,
            bible.foreshadows,
            bible.stagePlan,
            bible.endingDirection,
            bible.romanceAesthetics,
        )
        val filled = required.count { it.isNotBlank() }
        val detailSize = required.sumOf { it.trim().length }
        return filled >= 15 && detailSize >= 420
    }

    private suspend fun completeSingleStoryPayload(
        characterId: String,
        raw: String,
        direction: String,
        variant: Int,
    ): Result<String> = runCatching {
        LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                if (direction.isNotBlank()) appendLine("用户原始题材：$direction")
                appendLine("第${variant}套第一次输出如下。它可能JSON格式有问题，也可能缺字段：")
                appendLine(raw.take(26_000))
            },
            instruction = """
                保留这套方案的核心创意，把它补成一套完整的长篇小说方案。
                如果用户没给人名、职业或身份，主动补全并命名。不能输出“同上”“略”“待定”。
                以下字段全部必须有实质内容：title, worldview, hook, overview, highlights, cast, characterArcs, relationshipCore, plotSpine, mainLine, hiddenLine, foreshadowing, stagePlan, endingDirection, emotionalArc, proseStyle, romanceAesthetics, wordCount。
                只输出一个合法JSON对象，不要Markdown，不要解释：
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

    private suspend fun repairStoryBiblePayload(
        characterId: String,
        facts: String,
        raw: String,
        storyTitle: String,
        writtenCount: Int,
    ): Result<String> = runCatching {
        LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = buildString {
                appendLine(facts)
                appendLine()
                appendLine("第一次幕后规划输出如下；可能是JSON格式损坏、包了一层对象、字段名偏差，或部分字段为空：")
                appendLine(raw.take(28_000))
            },
            instruction = """
                重新整理并补全为一份完整的幕后规划。不是逐章规划。
                保留第一次输出中可用的故事创意，不得改写已经发生的正文事实。
                以下字段都要有实质内容：worldview, overview, hook, highlights, emotionalArc, proseStyle,
                cast, characterArcs, relationshipArc, plotSpine, mainLine, hiddenLine, foreshadows,
                stagePlan, endingDirection, romanceAesthetics。
                updatedThroughChapter 必须是 $writtenCount。
                只输出一个合法JSON对象，不要Markdown，不要解释：
                {"worldview":"","overview":"","hook":"","highlights":"","emotionalArc":"","proseStyle":"","cast":"","characterArcs":"","relationshipArc":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadows":"","stagePlan":"","endingDirection":"","romanceAesthetics":"","updatedThroughChapter":$writtenCount}
            """.trimIndent(),
            source = "剧场",
            title = "$storyTitle · 修复幕后规划",
            maxTokens = 7_200,
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
            plotSpine = text(obj, "plotSpine", "故事脉络", "剧情脉络", "长线脉络").ifBlank { overview },
            stagePlan = text(obj, "stagePlan", "阶段规划", "阶段高潮", "阶段节奏"),
            endingDirection = text(obj, "endingDirection", "结局方向", "结局"),
            romanceAesthetics = text(obj, "romanceAesthetics", "感情描写", "审美执行", "感情戏与人物描写"),
        )
    }

    private fun threeCompleteCandidates(items: List<StarWishPlotCandidate>): Boolean =
        items.size == 3 && items.take(3).all(::candidateCompleteEnough)

    private fun candidateCompleteEnough(item: StarWishPlotCandidate): Boolean {
        val required = listOf(
            item.worldview,
            item.overview,
            item.cast,
            item.characterArcs,
            item.relationshipCore,
            item.plotSpine,
            item.mainLine,
            item.hiddenLine,
            item.foreshadowing,
            item.stagePlan,
            item.endingDirection,
            item.emotionalArc,
            item.proseStyle,
            item.romanceAesthetics,
            item.highlights,
        )
        val filled = required.count { it.isNotBlank() }
        val detailSize = required.sumOf { it.trim().length } + item.hook.trim().length
        return item.title.isNotBlank() &&
            item.hook.isNotBlank() &&
            filled >= 14 &&
            detailSize >= 420
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
