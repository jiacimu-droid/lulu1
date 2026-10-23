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
            // Different touches in one gesture burst must keep their own
            // parts, instead of falsely reporting every touch on the head.
            val mixed = InteractionSignalBridge.recordAvatarTouches(
                role, call, listOf("head" to "stroke", "body" to "tap",
                    "head" to "stroke"))
            assertNotNull(mixed)
            try {
                val row = SharedExperienceTimeline.eventsByIds(role, listOf(mixed!!)).single()
                assertTrue(row.content.contains("头部"))
                assertTrue(row.content.contains("身体"))
                assertTrue(row.content.contains("共2次"))
                assertFalse(row.content.contains("身体，共3次"))
            } finally {
                mixed?.let(SharedExperienceTimeline::deleteEvent)
            }
            val withForces = InteractionSignalBridge.recordAvatarTouches(role, call,
                listOf("throat" to "kiss", "throat" to "kiss",
                    "beast-ear-right" to "stroke"), forces = listOf(.25f, .9f, .55f),
                durationsMillis = listOf(200L, 400L, 12_500L))
            assertNotNull(withForces)
            try {
                val row = SharedExperienceTimeline.eventsByIds(role, listOf(withForces!!)).single()
                assertTrue(row.content.contains("喉结（力度轻）"))
                assertTrue(row.content.contains("喉结（力度重）"))
                assertTrue(row.content.contains("右兽耳（力度中）"))
                assertTrue(row.content.contains("手势总时长12.5秒"))
                assertFalse(row.content.contains("共2次"))
            } finally {
                withForces?.let(SharedExperienceTimeline::deleteEvent)
            }
            assertNull(InteractionSignalBridge.recordAvatarTouches(role, call,
                listOf("throat" to "kiss"), forces = listOf(.25f, .9f)))
            assertNull(InteractionSignalBridge.recordAvatarTouches(role, call,
                listOf("cheek-left" to "kick")))
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
