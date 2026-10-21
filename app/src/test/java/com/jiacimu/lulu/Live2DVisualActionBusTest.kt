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
    @Test fun roleCanOnlyExecuteOwnImportedControlsInsideCurrentVideoCall() {
        val context = RuntimeEnvironment.getApplication()
        val role = "live2d-role-" + UUID.randomUUID()
        val call = "call-" + UUID.randomUUID()
        val relative = "models/test-" + UUID.randomUUID() + "/avatar.model3.json"
        val settings = File(Live2DAssets.base(context), relative)
        settings.parentFile?.mkdirs()
        settings.writeText(
            """{"FileReferences":{"Moc":"avatar.moc3","Textures":["skin.png"],""" +
                """"Expressions":[{"Name":"smile","File":"smile.exp3.json"}],""" +
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
            Live2DVisualActionBus.stageClosed(role, call)
            assertFalse(Live2DVisualActionBus.publish(context, role, call, smile))
        } finally {
            settings.parentFile?.deleteRecursively()
            prefs.edit().remove("model:$role").commit()
            Live2DVisualActionBus.stageClosed(role, call)
        }
    }
}
