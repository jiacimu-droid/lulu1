package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Mail wake gate (owner decision): a resident recipient wakes promptly on
 * delivered mail like the protagonist does, instead of waiting for its next
 * low-frequency wake or the next morning plan. Protagonist recipients still
 * need a resolved DIGITAL life form.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ResidentSocialMailboxWakeTest {

    @Test fun residentRecipientsWakePromptlyWhileUnresolvedProtagonistsDoNot() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        DigitalLifeProfileStore.initialize(ctx)
        DigitalLifeProfileStore.registerNewLife("mail-wake-hero-1", "主角", "用户")
        assertTrue(ResidentSocialMailbox.mailWakeEnabled("resident:abc"))
        assertTrue(ResidentSocialMailbox.mailWakeEnabled("mail-wake-hero-1"))
        assertFalse(ResidentSocialMailbox.mailWakeEnabled("unresolved-1"))
    }
}
