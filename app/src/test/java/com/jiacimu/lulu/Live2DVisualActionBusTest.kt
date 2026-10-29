package com.jiacimu.lulu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class Live2DVisualActionBusTest {
    @Test fun visualActionSurvivesHarmlessPhoneTextNormalizationButNotRejectedTurns() {
        assertTrue(Live2DVisualActionBus.canPublishPhoneVisual(
            false, false, "call-1", "已经净化并准备播放的口语"))
        assertFalse(Live2DVisualActionBus.canPublishPhoneVisual(
            true, false, "call-1", "口语"))
        assertFalse(Live2DVisualActionBus.canPublishPhoneVisual(
            false, true, "call-1", "口语"))
        assertFalse(Live2DVisualActionBus.canPublishPhoneVisual(
            false, false, "", "口语"))
        assertFalse(Live2DVisualActionBus.canPublishPhoneVisual(
            false, false, "call-1", ""))
    }

    @Test fun videoSpeechCanBePlannedBeforeTheFirstRenderedFrameButNeverForAnotherCall() {
        // The first model decision is often made while Cubism is loading.
        // Readiness must not be the permission gate for creating its Motion3 plan.
        val eligible = Live2DVisualActionBus::isVideoPlanEligible
        assertTrue(eligible(true, true, true, true, true))
        assertFalse(eligible(false, true, true, true, true)) // ordinary voice
        assertFalse(eligible(true, false, true, true, true)) // disconnected
        assertFalse(eligible(true, true, false, true, true)) // another role
        assertFalse(eligible(true, true, true, false, true)) // old call id
        assertFalse(eligible(true, true, true, true, false)) // no imported model
    }

    @Test fun starEyeAliasesOnlyResolveToRealModelExpressions() {
        val names = listOf("小狼-星星眼", "脸红", "吐舌", "笑眼")
        assertEquals("小狼-星星眼",
            Live2DVisualActionBus.resolveExpression("星星眼", names))
        assertEquals("小狼-星星眼",
            Live2DVisualActionBus.resolveExpression("StarryEyes", names))
        assertEquals("小狼-星星眼", Live2DVisualActionBus.resolveExpression(
            "爱心眼", names + "爱心眼"))
        assertEquals("脸红", Live2DVisualActionBus.resolveExpression(
            "爱心眼", listOf("爱心眼", "脸红")))
        assertEquals(null, Live2DVisualActionBus.resolveExpression(
            "爱心眼", listOf("爱心眼")))
        assertEquals("吐舌", Live2DVisualActionBus.resolveExpression("吐舌", names))
        assertEquals("脸红", Live2DVisualActionBus.resolveExpression("害羞", names))
        assertEquals(null,
            Live2DVisualActionBus.resolveExpression("星星眼", listOf("笑眼")))
        assertEquals(null,
            Live2DVisualActionBus.resolveExpression("挥手", names))
    }

    @Test fun roleCanOnlyExecuteOwnImportedControlsInsideCurrentVideoCall() {
        val context = RuntimeEnvironment.getApplication()
        val role = "live2d-role-" + UUID.randomUUID()
        val call = "call-" + UUID.randomUUID()
        val relative = "models/test-" + UUID.randomUUID() + "/avatar.model3.json"
        val settings = File(Live2DAssets.base(context), relative)
        settings.parentFile?.mkdirs()
        settings.writeText(
            """{"FileReferences":{"Moc":"avatar.moc3","Textures":["skin.png"],""" +
                """"Expressions":[{"Name":"smile","File":"smile.exp3.json"},{"Name":"流泪","File":"tears.exp3.json"}],""" +
                """"Motions":{"Gesture":[{"File":"wave.motion3.json"}]}}}"""
        )
        val prefs = context.getSharedPreferences("lulu_live2d_models_v1", 0)
        prefs.edit().putString("model:$role", relative).commit()
        try {
            val smile = JSONObject().put("expression", "smile")
            assertFalse(Live2DVisualActionBus.publish(context, role, call, smile))
            Live2DVisualActionBus.stageReady(role, call)
            assertFalse(Live2DVisualActionBus.publish(context, role, "other-call", smile))
            assertFalse(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("expression", "invented-happy-face")))
            // Head poses are not offered until the loaded model itself confirms parameters.
            assertFalse(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("head", JSONObject().put("yaw", 15))))
            Live2DVisualActionBus.stageParameters(role, call, "ParamAngleX,ParamAngleY")
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("head", JSONObject().put("yaw", 60).put("roll", 20))))
            assertEquals(25f, Live2DVisualActionBus.cues.value[role]?.head?.yaw)
            assertEquals(null, Live2DVisualActionBus.cues.value[role]?.head?.roll)
            assertTrue(Live2DVisualActionBus.publish(context, role, call, smile))
            assertEquals("smile", Live2DVisualActionBus.cues.value[role]?.expression)
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("motion", JSONObject().put("group", "Gesture").put("index", 0))))
            assertEquals("Gesture" to 0, Live2DVisualActionBus.cues.value[role]?.motion)
            // A role can smile WHILE performing an actually available motion;
            // previously choosing the expression silently erased the motion.
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject()
                    .put("expression", "smile")
                    .put("motion", JSONObject().put("group", "Gesture").put("index", 0))
                    .put("head", JSONObject().put("yaw", -13))))
            val combined = Live2DVisualActionBus.cues.value[role]
            assertEquals("smile", combined?.expression)
            assertEquals("Gesture" to 0, combined?.motion)
            assertEquals(-13f, combined?.head?.yaw)

            // These controls must only become available AFTER an actual
            // loaded model advertises their parameter IDs.
            val faceRequest = JSONObject().put("face", JSONObject()
                .put("gazeX", -0.8).put("smile", 0.75).put("eyeSmile", 0.7))
            assertFalse(Live2DVisualActionBus.publish(context, role, call, faceRequest))
            // A partial paired-eye capability is insufficient to drive a
            // symmetric eyeSmile, but the gaze and smile remain valid.
            Live2DVisualActionBus.stageParameters(role, call,
                "ParamEyeBallX,ParamMouthForm,ParamEyeLSmile,ParamBodyAngleX")
            assertTrue(Live2DVisualActionBus.publish(context, role, call, faceRequest))
            val partialFace = Live2DVisualActionBus.cues.value[role]?.face?.values
            assertEquals(-0.8f, partialFace?.get("gazeX"))
            assertEquals(0.75f, partialFace?.get("smile"))
            assertFalse(partialFace?.containsKey("eyeSmile") == true)
            // Values cannot exceed safe semantic ranges, and unsupported
            // gestures must never be invented for the imported model.
            val bodyAndFace = JSONObject()
                .put("body", JSONObject().put("yaw", 99).put("roll", 20))
                .put("face", JSONObject().put("gazeX", -2)
                    .put("eyeSmile", 0.6).put("smile", 3))
            assertTrue(Live2DVisualActionBus.publish(context, role, call, bodyAndFace))
            assertEquals(10f, Live2DVisualActionBus.cues.value[role]?.body?.yaw)
            assertEquals(null, Live2DVisualActionBus.cues.value[role]?.body?.roll)
            assertEquals(-1f, Live2DVisualActionBus.cues.value[role]?.face?.values?.get("gazeX"))
            assertEquals(1f, Live2DVisualActionBus.cues.value[role]?.face?.values?.get("smile"))
            Live2DVisualActionBus.stageParameters(role, call,
                "ParamEyeBallX,ParamMouthForm,ParamEyeLSmile,ParamEyeRSmile,ParamBodyAngleX")
            assertTrue(Live2DVisualActionBus.publish(context, role, call, bodyAndFace))
            assertEquals(0.6f, Live2DVisualActionBus.cues.value[role]?.face?.values?.get("eyeSmile"))
            // Multi-beat choreography is STILL one speech turn and is
            // strictly validated against that imported model's real assets.
            Live2DVisualActionBus.stageParameters(role, call,
                "ParamAngleX,ParamEyeLOpen,ParamEyeROpen,ParamMouthForm")
            val beats = org.json.JSONArray()
                .put(JSONObject().put("atMs", 1400)
                    .put("face", JSONObject().put("eyeOpen", 0.55)
                        .put("smile", 0.6).put("squint", 0.8)))
                .put(JSONObject().put("atMs", 0)
                    .put("head", JSONObject().put("yaw", 80))
                    .put("expression", "smile"))
                .put(JSONObject().put("atMs", 2000).put("expression", "invented"))
                .put(JSONObject().put("atMs", -2).put("expression", "smile"))
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("beats", beats)))
            val planned = Live2DVisualActionBus.cues.value[role]?.beats.orEmpty()
            assertEquals(2, planned.size)
            assertEquals(0, planned.first().atMs)
            assertEquals("smile", planned.first().expression)
            assertEquals(25f, planned.first().head?.yaw)
            assertEquals(1400, planned.last().atMs)
            assertEquals(0.55f, planned.last().face?.values?.get("eyeOpen"))
            assertFalse(planned.last().face?.values?.containsKey("squint") == true)
            // A precise gesture can be bound only by the SAME visible role,
            // call and original decision. Unmatched quotes never play early.
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("beats", org.json.JSONArray()
                    .put(JSONObject().put("anchor", "后来呢")
                        .put("face", JSONObject().put("smile", .5)))
                    .put(JSONObject().put("anchor", "没有说出的话")
                        .put("head", JSONObject().put("yaw", 4))))))
            val sourceSeq = Live2DVisualActionBus.cues.value[role]!!.sequence
            assertEquals(0L, Live2DVisualActionBus.bindSpeechAnchors(
                role, call, sourceSeq - 1, mapOf("后来呢" to 1)))
            assertEquals(0L, Live2DVisualActionBus.bindSpeechAnchors(
                role, "other-call", sourceSeq, mapOf("后来呢" to 1)))
            val boundSeq = Live2DVisualActionBus.bindSpeechAnchors(
                role, call, sourceSeq, mapOf("后来呢" to 1))
            assertTrue(boundSeq > sourceSeq)
            assertEquals(1, Live2DVisualActionBus.cues.value[role]!!.beats.size)
            assertEquals(1, Live2DVisualActionBus.cues.value[role]!!.beats[0].segmentIndex)
            assertEquals(0L, Live2DVisualActionBus.bindSpeechAnchors(
                role, call, sourceSeq, mapOf("后来呢" to 1)))
            // Ordinary conversation can choose a compact performance in the
            // SAME reply. Generated channels still pass the exact rig gate.
            Live2DVisualActionBus.stageParameters(role, call,
                "ParamAngleZ,ParamBodyAngleX,ParamMouthForm")
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("performance", JSONObject().put("feeling", "shy")
                    .put("movement", "turn").put("intensity", .5))))
            val automatic = Live2DVisualActionBus.cues.value[role]!!
            assertEquals(6, automatic.beats.size)
            assertTrue(automatic.beats[1].body?.yaw != null)
            assertEquals(null, automatic.beats[1].head?.yaw)
            assertFalse(automatic.beats[1].face?.values?.containsKey("eyeOpen") == true)
            assertEquals(1440, automatic.beats[2].durationMs)
            assertEquals(null, automatic.expression)
            assertFalse(automatic.immediate)
            Live2DVisualActionBus.stageParameters(role, call,
                "ParamAngleZ,ParamBodyAngleX,ParamMouthForm,ParamMouthFunnel,ParamBrowLY,ParamBrowRY")
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("performance", JSONObject().put("feeling", "tearful"))))
            val tearful = Live2DVisualActionBus.cues.value[role]!!
            assertEquals("流泪", tearful.expression)
            assertTrue(tearful.beats.any { (it.face?.values?.get("pout") ?: 0f) > 0f })
            assertFalse(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("expression", "黑脸")))
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("performance", JSONObject().put("movement", "tilt"))
                    .put("_executeNow", true)))
            assertTrue(Live2DVisualActionBus.cues.value[role]!!.immediate)
            // Even existing dimensions cannot leak across a closed call.
            Live2DVisualActionBus.stageClosed(role, call)
            assertFalse(Live2DVisualActionBus.publish(context, role, call, smile))
        } finally {
            settings.parentFile?.deleteRecursively()
            prefs.edit().remove("model:$role").commit()
            Live2DVisualActionBus.stageClosed(role, call)
        }
    }

    @Test fun catalogMotionFillsExpressionAndFaceUnlessExplicitlyOverridden() {
        val context = RuntimeEnvironment.getApplication()
        val role = "live2d-role-" + UUID.randomUUID()
        val call = "call-" + UUID.randomUUID()
        val relative = "models/test-" + UUID.randomUUID() + "/avatar.model3.json"
        val settings = File(Live2DAssets.base(context), relative)
        settings.parentFile?.mkdirs()
        val motions = (0..20).joinToString(",") { "{\"File\":\"m$it.motion3.json\"}" }
        settings.writeText(
            """{"FileReferences":{"Moc":"avatar.moc3","Textures":["skin.png"],""" +
                """"Expressions":[{"Name":"smile","File":"smile.exp3.json"},{"Name":"流泪","File":"tears.exp3.json"}],""" +
                """"Motions":{"LuluWolf":[$motions]}}}"""
        )
        val prefs = context.getSharedPreferences("lulu_live2d_models_v1", 0)
        prefs.edit().putString("model:$role", relative).commit()
        try {
            Live2DVisualActionBus.stageReady(role, call)
            Live2DVisualActionBus.stageParameters(role, call,
                "ParamEyeBallX,ParamEyeBallY,ParamMouthForm,ParamEyeLSmile,ParamEyeRSmile," +
                "ParamEyeLOpen,ParamEyeROpen,ParamBrowLX,ParamBrowRX,ParamBrowLY,ParamBrowRY," +
                "ParamBrowLAngle,ParamBrowRAngle,ParamBrowLForm,ParamBrowRForm," +
                "ParamEyeLSquint,ParamEyeRSquint,ParamCheekPuff,ParamMouthFunnel")
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("motion", JSONObject().put("group", "LuluWolf").put("index", 20))))
            val filled = Live2DVisualActionBus.cues.value[role]
            assertEquals("LuluWolf" to 20, filled?.motion)
            assertEquals("星星眼", Live2DMotionCatalog.entries[17]?.expression)
            assertEquals("星星眼", Live2DMotionCatalog.entries[25]?.expression)
            assertEquals("星星眼", Live2DMotionCatalog.entries[28]?.expression)
            assertEquals("流泪", filled?.expression)
            assertEquals(-0.3f, filled?.face?.values?.get("smile"))
            assertEquals(0.82f, filled?.face?.values?.get("eyeOpen"))
            assertEquals(0.2f, filled?.face?.values?.get("browY"))
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("motion", JSONObject().put("group", "LuluWolf").put("index", 20))
                    .put("expression", "smile")))
            assertEquals("smile", Live2DVisualActionBus.cues.value[role]?.expression)
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("motion", JSONObject().put("group", "LuluWolf").put("index", 20))
                    .put("face", JSONObject().put("smile", 0.9))))
            assertEquals(0.9f, Live2DVisualActionBus.cues.value[role]?.face?.values?.get("smile"))
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("motion", JSONObject().put("group", "LuluWolf").put("index", 0))))
            val nod = Live2DVisualActionBus.cues.value[role]
            assertEquals("LuluWolf" to 0, nod?.motion)
            assertEquals(null, nod?.expression)
            assertEquals(null, nod?.face)
            // Legacy parameter performances remain for old decisions, but
            // never silently pick a Motion3 instead of the role.
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("performance",JSONObject().put("feeling","happy")
                    .put("movement","lean").put("intensity",0.5))))
            val talking = Live2DVisualActionBus.cues.value[role]
            assertEquals(null,talking?.motion)
            assertTrue(talking?.beats?.any { it.head != null || it.face != null } == true)
            assertTrue(Live2DVisualActionBus.publish(context, role, call,
                JSONObject().put("performance",JSONObject().put("feeling","happy"))))
            assertEquals(null,Live2DVisualActionBus.cues.value[role]?.motion)
            // New path: the role selects exact authored Motion3 files AND
            // their phrase anchors; independent head/body params are optional.
            val authored = org.json.JSONArray()
                .put(JSONObject().put("anchor","你回来了").put("motion",
                    JSONObject().put("group","LuluWolf").put("index",17)))
                .put(JSONObject().put("anchor","不过我可没一直等你").put("motion",
                    JSONObject().put("group","LuluWolf").put("index",6)))
                .put(JSONObject().put("anchor","不存在的资源").put("motion",
                    JSONObject().put("group","WrongGroup").put("index",17)))
            assertTrue(Live2DVisualActionBus.publish(context,role,call,
                JSONObject().put("beats",authored)))
            val motionCue=Live2DVisualActionBus.cues.value[role]!!
            assertEquals(2,motionCue.beats.size)
            assertEquals("LuluWolf" to 17,motionCue.beats.first().motion)
            assertEquals("LuluWolf" to 6,motionCue.beats.last().motion)
            assertEquals(null,motionCue.motion)
            val afterBinding=Live2DVisualActionBus.bindSpeechAnchors(role,call,
                motionCue.sequence,mapOf("你回来了" to 0,"不过我可没一直等你" to 2))
            assertTrue(afterBinding>motionCue.sequence)
            assertEquals(listOf(0,2),Live2DVisualActionBus.cues.value[role]!!
                .beats.map { it.segmentIndex })
            // An invalid anchor must never slide a motion to an unrelated
            // fragment or make up an estimated timestamp.
            val unbound=Live2DVisualActionBus.cues.value[role]!!
            val next=Live2DVisualActionBus.bindSpeechAnchors(role,call,
                unbound.sequence,mapOf("你回来了" to 0))
            assertTrue(next>0L)
            assertEquals(1,Live2DVisualActionBus.cues.value[role]!!.beats.size)
        } finally {
            settings.parentFile?.deleteRecursively()
            prefs.edit().remove("model:$role").commit()
        }
    }

    @Test fun livenessFloorFillsOnlyNeutralEverydayClipsWhenReplyChoseNothing() {
        val context = RuntimeEnvironment.getApplication()
        val role = "live2d-role-" + UUID.randomUUID()
        val call = "call-" + UUID.randomUUID()
        val relative = "models/test-" + UUID.randomUUID() + "/avatar.model3.json"
        val settings = File(Live2DAssets.base(context), relative)
        settings.parentFile?.mkdirs()
        val motions = (0..20).joinToString(",") { "{\"File\":\"m$it.motion3.json\"}" }
        settings.writeText(
            """{"FileReferences":{"Moc":"avatar.moc3","Textures":["skin.png"],""" +
                """"Motions":{"LuluWolf":[$motions]}}}"""
        )
        val prefs = context.getSharedPreferences("lulu_live2d_models_v1", 0)
        prefs.edit().putString("model:$role", relative).commit()
        try {
            // No visible stage for this call: no floor.
            assertFalse(Live2DVisualActionBus.publishLivenessFloor(context, role, call))
            Live2DVisualActionBus.stageReady(role, call)
            assertFalse(Live2DVisualActionBus.publishLivenessFloor(context, role, "other-call"))
            // Only catalog indices 0 (nod) and 3 (listening) exist in this
            // model's 0..20 clips; the floor must stay inside that neutral
            // set and rotate between them across replies.
            assertTrue(Live2DVisualActionBus.publishLivenessFloor(context, role, call))
            val first = Live2DVisualActionBus.cues.value[role]?.motion
            assertTrue(first == ("LuluWolf" to 0) || first == ("LuluWolf" to 3))
            assertTrue(Live2DVisualActionBus.publishLivenessFloor(context, role, call))
            val second = Live2DVisualActionBus.cues.value[role]?.motion
            assertTrue(second == ("LuluWolf" to 0) || second == ("LuluWolf" to 3))
            assertTrue("floor rotates instead of repeating one clip", first != second)
        } finally {
            settings.parentFile?.deleteRecursively()
            prefs.edit().remove("model:$role").commit()
        }
    }
}
