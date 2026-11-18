package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class LuluCommunitySupportConfigTest {
    @Test fun mailComposerUsesConfiguredRecipientAndReviewableDraft() {
        val link = requireNotNull(LuluCommunitySupportConfig.emailComposerUrl(
            "  加个日程搜索  ", "功能许愿", "希望角色可以自己查日程",
        ))
        assertTrue(link.startsWith("mailto:1187725424@qq.com?subject="))
        assertFalse(link.contains("github.com"))
        val subject = URLDecoder.decode(link.substringAfter("subject=").substringBefore("&body="), "UTF-8")
        val body = URLDecoder.decode(link.substringAfter("&body="), "UTF-8")
        assertEquals("[许愿] 加个日程搜索", subject)
        assertTrue(body.contains("功能许愿"))
        assertTrue(body.contains("希望角色可以自己查日程"))
        assertFalse(body.contains("API Key: hidden_secret"))
    }

    @Test fun composerEscapesEmailQueryMetacharactersAndNewlines() {
        val link = requireNotNull(LuluCommunitySupportConfig.emailComposerUrl(
            "建议 A & B", "体验优化", "第一行\n第二行 & 好用?",
        ))
        assertTrue(link.contains("%20"))
        assertTrue(link.contains("%26"))
        val subject = URLDecoder.decode(link.substringAfter("subject=").substringBefore("&body="), "UTF-8")
        val body = URLDecoder.decode(link.substringAfter("&body="), "UTF-8")
        assertEquals("[许愿] 建议 A & B", subject)
        assertTrue(body.contains("第一行\n第二行 & 好用?"))
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
