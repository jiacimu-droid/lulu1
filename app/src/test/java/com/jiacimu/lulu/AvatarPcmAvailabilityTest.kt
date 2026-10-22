package com.jiacimu.lulu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AvatarPcmAvailabilityTest {
    @Test fun zeroPcmLevelIsStillARealSampleNotAMissingSignal() {
        val role = "pcm-silence-test"
        AvatarController.playback(role, true)
        assertFalse(AvatarController.states.value[role]?.hasPcmSamples == true)
        AvatarController.audio(role, 0f)
        assertTrue(AvatarController.states.value[role]?.hasPcmSamples == true)
        AvatarController.listening(role, true)
        assertTrue(AvatarController.states.value[role]?.hasPcmSamples == true)
        AvatarController.playback(role, false)
        assertFalse(AvatarController.states.value[role]?.hasPcmSamples == true)
    }
}
