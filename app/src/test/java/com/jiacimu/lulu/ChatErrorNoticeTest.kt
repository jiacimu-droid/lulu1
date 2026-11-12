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

    @Test fun billedButEmptyStreamHasSpecificTerminalError() {
        val message = chatErrorNotice("中转站发送了流式结束标记，但没有可显示的正文")
        assertTrue(message.contains("没有返回可用正文"))
        assertTrue(message.contains("可能已计费"))
        assertTrue(message.contains("未自动重试"))
        assertFalse(message.contains("正在输入"))
    }

    @Test fun uncertainTransportDoesNotSuggestBlindPaidReplay() {
        val message = chatErrorNotice("请求正文已发送，等待响应时连接中断或超时，服务端生成结果未知")
        assertTrue(message.contains("结果未能确认"))
        assertTrue(message.contains("可能已计费"))
        assertFalse(message.contains("点「回复」重试"))
    }

    @Test fun secretTokensStayRedacted() {
        val hint = chatErrorNotice(
            "已生成决定，但发送阶段未完成：Bearer secret_token_here")
        assertFalse(hint.contains("secret_token_here"))
        assertTrue(hint.contains("[隐藏]"))
    }
}
