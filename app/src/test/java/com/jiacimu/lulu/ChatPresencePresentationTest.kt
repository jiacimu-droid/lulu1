package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Never present a second "offline-but-working" state. Online means awake;
 * a pending actual role worker or expiry appraisal is awake until settled.
 */
class ChatPresencePresentationTest {
    @Test fun realActionCannotBeDescribedAsSleeping() {
        assertEquals("在线·正在执行任务 · model",
            chatPresenceLine(
                online = true, onlineUntilText = "15:00",
                expiryReview = false, activeRoleWork = true,
                modelLabel = "model",
            ))
        assertEquals("在线·正在执行任务 · model",
            chatPresenceLine(
                online = false, onlineUntilText = "",
                expiryReview = false, activeRoleWork = true,
                modelLabel = "model",
            ))
    }

    @Test fun expiryAppraisalIsStillAwakeUntilItFinishes() {
        assertTrue(chatPresenceLine(false, "", true, false, "model")
            .startsWith("在线·正在决定下一步"))
    }

    @Test fun onlyNoWorkNoReviewNoLeaseIsSleeping() {
        assertEquals("休眠 · model",
            chatPresenceLine(false, "", false, false, "model"))
        assertEquals("在线至 16:45 · model",
            chatPresenceLine(true, "16:45", false, false, "model"))
    }

    @Test fun toolFollowThroughDoesNotSilenceARealReply() {
        assertEquals("正在输入中", chatWorkHeaderLabel(true, true))
        assertEquals("执行后续中", chatWorkHeaderLabel(false, true))
        assertEquals(null, chatWorkHeaderLabel(false, false))
    }

    @Test fun prolongedNetworkWaitIsNotCalledCharacterTyping() {
        assertEquals("等待接口完成", chatWorkHeaderLabel(true, false,
            waitingForInterfaceResult = true))
        assertEquals("等待接口完成", chatWorkHeaderLabel(true, true,
            waitingForInterfaceResult = true))
        assertEquals("正在查手机", chatWorkHeaderLabel(true, false,
            checkingPhone = true, waitingForInterfaceResult = true))
        assertEquals("执行后续中", chatWorkHeaderLabel(false, true,
            waitingForInterfaceResult = true))
        assertEquals(null, chatWorkHeaderLabel(false, false,
            waitingForInterfaceResult = true))
    }
}
