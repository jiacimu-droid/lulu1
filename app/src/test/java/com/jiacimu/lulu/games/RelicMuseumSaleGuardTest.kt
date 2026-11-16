package com.jiacimu.lulu.games

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[28])
class RelicMuseumSaleGuardTest {
    @Test fun staleMuseumSaleCannotLiquidateRealLootDuringExpedition() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE)
        val loot=RelicLoot("museum-owned-1","月银胸针",RelicTier.RARE,180)
        val world=RelicEscapeWorld.create(8123,2,emptyList(),0,1)
        for (phase in listOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)) {
            val snapshot=RelicSnapshot(run=world.copy(phase=phase),
                vault=listOf(loot),coins=45,featuredLootId=loot.id)
            assertTrue(prefs.edit().putString("ledger",snapshot.json().toString()).commit())
            val ledger=RelicEscapeLedger(context)
            assertEquals(snapshot,ledger.sellLoot(loot.id))
            assertEquals(snapshot,RelicEscapeLedger(context).read())
        }
        val settled=RelicSnapshot(run=world.copy(phase=RelicPhase.SUCCESS),
            vault=listOf(loot),coins=45,featuredLootId=loot.id)
        assertTrue(prefs.edit().putString("ledger",settled.json().toString()).commit())
        val sold=RelicEscapeLedger(context).sellLoot(loot.id)
        assertTrue(sold.vault.isEmpty())
        assertEquals(225,sold.coins)
        assertNull(sold.featuredLootId)
        assertEquals(sold,RelicEscapeLedger(context).read())
        assertEquals(sold,RelicEscapeLedger(context).sellLoot(loot.id))
    }
    @Test fun staleMuseumPedestalChoiceCannotChangeDisplayDuringExpedition() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE)
        val first=RelicLoot("pedestal-first","月银胸针",RelicTier.RARE,180)
        val second=RelicLoot("pedestal-second","月银胸针",RelicTier.RARE,180)
        val world=RelicEscapeWorld.create(8124,2,emptyList(),0,1)
        for (phase in listOf(RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED)) {
            val before=RelicSnapshot(run=world.copy(phase=phase),
                vault=listOf(first,second),featuredLootId=first.id)
            assertTrue(prefs.edit().putString("ledger",before.json().toString()).commit())
            assertEquals(before,RelicEscapeLedger(context).featureLoot(second.id))
            assertEquals(before,RelicEscapeLedger(context).read())
        }
        val camp=RelicSnapshot(run=world.copy(phase=RelicPhase.SUCCESS),
            vault=listOf(first,second),featuredLootId=first.id)
        assertTrue(prefs.edit().putString("ledger",camp.json().toString()).commit())
        val changed=RelicEscapeLedger(context).featureLoot(second.id)
        assertEquals(second.id,changed.featuredLootId)
        assertEquals(changed,RelicEscapeLedger(context).read())
    }

}
