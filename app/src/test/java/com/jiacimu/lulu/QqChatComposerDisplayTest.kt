package com.jiacimu.lulu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A compact composer must not hide a sendable photo or show a phantom send. */
class QqChatComposerDisplayTest {
    @Test fun emptyDraftKeepsPlusVisible() {
        assertFalse(qqComposerHasDraft("", null))
        assertFalse(qqComposerHasDraft(" \n\t ", null))
    }

    @Test fun typedTextUsesSendButton() {
        assertTrue(qqComposerHasDraft("你好", null))
    }

    @Test fun photoAloneUsesSendButton() {
        assertTrue(qqComposerHasDraft("", "content://photo/123"))
    }

    @Test fun noPhantomSendForEmptyPhoto() {
        assertFalse(qqComposerHasDraft("", ""))
    }
}
