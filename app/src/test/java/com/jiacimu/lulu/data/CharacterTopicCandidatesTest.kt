package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * Batch 3: a private-chat "find a topic yourself" turn used to receive only
 * the single latest thought within 18h in compact context, so pending
 * thoughts almost never surfaced. Compact must now deliver up to 4
 * thoughts within 72h, with a pick-one instruction, and must still drop
 * thoughts older than 72h. The model still picks the topic (or none);
 * this test only checks delivery, never a topic choice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterTopicCandidatesTest {

    private fun seedThought(role: String, evidenceId: String, text: String, at: Instant) {
        CharacterInnerLifeStore.observe(
            role, evidenceId, "seed $evidenceId",
            JSONObject().put(
                "thoughts",
                JSONArray().put(
                    JSONObject().put("thought", text)
                        .put("impulse", "想聊聊")
                        .put("hesitation", "怕打扰"),
                ),
            ),
            now = at,
        )
    }

    @Test
    fun compactDeliversMultipleRecentThoughtsWithin72hAndDropsOlder() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        val role = "topic-test-a"
        CharacterInnerLifeStore.clear(role)
        val now = Instant.parse("2026-10-26T12:00:00Z")
        seedThought(role, "topic-1", "琢磨事一想和用户分享", now.minus(Duration.ofHours(1)))
        seedThought(role, "topic-2", "琢磨事二想和用户分享", now.minus(Duration.ofHours(20)))
        seedThought(role, "topic-3", "琢磨事三想和用户分享", now.minus(Duration.ofHours(50)))
        seedThought(role, "topic-old", "八十小时前的旧琢磨事", now.minus(Duration.ofHours(80)))

        val compact = CharacterInnerLifeStore.compactContext(role, now)
        assertTrue(compact.contains("琢磨事一想和用户分享"))
        assertTrue(compact.contains("琢磨事二想和用户分享"))
        assertTrue(compact.contains("琢磨事三想和用户分享"))
        assertFalse(compact.contains("八十小时前的旧琢磨事"))
        assertTrue(compact.contains("自己找话题时先从这里挑一个最想说的"))

        val full = CharacterInnerLifeStore.context(role, now)
        assertTrue(full.contains("琢磨事一想和用户分享"))
        assertTrue(full.contains("琢磨事二想和用户分享"))
        assertTrue(full.contains("琢磨事三想和用户分享"))
        assertFalse(full.contains("八十小时前的旧琢磨事"))
        assertTrue(full.contains("自己找话题时先从这里挑一个最想说的"))

        CharacterInnerLifeStore.clear(role)
    }
}
