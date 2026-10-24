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
    @Test fun starEyeAliasesOnlyResolveToRealModelExpressions() {
        val names = listOf("小狼-星星眼", "脸红", "吐舌", "笑眼")
        assertEquals("小狼-星星眼",
            Live2DVisualActionBus.resolveExpression("星星眼", names))
        assertEquals("小狼-星星眼",
            Live2DVisualActionBus.resolveExpression("StarryEyes", names))
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
            assertTrue(tearful.beats.any { (it.face?.values?.get("pout") ?: 0f) >= 0f })
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
}
