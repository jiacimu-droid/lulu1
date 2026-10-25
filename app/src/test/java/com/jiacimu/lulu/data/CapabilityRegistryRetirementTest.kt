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
class CapabilityRegistryRetirementTest {
    @Test fun aRetiredToolIsRecognizedForHistoryButNeverOfferedOrAuthorized() {
        val context = RuntimeEnvironment.getApplication() as Context
        val historical = CapabilityRegistry.find("write_journal")
        assertNotNull(historical)
        assertTrue(historical!!.retired)
        assertFalse(CapabilityRegistry.canChoose("write_journal"))
        assertFalse(CapabilityRegistry.allows(context, "retired-capability-test", historical, true))
        assertFalse(CapabilityRegistry.allows(context, "retired-capability-test", historical, false))
        assertTrue(CapabilityRegistry.canChoose("publish_moment"))
    }
}
