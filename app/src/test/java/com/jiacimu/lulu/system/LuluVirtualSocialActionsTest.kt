package com.jiacimu.lulu.system

import com.jiacimu.lulu.system.LuluVirtualChatSendPolicy.ComposerBounds
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LuluVirtualSocialActionsTest {
    @Test fun douyinMessagingRequiresAnExplicitPrivateMessageEditor() {
        val app = LuluVirtualChatSendPolicy.DOUYIN
        val editor = "android.widget.EditText"
        assertTrue(LuluVirtualChatSendPolicy.supports(app))
        assertFalse(LuluVirtualChatSendPolicy.supports("com.example.app"))
        assertTrue(LuluVirtualChatSendPolicy.isDouyinChatEntry("发消息"))
        assertFalse(LuluVirtualChatSendPolicy.isDouyinChatEntry("发送验证码"))
        assertTrue(LuluVirtualChatSendPolicy.chatEditorCandidate(editor, "发消息…", null, app))
        assertTrue(LuluVirtualChatSendPolicy.chatEditorCandidate(editor, null, "id/im_input", app))
        assertFalse(LuluVirtualChatSendPolicy.chatEditorCandidate(editor, "说点什么", null, app))
        assertFalse(LuluVirtualChatSendPolicy.chatEditorCandidate(editor, "发表评论", "id/im_input", app))
        assertFalse(LuluVirtualChatSendPolicy.chatEditorCandidate(editor, "搜索", null, app))
        assertFalse(LuluVirtualChatSendPolicy.windowTitleMatches("抖音", "抖音"))
        assertTrue(LuluVirtualChatSendPolicy.windowTitleMatches("测试昵称", "测试昵称"))
        assertFalse(LuluVirtualChatSendPolicy.windowTitleMatches("测试昵称2", "测试昵称"))
    }

    @Test fun wechatMomentsNavigationDoesNotAllowGenericPublishing() {
        assertTrue(LuluWeChatMomentsPolicy.isNavigation("朋友圈"))
        assertTrue(LuluWeChatMomentsPolicy.isNavigation("朋友圈消息"))
        assertFalse(LuluWeChatMomentsPolicy.isNavigation("发表"))
        assertFalse(LuluWeChatMomentsPolicy.isNavigation("发送"))
        assertFalse(LuluVirtualUiPolicy.mayClick("发布", "", ""))
        assertFalse(LuluVirtualUiPolicy.mayClick("发表", "", ""))
    }

    @Test fun momentsComposerRequiresTopRowAndASeparateWideBodyField() {
        val width = 720
        val height = 1280
        val camera = ComposerBounds(630, 55, 700, 126)
        val cancel = ComposerBounds(20, 52, 110, 118)
        val publish = ComposerBounds(605, 52, 707, 118)
        val body = ComposerBounds(25, 150, 695, 360)
        assertTrue(LuluWeChatMomentsPolicy.isCameraControl(camera, width, height, "拍照"))
        assertFalse(LuluWeChatMomentsPolicy.isCameraControl(camera, width, height, "发送"))
        assertFalse(LuluWeChatMomentsPolicy.isCameraControl(cancel, width, height, "拍照"))
        assertTrue(LuluWeChatMomentsPolicy.composerToolbar(cancel, publish, width, height))
        assertFalse(LuluWeChatMomentsPolicy.composerToolbar(publish, cancel, width, height))
        assertTrue(LuluWeChatMomentsPolicy.composerEditor(body, publish, width, height, "这一刻的想法", ""))
        assertFalse(LuluWeChatMomentsPolicy.composerEditor(body, publish, width, height, "发表评论", ""))
        assertFalse(LuluWeChatMomentsPolicy.composerEditor(
            ComposerBounds(25, 900, 695, 960), publish, width, height, "输入", ""))
    }

    @Test fun momentsActionsRequireTheCorrespondingLiveSnapshotAndExplicitInput() {
        val open = LuluWeChatMomentsPolicy.OPEN_COMPOSER
        val publish = LuluWeChatMomentsPolicy.PUBLISH
        val snapshot = JSONObject().put("screenSnapshotId", "observed")
        assertTrue(LuluBackgroundPhoneArgumentPolicy.needsSnapshot(open))
        assertTrue(LuluBackgroundPhoneArgumentPolicy.needsSnapshot(publish))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason(open, snapshot))
        assertNotNull(LuluBackgroundPhoneArgumentPolicy.invalidReason(publish, snapshot))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason(open, snapshot.put("elementRef", "e2")))
        assertNull(LuluBackgroundPhoneArgumentPolicy.invalidReason(publish, snapshot.put("text", "测试动态")))
    }
}
