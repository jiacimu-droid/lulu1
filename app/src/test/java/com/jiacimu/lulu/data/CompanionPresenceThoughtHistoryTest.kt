package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CompanionPresenceThoughtHistoryTest {
    private val role = "thought-polarity-" + java.util.UUID.randomUUID()
    private val now = Instant.parse("2026-11-04T04:00:00Z")

    @Before fun before() {
        MemoryTestEnvironment.release()
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CompanionPresenceStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        CharacterInnerLifeStore.clear(role)
        CompanionPresenceStore.clearCharacter(role)
    }

    @After fun after() {
        CharacterInnerLifeStore.clear(role)
        CompanionPresenceStore.clearCharacter(role)
        MemoryTestEnvironment.release()
    }

    private fun update(text: String, cause: String, offset: Long) {
        CompanionPresenceStore.update(role, statusText = null, gesture = null,
            innerThought = text, mood = "在意", source = "聊天",
            now = now.plusSeconds(offset), innerThoughtFingerprint = cause,
            momentReflection = text)
    }

    @Test fun changedCauseAndNegationSurviveHistoryAndTimelineWhileExactRepeatDoesNot() {
        val positive = "我想继续和她聊下去。"
        val negative = "我不想继续和她聊下去。"
        update(positive, "first-cause", 0)
        update(negative, "changed-cause", 1)

        val changed = CompanionPresenceStore.histories.value[role].orEmpty().first()
        assertEquals(negative, changed.innerThought)
        assertEquals(negative, changed.momentReflection)
        assertTrue(changed.showInHistory)
        assertTrue(SharedExperienceTimeline.all(role).any {
            it.source == "presence" && it.content.contains("心声：" + negative)
        })

        // A new cause alone still cannot create a second identical sentence.
        update(negative, "third-cause", 2)
        assertEquals(1, CompanionPresenceStore.histories.value[role].orEmpty()
            .count { it.innerThought == negative })
    }

    @Test fun addingAConditionToTheSameSentenceIsNotAnIdenticalThought() {
        val original = "我想等她来找我。"
        val qualified = original + "但我不能一直只等着，也可以先联系她。"
        update(original, "wait", 0)
        update(qualified, "reconsider-waiting", 1)

        val changed = CompanionPresenceStore.histories.value[role].orEmpty().first()
        assertEquals(qualified, changed.innerThought)
        assertEquals(qualified, changed.momentReflection)
        assertTrue(SharedExperienceTimeline.all(role).any {
            it.source == "presence" && it.content.contains(qualified)
        })
    }
    @Test fun changedResponseAimSurvivesSameCauseFingerprintIntoBothContinuityStores() {
        val positive = "我想继续和她聊下去。"
        val negative = "我不想继续和她聊下去。"
        val basis = JSONObject().put("focus", "刚才的谈话")
            .put("unsaidWhy", "这是我还没有说出口的意愿")
        val firstAppraisal = JSONObject().put("meaning", "我在考虑这次谈话")
            .put("responseAim", "继续交流")
        val nextAppraisal = JSONObject(firstAppraisal.toString())
            .put("responseAim", "结束这次交流")
        val first = PrivateStateDeltaEngine.evaluate(JSONObject(), null,
            firstAppraisal, basis, positive)
        val previous = JSONObject().put("causalTransitions", JSONArray().put(
            JSONObject().put("appraisal", firstAppraisal).put("innerThoughtBasis", basis)))
        val next = PrivateStateDeltaEngine.evaluate(previous, null,
            nextAppraisal, basis, negative)
        assertTrue(next.appraisalChanged)
        assertTrue(next.meaningful && next.privateResidue)
        // A fingerprint is a lossy cause projection, not a thought/event ID.
        // This is the actual delta engine, rather than two invented hashes.
        assertEquals(first.fingerprint, next.fingerprint)

        listOf(positive to first, negative to next).forEachIndexed { index, (text, delta) ->
            val grounded = CharacterHeartVoicePolicy.keepOrBlank(text,
                basis = basis, delta = delta, hasFreshEvidence = true)
            assertEquals(text, grounded)
            CharacterInnerLifeStore.recordCausalTransition(role, "source-$index",
                if (index == 0) firstAppraisal else nextAppraisal, null, basis,
                "silent", grounded, "本人选择先不说出口", now.plusSeconds(index.toLong()))
            CharacterInnerLifeStore.recordInnerVoice(role, "source-$index",
                grounded, now.plusSeconds(index.toLong()), delta.fingerprint)
            update(grounded, delta.fingerprint, index.toLong())
        }
        assertEquals(2, CharacterInnerLifeStore.snapshot(role)
            .getJSONArray("innerVoices").length())
        assertEquals(negative, CompanionPresenceStore.histories.value[role]
            .orEmpty().first().innerThought)
        listOf(CharacterInnerLifeStore.context(role, now.plusSeconds(2)),
            CharacterInnerLifeStore.compactContext(role, now.plusSeconds(2))).forEach {
            assertTrue(it.contains(negative))
        }
        assertTrue(SharedExperienceTimeline.all(role).any {
            it.source == "presence" && it.content.contains("心声：" + negative)
        })
        assertFalse(CharacterInnerLifeStore.context(role + "-other",
            now.plusSeconds(2)).contains(negative))

        // Replaying a source or the same sentence still does not create a
        // second voice. Withdrawing one source leaves the other voice intact.
        CharacterInnerLifeStore.recordInnerVoice(role, "source-1",
            "迟到的重复响应", now.plusSeconds(3), next.fingerprint)
        CharacterInnerLifeStore.recordInnerVoice(role, "source-exact-repeat",
            negative, now.plusSeconds(4), "another-cause")
        assertEquals(2, CharacterInnerLifeStore.snapshot(role)
            .getJSONArray("innerVoices").length())
        CharacterInnerLifeStore.invalidateEvidence("source-0")
        val remaining = CharacterInnerLifeStore.snapshot(role).getJSONArray("innerVoices")
        assertEquals(1, remaining.length())
        assertEquals(negative, remaining.getJSONObject(0).getString("thought"))
    }

}
