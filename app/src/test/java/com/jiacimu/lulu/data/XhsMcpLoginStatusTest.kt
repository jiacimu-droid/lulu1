package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class XhsMcpLoginStatusTest {
    @Test fun upstreamGoLoginStatusUsesPlainText() {
        val status = XhsMcpLoginStatus.fromToolText("✅ 已登录\n用户名: 实际登录账号\n\n你可以使用其他功能了。")
        assertTrue(status.loggedIn)
        assertFalse(status.explicitlyLoggedOut)
        assertEquals("实际登录账号", status.displayName)
    }

    @Test fun missingSessionMustNotBeMisreportedAsLoggedIn() {
        val status = XhsMcpLoginStatus.fromToolText("❌ 未登录\n请使用 get_login_qrcode 工具获取二维码")
        assertFalse(status.loggedIn)
        assertTrue(status.explicitlyLoggedOut)
    }

    @Test fun jsonLegacyFormatAlsoWorks() {
        assertTrue(XhsMcpLoginStatus.fromToolText("""{"isLoggedIn":true,"nickname":"Alice"}""").loggedIn)
        assertFalse(XhsMcpLoginStatus.fromToolText("""{"is_logged_in":false}""").loggedIn)
        assertFalse(XhsMcpLoginStatus.fromToolText("检查登录状态失败: server disconnected").loggedIn)
    }
}
