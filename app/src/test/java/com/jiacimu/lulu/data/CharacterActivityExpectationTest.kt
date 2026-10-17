package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterActivityExpectationTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")

    @Test fun theOriginalHopeSurvivesRetryAndAnUnmetGoalIsNotAnExecutionFailure() {
        val before = JSONObject().put("wanted", "这次想超过900分").put("why", "上次差一点，自己有点不服气")
        val stored = CharacterActivityExpectation.begin(null, "game-1", "play_solo_game", before, now)!!
        assertTrue(CharacterActivityExpectation.context(stored, now).contains("尚无结果"))
        assertTrue(CharacterActivityExpectation.resolve(stored, "game-1", "real-game-1", true,
            "实际得到840分，没有达到900分", now.plusSeconds(4)))
        assertNull(CharacterActivityExpectation.begin(stored, "game-1", "play_solo_game",
            before.put("wanted", "本来只想随便玩玩"), now.plusSeconds(5)))
        assertFalse(CharacterActivityExpectation.resolve(stored, "game-1", "invented-result", false,
            "模型想改成输了", now.plusSeconds(6)))
        val afterReload = org.json.JSONArray(stored.toString())
        val entry = afterReload.getJSONObject(0)
        assertEquals("这次想超过900分", entry.getString("wanted"))
        assertTrue(entry.getBoolean("success"))
        assertFalse(entry.has("emotion"))
        assertTrue(CharacterActivityExpectation.context(afterReload, now.plusSeconds(8)).contains("840分"))
    }

    @Test fun missingProofDoesNotProduceAnOutcomeAndOldEventsDoNotBecomeFreshFeelings() {
        val records = CharacterActivityExpectation.begin(null, "read", "read_book", JSONObject()
            .put("wanted", "想知道人物是否和好").put("why", "在意这段关系"), now)!!
        assertFalse(CharacterActivityExpectation.resolve(records, "read", "", true, "读完了", now))
        assertNull(CharacterActivityExpectation.begin(records, "other", "send_private_message",
            JSONObject().put("wanted", "收到回复").put("why", "想她了"), now))
        assertEquals("", CharacterActivityExpectation.context(records, now.plusSeconds(4 * 86400)))
    }
}
