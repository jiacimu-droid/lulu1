package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CallMediaSemanticBoundaryTest {
    @Test fun sameWordsAreNotAProgrammedMediaCommand() {
        val understanding = JSONObject().put("music", "unchanged")
            .put("wake", "unconfirmed")
            .put("reason", "她只是提到以前音乐自己重播的故障")
        assertEquals("unchanged", CallMediaSemanticBoundary.musicChoice(understanding, true))
        assertFalse(CallMediaSemanticBoundary.confirmedAwake(understanding, true))
    }

    @Test fun modelCanInterpretContextualStopOrNewPlayWithoutKeywordRules() {
        val decision = JSONObject().put("music", "stop")
        assertEquals("stop", CallMediaSemanticBoundary.musicChoice(decision, true))
        decision.put("music", "play")
        assertEquals("play", CallMediaSemanticBoundary.musicChoice(decision, true))
        assertEquals("unchanged", CallMediaSemanticBoundary.musicChoice(decision, false))
    }

    @Test fun oldOrSilentPerceptionCannotConfirmWakingOrReauthorizeMusic() {
        val decision = JSONObject().put("music", "play").put("wake", "confirmed")
        assertFalse(CallMediaSemanticBoundary.confirmedAwake(decision, false))
        assertEquals("unchanged", CallMediaSemanticBoundary.musicChoice(decision, false))
        assertTrue(CallMediaSemanticBoundary.confirmedAwake(decision, true))
        assertFalse(CallMediaSemanticBoundary.confirmedAwake(
            JSONObject().put("wake", "unconfirmed"), true))
    }

    @Test fun absentOrUnexpectedValuesHaveNoExternalEffect() {
        assertEquals("unchanged", CallMediaSemanticBoundary.musicChoice(null, true))
        assertEquals("unchanged", CallMediaSemanticBoundary.musicChoice(
            JSONObject().put("music", "music_was_played_last_week"), true))
        assertFalse(CallMediaSemanticBoundary.confirmedAwake(null, true))
    }
}
