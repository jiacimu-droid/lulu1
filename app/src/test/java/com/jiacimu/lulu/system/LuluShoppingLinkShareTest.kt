package com.jiacimu.lulu.system

import com.jiacimu.lulu.decodeQqShoppingProductLink
import com.jiacimu.lulu.encodeQqShoppingProductLink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LuluShoppingLinkShareTest {
    private val pdd = "com.xunmeng.pinduoduo"

    @Test fun onlyOwnerApprovedVerifiedMerchantCopyControlsQualify() {
        assertTrue(LuluShoppingLinkShare.eligibleCopy(pdd, "复制链接", true, true))
        assertFalse(LuluShoppingLinkShare.eligibleCopy(pdd, "确认付款", true, true))
        assertFalse(LuluShoppingLinkShare.eligibleCopy(pdd, "复制链接", false, true))
        assertFalse(LuluShoppingLinkShare.eligibleCopy(pdd, "复制链接", true, false))
        assertFalse(LuluShoppingLinkShare.eligibleCopy("com.tencent.mm", "复制链接", true, true))
    }

    @Test fun changedVendorHttpsUrlRequiredAndUnrelatedClipboardNeverSent() {
        val valid = "快来看看 https://mobile.yangkeduo.com/goods.html?goods_id=123 拼多多"
        assertEquals("https://mobile.yangkeduo.com/goods.html?goods_id=123",
            LuluShoppingLinkShare.verifiedChangedLink(pdd, "old", valid))
        assertNull(LuluShoppingLinkShare.verifiedChangedLink(pdd, valid, valid))
        assertNull(LuluShoppingLinkShare.verifiedChangedLink(pdd, "before", "https://example.com/private"))
        assertNull(LuluShoppingLinkShare.verifiedChangedLink(pdd, "before", "http://mobile.yangkeduo.com/goods.html"))
        assertNull(LuluShoppingLinkShare.verifiedChangedLink(pdd, "before", "https://evil-yangkeduo.com/a"))
        assertNull(LuluShoppingLinkShare.verifiedChangedLink(pdd, "before", "https://yangkeduo.com.evil.test/a"))
        assertNull(LuluShoppingLinkShare.verifiedChangedLink(pdd, "before", "https://user:pass@yangkeduo.com/a"))
    }

    @Test fun userPreferredMarketplacesAreSeparateVerifiedSources() {
        val fish = "com.taobao.idlefish"
        val delivery = "me.ele"
        assertEquals("闲鱼", LuluShoppingLinkShare.merchantName(fish))
        assertEquals("淘宝闪购", LuluShoppingLinkShare.merchantName(delivery))
        assertTrue(LuluShoppingLinkShare.eligibleCopy(fish, "复制链接", true, true))
        assertTrue(LuluShoppingLinkShare.eligibleCopy(delivery, "复制链接", true, true))
        assertEquals("https://h5.m.goofish.com/item?id=12",
            LuluShoppingLinkShare.verifiedChangedLink(fish, "old",
                "https://h5.m.goofish.com/item?id=12"))
        assertEquals("https://u.ele.me/Abc123",
            LuluShoppingLinkShare.verifiedChangedLink(delivery, "old", "https://u.ele.me/Abc123"))
        assertNull(LuluShoppingLinkShare.verifiedChangedLink(delivery, "old",
            "https://evil-ele.me/path"))
        assertNull(LuluShoppingLinkShare.verifiedChangedLink(fish, "old",
            "https://goofish.com.evil.tld/item"))
        assertFalse(LuluShoppingLinkShare.matchesMerchantLink("闲鱼",
            "https://u.ele.me/Abc123"))
        assertFalse(LuluShoppingLinkShare.matchesMerchantLink("淘宝闪购",
            "https://h5.m.goofish.com/item?id=12"))
    }

    @Test fun copiedLinkRequiresNewClipboardEventAfterTheActualClickStarted() {
        assertTrue(LuluShoppingLinkShare.freshCopyEvent(1000L, 1200L, 1200L))
        assertTrue(LuluShoppingLinkShare.freshCopyEvent(1000L, 1200L, 1201L))
        assertFalse(LuluShoppingLinkShare.freshCopyEvent(1000L, 1200L, 1199L))
        assertFalse(LuluShoppingLinkShare.freshCopyEvent(1200L, 1200L, 1200L))
        assertFalse(LuluShoppingLinkShare.freshCopyEvent(0L, 1200L, 0L))
    }

    @Test fun durableCardCodecRoundTripsWithoutTreatingOrdinaryTextAsLink() {
        val url = "https://p.pinduoduo.com/AbC123"
        val encoded = encodeQqShoppingProductLink("拼多多", url, "这款脆香米我觉得好吃～")
        val decoded = decodeQqShoppingProductLink(encoded)
        assertEquals("拼多多", decoded?.merchant)
        assertEquals(url, decoded?.url)
        assertEquals("这款脆香米我觉得好吃～", decoded?.caption)
        assertNull(decodeQqShoppingProductLink("我给你找到了一个商品链接：" + url))
        assertNull(decodeQqShoppingProductLink(encoded.replace("https://", "javascript:")))
    }
}
