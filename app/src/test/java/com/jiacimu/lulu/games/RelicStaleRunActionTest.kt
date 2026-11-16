package com.jiacimu.lulu.games

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[28])
class RelicStaleRunActionTest {
    @Test fun oldMapAndCompanionCallbacksCannotAbandonOrAdvanceNewExpedition() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE).edit().clear().commit()
        val ledger = RelicEscapeLedger(context)
        val first = checkNotNull(ledger.begin(emptyList(),0).run)
        ledger.perform("abandon",expectedRunId=first.id)
        val second = checkNotNull(ledger.begin(emptyList(),0).run)
        assertNotEquals(first.id,second.id)
        val before = ledger.read()
        assertEquals(before,ledger.perform("abandon",expectedRunId=first.id))
        assertEquals(before,ledger.perform("wait",expectedRunId=first.id))
        assertEquals(before,ledger.perform("companion_tick",expectedRunId=first.id))
        assertEquals(before,RelicEscapeLedger(context).read())
        assertTrue(second.phase in setOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT))
        val ended = ledger.perform("abandon",expectedRunId=second.id)
        assertEquals(RelicPhase.FAILURE,ended.run?.phase)
        assertEquals(ended,RelicEscapeLedger(context).read())
    }
}
