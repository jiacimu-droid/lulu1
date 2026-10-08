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
        assertEquals(16, theaterBibleFields.size)
        assertEquals(theaterBibleFields.keys, concise.fieldValues().keys)
    }
    @Test fun emptyAndTruncatedRepliesCannotBecomeCompletedPlanning() {
        assertTrue(StarWishTheaterPlanningEngine.parseChapterPlans("", 1, 1).isEmpty())
        assertTrue(StarWishTheaterPlanningEngine.parseChapterPlans("{\"outline\":\"尚未完成的模型输出", 1, 1).isEmpty())
        assertTrue(runCatching { StarWishTheaterPlanningEngine.parseStoryBible("", 0) }.isFailure)
    }
}
