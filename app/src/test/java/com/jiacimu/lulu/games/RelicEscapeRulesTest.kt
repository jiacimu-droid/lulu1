package com.jiacimu.lulu.games

import android.content.Context
import org.robolectric.RuntimeEnvironment
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
        val context=RuntimeEnvironment.getApplication()
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
        val reopened=RelicEscapeLedger(RuntimeEnvironment.getApplication())
        assertEquals(failed,reopened.read())
        val again=reopened.perform("abandon")
        assertEquals(failed.awarded,again.awarded)
        assertEquals(failed.vault,again.vault)
        assertEquals(failed.coins,again.coins)
        val newRun=reopened.begin(emptyList(),0)
        assertNotEquals(failed.run?.id,newRun.run?.id)
        assertEquals(1,newRun.awarded.size)
    }

    @Test fun traverseOpenFightAndExtractCarriesConcreteItem() {
        var s=RelicEscapeWorld.create(915,2,emptyList(),2,2).copy(mobs=emptyList())
        val chest=s.chests.first()
        repeat(100) {
            if(s.hero.x==chest.x && s.hero.y==chest.y)return@repeat
            val step=RelicEscapeRules.path(s,s.hero.x,s.hero.y,chest.x,chest.y)
                ?:return@repeat
            s=RelicEscapeRules.act(s,"move",step.first,step.second)
        }
        assertEquals(chest.x,s.hero.x)
        assertEquals(chest.y,s.hero.y)
        s=RelicEscapeRules.act(s,"open",chest.x,chest.y)
        assertTrue(s.hero.bag.any{it.id==s.id+":chest:"+chest.id})
        val once=s.carried
        s=RelicEscapeRules.act(s,"open",chest.x,chest.y)
        assertEquals(once,s.carried)
        var safety=100
        while(RelicEscapeRules.distance(s.hero.x,s.hero.y,s.exitX,s.exitY)>0 && safety-->0) {
            val step=RelicEscapeRules.path(s,s.hero.x,s.hero.y,s.exitX,s.exitY)!!
            s=RelicEscapeRules.act(s,"move",step.first,step.second)
        }
        assertTrue(safety>0)
        assertEquals(RelicPhase.EXTRACT,RelicEscapeRules.act(s,"extract").phase)
        s=RelicEscapeRules.act(s,"extract")
        repeat(3){s=RelicEscapeRules.act(s,"wait")}
        assertEquals(RelicPhase.SUCCESS,s.phase)
        assertEquals(once,s.secured)
        assertEquals(once.sumOf{it.value},s.secured.sumOf{it.value})
    }

    @Test fun rescueConsumesRealMedicineAndClearsTeammateDown() {
        val raw=RelicEscapeWorld.create(4,1,listOf("ally-a"),1,1).copy(mobs=emptyList())
        val actor=raw.allies.first()
        val injured=raw.copy(allies=listOf(actor.copy(hp=0,downs=1)))
        val healed=RelicEscapeRules.act(injured,"revive",lootId="ally-a")
        assertEquals(4,healed.allies.first().hp)
        assertEquals(0,healed.hero.kits)
        assertEquals(1,healed.hero.rescues)
        assertTrue(healed.events.any{it.action=="rescue"})
    }

    @Test fun repeatedSettlementAndTwoDifferentRunsNeverMintSameTreasureTwice() {
        val ledger=cleared()
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE)
        val first=RelicEscapeWorld.create(551,1,emptyList(),0,1)
        val second=RelicEscapeWorld.create(552,1,emptyList(),0,1)
        for((index,run) in listOf(first,second).withIndex()) {
            val unique=RelicLoot(run.id+":manual","测试遗物",RelicTier.RARE,200+index)
            val prepared=run.copy(mobs=emptyList(),
                hero=run.hero.copy(x=run.exitX,y=run.exitY,bag=listOf(unique)))
            val previous=ledger.read()
            prefs.edit().putString("ledger",previous.copy(run=prepared).json().toString()).commit()
            assertEquals(RelicPhase.EXTRACT,ledger.perform("extract").run!!.phase)
            repeat(3){ledger.perform("wait")}
            val settled=ledger.read()
            assertEquals(index+1,settled.vault.size)
            assertEquals(index+1,settled.awarded.size)
            assertEquals(index+1,settled.successes)
            val reopened=RelicEscapeLedger(context)
            assertEquals(settled.vault,reopened.read().vault)
            reopened.perform("wait")
            reopened.perform("extract")
            assertEquals(settled.vault,reopened.read().vault)
        }
        assertEquals(401,ledger.read().vault.sumOf{it.value})
    }

    @Test fun midRunRecoveryRetainsExploredTilesAndCannotStartSecondRun() {
        val ledger=cleared()
        val original=ledger.begin(listOf("ally-a"),1)
        val s=original.run!!
        val next=RelicEscapeRules.path(s,s.hero.x,s.hero.y,s.exitX,s.exitY)!!
        val moved=ledger.perform("move",next.first,next.second)
        val reopened=RelicEscapeLedger(RuntimeEnvironment.getApplication())
        assertEquals(moved,reopened.read())
        assertEquals(moved,reopened.begin(emptyList(),0))
        assertEquals(original.run!!.id,moved.run!!.id)
        assertTrue(moved.run!!.seen.contains(moved.run!!.index(next.first,next.second)))
    }

    @Test fun altarSpawnsRealGuardianAndOneUniqueHighValueRelic() {
        val run=RelicEscapeWorld.create(1281,3,emptyList(),1,1).copy(mobs=emptyList())
        val altar=run.sites.first{it.kind=="altar"}
        val at=RelicEscapeRules.reveal(run.copy(
            hero=run.hero.copy(x=altar.x,y=altar.y),allies=emptyList()))
        val opened=RelicEscapeRules.act(at,"invoke",altar.x,altar.y)
        assertTrue(opened.sites.first{it.id==altar.id}.spent)
        val rewardId=run.id+":altar:"+altar.id
        assertTrue((opened.hero.bag+opened.drops.map{it.loot}).any{
            it.id==rewardId && it.tier==RelicTier.PRECIOUS && it.value>=650
        })
        assertTrue(opened.mobs.any{it.kind==2 && it.hp>0})
        val repeated=RelicEscapeRules.act(opened,"invoke",altar.x,altar.y)
        assertEquals(opened.turns,repeated.turns)
        assertEquals(1,(repeated.hero.bag+repeated.drops.map{it.loot})
            .count{it.id==rewardId})
        val restored=RelicRun.read(JSONObject(opened.json().toString()))!!
        assertEquals(opened.sites,restored.sites)
        assertEquals(opened.hero.bag,restored.hero.bag)
    }

    @Test fun trapsCanBeDisarmedOrCauseActualDamage() {
        val world=RelicEscapeWorld.create(1289,2,emptyList(),0,1).copy(mobs=emptyList())
        val trap=world.sites.first{it.kind=="trap"}
        val around=listOf(trap.x+1 to trap.y,trap.x-1 to trap.y,
            trap.x to trap.y+1,trap.x to trap.y-1).first{(x,y)->world.walk(x,y)}
        val at=RelicEscapeRules.reveal(world.copy(hero=world.hero.copy(x=around.first,y=around.second)))
        val disarmed=RelicEscapeRules.act(at,"disarm",trap.x,trap.y)
        assertTrue(disarmed.sites.first{it.id==trap.id}.spent)
        assertEquals(world.tools-1,disarmed.tools)
        val safe=RelicEscapeRules.act(disarmed,"move",trap.x,trap.y)
        assertEquals(world.hero.hp,safe.hero.hp)
        val danger=RelicEscapeRules.act(at,"move",trap.x,trap.y)
        assertEquals(world.hero.hp-2,danger.hero.hp)
        assertTrue(danger.sites.first{it.id==trap.id}.spent)
        val again=RelicEscapeRules.act(danger,"disarm",trap.x,trap.y)
        assertEquals(danger.tools,again.tools)
    }

    @Test fun tacticalLightAndLureConsumeOnceAndPersistThroughRestore() {
        val run=RelicEscapeWorld.create(1293,1,emptyList(),1,1).copy(mobs=emptyList())
        val lit=RelicEscapeRules.act(run,"flare")
        assertEquals(1,lit.flares)
        assertTrue(lit.seen.size>run.seen.size)
        assertTrue(lit.flareTurns>0)
        val lure=RelicEscapeRules.act(lit,"lure",run.hero.x,run.hero.y)
        assertEquals(0,lure.lures)
        assertTrue(lure.lureTurns>0)
        val duplicate=RelicEscapeRules.act(lure,"lure",run.hero.x,run.hero.y)
        assertEquals(lure.turns,duplicate.turns)
        val restored=RelicRun.read(JSONObject(lure.json().toString()))!!
        assertEquals(lure.flares,restored.flares)
        assertEquals(lure.lures,restored.lures)
        assertEquals(lure.lureX to lure.lureY,restored.lureX to restored.lureY)
        assertEquals(lure.sites,restored.sites)
    }

    @Test fun oldSaveRestoresWithDefaultTacticalGearWithoutMintingLoot() {
        val run=RelicEscapeWorld.create(1294,1,emptyList(),0,1)
        val old=JSONObject(run.json().toString())
        listOf("sites","flares","lures","tools","flareX","flareY","flareTurns",
            "lureX","lureY","lureTurns").forEach{old.remove(it)}
        val restored=RelicRun.read(old)!!
        assertTrue(restored.sites.isEmpty())
        assertEquals(2,restored.flares)
        assertEquals(1,restored.lures)
        assertEquals(2,restored.tools)
        assertEquals(run.hero.bag,restored.hero.bag)
        assertEquals(run.chests,restored.chests)
    }

    @Test fun everySpawnHasOptionalLandmarksAndTrapsAreNeverUnderChests() {
        for(seed in listOf(55,1294,20261028,-832)) {
            val run=RelicEscapeWorld.create(seed,4,listOf("friend"),1,1)
            assertTrue(run.sites.any{it.kind=="altar"})
            assertTrue(run.sites.any{it.kind=="trap"})
            for(site in run.sites) {
                assertTrue(run.walk(site.x,site.y))
                assertTrue(RelicEscapeRules.distance(site.x,site.y,2,2)>=5)
                assertTrue(run.chests.none{it.x==site.x && it.y==site.y})
                assertFalse(site.x==run.exitX && site.y==run.exitY)
                assertNotNull(RelicEscapeRules.path(run,2,2,site.x,site.y))
            }
        }
    }
}
