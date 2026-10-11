package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterRelationalInitiativeTest {
    private val id = "relationship-initiative-test"
    private val now = Instant.parse("2026-10-11T09:00:00Z")

    private fun start(): Context {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterRelationalInitiative.initialize(context)
        CharacterRelationalInitiative.clear(id)
        return context
    }

    private fun userEvent(text: String, eventId: String, at: Instant = now): SharedTimelineEvent =
        SharedTimelineEvent(eventId, id, "私聊", "用户", text, at,
            evidenceKind = EventEvidenceKind.UserStatement)

    private fun plan(action: String = "message") = JSONObject()
        .put("status", "ready")
        .put("aim", "用自己读到的东西给对方一个小惊喜")
        .put("why", "自己喜欢这个人，而且真正读过一篇有趣的故事")
        .put("approach", "找合适的机会用自己的口吻分享")
        .put("deliveryAction", action)

    @Test fun preferencesMustBeOwnStatementsNotRoleClaimsOrOldEvents() {
        val valid = userEvent("我喜欢有一点暧昧张力的互动", "yes")
        val refusal = userEvent("我不喜欢反复催我回复", "no")
        val characterGuess = valid.copy(id = "guess", content = "她喜欢我吧",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        val stale = userEvent("我喜欢惊喜", "old", now.minusSeconds(210L * 86400))
        val unrelated = userEvent("今天天气真好", "weather")
        val matches = CharacterRelationalInitiative.explicitStatements(
            listOf(valid, characterGuess, stale, unrelated, refusal), now)
        assertEquals(listOf("no", "yes"), matches.map { it.id })
        assertTrue(matches.none { it.id == "guess" || it.id == "old" })
    }

    @Test fun wishCanWaitButUnrelatedSuccessfulActivityCannotCompleteIt() {
        val context = start()
        CharacterRelationalInitiative.recordDecision(id, plan(), "silent", false, "想一想", now)
        assertTrue(CharacterRelationalInitiative.context(id, now).contains("尚未实行的主意"))
        assertTrue(CharacterRelationalInitiative.expressionHandoff(id).contains("自己此前考虑"))
        assertFalse(CharacterRelationalInitiative.expressionHandoff(id).contains("用户原话"))
        CharacterRelationalInitiative.recordDecision(id, plan(), "journal", true, "日记写好了", now)
        val state = JSONObject(context.getSharedPreferences("lulu_relational_initiative_v1", 0).getString(id, "{}"))
        assertNotNull(state.optJSONObject("pending"))
        assertNull(state.optJSONObject("lastDelivery"))
    }

    @Test fun failedMatchingActionStaysPendingUntilRealSuccess() {
        val context = start()
        CharacterRelationalInitiative.recordDecision(id, plan(), "message", false, "网络请求失败", now)
        val prefs = context.getSharedPreferences("lulu_relational_initiative_v1", 0)
        assertTrue(JSONObject(prefs.getString(id, "{}")).getJSONObject("pending")
            .getString("failure").contains("网络请求失败"))
        CharacterRelationalInitiative.recordDecision(id, plan(), "message", true, "消息已经发送", now.plusSeconds(60))
        val stored = JSONObject(prefs.getString(id, "{}"))
        assertNull(stored.optJSONObject("pending"))
        assertEquals("message", stored.getJSONObject("lastDelivery").getString("action"))
        assertEquals("消息已经发送", stored.getJSONObject("lastDelivery").getString("summary"))
    }

    @Test fun deletionOfSourceRetractsOnlyItsDependentPrivatePlan() {
        val context = start()
        val prefs = context.getSharedPreferences("lulu_relational_initiative_v1", 0)
        val root = JSONObject()
            .put("pending", JSONObject().put("evidenceId", "deleted-preference")
                .put("aim", "暂时想试一个小惊喜"))
            .put("lastDelivery", JSONObject().put("action", "message")
                .put("summary", "曾经真实发送"))
        prefs.edit().putString(id, root.toString()).commit()
        CharacterRelationalInitiative.invalidateEvidence("deleted-preference")
        val after = JSONObject(prefs.getString(id, "{}"))
        assertNull(after.optJSONObject("pending"))
        assertEquals("message", after.getJSONObject("lastDelivery").getString("action"))
        CharacterRelationalInitiative.clear(id)
    }

    @Test fun canReleaseUndeliveredPlanAndRejectForgedPreferenceSource() {
        val context = start()
        val invalid = plan().put("evidenceId", "nonexistent-user-message")
        CharacterRelationalInitiative.recordDecision(id, invalid, "silent", false, "", now)
        assertFalse(CharacterRelationalInitiative.context(id, now).contains("尚未实行的主意"))
        CharacterRelationalInitiative.recordDecision(id, plan(), "silent", false, "", now)
        CharacterRelationalInitiative.recordDecision(id,
            JSONObject().put("status", "released"), "silent", false, "", now)
        val data = JSONObject(context.getSharedPreferences("lulu_relational_initiative_v1", 0)
            .getString(id, "{}"))
        assertNull(data.optJSONObject("pending"))
        CharacterRelationalInitiative.clear(id)
    }
}
