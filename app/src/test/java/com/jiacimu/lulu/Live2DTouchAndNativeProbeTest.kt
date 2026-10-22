package com.jiacimu.lulu

import com.jiacimu.lulu.data.EventEvidenceKind
import com.jiacimu.lulu.data.InteractionSignalBridge
import com.jiacimu.lulu.data.SharedExperienceTimeline
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Live2DTouchAndNativeProbeTest {
    @Test fun onlyRenderedAvatarCanRecordARealContact() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(app)
        val role = "touch-role-" + UUID.randomUUID()
        val call = "touch-call-" + UUID.randomUUID()
        val renderedId = "interaction-call-video-rendered-$call"
        assertNull(InteractionSignalBridge.recordAvatarTouch(role, call, "head", "stroke", 1))
        assertNull(InteractionSignalBridge.avatarContactDescription("background", "tap", 1))
        assertNull(InteractionSignalBridge.avatarContactDescription("head", "unknown", 1))
        InteractionSignalBridge.recordVideoRendered(role, call)
        var recordedId: String? = null
        try {
            recordedId = InteractionSignalBridge.recordAvatarTouch(role, call, "head", "stroke", 3)
            assertNotNull(recordedId)
            val evidence = SharedExperienceTimeline.eventsByIds(role, listOf(recordedId!!)).single()
            assertEquals(EventEvidenceKind.Observation, evidence.evidenceKind)
            assertEquals(InteractionSignalBridge.SOURCE, evidence.source)
            assertEquals(call, evidence.sessionId)
            assertTrue(evidence.content.contains("头部"))
            assertTrue(evidence.content.contains("3次"))
            assertTrue(evidence.content.contains("不替角色推断情绪"))
        } finally {
            recordedId?.let(SharedExperienceTimeline::deleteEvent)
            SharedExperienceTimeline.deleteEvent(renderedId)
        }
    }

    @Test fun nativeProbeIsHonestInAnApkWithoutProprietaryCore() {
        if (!NativeCubismProbe.nativeCoreLinked) {
            val result = NativeCubismProbe.inspect(byteArrayOf(1, 2, 3))
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Native Core"))
        }
    }
}
