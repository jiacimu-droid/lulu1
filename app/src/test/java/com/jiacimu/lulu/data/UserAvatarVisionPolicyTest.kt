package com.jiacimu.lulu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAvatarVisionPolicyTest {
    @Test fun previousAvatarIsNotCurrentAfterTheNextEdit() {
        assertTrue(UserAvatarVision.stillCurrent("content://new", "r2", "content://new", "r2"))
        assertFalse(UserAvatarVision.stillCurrent("content://new", "r2", "content://old", "r1"))
        assertFalse(UserAvatarVision.stillCurrent("content://new", "r2", "content://new", "r1"))
        assertFalse(UserAvatarVision.stillCurrent(null, "r3", "content://new", "r2"))
    }

    @Test fun actualPixelsAreAttachedToAnExistingDecisionWithoutForcedCommentary() {
        val observation = UserAvatarVision.Attachment(
            "revision", "content://photo", "data:image/jpeg;base64,YWJj",
        )
        assertTrue(observation.dataUrl.startsWith("data:image/jpeg;base64,"))
        assertTrue(observation.promptNote.contains("avatarObservation"))
        assertTrue(observation.promptNote.contains("由你自己决定"))
    }
}
