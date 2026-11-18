package com.jiacimu.lulu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluFirstRunAgreementTest {
    @Test fun consentIsExplicitAndVersioned() {
        assertFalse(LuluFirstRunAgreement.acceptsCurrentVersion(0))
        assertFalse(LuluFirstRunAgreement.acceptsCurrentVersion(-1))
        assertTrue(LuluFirstRunAgreement.acceptsCurrentVersion(LuluFirstRunAgreement.VERSION))
        assertFalse(LuluFirstRunAgreement.acceptsCurrentVersion(LuluFirstRunAgreement.VERSION + 1))
    }

    @Test fun agreementExplainsActualLocalAndExternalDataPaths() {
        val titles = luluAgreementSections.map { it.first }
        val body = luluAgreementSections.joinToString("\n") { it.second }
        assertTrue(titles.distinct().size == titles.size)
        assertTrue(titles.size >= 5)
        assertTrue(body.contains("第三方"))
        assertTrue(body.contains("WebDAV"))
        assertTrue(body.contains("Android 系统备份"))
        assertTrue(body.contains("密钥"))
        assertTrue(body.contains("未满") || body.contains("成年人"))
        assertFalse(body.contains("全部数据绝不离开本机"))
        assertFalse(body.contains("江渡"))
        assertFalse(body.contains("星禾"))
    }
}
