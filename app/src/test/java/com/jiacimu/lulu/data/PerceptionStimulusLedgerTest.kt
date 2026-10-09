package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PerceptionStimulusLedgerTest {
    @Test fun oldUserMessageCannotRepeatEmotionUntilDeletedOrReset() {
        val context = RuntimeEnvironment.getApplication() as Context
        PerceptionStimulusLedger.initialize(context)
        val role = "perception-ledger-replay"
        val stimulus = PerceptionStimulus("original-msg-1", "用户真实说了一句话", setOf("user"))
        PerceptionStimulusLedger.clear(role)
        assertTrue(PerceptionStimulusLedger.claim(context, role, stimulus))
        assertFalse(PerceptionStimulusLedger.claim(context, role, stimulus))
        PerceptionStimulusLedger.invalidate("original-msg-1")
        assertTrue(PerceptionStimulusLedger.claim(context, role, stimulus))
        PerceptionStimulusLedger.clear(role)
        assertTrue(PerceptionStimulusLedger.claim(context, role, stimulus))
        PerceptionStimulusLedger.clear(role)
    }

    @Test fun groupEventDeletionClearsTheOriginalObservedBubbleToo() {
        val context = RuntimeEnvironment.getApplication() as Context
        PerceptionStimulusLedger.initialize(context)
        val role = "perception-group-deleted-test"
        PerceptionStimulusLedger.clear(role)
        val seen = PerceptionStimulus("group-bubble-001", "用户群里说话")
        assertTrue(PerceptionStimulusLedger.claim(context, role, seen))
        assertFalse(PerceptionStimulusLedger.claim(context, role, seen))
        PerceptionStimulusLedger.invalidate("group-bubble-001:group:$role")
        assertTrue(PerceptionStimulusLedger.claim(context, role, seen))
        PerceptionStimulusLedger.clear(role)
    }

    @Test fun samePersonCanNoticeAnActuallyDifferentNewEvent() {
        val context = RuntimeEnvironment.getApplication() as Context
        PerceptionStimulusLedger.initialize(context)
        val role = "perception-ledger-distinct"
        PerceptionStimulusLedger.clear(role)
        assertTrue(PerceptionStimulusLedger.claim(context, role,
            PerceptionStimulus("message-a", "聊天A")))
        assertTrue(PerceptionStimulusLedger.claim(context, role,
            PerceptionStimulus("message-b", "聊天B")))
        assertFalse(PerceptionStimulusLedger.claim(context, role,
            PerceptionStimulus("message-a", "聊天A")))
        PerceptionStimulusLedger.clear(role)
    }
}
