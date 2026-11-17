package com.jiacimu.lulu.system

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Model parameter mistakes must be refused before shell I/O or display reset. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LuluBackgroundPhoneArgumentPolicyTest {
    @Test fun incompleteSwipeIsRejectedInsteadOfDefaultingOrThrowing() {
        val invalid = JSONObject()
            .put("action", "swipe")
            .put("startY", 780).put("endX", 500).put("endY", 220)
        val reason = LuluBackgroundPhoneArgumentPolicy.invalidReason("swipe", invalid)
        assertNotNull(reason)
        assertTrue(reason!!.contains("startX"))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("swipe",
            invalid.put("startX", 500)))
    }

    @Test fun coordinatesMustBeFiniteIntegersWithinVirtualScreen() {
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("swipe",
            JSONObject().put("startX", -1).put("startY", 800)
                .put("endX", 500).put("endY", 200)))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("swipe",
            JSONObject().put("startX", 500.25).put("startY", 800)
                .put("endX", 500).put("endY", 200)))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("tap",
            JSONObject().put("x", 200)))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("tap",
            JSONObject().put("x", 200).put("y", 700)
                .put("target", "当前图片中的评论按钮").put("screenSnapshotId", "current")))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("swipe",
            JSONObject().put("startX", 500).put("startY", 800)
                .put("endX", 500).put("endY", 200).put("durationMs", "fast")))
    }

    @Test fun unlaunchableAndEmptyTextActionsAreRejectedBeforeStartingDisplay() {
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("launch", JSONObject()))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("launch",
            JSONObject().put("appName", "小红书")))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("type", JSONObject()))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("type",
            JSONObject().put("text", "")))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("type",
            JSONObject().put("text", "搜索内容")))
    }

    @Test fun searchRequiresAnExplicitNonBlankQueryBeforeDisplayIo() {
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("search", JSONObject()))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("search",
            JSONObject().put("text", "a".repeat(501))))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("search",
            JSONObject().put("text", "红米后台搜索")))
    }

    @Test fun unknownOrMissingActionIsExplainedWithoutGuessingWhatCharacterWanted() {
        val missing = LuluBackgroundPhoneArgumentPolicy.invalidReason("", JSONObject().put("appName", "微信"))
        assertNotNull(missing)
        assertTrue(missing!!.contains("args.action"))
        assertTrue(missing.contains("缺失"))
        assertTrue(missing.contains("launch"))
        val unsupported = LuluBackgroundPhoneArgumentPolicy.invalidReason(
            "open", JSONObject().put("appName", "微信"))
        assertNotNull(unsupported)
        assertTrue(unsupported!!.contains("open"))
        assertTrue(unsupported.contains("launch"))
        assertTrue(!LuluBackgroundPhoneArgumentPolicy.isSupportedAction("open"))
        assertTrue(LuluBackgroundPhoneArgumentPolicy.isSupportedAction("launch"))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason(
            "launch", JSONObject().put("appName", "微信")))
    }

    @Test fun observationAndStartHaveNoInventedCoordinateRequirement() {
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("start", JSONObject()))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("observe", JSONObject()))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("focus_search", JSONObject()))
        assertEquals(null, LuluBackgroundPhoneArgumentPolicy.invalidReason("stop", JSONObject()))
    }
    @Test fun publicCommentMustNameDifferentLiveEditorAndSubmitControlAndTargetPost() {
        val args = JSONObject().put("screenSnapshotId", "current").put("editorRef", "e1")
            .put("elementRef", "e2").put("text", "这是我的评论").put("contextText", "视频标题原文")
        assertTrue(LuluBackgroundPhoneArgumentPolicy.isSupportedAction("publish_comment"))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("publish_comment", args))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("publish_comment", JSONObject(args.toString()).put("editorRef", "e2")))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("publish_comment", JSONObject(args.toString()).put("contextText", "这是我的评论")))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("publish_comment", JSONObject(args.toString()).put("screenSnapshotId", "")))
    }

    @Test fun visualTapAndLongPressRequireCurrentPictureAndAnAuthoredTarget() {
        val point = JSONObject().put("x", 500).put("y", 400)
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("tap", point))
        point.put("screenSnapshotId", "picture").put("target", "相机按钮")
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("tap", point))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("long_press", point))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("long_press", point.put("durationMs", 5001)))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("adopt_app", JSONObject().put("packageName", "com.android.documentsui")))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason("adopt_app", JSONObject().put("packageName", "guessed")))
    }

}
