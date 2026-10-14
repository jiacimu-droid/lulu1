package com.jiacimu.lulu.system

import android.media.session.PlaybackState
import org.junit.Assert.*
import org.junit.Test

class LuluMediaTransportPolicyTest {
    private fun state(actions: Long, state: Int = PlaybackState.STATE_PLAYING): PlaybackState =
        PlaybackState.Builder()
            .setActions(actions)
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build()

    @Test fun toggleOnlyPlayerIsStillEligibleForPlayAndPause() {
        val session = state(PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_PLAY_PAUSE)
        assertTrue(LuluMediaPlayback.supports("pause", session))
        assertTrue(LuluMediaPlayback.supports("play", session))
        assertTrue(LuluMediaPlayback.supports("next", session))
    }

    @Test fun transportAllowsIndependentPlayAndPauseWithoutToggle() {
        val playing = state(PlaybackState.ACTION_PAUSE, PlaybackState.STATE_PLAYING)
        val paused = state(PlaybackState.ACTION_PLAY, PlaybackState.STATE_PAUSED)
        assertTrue(LuluMediaPlayback.supports("pause", playing))
        assertTrue(LuluMediaPlayback.supports("play", paused))
        assertFalse(LuluMediaPlayback.supports("play", playing))
    }

    @Test fun userWordsMapToDistinctSafeCommands() {
        assertEquals("pause", LuluMediaPlayback.requestedAction("暂停", true))
        assertEquals("pause", LuluMediaPlayback.requestedAction("stop", true))
        assertEquals("play", LuluMediaPlayback.requestedAction("继续播放", false))
        assertEquals("pause", LuluMediaPlayback.requestedAction("toggle", true))
        assertEquals("play", LuluMediaPlayback.requestedAction("toggle", false))
        assertEquals("next", LuluMediaPlayback.requestedAction("下一首", true))
    }
}
