package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Relationship network section (batch 15): private chat used to deliver
 * only the focal character's own bonds. The network section adds how the
 * focal character's contacts relate to EACH OTHER, from stored facts
 * only (shared groups, real meetings, direct mail contact). No scores,
 * no private impressions of others, no names of groups the focal
 * character is not in, and unconnected pairs are skipped entirely so
 * the section stays silent instead of dumping "尚未认识" lines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterSocialNetworkTest {

    @Test
    fun pairLineSkipsUnconnectedPairs() {
        val unconnected = CharacterSocialRelationshipSnapshot(
            "a", "b", emptyList(), 0, null, "尚未认识", false)
        assertNull(CharacterSocialRelationship.networkPairLine("甲", "乙", unconnected))
    }

    @Test
    fun pairLineReportsConnectionFactsWithoutScoresOrGroupNames() {
        val met = CharacterSocialRelationshipSnapshot(
            "a", "b", listOf("共同群聊甲"), 3, Instant.parse("2026-10-20T10:00:00Z"),
            "共同群聊认识且真实见过面", true)
        val line = CharacterSocialRelationship.networkPairLine("甲", "乙", met)
        assertNotNull(line)
        assertTrue(line!!.contains("甲 ↔ 乙"))
        assertTrue(line.contains("真实见面3次"))
        assertTrue(line.contains("有真实私信来往"))
        assertFalse(line.contains("共同群聊甲"))
        assertFalse(line.contains("好感"))
    }

    @Test
    fun networkContextIsSilentWithoutConnections() {
        assertEquals("", CharacterSocialRelationship.networkContext("focal", emptyList()))
        assertEquals("", CharacterSocialRelationship.networkContext("focal", listOf("only-one")))
        // Fresh roles share no group, meeting or mail contact.
        assertEquals("", CharacterSocialRelationship.networkContext(
            "focal", listOf("contact-a", "contact-b")))
    }

    @Test
    fun compactContextStaysSilentWhenContactsAreUnconnected() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        val role = "network-quiet"
        CharacterInnerLifeStore.clear(role)
        // Seed two bonds the way the witness rule requires: the target
        // must be among the witnesses of the observed event.
        listOf("friend-a" to "群里接话很体贴", "friend-b" to "群里发言很积极").forEach { (target, view) ->
            val sentiment = JSONObject().put("social", JSONObject()
                .put("targetId", target).put("interpretation", view)
                .put("reason", "在共同群聊主动接了话"))
            CharacterInnerLifeStore.observe(role, "group-$target", "朋友在群里主动接了话",
                sentiment, setOf(target, "user"))
        }
        val compact = CharacterInnerLifeStore.compactContext(role)
        assertTrue(compact.contains("群里接话很体贴"))
        assertFalse(compact.contains("身边人的关系网"))
        CharacterInnerLifeStore.clear(role)
    }
}
