package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class UserInteractionPresenceTest {
    private val now = Instant.parse("2026-10-10T08:00:00Z")

    @Test fun sameSilenceDistinguishesForegroundChatFromLeavingAppWithoutClaimingReadOrAnger() {
        val page = UserInteractionPresence(true, now.minusSeconds(600), "private", now.minusSeconds(60))
        fun describe(presence: UserInteractionPresence) = UserInteractionPresenceStore.describe(
            presence, true, now.minusSeconds(1200), now.minusSeconds(900), now)
        val here = describe(page)
        val away = describe(page.copy(appVisible = false))
        assertTrue(here.contains("当前打开的是你参与的聊天页面"))
        assertTrue(here.contains("已等待约 900 秒"))
        assertTrue(here.contains("触摸操作约 60 秒"))
        assertTrue(away.contains("可能切换应用、锁屏或被其他窗口遮挡"))
        assertFalse(away.contains("当前打开的是你参与的聊天页面"))
        for (text in listOf(here, away)) {
            assertTrue(text.contains("之前的真实对话、关系、已有情绪"))
            assertTrue(text.contains("不能证明已读、生气、离开或同意"))
            assertTrue(text.contains("推测保持为推测"))
        }
    }

    @Test fun missingForegroundEvidenceAndNewUserReplyDoNotInventAbsenceOrUnansweredSpeech() {
        val text = UserInteractionPresenceStore.describe(UserInteractionPresence(), null,
            now.minusSeconds(10), now.minusSeconds(20), now)
        assertTrue(text.contains("是否在前台未知"))
        assertFalse(text.contains("尚未收到新的用户回应"))
        assertFalse(text.contains("触摸操作约"))
        assertFalse(text.contains("当前未处于前台可交互状态"))
    }
}
