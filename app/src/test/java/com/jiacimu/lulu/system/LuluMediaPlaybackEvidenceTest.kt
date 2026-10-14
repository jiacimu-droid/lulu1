package com.jiacimu.lulu.system

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LuluMediaPlaybackEvidenceTest {
    @Test fun missingSessionOrNotificationAccessMustNeverBeReportedAsCertainSilence() {
        val context = RuntimeEnvironment.getApplication() as Context
        // A missing notification-listener grant, an unsupported music app, or
        // an inactive MediaSession cannot prove the phone has stopped producing audio.
        val observation = LuluMediaPlayback.observation(context)
        assertTrue(
            "Missing media evidence is not proof the user has no music on: $observation",
            observation.contains("不能断言") ||
                observation.contains("不可断言") ||
                observation.contains("已发现正在播放的媒体会话"),
        )
    }
}
