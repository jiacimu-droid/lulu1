package com.jiacimu.lulu.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure bounding-box tests; no network, camera or actual device permission. */
class LuluVirtualChatTitleOcrTest {
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) =
        LuluVirtualChatTitleOcr.Line(text,
            LuluVirtualChatSendPolicy.ComposerBounds(l, t, r, b))

    @Test fun exactRecipientInTopWeChatToolbarIsAccepted() {
        assertTrue(LuluVirtualChatTitleOcr.hasExactToolbarTitle(
            listOf(line("文件传输助手", 182, 85, 426, 152)),
            "文件传输助手", 720, 1280))
        assertTrue(LuluVirtualChatTitleOcr.hasExactToolbarTitle(
            listOf(line("4 文件传输助手", 134, 85, 435, 152)),
            "文件传输助手", 720, 1280))
    }

    @Test fun textInChatHistoryOrSearchBelowToolbarIsNeverTitleProof() {
        assertFalse(LuluVirtualChatTitleOcr.hasExactToolbarTitle(
            listOf(line("文件传输助手", 165, 395, 440, 470)),
            "文件传输助手", 720, 1280))
        assertFalse(LuluVirtualChatTitleOcr.hasExactToolbarTitle(
            listOf(line("文件传输助手", 165, 720, 440, 780)),
            "文件传输助手", 720, 1280))
        assertFalse(LuluVirtualChatTitleOcr.hasExactToolbarTitle(
            listOf(line("文件传输助手", 0, 0, 720, 45)),
            "文件传输助手", 720, 1280))
    }

    @Test fun partialNameAndConflictingHeaderPositionsAreRejected() {
        assertFalse(LuluVirtualChatTitleOcr.hasExactToolbarTitle(
            listOf(line("文件传输助手的聊天", 182, 85, 426, 152)),
            "文件传输助手", 720, 1280))
        assertFalse(LuluVirtualChatTitleOcr.hasExactToolbarTitle(
            listOf(line("文件传输助手", 180, 85, 430, 128),
                   line("文件传输助手", 180, 151, 430, 190)),
            "文件传输助手", 720, 1280))
        assertFalse(LuluVirtualChatTitleOcr.hasExactToolbarTitle(
            listOf(line("文件传输助手", 180, 85, 430, 128)),
            "另一个联系人", 720, 1280))
    }
}
