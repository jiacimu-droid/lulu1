package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DigitalWorldResidentEpisodeStoreTest {
    private val context get() = RuntimeEnvironment.getApplication() as android.content.Context
    private val role = "episode-test-role"
    private val now = Instant.parse("2026-10-16T12:00:00Z")

    @Before fun initialize() {
        SharedExperienceTimeline.initialize(context)
        DigitalWorldResidentEpisodeStore.initialize(context)
        DigitalWorldResidentEpisodeStore.clearCharacter(role)
    }

    private fun proof(id: String) {
        SharedExperienceTimeline.record(id, role, "数字世界见面", "居民", "实际保存的双方原话", now,
            triggerExtraction = false, source = "meeting", evidenceKind = EventEvidenceKind.ToolResult)
    }

    private fun turns(text: String, reply: String) = listOf(
        MeetingTurn("mine", role, "本人", "", text, now),
        MeetingTurn("theirs", "resident:npc", "居民", "", reply, now.plusMillis(1)))

    private fun result(choice: String, text: String, index: Int = 1) = JSONObject().put("episode", JSONObject()
        .put("response", choice).put("residentTurnIndex", index).put("resultText", text))

    @Test fun aSavedInvitationSurvivesRestartAndOnlyRealLaterWordsCanAdvanceIt() {
        val step = DigitalWorldResidentEpisodeStore.prepare(role, "npc", "share_text",
            JSONObject().put("text", "风从窗缝递进来一封信。"), now)
        val spoken = turns(step.contribution, "我想再想想这句，十分钟后我们再聊。")
        val actual = DigitalWorldResidentEpisodeStore.response(step,
            result("waiting", "十分钟后我们再聊"), spoken)
        assertTrue(runCatching { DigitalWorldResidentEpisodeStore.commit(step, actual, "missing-receipt", now) }.isFailure)
        proof("episode-open")
        val saved = DigitalWorldResidentEpisodeStore.commit(step, actual, "episode-open", now)
        // A retry of the same receipt cannot grow another round.
        assertEquals(1, DigitalWorldResidentEpisodeStore.commit(step, actual, "episode-open", now).getInt("rounds"))
        DigitalWorldResidentEpisodeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(DigitalWorldResidentEpisodeStore, null)
        DigitalWorldResidentEpisodeStore.initialize(context)
        val args = JSONObject().put("incidentId", saved.getString("id"))
        assertTrue(runCatching { DigitalWorldResidentEpisodeStore.prepare(role, "npc", "continue_episode", args,
            now.plusSeconds(20)) }.isFailure)
        assertTrue(runCatching { DigitalWorldResidentEpisodeStore.prepare("someone-else", "npc", "continue_episode", args,
            now.plusSeconds(700)) }.isFailure)
        assertTrue(runCatching { DigitalWorldResidentEpisodeStore.prepare(role, "other-npc", "continue_episode", args,
            now.plusSeconds(700)) }.isFailure)
        val next = DigitalWorldResidentEpisodeStore.prepare(role, "npc", "continue_episode", args, now.plusSeconds(700))
        val critique = "信的意象我喜欢，不过后半句有点满，想留一点空。"
        val second = DigitalWorldResidentEpisodeStore.response(next, result("revision", "后半句有点满"),
            turns("你怎么看？", critique))
        proof("episode-revision")
        DigitalWorldResidentEpisodeStore.commit(next, second, "episode-revision", now.plusSeconds(700))
        assertTrue(DigitalWorldResidentEpisodeStore.contextFor(role, now.plusSeconds(710)).contains(critique))
        assertTrue(runCatching { DigitalWorldResidentEpisodeStore.prepare(role, "npc", "continue_episode", args,
            now.plusSeconds(1400)) }.isFailure) // No genuine revised text supplied.
        SharedExperienceTimeline.deleteEvent("episode-open")
        assertEquals("", DigitalWorldResidentEpisodeStore.contextFor(role, now.plusSeconds(1400)))
    }

    @Test fun aSynopsisOrRolesOwnPraiseCannotStandInForTheResidentsResponse() {
        val step = DigitalWorldResidentEpisodeStore.prepare(role, "npc", "co_write",
            JSONObject().put("text", "夜色收起最后一把伞。"), now)
        val spoken = turns(step.contribution, "好，十分钟后再接下去。")
        assertTrue(runCatching { DigitalWorldResidentEpisodeStore.response(step,
            result("waiting", "他非常喜欢我的作品"), spoken) }.isFailure)
        assertTrue(runCatching { DigitalWorldResidentEpisodeStore.response(step,
            result("waiting", step.contribution, index = 0), spoken) }.isFailure)
        assertTrue(runCatching { DigitalWorldResidentEpisodeStore.response(step,
            result("waiting", "十分钟后"), turns("我写了一个上句", spoken[1].dialogue)) }.isFailure)
        proof("episode-decline")
        val declined = DigitalWorldResidentEpisodeStore.response(step,
            result("declined", "今天想自己待会儿"), turns(step.contribution, "谢谢，不过今天想自己待会儿。"))
        val stored = DigitalWorldResidentEpisodeStore.commit(step, declined, "episode-decline", now)
        assertEquals("declined", stored.getString("status"))
        assertFalse(stored.has("emotion"))
        assertTrue(runCatching { DigitalWorldResidentEpisodeStore.prepare(role, "npc", "continue_episode",
            JSONObject().put("incidentId", stored.getString("id")), now.plusSeconds(700)) }.isFailure)
    }
}
