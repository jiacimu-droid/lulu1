package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])

class WorldLifeEpisodeComposerTest {
    @Test fun modelFailureReasonUsesStableHttpStatusNotObfuscatedClassName() {
        assertEquals("模型接口鉴权或权限失败（HTTP 401）",
            WorldLifeEpisodeComposer.failureSummary(IllegalStateException("请求失败（401）：unauthorized")))
        assertTrue(WorldLifeEpisodeComposer.permanentFailure(IllegalStateException("HTTP 401")))
        assertEquals("模型接口限流（HTTP 429）",
            WorldLifeEpisodeComposer.failureSummary(IllegalStateException("HTTP 429")))
        assertFalse(WorldLifeEpisodeComposer.permanentFailure(IllegalStateException("HTTP 429")))
        assertEquals("模型响应超时",
            WorldLifeEpisodeComposer.failureSummary(IllegalStateException("read timeout")))
    }

    @Test fun completedSceneFieldSurvivesMissingOptionalMetadata() {
        val prose = ("他打开灯，光落在桌角。想把没做完的小事搁置，又不甘心。" +
            "迟疑了一会儿，他换了个姿势，重新审视刚才的选择。" +
            "窗外很静，他决定把那个问题留给明天，今晚先睡一会儿。").repeat(3)
        val raw = "{\"scene\":" + org.json.JSONObject.quote(prose) +
            ",\"title\":\"台灯亮起\",\"unresolvedThread\":\"没有结束"
        val result = WorldLifeEpisodeComposer.parseModelScene(raw, "晚上")
        assertEquals(prose, result?.scene)
        assertEquals("晚上", result?.title)
    }

    @Test fun proseOnlyModelResponseCanBeKeptIfComplete() {
        val prose = ("台灯亮起后他却迟迟没坐下。旧念头不合时宜地出现。" +
            "他想装作没看见，手却停在椅背上。最后他收起了那份急躁，" +
            "决定给自己一点时间，把话想明白再开口。").repeat(4)
        assertEquals(prose, WorldLifeEpisodeComposer.parseModelScene(prose, "夜间片刻")?.scene)
        assertNull(WorldLifeEpisodeComposer.parseModelScene("{\"scene\":\"半句话", "片段"))
    }

    @Test fun shortButCompleteSceneIsNotThrownAwayByArbitraryLengthOrPunctuation() {
        val shortScene = "江渡在灯旁站了一会儿，想起半小时前的犹豫，又慢慢将椅子拉近。灯光比想象中安静"
        assertTrue(WorldLifeEpisodeComposer.readableScene(shortScene))
        assertEquals(shortScene, WorldLifeEpisodeComposer.parseModelScene(shortScene, "今晚")?.scene)
        val tagged = "【心声】" + shortScene
        assertEquals(tagged, WorldLifeEpisodeComposer.parseModelScene(tagged, "今晚")?.scene)
    }

    @Test fun literarySceneNeedsActualDramaticMovementNotAReport() {
        val scripted = "江渡在家中停下来。" +
            "这里确实发生的，只有已经记录下来的活动；至于心里究竟在想什么，没有人替他宣布答案。" +
            "他把注意力放回自己正在做的事上，容许这一段时间有一个自然的开头和结束。" +
            "等到下次真的遇见新东西、产生新兴趣，才值得继续写下去。"
        assertFalse(WorldLifeEpisodeComposer.readableScene(scripted))
        val grounded = """
            门合上以后，屋里只剩他自己的脚步声。江渡本想直接睡觉，走到书架旁却又停住了。
            白天说出去的那句话还悬在心头。他原以为那是足够好的安慰，可回到自己屋里，
            他忽然不知道自己当时究竟听懂了多少。这念头让他有些烦，偏偏越想压下去就越清晰。
            他拉开椅子，又没有坐。真要明天重新提起，他怕给对方添负担；不提，却也像是在逃避。
            窗外安静得很，他终于坐下，决定先记住这个疑问，不急着把它包装成一个完美答案。
            明早醒来，他也许会换一种说法。那时他得先弄明白，她究竟在害怕失去什么。
        """.trimIndent()
        assertTrue(WorldLifeEpisodeComposer.readableScene(grounded))
    }

    @Test fun literaryHeadingIsSeparatedFromBodyWithoutPersistingMarkdownNoise() {
        val prose = ("江渡翻开桌边的书，窗外的光在书页上轻轻挪动。" +
            "他伸手去扶正那张纸，却忽然觉得屋子也安静下来了。").repeat(3)
        val raw = "《书脊上的晚光》\\n\\n" + prose
        val draft = WorldLifeEpisodeComposer.parseModelScene(raw, "一段独处时光")
        assertEquals("书脊上的晚光", draft?.title)
        assertEquals(prose, draft?.scene)
    }

    @Test fun incompleteHeadingNeverEatsARealScene() {
        val prose = "小屋里有一盏灯，他走进来，慢慢整理自己的书架。".repeat(5)
        assertEquals(prose, WorldLifeEpisodeComposer.parseModelScene(prose, "短暂独处")?.scene)
        assertNull(WorldLifeEpisodeComposer.literaryHeading("《没有结尾的标题"))
    }

    @Test fun softOrUnfinishedFillerIsNeverACompletedSoloStory() {
        assertFalse(WorldLifeEpisodeComposer.readableScene("江渡回了家，安静地坐下。"))
        assertFalse(WorldLifeEpisodeComposer.readableScene(
            ("江渡并没有做什么特别的事，只是觉得时间还在往前走。" +
                "至于心里究竟在想什么，没有人替他宣布答案。").repeat(6)))
    }
}
