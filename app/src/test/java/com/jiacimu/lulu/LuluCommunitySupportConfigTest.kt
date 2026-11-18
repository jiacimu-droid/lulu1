package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class LuluCommunitySupportConfigTest {
    @Test fun wishComposerCreatesAReviewableIssueNotASilentSubmission() {
        val link = LuluCommunitySupportConfig.issueComposerUrl(
            "  加个日程搜索  ", "功能许愿", "希望角色可以自己查日程",
        )
        assertTrue(link.startsWith("https://github.com/jiacimu-droid/lulu1/issues/new?"))
        val title = URLDecoder.decode(link.substringAfter("title=").substringBefore("&body="), "UTF-8")
        val body = URLDecoder.decode(link.substringAfter("&body="), "UTF-8")
        assertEquals("[许愿] 加个日程搜索", title)
        assertTrue(body.contains("功能许愿"))
        assertTrue(body.contains("希望角色可以自己查日程"))
        assertFalse(body.contains("API Key: hidden_secret"))
    }

    @Test fun longAndBlankWishTextAreBoundedAndNonIdentifying() {
        assertTrue(LuluCommunitySupportConfig.wishTitle("a".repeat(500)).length <= 78)
        assertTrue(LuluCommunitySupportConfig.wishBody("类型", "b".repeat(9000)).length < 2_100)
        assertFalse(LuluCommunitySupportConfig.wishBody("功能许愿", "文字").contains("设备编号"))
    }

    @Test fun noPaymentDestinationIsInvented() {
        // The owner must supply a real public payment URL before enabling donations.
        assertEquals("", LuluCommunitySupportConfig.DONATION_URL)
        assertEquals(null, LuluCommunitySupportConfig.safeDonationUrl())
        assertEquals(null, LuluCommunitySupportConfig.emailAddress())
    }
}
