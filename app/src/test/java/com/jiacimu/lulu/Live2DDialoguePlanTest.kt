package com.jiacimu.lulu

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Live2DDialoguePlanTest {
    private fun envelope() = JSONObject("""{"action":"reply","speech":[
        {"id":"s1","text":"[warmly]你好呀！","motion":{"group":"Gesture","index":0}},
        {"id":"s2","text":"[warmly]你好呀！","motion":{"group":"Gesture","index":0},"transition":"continue"},
        {"id":"s3","text":"我在听呢。","motion":{"group":"Gesture","index":1}}]}""")
    private val motions = listOf("Gesture" to 0, "Gesture" to 1)

    @Test fun pairedWordsNeedNoAnchorsEvenWithRepeatedSentenceAndVoiceTags() = runBlocking {
        val envelope = envelope()
        val text = Live2DDialoguePlan.spokenText(envelope)!!
        assertEquals("[warmly]你好呀！[warmly]你好呀！我在听呢。", text)
        val visual = Live2DDialoguePlan.visual(envelope)!!
        val resolved = Live2DSpokenMotionContract.resolve(text, visual, motions) { _, _ ->
            fail("Valid paired reply must not purchase another model turn"); null
        }
        assertTrue(resolved.errors.isEmpty())
        val parts = Live2DDialoguePlan.queuedParts(Live2DDialoguePlan.parts(visual)!!, text)!!
        assertEquals(listOf("s1", "s2", "s3"), parts.map { it.id })
        assertEquals("continue", parts[1].transition)
        assertNull(Live2DDialoguePlan.queuedParts(parts, "改写后的另一句话"))
    }

    @Test fun longSpeechPartCarriesMultipleChosenFollowUpMotions() {
        val visual = envelope().apply {
            getJSONArray("speech").getJSONObject(0).put("followUps", org.json.JSONArray()
                .put(JSONObject().put("motion", JSONObject().put("group", "Gesture").put("index", 1)))
                .put(JSONObject().put("motion", JSONObject().put("group", "Gesture").put("index", 0))
                    .put("transition", "continue")))
        }
        val text = Live2DDialoguePlan.spokenText(visual)!!
        val parts = Live2DDialoguePlan.parts(visual)!!
        assertEquals(2, parts[0].followUps.size)
        assertEquals("Gesture" to 1, parts[0].followUps[0].motion)
        assertEquals("continue", parts[0].followUps[1].transition)
        assertTrue(Live2DDialoguePlan.errors(text, visual, motions).isEmpty())
        val compiled = Live2DDialoguePlan.beats(parts).getJSONObject(0)
        assertEquals(2, compiled.getJSONArray("followUps").length())
        assertEquals("s1", compiled.getString("segmentId"))
        val invented = JSONObject(visual.toString())
        invented.getJSONArray("speech").getJSONObject(0).getJSONArray("followUps")
            .getJSONObject(0).getJSONObject("motion").put("index", 99)
        assertTrue(Live2DDialoguePlan.errors(text, invented, motions).any { it.contains("后续动作1") })
        val missing = JSONObject(visual.toString())
        missing.getJSONArray("speech").getJSONObject(0).getJSONArray("followUps")
            .getJSONObject(0).remove("motion")
        assertNull(Live2DDialoguePlan.parts(missing))
        val invalidTransition = JSONObject(visual.toString())
        invalidTransition.getJSONArray("speech").getJSONObject(0).getJSONArray("followUps")
            .getJSONObject(0).put("transition", "loop")
        assertNull(Live2DDialoguePlan.parts(invalidTransition))
    }

    @Test fun duplicateIdsMissingTextAndInventedMotionCannotBeSilentlyCompiled() {
        val duplicate = Live2DDialoguePlan.visual(envelope())!!
        duplicate.getJSONArray("speech").getJSONObject(1).put("id", "s1")
        assertNull(Live2DDialoguePlan.parts(duplicate))
        val missing = Live2DDialoguePlan.visual(envelope())!!
        missing.getJSONArray("speech").getJSONObject(1).remove("text")
        assertNull(Live2DDialoguePlan.parts(missing))
        val invalid = Live2DDialoguePlan.visual(envelope())!!
        invalid.getJSONArray("speech").getJSONObject(2).getJSONObject("motion").put("index", 8)
        assertEquals(1, Live2DDialoguePlan.errors(Live2DDialoguePlan.spokenText(envelope())!!, invalid, motions).size)
    }

    @Test fun changedSpeechDoesNotReceiveAnOldPerformanceAfterFailedCorrection() = runBlocking {
        val visual = Live2DDialoguePlan.visual(envelope())!!
        val result = Live2DSpokenMotionContract.resolve("实际已经改口。", visual, motions) { _, _ -> visual }
        assertFalse(result.repaired)
        assertNull(result.chosen)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test fun realModelBusCarriesSamePartIdentityAndMotionFileFactsToAudioQueue() {
        val context = RuntimeEnvironment.getApplication()
        val role = "paired-" + UUID.randomUUID()
        val call = "call-" + UUID.randomUUID()
        val relative = "models/$role/avatar.model3.json"
        val settings = File(Live2DAssets.base(context), relative)
        settings.parentFile!!.mkdirs()
        settings.writeText("""{"FileReferences":{"Moc":"avatar.moc3","Textures":[],"Motions":{"Gesture":[{"File":"a.motion3.json"},{"File":"b.motion3.json"}]}}}""")
        for (name in listOf("a", "b")) File(settings.parentFile, "$name.motion3.json").writeText("""
            {"Version":3,"Meta":{"Duration":2.5,"FadeInTime":0.25},"Curves":[
              {"Target":"Parameter","Id":"ParamAngleX","Segments":[0,0]},
              {"Target":"Model","Id":"Opacity","Segments":[0,1]}]}
        """.trimIndent())
        val prefs = context.getSharedPreferences("lulu_live2d_models_v1", 0)
        prefs.edit().putString("model:$role", relative).commit()
        try {
            Live2DVisualActionBus.stageReady(role, call)
            val authored = envelope().apply {
                getJSONArray("speech").getJSONObject(0).put("followUps",
                    org.json.JSONArray().put(JSONObject().put("motion",
                        JSONObject().put("group", "Gesture").put("index", 1))))
            }
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                Live2DDialoguePlan.visual(authored)))
            val cue = Live2DVisualActionBus.cues.value.getValue(role)
            assertEquals(1, cue.beats[0].followUps.size)
            assertEquals("Gesture" to 1, cue.beats[0].followUps[0].motion)
            assertEquals(2500, cue.beats[0].followUps[0].metadata?.durationMs)
            // The role cannot claim the visual cue was performed before Cubism reports it.
            assertFalse(Live2DVisualActionBus.capabilityNote(context, role, call)
                .contains("真实身体执行回执"))
            fun event(sequence: Long, index: Int, segmentId: String, source: String = "character") =
                JSONObject().put("sequence", sequence).put("group", "Gesture")
                    .put("index", index).put("segmentId", segmentId)
                    .put("source", source).toString()
            assertFalse(Live2DVisualActionBus.recordMotionOutcome(role, call,
                "motion-started", event(cue.sequence - 1, 0, "s1")))
            assertFalse(Live2DVisualActionBus.recordMotionOutcome(role, call,
                "motion-started", event(cue.sequence, 0, "s1", "manual")))
            assertFalse(Live2DVisualActionBus.recordMotionOutcome(role, "other-call",
                "motion-started", event(cue.sequence, 0, "s1")))
            assertFalse(Live2DVisualActionBus.recordMotionOutcome(role, call,
                "motion-started", event(cue.sequence, 0, "s3")))
            assertTrue(Live2DVisualActionBus.recordMotionOutcome(role, call,
                "motion-started", event(cue.sequence, 0, "s1")))
            assertTrue(Live2DVisualActionBus.recordMotionOutcome(role, call,
                "motion-started", event(cue.sequence, 1, "s1")))
            assertTrue(Live2DVisualActionBus.capabilityNote(context, role, call)
                .contains("（Gesture#0）：已启动"))
            assertTrue(Live2DVisualActionBus.recordMotionOutcome(role, call,
                "motion-failed", event(cue.sequence, 1, "s3")))
            assertTrue(Live2DVisualActionBus.capabilityNote(context, role, call)
                .contains("（Gesture#1）：被播放器拒绝/失败"))
            val parts = Live2DDialoguePlan.queuedParts(cue.speechParts, Live2DDialoguePlan.spokenText(envelope())!!)!!
            assertEquals(listOf("s1", "s2", "s3"), cue.beats.map { it.segmentId })
            assertEquals(listOf(0, 1, 2), cue.beats.map { it.segmentIndex })
            assertTrue(cue.beats.all { it.anchor == null })
            assertEquals(2500, cue.beats[0].motionMetadata?.durationMs)
            assertEquals(250, cue.beats[0].motionMetadata?.fadeInMs)
            assertEquals(listOf("ParamAngleX"), cue.beats[0].motionMetadata?.parameterIds)
            parts.forEachIndexed { index, part ->
                Live2DSpeechTimeline.started(role, call, "u$index", index, cue.sequence, part.id)
                assertEquals(cue.beats[index].segmentId, Live2DSpeechTimeline.events.value.segmentId)
                Live2DSpeechTimeline.progressed(role, call, "u$index", 100)
                assertEquals(part.id, Live2DSpeechTimeline.position.value.segmentId)
                assertEquals(100L, Live2DSpeechTimeline.position.value.elapsedMs)
                Live2DSpeechTimeline.finished(role, call, "u$index")
            }
            // Losing the next turn's chosen Motion3 must NOT reuse the prior
            // cue/sequence, or stage.js will reject the new TTS as stale.
            val emptySpeechSequence = Live2DVisualActionBus.publishUnplannedSpeech(
                context, role, call)
            assertTrue(emptySpeechSequence > cue.sequence)
            val emptySpeechCue = Live2DVisualActionBus.cues.value.getValue(role)
            assertEquals(emptySpeechSequence, emptySpeechCue.sequence)
            assertNull(emptySpeechCue.motion)
            assertTrue(emptySpeechCue.beats.isEmpty())
            assertTrue(emptySpeechCue.speechParts.isEmpty())
            assertFalse(Live2DVisualActionBus.recordMotionOutcome(role, call,
                "motion-started", event(emptySpeechSequence, 0, "s1")))
        } finally {
            Live2DVisualActionBus.stageClosed(role, call)
            prefs.edit().remove("model:$role").commit()
            settings.parentFile!!.deleteRecursively()
        }
    }
}
