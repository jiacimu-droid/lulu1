package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class WorldLifeEpisodeComposerTest {
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

    @Test fun softOrUnfinishedFillerIsNeverACompletedSoloStory() {
        assertFalse(WorldLifeEpisodeComposer.readableScene("江渡回了家，安静地坐下。"))
        assertFalse(WorldLifeEpisodeComposer.readableScene(
            ("江渡并没有做什么特别的事，只是觉得时间还在往前走。" +
                "至于心里究竟在想什么，没有人替他宣布答案。").repeat(6)))
    }
}
