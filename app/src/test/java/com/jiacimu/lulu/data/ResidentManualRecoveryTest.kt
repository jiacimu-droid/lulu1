package com.jiacimu.lulu.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ResidentManualRecoveryTest {
    @Test fun unknownAndInterruptedStatesAreVisibleButNeverPretendToHaveAValidDecision() {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1", 0)
        val native = "manual-" + UUID.randomUUID()
        val now = Instant.now()
        val day = ResidentDailyDecision.date(now)
        for (phase in listOf("uncertain", "repair_requesting", "requesting", "invalid_output", "definite_rejected")) {
            p.edit().putString("attempt:$native", day).putString("phase:$native:$day", phase).commit()
            val state = ResidentDailyDecision.dayRecovery(app, native, now)
            assertNotNull(state)
            assertEquals(phase, state!!.phase)
            assertTrue(state.label.isNotBlank())
        }
        assertNull(ResidentDailyDecision.dayRecovery(app, "other-$native", now))
        p.edit().putString("decision:$native:$day", "{}").commit()
        assertNull(ResidentDailyDecision.dayRecovery(app, native, now))
    }

    @Test fun staleConfirmationAndYesterdayRequestCannotAuthorizeANewPurchase() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1", 0)
        val native = "manual-" + UUID.randomUUID()
        val now = Instant.now()
        val day = ResidentDailyDecision.date(now)
        p.edit().putString("attempt:$native", day).putString("phase:$native:$day", "uncertain").commit()
        val before = ResidentDailyDecision.dayRecovery(app, native, now)!!
        p.edit().putInt("manual-retry:$native:$day", 1).commit()
        assertFalse(ResidentDailyDecision.retryDayDecision(app, native, before) { now })
        assertTrue(p.contains("attempt:$native"))
        val current = ResidentDailyDecision.dayRecovery(app, native, now)!!
        assertFalse(ResidentDailyDecision.retryDayDecision(app, native, current) { now.plusSeconds(86400) })
        assertEquals(day, p.getString("attempt:$native", ""))
        assertNull(ResidentDailyDecision.dayRecovery(app, native, now.plusSeconds(86400)))
    }
}
