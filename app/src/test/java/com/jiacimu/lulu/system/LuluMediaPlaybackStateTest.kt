package com.jiacimu.lulu.system

import android.media.session.PlaybackState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LuluMediaPlaybackStateTest {
    @Test fun pausingAlreadyPausedMusicMustNotDispatchToggle() {
        assertTrue(LuluMediaPlayback.alreadyAtTarget("pause", PlaybackState.STATE_PAUSED))
        assertTrue(LuluMediaPlayback.alreadyAtTarget("pause", PlaybackState.STATE_STOPPED))
        assertTrue(LuluMediaPlayback.alreadyAtTarget("pause", PlaybackState.STATE_NONE))
        assertFalse(LuluMediaPlayback.alreadyAtTarget("play", PlaybackState.STATE_PAUSED))
    }

    @Test fun playingAlreadyPlayingMusicMustNotDispatchToggle() {
        assertTrue(LuluMediaPlayback.alreadyAtTarget("play", PlaybackState.STATE_PLAYING))
        assertFalse(LuluMediaPlayback.alreadyAtTarget("pause", PlaybackState.STATE_PLAYING))
        assertFalse(LuluMediaPlayback.alreadyAtTarget("next", PlaybackState.STATE_PLAYING))
    }
}
