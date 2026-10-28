package com.jiacimu.lulu.games

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class RelicEscapeRulesTest {
    private fun cleared():RelicEscapeLedger {
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE).edit().clear().commit()
        return RelicEscapeLedger(context)
    }
    @Test fun generatedMapHasRealReachableChestsAndExitOnEverySeed() {
        for(seed in listOf(1,2,3,20261028,-459)) {
            val a=RelicEscapeWorld.create(seed,3,listOf("a","b","c","d"),1,1)
            val b=RelicEscapeWorld.create(seed,3,listOf("a","b","c"),1,1)
            assertEquals(a.tiles,b.tiles)
            assertEquals(a.chests.map{it.x to it.y},b.chests.map{it.x to it.y})
            assertEquals(a.exitX to a.exitY,b.exitX to b.exitY)
            assertEquals(3,a.allies.size)
            assertTrue(a.chests.size>=5)
            assertTrue(a.chests.any{it.tier==RelicTier.PRECIOUS})
            for(chest in a.chests)assertNotNull(RelicEscapeRules.path(a,2,2,chest.x,chest.y))
            assertNotNull(RelicEscapeRules.path(a,2,2,a.exitX,a.exitY))
        }
    }
    @Test fun openingOnceBagLimitDroppingAndRecoveryAreRealState() {
        val base=RelicEscapeWorld.create(56,2,emptyList(),0,1)
            .copy(mobs=emptyList())
        val chest=base.chests.first()
        val near=RelicEscapeRules.reveal(base.copy(hero=base.hero.copy(x=chest.x,y=chest.y)))
        val loaded=RelicEscapeRules.act(near,"open",chest.x,chest.y)
        assertEquals(1,loaded.hero.bag.size)
        val repeated=RelicEscapeRules.act(loaded,"open",chest.x,chest.y)
        assertEquals(loaded.turns,repeated.turns)
        assertEquals(loaded.hero.bag,repeated.hero.bag)
        val full=near.copy(hero=near.hero.copy(bag=List(8){
            RelicLoot("old-"+it,"碎片",RelicTier.COMMON,50)
        }))
        val dropped=RelicEscapeRules.act(full,"open",chest.x,chest.y)
        assertEquals(8,dropped.hero.bag.size)
        assertEquals(1,dropped.drops.size)
        val remove=(0 until 3).fold(dropped){s,i->RelicEscapeRules.act(s,"drop",lootId="old-"+i)}
        val picked=RelicEscapeRules.act(remove,"pickup",lootId=dropped.drops.first().loot.id)
        assertEquals(5+dropped.drops.first().loot.weight,picked.hero.load)
        assertTrue(picked.hero.bag.any{it.id==dropped.drops.first().loot.id})
        val restored=RelicRun.read(JSONObject(dropped.json().toString()))!!
        assertEquals(dropped.drops,restored.drops)
        assertEquals(dropped.chests,restored.chests)
    }
    @Test fun unladenExtractionIsAllowedAndSuccessfulLootIsPhysical() {
        val raw=RelicEscapeWorld.create(111,1,emptyList(),0,1).copy(mobs=emptyList())
        val at=raw.copy(hero=raw.hero.copy(x=raw.exitX,y=raw.exitY))
        val called=RelicEscapeRules.act(at,"extract")
        assertEquals(RelicPhase.EXTRACT,called.phase)
        val extracted=(1..3).fold(called){s,_->RelicEscapeRules.act(s,"wait")}
        assertEquals(RelicPhase.SUCCESS,extracted.phase)
        assertTrue(extracted.secured.isEmpty())
        assertEquals(RelicPhase.SUCCESS,RelicEscapeRules.act(extracted,"extract").phase)
    }
    @Test fun downedPlayerCanFailAndLoseUnextractedLoot() {
        val raw=RelicEscapeWorld.create(123,1,emptyList(),0,1)
        val item=RelicLoot("unique","宝物",RelicTier.PRECIOUS,444)
        val mob=RelicMob(55,raw.hero.x+1,raw.hero.y,3,1,windup=true)
        val unsafe=raw.copy(hero=raw.hero.copy(hp=1,bag=listOf(item)),mobs=listOf(mob))
        val down=RelicEscapeRules.act(unsafe,"wait")
        assertEquals(RelicPhase.DOWNED,down.phase)
        val failed=(1..3).fold(down){s,_->RelicEscapeRules.act(s,"wait")}
        assertEquals(RelicPhase.FAILURE,failed.phase)
        assertTrue(failed.secured.isEmpty())
        assertTrue(failed.lost.contains(item))
    }
    @Test fun settlementTombstonePersistsAcrossLedgerRecreation() {
        val ledger=cleared()
        val initial=ledger.begin(emptyList(),0)
        assertNotNull(initial.run)
        val failed=ledger.perform("abandon")
        assertEquals(RelicPhase.FAILURE,failed.run?.phase)
        assertEquals(1,failed.awarded.size)
        val reopened=RelicEscapeLedger(ApplicationProvider.getApplicationContext<Context>())
        assertEquals(failed,reopened.read())
        val again=reopened.perform("abandon")
        assertEquals(failed.awarded,again.awarded)
        assertEquals(failed.vault,again.vault)
        assertEquals(failed.coins,again.coins)
        val newRun=reopened.begin(emptyList(),0)
        assertNotEquals(failed.run?.id,newRun.run?.id)
        assertEquals(1,newRun.awarded.size)
    }
}
