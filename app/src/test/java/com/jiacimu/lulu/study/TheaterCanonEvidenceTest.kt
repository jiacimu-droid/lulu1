package com.jiacimu.lulu.study

import org.junit.Assert.*
import org.junit.Test

class TheaterCanonEvidenceTest {
    @Test fun oldIrreversibleEventsStayInContextEvenWithManyNewChapters() {
        val first = StarWishTheaterChapter(theater = "小说", chapter = 1, title = "血誓",
            content = "那半块黑玉化作飞灰。\n在三年前，少年拾得古卷。", userInfluence = "")
        val second = StarWishTheaterChapter(theater = "小说", chapter = 2, title = "代价",
            content = "李长老的舌头被连根拔去，从此失语。", userInfluence = "")
        val later = (3..14).map { number ->
            StarWishTheaterChapter(theater = "小说", chapter = number, title = "第${number}章",
                content = "两人在路上继续交谈并前行。", userInfluence = "")
        }
        val evidence = TheaterCanonEvidence.index(listOf(second) + later + first)
        assertTrue(evidence.contains("第1章原文：那半块黑玉化作飞灰。"))
        assertTrue(evidence.contains("第2章原文：李长老的舌头被连根拔去"))
        assertTrue(evidence.indexOf("第1章原文") < evidence.indexOf("第2章原文"))
    }

    @Test fun concreteSceneDirectionsReachWriterWithoutGenericSynopsis() {
        val plan = StarWishChapterPlan(
            number = 7, title = "深夜试探", outline = "二人共同躲入客栈。",
            spotlight = "不要跳转追杀；聚焦双方对视和权力反转。",
            sceneBeats = "先越界靠近，再试探底线，最后改变对方的判断。",
            relationshipBeat = "双方都不承认自己动心。",
        )
        val prompt = TheaterCanonEvidence.planningText(plan)
        assertTrue(prompt.contains("【本章高光】"))
        assertTrue(prompt.contains("权力反转"))
        assertTrue(prompt.contains("【场面节拍】"))
        assertTrue(prompt.contains("【关系变化】"))
    }

    @Test fun boundedEvidenceDoesNotInventFactsOrTakeOverContext() {
        val novels = listOf(StarWishTheaterChapter(theater = "书", chapter = 1,
            title = "开篇", content = "有一个人默默吃饭。", userInfluence = ""))
        assertEquals("", TheaterCanonEvidence.index(novels))
        assertEquals("", TheaterCanonEvidence.index(novels, maxChars = 0))
    }
}
