package com.jiacimu.lulu.study

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class TheaterContinuityPayloadTest {
    @Test fun encodedWrappedAndStructuredLedgerFieldsAreReadable() {
        val ledger = JSONObject().put("summary", "她离开旧城。")
            .put("characters", JSONObject().put("江渡", "留在车站"))
            .put("hardFacts", listOf("姐姐仍然在世"))
        val raw = JSONObject().put("data", JSONObject().put("result", ledger.toString())).toString()
        val result = parseTheaterLedger(JSONObject.quote(raw), 3)
        assertEquals("她离开旧城。", result.summary)
        assertTrue(result.characters.contains("留在车站"))
        assertTrue(result.hardFacts.contains("姐姐仍然在世"))
        assertEquals(3, result.updatedThroughChapter)
    }
    @Test fun markdownAndPartialUpdatesPreserveKnownHardFacts() {
        val previous = StarWishStoryLedger(hardFacts = "姐姐仍然在世", updatedThroughChapter = 2)
        val next = parseTheaterLedger("## 剧情摘要\n她到了车站。\n## 人物当前状态\n江渡在车站。", 3, previous)
        assertEquals("她到了车站。", next.summary)
        assertEquals("江渡在车站。", next.characters)
        assertEquals(previous.hardFacts, next.hardFacts)
        assertFalse(next.evidenceOnly)
    }
    @Test fun keepsTimeInjuryAndPropAnchorsWhenProviderReturnsPartialLedger() {
        val old = StarWishStoryLedger(
            chronology = "第0天祭坛出事，三年前发现古卷。",
            physicalStates = "甲的舌头被拔，尚未恢复。",
            itemTransitions = "黑玉在第1章化为飞灰。",
            updatedThroughChapter = 2,
        )
        val next = parseTheaterLedger(
            JSONObject().put("summary", "第3章有人追来。").toString(), 3, old
        )
        assertEquals(old.chronology, next.chronology)
        assertEquals(old.physicalStates, next.physicalStates)
        assertEquals(old.itemTransitions, next.itemTransitions)
        val update = parseTheaterLedger(JSONObject()
            .put("summary", "第4章主角入城。")
            .put("chronology", "第0天出事，第1天来到黑市")
            .put("physicalStates", "甲仍然失语")
            .put("itemTransitions", "黑玉已经损毁，不可再次出现").toString(), 4, next)
        assertEquals("甲仍然失语", update.physicalStates)
        assertEquals("第0天出事，第1天来到黑市", update.chronology)
    }
    @Test fun failedSummaryUsesExactSavedEvidenceAndRetainsEarlierFacts() {
        val previous = StarWishStoryLedger(summary = "旧摘要", hardFacts = "姐姐仍然在世", updatedThroughChapter = 1)
        val old = StarWishTheaterChapter(theater = "书", chapter = 1, title = "旧章", content = "不应重复追加", userInfluence = "")
        val chapter = old.copy(chapter = 2, title = "车站", content = "江渡把钥匙交给姐姐，随后离开车站。")
        val result = theaterLedgerFromEvidence(previous, listOf(old, chapter))
        assertTrue(result.evidenceOnly)
        assertEquals(2, result.updatedThroughChapter)
        assertTrue(result.summary.contains(chapter.content))
        assertFalse(result.summary.contains(old.content))
        assertEquals(previous.hardFacts, result.hardFacts)
        assertTrue(runCatching { parseTheaterLedger("不是档案 JSON", 2) }.isFailure)
    }
}
