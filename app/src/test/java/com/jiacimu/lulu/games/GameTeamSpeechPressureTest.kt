package com.jiacimu.lulu.games

import android.content.Context
import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.SharedExperienceTimeline
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GameTeamSpeechPressureTest {
    @Test fun hundredsOfEngineReceiptsDoNotEraseTheLastActualTeamConversation() {
        val app = RuntimeEnvironment.getApplication() as Context
        val role = "game-speech-pressure-" + UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val now = Instant.now().minusSeconds(400)
        assertTrue(GameTeamConversationTimeline.recordOwner(app, "relic_escape", run,
            listOf(role), "左边有怪，快来", true, "user-1", now))
        assertTrue(GameTeamConversationTimeline.recordCompanion(app, "relic_escape", run,
            listOf(role), role, "我去救你", "role-1", now.plusSeconds(1)))
        repeat(100) { step ->
            assertTrue(GameTeamConversationTimeline.recordEngineAcceptedAction(
                app, "relic_escape", run, role, step, "step-$step",
                "实际游戏执行成功", now.plusSeconds(step + 2L)))
        }
        // A plain 12-event slice now contains only actions; the speech query
        // must still read the persisted dialogue directly BEFORE SQL LIMIT.
        val allRecent = SharedExperienceTimeline.sessionEvents(
            role, "game-session-relic_escape-" + run, 12)
        assertTrue(allRecent.none { it.evidenceKind == EventEvidenceKind.UserStatement })
        val spoken = GameTeamConversationTimeline.recentLines(role, "relic_escape", run)
        assertEquals(2, spoken.size)
        assertTrue(spoken[0].contains("左边有怪，快来"))
        assertTrue(spoken[1].contains("我去救你"))
        assertFalse(spoken.any { it.contains("实际游戏执行成功") })
    }
}
