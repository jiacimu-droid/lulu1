package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import java.time.Instant
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
        CompanionPresenceStore.clearCharacter(role)
    }

    @After fun after() {
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
}
