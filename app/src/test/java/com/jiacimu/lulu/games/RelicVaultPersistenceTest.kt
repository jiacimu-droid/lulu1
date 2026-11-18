package com.jiacimu.lulu.games

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Museum operations must follow the same physical item across process re-entry. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[28])
class RelicVaultPersistenceTest {
    @Test fun extractedPhysicalRelicSurvivesLegacyVaultIdCollisionAndSettlesOnce() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val old=RelicLoot("legacy-shared-id","月银胸针",RelicTier.RARE,220)
        val carried=RelicLoot("legacy-shared-id","星穹冠冕",RelicTier.PRECIOUS,900)
        assertTrue(prefs.edit().putString("ledger",
            RelicSnapshot(vault=listOf(old),coins=15).json().toString()).commit())
        val ledger=RelicEscapeLedger(context)
        val launched=ledger.begin(emptyList(),0,prepare={run->
            run.copy(phase=RelicPhase.EXTRACT,extraction=1000,
                hero=run.hero.copy(hp=10,bag=listOf(carried)),
                mobs=emptyList())
        })
        val runId=launched.run!!.id
        val finished=ledger.perform("wait",expectedRunId=runId)
        assertEquals(RelicPhase.SUCCESS,finished.run!!.phase)
        assertEquals(listOf(old,carried),finished.vault)
        assertEquals(1,finished.successes)
        assertTrue(runId in finished.awarded)
        assertEquals(finished,RelicEscapeLedger(context).read())
        assertEquals(finished,ledger.perform("wait",expectedRunId=runId))
        assertEquals(2,RelicEscapeLedger(context).read().vault.size)
    }

    @Test fun legacyDuplicateMuseumIdsSellExactlyOnePhysicalRelic() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val first=RelicLoot("legacy-duplicate","月银胸针",RelicTier.RARE,220)
        val second=RelicLoot("legacy-duplicate","幽蓝宝石",RelicTier.RARE,410)
        val original=RelicSnapshot(vault=listOf(first,second),coins=35,
            discovered=setOf(first.name,second.name),featuredLootId=first.id)
        assertTrue(prefs.edit().putString("ledger",original.json().toString()).commit())
        val sold=RelicEscapeLedger(context).sellLoot(first.id)
        assertEquals(listOf(second),sold.vault)
        assertEquals(255,sold.coins)
        assertNull(sold.featuredLootId)
        assertEquals(setOf(first.name,second.name),sold.discovered)
        assertEquals(sold,RelicEscapeLedger(context).read())
        val secondSale=RelicEscapeLedger(context).sellLoot(second.id)
        assertTrue(secondSale.vault.isEmpty())
        assertEquals(665,secondSale.coins)
        assertEquals(secondSale,RelicEscapeLedger(context).read())
        assertEquals(secondSale,RelicEscapeLedger(context).sellLoot(second.id))
    }

    @Test fun realOwnedExhibitSurvivesReentryAndSellingClearsOnlyItsPedestal() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val run=RelicEscapeWorld.create(91824,2,emptyList(),0,1)
        val loot=RelicEscapeRules.treasure(run,RelicChest(41,5,5,RelicTier.RARE,grade=3))
        val other=RelicEscapeRules.treasure(run,RelicChest(42,5,5,RelicTier.RARE,grade=3))
        assertNotEquals(loot.id,other.id)
        val settled=RelicSnapshot(vault=listOf(loot,other),coins=120,
            discovered=setOf(loot.name,other.name),awarded=setOf(run.id))
        assertTrue(prefs.edit().putString("ledger",settled.json().toString()).commit())
        val ledger=RelicEscapeLedger(context)
        assertEquals(settled,ledger.featureLoot("missing"))
        assertEquals(loot.id,ledger.featureLoot(loot.id).featuredLootId)
        assertEquals(loot.id,RelicEscapeLedger(context).read().featuredLootId)
        val saved=RelicSnapshot.read(prefs.getString("ledger",null))
        assertEquals(loot.id,saved.featuredLootId)
        assertEquals(listOf(loot,other),saved.vault)
        val sold=RelicEscapeLedger(context).sellLoot(loot.id)
        assertNull(sold.featuredLootId)
        assertEquals(listOf(other),sold.vault)
        assertEquals(120+loot.value,sold.coins)
        assertTrue(loot.name in sold.discovered)
        assertEquals(sold,RelicEscapeLedger(context).read())
        assertEquals(sold,RelicSnapshot.read(JSONObject(sold.json().toString()).toString()))
        assertEquals(sold,ledger.sellLoot(loot.id))
    }
}
