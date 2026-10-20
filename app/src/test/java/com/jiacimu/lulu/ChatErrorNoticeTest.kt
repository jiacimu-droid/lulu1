package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class ChatErrorNoticeTest {
    @Test fun modelDidReturnButDeliveryFailedIsNotCalledApiFailure() {
        val hint = chatErrorNotice(
            "已生成决定，但发送阶段未完成：角色本轮没有提出行动动机；消息仍待处理，可重试")
        assertTrue(hint.contains("发送阶段未完成"))
        assertTrue(hint.contains("行动动机"))
        assertFalse(hint.contains("网络连接"))
    }

    @Test fun invalidJsonAndToolNameHaveDifferentReasons() {
        assertTrue(chatErrorNotice("模型输出格式不完整，消息仍未回复")
            .contains("格式未通过解析"))
        assertTrue(chatErrorNotice("未知能力：x")
            .contains("工具名称无效"))
    }

    @Test fun secretTokensStayRedacted() {
        val hint = chatErrorNotice(
            "已生成决定，但发送阶段未完成：Bearer secret_token_here")
        assertFalse(hint.contains("secret_token_here"))
        assertTrue(hint.contains("[隐藏]"))
    }
}
