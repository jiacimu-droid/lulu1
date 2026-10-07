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
            if (!existingGuide.isNullOrBlank()) appendLine("现有故事地图：\n$existingGuide")
            if (direction.isNotBlank()) {
                appendLine("用户提供的题材或大纲（最高优先级）：\n$direction")
            } else {
                appendLine("用户没有指定题材，请主动构思三套差异明显、适合长篇发展的故事。")
            }
            appendLine("注意：用户只给题材、梗或一句话也已经足够。缺少男女主姓名、职业、身份等不是缺信息，你必须主动补齐，不得要求用户先提供名字。")
        }
        val instruction = """
            你是成熟的长篇类型小说总策划。必须一次给出恰好3套完整、彼此差异明显、可以真正展开成长篇小说的方案。
            这是完全独立的剧场小说，不得引用真实角色人设、聊天、记忆、共同时间线、用户资料或世界书。
            用户即使只写“追妻火葬场”“留在公司”“先婚后爱”这种很短的题材，也必须直接自行创造男女主姓名、身份、职业、处境和冲突，不要反问，不要因为缺名字而省略字段。
            若用户给的是完整或半完整大纲，三套方案都要尊重核心设定；差异放在人物动机、冲突设计、暗线真相、感情推进、阶段高潮和结局路径上。

            三套方案必须使用完全相同的完整模板，而且每一套都要独立成立。禁止“方案1写完整，方案2/3只写与方案1的区别”。
            每套都必须认真写满这些字段：
            - title：故事名；若用户没给，自行起名。
            - worldview：世界观、时代/行业/环境规则和人物所处现实。
            - hook：开篇最想让人继续读的钩子。
            - overview：从开篇到结局的故事总纲。
            - cast：主要人物与人设，至少男女主；姓名、身份、外貌气质、欲望、恐惧、底线、秘密、行为方式。
            - characterArcs：人物成长/变化弧，以及变化由什么事件推动。
            - relationshipCore：男女主和关键关系的长期感情线。
            - plotSpine：整本书的故事脉络、阶段目标、关键转折和高潮。
            - mainLine：明线目标、阻力与推进方式。
            - hiddenLine：暗线真相、幕后因果以及逐步揭露方式。
            - foreshadowing：伏笔系统，写清埋设、表层含义、真实含义和预计回收阶段。
            - stagePlan：阶段节奏，安排冲突、甜点、误会/低谷、反转、高潮与喘息。
            - endingDirection：结局方向和必须兑现的承诺。
            - emotionalArc：读者情绪曲线。
            - proseStyle：可执行文风，包括镜头、五感、对白、心理、留白等。
            - romanceAesthetics：感情戏与人物吸引力的描写方式，强调眼神、手、腕骨、锁骨、肩颈、衣料、声音、呼吸、距离、动作停顿与潜台词，但不要机械堆砌。
            - highlights：核心看点/爽点/最想追下去的理由。
            - wordCount：每章建议字数。

            不要提前生成逐章规划，章数由用户之后自己建立空白章节。
            只输出合法JSON数组，不要Markdown，不要解释。顶层必须恰好3个对象；三个对象必须字段齐全、信息量接近：
            [
              {"title":"","worldview":"","hook":"","overview":"","cast":"","characterArcs":"","relationshipCore":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadowing":"","stagePlan":"","endingDirection":"","emotionalArc":"","proseStyle":"","romanceAesthetics":"","highlights":"","wordCount":"1800-3000"},
              {"title":"","worldview":"","hook":"","overview":"","cast":"","characterArcs":"","relationshipCore":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadowing":"","stagePlan":"","endingDirection":"","emotionalArc":"","proseStyle":"","romanceAesthetics":"","highlights":"","wordCount":"1800-3000"},
              {"title":"","worldview":"","hook":"","overview":"","cast":"","characterArcs":"","relationshipCore":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadowing":"","stagePlan":"","endingDirection":"","emotionalArc":"","proseStyle":"","romanceAesthetics":"","highlights":"","wordCount":"1800-3000"}
            ]
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

        var candidates = parseCandidates(reply)
        if (!threeCompleteCandidates(candidates)) {
            val repairedRaw = completeStoryPayload(
                characterId = characterId,
                raw = reply,
                existingTitle = existingTitle,
                existingGuide = existingGuide,
                direction = direction,
            ).getOrNull()
            if (!repairedRaw.isNullOrBlank()) {
                val repaired = parseCandidates(repairedRaw)
                if (repaired.isNotEmpty()) candidates = repaired
            }
        }

        val finalCandidates = candidates.take(3)
        if (!threeCompleteCandidates(finalCandidates)) {
            error("这次模型没有完整返回三套剧情方案，已自动补全过一次；请重新生成。")
        }
        finalCandidates
    }

    suspend fun generateStoryBible(
        characterId: String,
        storyTitle: String,
        storyGuide: String,
        chapterCount: Int,
        writtenChapters: List<StarWishTheaterChapter>,
        existingBible: StarWishStoryBible? = null,
        ledger: StarWishStoryLedger? = null,
    ): Result<StarWishStoryBible> = runCatching {
        require(storyGuide.isNotBlank()) { "故事地图不能为空" }
        val writtenEvidence = writtenChapters.takeLast(8).joinToString("\n\n") { chapter ->
            "第${chapter.chapter}章 ${chapter.title}\n${chapter.content.takeLast(2_400)}"
        }
        val facts = buildString {
            appendLine("独立剧场故事：《$storyTitle》")
            appendLine("故事地图（核心方向与看点，不是逐章细纲）：\n$storyGuide")
            appendLine("计划总章数：$chapterCount；已经写完：${writtenChapters.size}章。")
            existingBible?.promptText()?.takeIf(String::isNotBlank)?.let {
                appendLine("旧幕后规划，仅供继承仍然有效的长期结构：\n$it")
            }
            ledger?.promptText()?.takeIf(String::isNotBlank)?.let {
                appendLine("正文确认的当前状态与硬事实，优先级最高：\n$it")
            }
            if (writtenEvidence.isNotBlank()) appendLine("最近已写正文证据：\n$writtenEvidence")
        }
        val raw = LuluAiServices.gateway.generate(
            characterId = characterId,
            facts = facts,
            instruction = """
                你是这部长篇小说的幕后总导演。请生成/刷新一份“幕后规划”，它不是逐章规划，而是长期稳定的故事圣经。
                已经写出的正文和连续性档案中的硬事实是最高事实：人物死亡、生死状态、亲属关系、身份、性别、婚姻/恋爱关系、阵营、已知秘密、伤势、物品归属、地点与已经发生的关键事件绝对不能被未来规划改写。
                可以为了更精彩而重新设计尚未发生的未来剧情，但必须自然承接已经写出的内容，不能让人物性格和关系无理由跳变。

                幕后规划必须包含：
                1. cast：主要人物卡。姓名/身份/年龄感/外貌气质/标志性细节/欲望/恐惧/底线/秘密/行为习惯；人物必须鲜明且会主动做决定。
                2. characterArcs：主要人物从开篇到终局可能发生的成长、堕落、改变，以及每个变化需要什么事件推动，禁止突然性格翻转。
                3. relationshipArc：男女主及关键关系的长期推进，写清吸引、试探、误会、靠近、冲突、确认关系等阶段，不要只靠直白告白。
                4. plotSpine：整本书从当前进度到终局的长线脉络、阶段目标、关键转折和高潮。
                5. mainLine：读者能直接看到的主线目标与阻力。
                6. hiddenLine：暗线真相、幕后因果、何时逐步露出。
                7. foreshadows：伏笔清单，写明已埋/待埋、表层含义、真实含义、预计回收阶段；已经回收的不要重复当新伏笔。
                8. stagePlan：按阶段安排冲突、甜点、反转、低谷、高潮和喘息，避免长篇一直原地打转。
                9. endingDirection：结局方向与必须兑现的核心承诺，可以保留少量可调整空间。
                10. romanceAesthetics：感情戏的审美执行。男女主外貌与吸引力描写要自然、有画面，善用眼神、手指、腕骨、锁骨、肩颈线条、衣料、声音、呼吸、距离、光影、动作停顿和潜台词制造心动感；不能机械重复部位，也不能牺牲人物性格与剧情。

                目标只有两个：精彩、连贯。未来规划要有主动人物、因果链、伏笔与回收、关系变化和真正推进的事件。
                只输出合法JSON对象，不要Markdown：
                {"cast":"","characterArcs":"","relationshipArc":"","plotSpine":"","mainLine":"","hiddenLine":"","foreshadows":"","stagePlan":"","endingDirection":"","romanceAesthetics":"","updatedThroughChapter":0}
            """.trimIndent(),
            source = "剧场",
            title = "$storyTitle · 幕后规划",
            maxTokens = 5_800,
            connectionOverride = ScopedModelSelections.resolveConnection(ScopedModelSelections.THEATER),
            contextMode = CompanionContextMode.Isolated,
            readTimeoutMillis = 240_000,
        ).getOrThrow().text
        parseStoryBible(raw, writtenChapters.size)
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
    ): Result<List<StarWishChapterPlan>> = runCatching {
        require(storyGuide.isNotBlank()) { "总大纲不能为空" }
        require(chapterCount in 1..StarWishRules.MAX_CHAPTERS_PER_THEATER) { "章节数量不正确" }

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
            val end = minOf(start + 8, chapterCount)
            val batchCount = end - start + 1
            val previous = collected.takeLast(4).joinToString("\n") { plan ->
                "第" + plan.number + "章 " + plan.title + "：" + plan.outline.take(500)
            }
            val facts = buildString {
                appendLine("独立剧场故事：《$storyTitle》")
                appendLine("故事地图（核心方向与看点，不得推翻）：\n$storyGuide")
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

    private fun parseStoryBible(raw: String, writtenCount: Int): StarWishStoryBible {
        val root = parseJsonValue(raw) as? JSONObject ?: error("幕后规划格式无法识别")
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
                for (index in 0 until root.length()) {
                    when (val item = root.opt(index)) {
                        is JSONObject -> add(item)
                        is String -> runCatching { JSONTokener(item).nextValue() as? JSONObject }.getOrNull()?.let(::add)
                    }
                }
            }
            is JSONObject -> {
                val nested = firstArray(root, "candidates", "plans", "stories", "方案", "方案列表", "options")
                when {
                    nested != null -> buildList {
                        for (index in 0 until nested.length()) nested.optJSONObject(index)?.let(::add)
                    }
                    looksLikeCandidate(root) -> listOf(root)
                    else -> buildList {
                        val keys = root.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            root.optJSONObject(key)?.let(::add)
                        }
                    }
                }
            }
            else -> emptyList()
        }
        return objects.mapIndexedNotNull { index, obj -> candidate(obj, index + 1) }
    }

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
        val core = listOf(
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
        val filled = core.count { it.trim().length >= 12 }
        return item.title.isNotBlank() && item.hook.trim().length >= 8 && filled >= 13
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
