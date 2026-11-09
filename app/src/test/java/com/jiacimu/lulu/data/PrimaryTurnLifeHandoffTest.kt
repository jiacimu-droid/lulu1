package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PrimaryTurnLifeHandoffTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val role = "primary-life-handoff-test"

    @Before fun setup() {
        MemoryTestEnvironment.release()
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        ChatLexiconAutomation.initialize(ctx)
    }

    @After fun cleanup() {
        // All test events have their own stable IDs. No real owner source is
        // removed, and no model HTTP call is necessary to exercise this path.
        SharedExperienceTimeline.deleteEvent("life-owner-1")
        SharedExperienceTimeline.deleteEvent("life-owner-2")
        MemoryTestEnvironment.release()
    }

    private fun record(id: String, content: String) {
        SharedExperienceTimeline.record(
            eventId = id, characterId = role, channel = "私聊",
            speaker = "用户", content = content, occurredAt = Instant.now(),
            source = "message", evidenceKind = EventEvidenceKind.UserStatement,
            triggerExtraction = false)
    }

    @Test fun aModelAuthoredLifeChangeIsSavedOnceWithoutAnotherApiRequest() = runBlocking {
        record("life-owner-1", "我最近开始早睡，今晚计划十一点躺下")
        val changes = JSONArray().put(JSONObject().put("section", "life")
            .put("title", "睡前安排").put("content", "用户近期计划23点休息")
            .put("sourceEventId", "life-owner-1")
            .put("evidenceQuote", "今晚计划十一点躺下")
            .put("status", "Active"))
        assertTrue(ChatLexiconAutomation.acceptMainTurn(
            role, "chat-life-one", listOf("life-owner-1"), changes, true))
        val first = LuluRepositories.lexicon.snapshot(role)
            .filter { it.content.contains("23点休息") }
        assertEquals(1, first.size)
        assertTrue(ChatLexiconAutomation.acceptMainTurn(
            role, "chat-life-one", listOf("life-owner-1"), changes, true))
        val after = LuluRepositories.lexicon.snapshot(role)
            .filter { it.content.contains("23点休息") }
        assertEquals(1, after.size)
    }

    @Test fun invalidSourceQuoteIsNotSavedAndTheOriginalTurnRemainsRecoverable() = runBlocking {
        record("life-owner-2", "明天我去公园散步")
        val bogus = JSONArray().put(JSONObject().put("section", "life")
            .put("content", "用户将搬家到海外").put("title", "生活")
            .put("sourceEventId", "life-owner-2")
            .put("evidenceQuote", "搬家到海外")
            .put("status", "Active"))
        assertFalse(ChatLexiconAutomation.acceptMainTurn(
            role, "chat-life-invalid", listOf("life-owner-2"), bogus, true))
        assertFalse(LuluRepositories.lexicon.snapshot(role)
            .any { it.content.contains("搬家到海外") })
        assertFalse(ChatLexiconAutomation.acceptMainTurn(
            role, "chat-life-invalid", listOf("life-owner-2"), bogus, false))
    }

    @Test fun emptyArrayAcknowledgesThisTurnWithoutPurchasingAnExtraction() = runBlocking {
        record("life-owner-1", "你好呀")
        assertTrue(ChatLexiconAutomation.acceptMainTurn(
            role, "chat-life-empty", listOf("life-owner-1"), JSONArray(), true))
        assertTrue(ChatLexiconAutomation.acceptMainTurn(
            role, "chat-life-empty", listOf("life-owner-1"), JSONArray(), true))
    }
}
