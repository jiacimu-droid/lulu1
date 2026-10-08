package com.jiacimu.lulu.study

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TheaterPlanningPayloadTest {
    @Test fun wrappedChinesePlanningReflowsWithoutSplittingWordsOrLeavingPunctuationAlone() {
        val wrapped = "红雾感染肆虐的废土末世\n，秩序崩坏。幸存者在\n残垣断壁间苟延残\n喘。\n\n野犬般的少年把所有的温软与忠\n诚，毫无保留地献给他的庇护者\n。"
        val expected = "红雾感染肆虐的废土末世，秩序崩坏。幸存者在残垣断壁间苟延残喘。\n\n野犬般的少年把所有的温软与忠诚，毫无保留地献给他的庇护者。"
        assertEquals(expected, theaterPlanningTypography(wrapped))
        assertEquals(expected, StarWishTheaterPlanningEngine.parseStoryBible(
            JSONObject().put("worldview", wrapped).toString(), 0).worldview)
        assertEquals(expected, theaterPlanningTypography(expected))
    }

    @Test fun planningReflowPreservesParagraphsListsCharacterLabelsAndEnglishText() {
        val structured = "第一段完整。\n第二段完整。\n\n露洲：想留下\n女主：保持警惕\n1. 先建立信任\n2. 再共同选择\n- 身份未定\n- 关系未定\n【人物成长】\n主动选择\n\nA quiet night\nAn open door"
        assertEquals(structured, theaterPlanningTypography(structured.replace("\n", "\r\n")))
        val array = org.json.JSONArray().put("露洲想留下").put("女主保持警惕")
        val parsed = StarWishTheaterPlanningEngine.parseStoryBible(JSONObject().put("cast", array).toString(), 0)
        assertEquals("露洲想留下\n\n女主保持警惕", parsed.cast)
        assertEquals(parsed.cast, theaterPlanningTypography(parsed.cast))
    }

    @Test fun normalChineseDialogueQuotesNeverCorruptJson() {
        val body = "她说“别走”，却先关上了门。"
        val raw = "模型说明\n```json\n" + JSONObject().put("title", "夜色").put("worldview", "城市")
            .put("hook", body).put("overview", body).toString() + "\n```\n结束"
        val candidate = StarWishTheaterPlanningEngine.parseCandidates(raw).single()
        assertEquals(body, candidate.hook)
        assertEquals(body, candidate.overview)
    }
    @Test fun wrappedAndEncodedBiblesKeepContentAndRecognizeVisibleSectionNames() {
        val data = JSONObject().put("世界观", "旧城").put("人物与人设", "女主有自己的判断")
            .put("人物成长弧", "从回避到面对").put("长期感情线", "由试探到信任")
            .put("阶段高潮与节奏", "每阶段推进一个关键选择").put("感情戏与人物描写", "用动作与潜台词")
        val raw = JSONObject().put("data", JSONObject().put("result", data.toString())).toString()
        val bible = StarWishTheaterPlanningEngine.parseStoryBible(raw, 3)
        assertEquals("女主有自己的判断", bible.cast)
        assertEquals("由试探到信任", bible.relationshipArc)
        assertEquals("每阶段推进一个关键选择", bible.stagePlan)
        assertEquals(3, bible.updatedThroughChapter)
        val repaired = StarWishStoryBible(worldview = "新世界").withMissingFieldsFrom(bible)
        assertEquals("新世界", repaired.worldview)
        assertEquals(bible.cast, repaired.cast)
    }
    @Test fun readableMarkdownBibleAndNestedChapterPlanDoNotRequireAnotherApiCall() {
        val bible = StarWishTheaterPlanningEngine.parseStoryBible("""
            ## 一、世界观
            一座旧城。
            ## 二、人物与人设
            她有自己的判断。
            ## 三、长期感情线
            信任从共同选择中建立。
        """.trimIndent(), 0)
        assertEquals("一座旧城。", bible.worldview)
        assertEquals("她有自己的判断。", bible.cast)
        assertEquals("信任从共同选择中建立。", bible.relationshipArc)
        val story = StarWishTheaterPlanningEngine.parseCandidates("标题：旧城\n世界观：一座旧城。\n核心钩子：她发现所有人都在隐瞒同一件事。").single()
        assertEquals("旧城", story.title)
        val plan = JSONObject().put("title", "新的选择").put("outline", "她主动离开旧城，并留下能被找到的线索。")
        val raw = JSONObject().put("data", JSONObject().put("result", JSONObject().put("plan", plan))).toString()
        val parsed = StarWishTheaterPlanningEngine.parseChapterPlans(raw, 7, 7).single()
        assertEquals("新的选择", parsed.title)
        assertEquals(plan.getString("outline"), parsed.outline)
        assertEquals(7, parsed.number)
    }
    @Test fun directorFrameworkAcceptsOneFilledSlotAndConciseCoreWithoutLengthThreshold() {
        assertEquals("主动选择", StarWishTheaterPlanningEngine.parseStoryBible("{\"mainLine\":\"主动选择\"}", 2).mainLine)
        val concise = StarWishStoryBible(worldview = "旧城", overview = "找回家人", cast = "姐弟", plotSpine = "寻找再相认",
            mainLine = "寻找", stagePlan = "发现线索，面对真相")
        assertTrue(StarWishTheaterPlanningEngine.storyBibleCompleteEnough(concise))
        assertFalse(StarWishTheaterPlanningEngine.storyBibleCompleteEnough(StarWishStoryBible(cast = "姐弟")))
        assertEquals(19, theaterBibleFields.size)
        assertEquals(theaterBibleFields.keys, concise.fieldValues().keys)
    }
    @Test fun experienceLedBibleDoesNotRequireLongFormSlots() {
        val focus = StarWishStoryBible(
            overview = "三章内只看主角在众人面前连续打脸，痛快收束。",
            highlights = "每章都要有直接兑现的爽点。",
        )
        assertTrue(StarWishTheaterPlanningEngine.storyBibleCompleteEnough(focus))
        assertTrue(focus.mainLine.isBlank())
        assertTrue(focus.hiddenLine.isBlank())
        assertTrue(focus.characterArcs.isBlank())
        assertFalse(StarWishTheaterPlanningEngine.storyBibleCompleteEnough(StarWishStoryBible()))
    }
    @Test fun originalCreativeIntentSurvivesStorySelectionWithoutInventingPlotSpine() {
        val chosen = StarWishTheaterPlanningEngine.parseCandidates(
            JSONObject().put("title", "今日无敌")
                .put("overview", "三章里只写痛快的无敌爽点，不加虐恋或人物成长。")
                .put("highlights", "不被看好的主角一出手，全场噤声。").toString()
        ).single().copy(creativeIntent = "我要纯爽文，三章结束，不要爱情主线")
        assertTrue(chosen.storyGuide().contains("【用户原始创作要求（最高优先级）】"))
        assertTrue(chosen.storyGuide().contains("我要纯爽文，三章结束，不要爱情主线"))
        assertEquals("", chosen.storyBible().plotSpine)
        assertEquals("", chosen.storyBible().relationshipArc)
        assertTrue(StarWishTheaterPlanningEngine.storyBibleCompleteEnough(chosen.storyBible()))
    }
    @Test fun threePlansCanBeDecodedFromOneBatchResponse() {
        val chapters = org.json.JSONArray().apply {
            (1..3).forEach { number ->
                put(JSONObject().put("number", number)
                    .put("title", "第${number}章")
                    .put("outline", "第${number}章兑现不同的高光场面，并自然衔接下一幕。"))
            }
        }
        val result = StarWishTheaterPlanningEngine.parseChapterPlans(chapters.toString(), 4, 6)
        assertEquals(3, result.size)
        assertEquals(listOf(4, 5, 6), result.map { it.number })
        assertTrue(result.all { it.outline.contains("高光场面") })
    }


    @Test fun interruptedBatchSalvagesWholeObjectsWithoutInventingTheMissingChapter() {
        val complete4 = JSONObject().put("number", 4).put("title", "靠近").put("outline", "他主动走近，她却先打断告白，气氛骤然变化。")
        val complete6 = JSONObject().put("number", 6).put("title", "选择").put("outline", "终于坦诚相待，却留下新的未解问题。")
        val interrupted = "[" + complete4.toString() + "," + complete6.toString() +
            ",{\"number\":5,\"title\":\"还没写完\",\"outline\":\""
        val plans = StarWishTheaterPlanningEngine.parseChapterPlans(interrupted, 4, 6)
        assertEquals(listOf(4, 6), plans.map { it.number })
        assertEquals("靠近", plans.first().title)
        assertTrue(plans.last().outline.contains("坦诚"))
    }

    @Test fun markdownChaptersRecoverWithoutASecondFormattingRequest() {
        val raw = """
            ## 第7章：压抑的对峙
            他不敢看她，却在她转身的瞬间伸手，眼底的情绪突然泄露。
            ## 第8章：反过来的试探
            她这次抢先贴近，故意轻声提起昨晚的争执，他没能维持平静。
            ## 第9章：终于回应
            两个人都放下各自的伪装，认真选择了下一步，而非空泛地说关系升温。
        """.trimIndent()
        val plans = StarWishTheaterPlanningEngine.parseChapterPlans(raw, 7, 9)
        assertEquals(listOf(7, 8, 9), plans.map { it.number })
        assertEquals("压抑的对峙", plans.first().title)
        assertTrue(plans[1].outline.contains("昨晚"))
    }

    @Test fun incompletePlanShellDoesNotBecomeACompletedChapter() {
        val raw = org.json.JSONArray().put(JSONObject().put("number", 1).put("title", "只有标题"))
            .put(JSONObject().put("number", 2).put("title", "下一章").put("sceneBeats", "他发现了真实线索，并决定质问她。"))
        val plans = StarWishTheaterPlanningEngine.parseChapterPlans(raw.toString(), 1, 2)
        assertEquals(listOf(2), plans.map { it.number })
        assertEquals("下一章", plans.single().title)
    }

    @Test fun oneStoryProposalWorksWithoutRequiringThreeChoices() {
        val response = JSONObject()
            .put("title", "群雄俯首")
            .put("overview", "三章内集中呈现主角绝对实力，不加虐恋。")
            .put("highlights", "主角出手，众人彻底折服。")
        val parsed = StarWishTheaterPlanningEngine.parseCandidates(response.toString())
        assertEquals(1, parsed.size)
        assertEquals("群雄俯首", parsed.single().title)
        assertEquals("", parsed.single().mainLine)
        assertEquals("", parsed.single().characterArcs)
    }

    @Test fun newStoryCarriesVisualAttractionAndTwoSidedRelationshipIntoBibleAndGuide() {
        val response = JSONObject().put("title", "双月之下")
            .put("overview", "一场对峙改变两个人的信任。")
            .put("highlights", "近距离试探和有力回应。")
            .put("experienceFocus", "把权力拉扯写成高光，不拿追杀填充。")
            .put("appearanceDesign", "甲有清晰的眉骨、微卷黑发，乙身形挺拔、声线明亮。")
            .put("relationshipDynamics", "甲试探边界，乙保持主动判断；关系不被预设为爱情。")
        val candidate = StarWishTheaterPlanningEngine.parseCandidates(response.toString()).single()
        assertTrue(candidate.storyGuide().contains("【主要人物视觉档案】"))
        assertEquals(candidate.experienceFocus, candidate.storyBible().experienceFocus)
        assertEquals(candidate.appearanceDesign, candidate.storyBible().appearanceDesign)
        assertEquals(candidate.relationshipDynamics, candidate.storyBible().relationshipDynamics)
        val parsed = StarWishTheaterPlanningEngine.parseStoryBible(JSONObject()
            .put("experienceFocus", "悬疑推理和翻案。")
            .put("appearanceDesign", "角色长相保持不变。")
            .put("relationshipDynamics", "").toString(), 0)
        assertEquals("悬疑推理和翻案。", parsed.experienceFocus)
        assertEquals("角色长相保持不变。", parsed.appearanceDesign)
    }

    @Test fun batchChapterPlansRetainConcreteScenePayoffs() {
        val plans = org.json.JSONArray().apply {
            (1..3).forEach { num ->
                put(JSONObject().put("number", num).put("title", "房间里的对峙")
                    .put("outline", "主角进门并发出质问")
                    .put("spotlight", "两人独处，权力关系瞬间倒转")
                    .put("sceneBeats", "先靠近，再逼问，最后由沉默收束")
                    .put("relationshipBeat", "由怀疑转为不得不正视对方"))
            }
        }
        val result = StarWishTheaterPlanningEngine.parseChapterPlans(plans.toString(), 10, 12)
        assertEquals(listOf(10, 11, 12), result.map { it.number })
        assertTrue(result.all { it.spotlight.contains("权力") })
        assertTrue(result.all { TheaterCanonEvidence.planningText(it).contains("场面节拍") })
        assertEquals("由怀疑转为不得不正视对方", result.first().relationshipBeat)
    }

    @Test fun emptyAndTruncatedRepliesCannotBecomeCompletedPlanning() {
        assertTrue(StarWishTheaterPlanningEngine.parseChapterPlans("", 1, 1).isEmpty())
        assertTrue(StarWishTheaterPlanningEngine.parseChapterPlans("{\"outline\":\"尚未完成的模型输出", 1, 1).isEmpty())
        assertTrue(runCatching { StarWishTheaterPlanningEngine.parseStoryBible("", 0) }.isFailure)
    }
}
