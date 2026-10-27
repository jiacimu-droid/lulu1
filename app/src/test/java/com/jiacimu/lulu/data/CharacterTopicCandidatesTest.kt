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
 * Batch 3 fix: the 72-hour limit on thoughts is removed, per the user's
 * correction — anything still unresolved may be brought up, however old.
 *
 * Reservoir basis, read from CharacterInnerLifeStore.observe(): a
 * thought stores thought/impulse/hesitation/evidenceId/at plus
 * status (held/resolved/discarded/evolved), mentions (a factual count
 * of how many times it came up), stimuli (triggering evidence ids) and
 * optional spokenAt/resolvedAt/lastSeenAt. Delivery therefore has a
 * system-level rule now: only still-held thoughts are delivered, newest
 * 6 in both compact and full, no time limit, age shown in days once
 * past a day. Resolved/discarded/evolved thoughts never appear again;
 * spoken thoughts stay held with their spoken time annotated. Explicit
 * deletion via forgetExactThought (or source-deletion invalidation)
 * still works as well. This test only checks delivery, never a topic choice.
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
    fun compactAndFullDeliverOldThoughtsWithNoTimeLimit() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        val role = "topic-fix-a"
        CharacterInnerLifeStore.clear(role)
        val now = Instant.parse("2026-10-26T12:00:00Z")
        seedThought(role, "topic-1", "琢磨事一想和用户分享", now.minus(Duration.ofHours(1)))
        seedThought(role, "topic-2", "琢磨事二想和用户分享", now.minus(Duration.ofHours(50)))
        // Previously excluded by the 72h wall; under the new standard it
        // must be delivered, because it carries no resolved marker.
        seedThought(role, "topic-old", "八十小时前的旧琢磨事", now.minus(Duration.ofHours(80)))
        seedThought(role, "topic-very-old", "三十天前的旧琢磨事", now.minus(Duration.ofDays(30)))
        // The only real exclusion for a thought: explicit deletion.
        seedThought(role, "topic-deleted", "已经删掉的琢磨事", now.minus(Duration.ofHours(2)))
        assertTrue(CharacterInnerLifeStore.forgetExactThought(role, "已经删掉的琢磨事"))

        val compact = CharacterInnerLifeStore.compactContext(role, now)
        assertTrue(compact.contains("琢磨事一想和用户分享"))
        assertTrue(compact.contains("琢磨事二想和用户分享"))
        assertTrue(compact.contains("八十小时前的旧琢磨事"))
        assertTrue(compact.contains("三十天前的旧琢磨事"))
        assertTrue(compact.contains("约30天前"))
        assertFalse(compact.contains("已经删掉的琢磨事"))
        assertTrue(compact.contains("没有时间限制"))

        val full = CharacterInnerLifeStore.context(role, now)
        assertTrue(full.contains("琢磨事一想和用户分享"))
        assertTrue(full.contains("琢磨事二想和用户分享"))
        assertTrue(full.contains("八十小时前的旧琢磨事"))
        assertTrue(full.contains("三十天前的旧琢磨事"))
        assertTrue(full.contains("约30天前"))
        assertFalse(full.contains("已经删掉的琢磨事"))
        assertTrue(full.contains("没有时间限制"))

        CharacterInnerLifeStore.clear(role)
    }

    @Test
    fun deliveryIsStillBoundedToNewestThoughts() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        val role = "topic-fix-cap"
        CharacterInnerLifeStore.clear(role)
        val now = Instant.parse("2026-10-26T12:00:00Z")
        // Seeded oldest-first; compact and full both take the newest 6
        // (topic unification: casual chat must not hide the 5th/6th thought).
        // Ages are deliberately mixed, including very old ones: age alone
        // must not exclude, and the cap — not age — is what bounds delivery.
        seedThought(role, "cap-1", "上限测试最旧的琢磨事", now.minus(Duration.ofDays(60)))
        seedThought(role, "cap-2", "上限测试第二旧的琢磨事", now.minus(Duration.ofDays(40)))
        seedThought(role, "cap-3", "上限测试第三个琢磨事", now.minus(Duration.ofDays(20)))
        seedThought(role, "cap-4", "上限测试第四个琢磨事", now.minus(Duration.ofDays(10)))
        seedThought(role, "cap-5", "上限测试第五个琢磨事", now.minus(Duration.ofDays(5)))
        seedThought(role, "cap-6", "上限测试第六个琢磨事", now.minus(Duration.ofDays(2)))
        seedThought(role, "cap-7", "上限测试最新的琢磨事", now.minus(Duration.ofHours(1)))

        val compact = CharacterInnerLifeStore.compactContext(role, now)
        assertFalse(compact.contains("上限测试最旧的琢磨事"))
        assertTrue(compact.contains("上限测试第二旧的琢磨事"))
        assertTrue(compact.contains("上限测试第三个琢磨事"))
        assertTrue(compact.contains("上限测试第四个琢磨事"))
        assertTrue(compact.contains("上限测试第五个琢磨事"))
        assertTrue(compact.contains("上限测试第六个琢磨事"))
        assertTrue(compact.contains("上限测试最新的琢磨事"))

        val full = CharacterInnerLifeStore.context(role, now)
        assertFalse(full.contains("上限测试最旧的琢磨事"))
        assertTrue(full.contains("上限测试第二旧的琢磨事"))
        assertTrue(full.contains("上限测试第三个琢磨事"))
        assertTrue(full.contains("上限测试第四个琢磨事"))
        assertTrue(full.contains("上限测试第五个琢磨事"))
        assertTrue(full.contains("上限测试第六个琢磨事"))
        assertTrue(full.contains("上限测试最新的琢磨事"))

        CharacterInnerLifeStore.clear(role)
    }

    @Test
    fun selfOrganizeSectionAppearsWhenThoughtsAreDelivered() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        val role = "self-organize-a"
        CharacterInnerLifeStore.clear(role)
        val now = Instant.parse("2026-10-26T12:00:00Z")
        seedThought(role, "org-1", "还没说出口的整理测试琢磨事", now.minus(Duration.ofHours(3)))

        val compact = CharacterInnerLifeStore.compactContext(role, now)
        assertTrue(compact.contains("【自我整理"))
        assertTrue(compact.contains("不自动变成待办或日程"))
        assertTrue(compact.contains("真实回执"))

        val full = CharacterInnerLifeStore.context(role, now)
        assertTrue(full.contains("【自我整理"))
        assertTrue(full.contains("不自动变成待办或日程"))
        assertTrue(full.contains("真实回执"))

        CharacterInnerLifeStore.clear(role)
    }

    private fun updateThought(
        role: String, evidenceId: String, text: String, op: String,
        newThought: String? = null, at: Instant,
    ) {
        val update = JSONObject().put("thought", text).put("op", op)
        if (newThought != null) update.put("newThought", newThought)
        CharacterInnerLifeStore.observe(
            role, evidenceId, "seed $evidenceId",
            JSONObject().put("thoughtUpdates", JSONArray().put(update)),
            now = at,
        )
    }

    @Test
    fun repeatedThoughtCountsMentionsInsteadOfDuplicating() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        val role = "reservoir-mentions"
        CharacterInnerLifeStore.clear(role)
        val now = Instant.parse("2026-10-26T12:00:00Z")
        seedThought(role, "men-1", "反复琢磨的旧事", now.minus(Duration.ofHours(5)))
        seedThought(role, "men-2", "反复琢磨的旧事", now.minus(Duration.ofHours(1)))
        seedThought(role, "men-3", "只出现一次的琢磨事", now.minus(Duration.ofHours(2)))

        val stored = CharacterInnerLifeStore.snapshot(role).optJSONArray("thoughts")!!
        assertTrue(stored.length() == 2)
        val repeated = (0 until stored.length()).mapNotNull(stored::optJSONObject)
            .first { it.optString("thought") == "反复琢磨的旧事" }
        assertTrue(repeated.optInt("mentions", 0) == 2)
        assertTrue(repeated.optJSONArray("stimuli")!!.length() == 2)
        // Original time is kept, so the age stays honest.
        assertTrue(repeated.optString("at") == now.minus(Duration.ofHours(5)).toString())

        val compact = CharacterInnerLifeStore.compactContext(role, now)
        assertTrue(compact.contains("反复琢磨的旧事"))
        assertTrue(compact.contains("被想起过2次"))
        // The repeated thought is delivered once, not once per mention.
        assertTrue(compact.split("反复琢磨的旧事").size - 1 == 1)

        CharacterInnerLifeStore.clear(role)
    }

    @Test
    fun resolvedDiscardedAndEvolvedThoughtsAreNotDelivered() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        val role = "reservoir-settled"
        CharacterInnerLifeStore.clear(role)
        val now = Instant.parse("2026-10-26T12:00:00Z")
        seedThought(role, "set-1", "已经想通的琢磨事", now.minus(Duration.ofHours(5)))
        seedThought(role, "set-2", "已经放下的琢磨事", now.minus(Duration.ofHours(4)))
        seedThought(role, "set-3", "已经变了的琢磨事", now.minus(Duration.ofHours(3)))
        seedThought(role, "set-4", "还悬着的琢磨事", now.minus(Duration.ofHours(2)))
        updateThought(role, "set-u1", "已经想通的琢磨事", "resolve", at = now)
        updateThought(role, "set-u2", "已经放下的琢磨事", "discard", at = now)
        updateThought(role, "set-u3", "已经变了的琢磨事", "evolve", newThought = "变了之后的新琢磨事", at = now)

        val compact = CharacterInnerLifeStore.compactContext(role, now)
        assertFalse(compact.contains("已经想通的琢磨事"))
        assertFalse(compact.contains("已经放下的琢磨事"))
        assertFalse(compact.contains("已经变了的琢磨事"))
        assertTrue(compact.contains("变了之后的新琢磨事"))
        assertTrue(compact.contains("还悬着的琢磨事"))

        val full = CharacterInnerLifeStore.context(role, now)
        assertFalse(full.contains("已经想通的琢磨事"))
        assertFalse(full.contains("已经放下的琢磨事"))
        assertFalse(full.contains("已经变了的琢磨事"))
        assertTrue(full.contains("变了之后的新琢磨事"))
        assertTrue(full.contains("还悬着的琢磨事"))

        CharacterInnerLifeStore.clear(role)
    }

    @Test
    fun spokenThoughtStaysHeldWithSpokenTime() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        val role = "reservoir-spoken"
        CharacterInnerLifeStore.clear(role)
        val now = Instant.parse("2026-10-26T12:00:00Z")
        seedThought(role, "spk-1", "已经说过的琢磨事", now.minus(Duration.ofHours(5)))
        updateThought(role, "spk-u1", "已经说过的琢磨事", "spoken", at = now)

        val compact = CharacterInnerLifeStore.compactContext(role, now)
        assertTrue(compact.contains("已经说过的琢磨事"))
        assertTrue(compact.contains("上次说过10月26日 20:00"))

        CharacterInnerLifeStore.clear(role)
    }

    @Test
    fun selfOrganizeSectionIsAbsentWhenNothingIsDelivered() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        val role = "self-organize-empty"
        CharacterInnerLifeStore.clear(role)
        val now = Instant.parse("2026-10-26T12:00:00Z")

        // No motives, no open concerns, no thoughts: the section must not
        // invent a tidy-up prompt out of an empty inner life.
        assertFalse(CharacterInnerLifeStore.compactContext(role, now).contains("【自我整理"))
        assertFalse(CharacterInnerLifeStore.context(role, now).contains("【自我整理"))

        CharacterInnerLifeStore.clear(role)
    }
}
