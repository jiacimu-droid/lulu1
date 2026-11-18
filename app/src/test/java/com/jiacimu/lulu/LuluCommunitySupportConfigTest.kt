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
        assertTrue(LuluCommunitySupportConfig.wishesListUrl().startsWith(
            "https://github.com/jiacimu-droid/lulu1/issues?q="))
        assertTrue(URLDecoder.decode(LuluCommunitySupportConfig.wishesListUrl()
            .substringAfter("?q="), "UTF-8").contains("in:title"))
        assertTrue(body.contains("功能许愿"))
        assertTrue(body.contains("希望角色可以自己查日程"))
        assertFalse(body.contains("API Key: hidden_secret"))
    }

    @Test fun longAndBlankWishTextAreBoundedAndNonIdentifying() {
        assertTrue(LuluCommunitySupportConfig.wishTitle("a".repeat(500)).length <= 78)
        assertTrue(LuluCommunitySupportConfig.wishBody("类型", "b".repeat(9000)).length < 2_100)
        assertFalse(LuluCommunitySupportConfig.wishBody("功能许愿", "文字").contains("设备编号"))
    }

    @Test fun donationQrUsesOwnersImagePayloadAndIsReadyOffline() {
        // Decoded from the image supplied by the maintainer, not a guessed recipient.
        assertEquals(LuluAlipayDonationQrData.expectedPayload, LuluCommunitySupportConfig.DONATION_URL)
        assertEquals(LuluAlipayDonationQrData.expectedPayload, LuluCommunitySupportConfig.safeDonationUrl())
        assertTrue(LuluAlipayDonationQrData.valid())
        assertEquals(41, LuluAlipayDonationQrData.rows.size)
        assertEquals(41, LuluAlipayDonationQrData.rows[0].length)
        // QR quiet zone must stay blank so nearby UI cannot disrupt scanning.
        assertTrue(LuluAlipayDonationQrData.rows.take(4).all { it.all { cell -> cell == '0' } })
        assertEquals("1187725424@qq.com", LuluCommunitySupportConfig.emailAddress())
    }
}
