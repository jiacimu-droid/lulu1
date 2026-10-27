package com.jiacimu.lulu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAvatarVisionPolicyTest {
    @Test fun onlyTheNewestSavedAvatarCanProduceAnObservation() {
        assertTrue(UserAvatarVision.stillCurrent("content://new", "new-revision",
            "content://new", "new-revision"))
        assertFalse(UserAvatarVision.stillCurrent("content://new", "new-revision",
            "content://old", "old-revision"))
        assertFalse(UserAvatarVision.stillCurrent("content://new", "new-revision",
            "content://new", "old-revision"))
        assertFalse(UserAvatarVision.stillCurrent("", "deleted-revision",
            "content://old", "old-revision"))
    }

    @Test fun anActualNonemptyVisualResponseIsRequiredBeforeClaimingToSeeTheAvatar() {
        assertNull(UserAvatarVision.successEvidence("   "))
        assertTrue(UserAvatarVision.successEvidence("蓝色天空下的小猫")
            ?.contains("蓝色天空下的小猫") == true)
        assertFalse(UserAvatarVision.failureEvidence("视觉服务请求失败")
            .contains("已实际读取"))
    }
}
