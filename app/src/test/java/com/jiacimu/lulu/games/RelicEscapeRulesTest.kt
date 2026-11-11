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
    @Test fun treasureOrExitStaysARealPlayerChoiceAfterFindingLoot() {
        val base=RelicEscapeWorld.create(912,2,emptyList(),0,1)
        val chest=base.chests.first()
        val loot=RelicLoot("risk-choice","旧王银币",RelicTier.COMMON,90)
        val seen=setOf(base.index(chest.x,chest.y),
            base.index(base.exitX,base.exitY))
        val carrying=base.copy(seen=seen,
            hero=base.hero.copy(bag=listOf(loot)))
        val greed=RelicObjectiveGuide.visibleChest(carrying)!!
        val safety=RelicObjectiveGuide.visibleExit(carrying)!!
        assertEquals(chest.x to chest.y,greed.x to greed.y)
        assertEquals(base.exitX to base.exitY,safety.x to safety.y)
        // The legacy default recommendation may prefer safety, but both
        // deliberate options remain available for the player's choice.
        assertEquals(safety,RelicObjectiveGuide.nextVisible(carrying))
        assertEquals("路径未探明",RelicObjectiveGuide.optionLabel(
            carrying.copy(seen=emptySet()),greed))
        val concealed=carrying.copy(seen=setOf(base.index(chest.x,chest.y)))
        assertNull(RelicObjectiveGuide.visibleExit(concealed))
        assertNotNull(RelicObjectiveGuide.visibleChest(concealed))
        val cleared=carrying.copy(chests=carrying.chests.map {
            if(it.id==chest.id)it.copy(opened=true) else it
        })
        assertNull(RelicObjectiveGuide.visibleChest(cleared))
        assertNotNull(RelicObjectiveGuide.visibleExit(cleared))
        val failed=carrying.copy(phase=RelicPhase.FAILURE)
        assertNull(RelicObjectiveGuide.visibleChest(failed))
        assertNull(RelicObjectiveGuide.visibleExit(failed))
        // Projection does not modify seen, carried, inventory, or turns.
        assertEquals(seen,carrying.seen)
        assertEquals(loot,carrying.hero.bag.first())
        assertEquals(base.turns,carrying.turns)
    }

    @Test fun objectiveGuideNeverRevealsUnseenChestsAndOnlyFocusesKnownPlaces() {
        val base=RelicEscapeWorld.create(437,2,emptyList(),0,1)
        val unknown=base.copy(seen=emptySet())
        assertNull(RelicObjectiveGuide.nextVisible(unknown))
        assertTrue(RelicObjectiveGuide.prompt(unknown).contains("尚未发现宝箱"))
        val first=base.chests.first()
        val known=unknown.copy(seen=setOf(base.index(first.x,first.y)))
        assertEquals(first.x to first.y,
            RelicObjectiveGuide.nextVisible(known)?.let { it.x to it.y })
        val opened=known.copy(chests=known.chests.map {
            if(it.id==first.id)it.copy(opened=true)else it
        })
        assertNull(RelicObjectiveGuide.nextVisible(opened))
        val exitKnown=known.copy(seen=known.seen+base.index(base.exitX,base.exitY))
        val loot=RelicLoot("objective-guide","旧王银币",RelicTier.COMMON,70)
        val carrying=exitKnown.copy(hero=exitKnown.hero.copy(bag=listOf(loot)))
        assertEquals(base.exitX to base.exitY,
            RelicObjectiveGuide.nextVisible(carrying)?.let { it.x to it.y })
        val exitHidden=carrying.copy(seen=carrying.seen-base.index(base.exitX,base.exitY))
        assertNull(RelicObjectiveGuide.nextVisible(exitHidden))
        assertTrue(RelicObjectiveGuide.prompt(exitHidden).contains("尚未发现出口"))
        assertNull(RelicObjectiveGuide.nextVisible(
            carrying.copy(phase=RelicPhase.FAILURE)))
    }

    @Test fun discoveredRouteHintNeverInventsPathOrRevealsFog() {
        val base=RelicEscapeWorld.create(437,2,emptyList(),0,1)
        val exit=RelicObjectiveTarget(base.exitX,base.exitY,"已发现的撤离门")
        val hidden=base.copy(seen=emptySet())
        assertNull(RelicObjectiveGuide.knownRouteTiles(hidden,exit))
        // A visible destination is not enough: every intermediate step must
        // be discovered and physically traversable.
        val exitOnly=base.copy(seen=setOf(base.index(base.exitX,base.exitY)))
        assertNull(RelicObjectiveGuide.knownRouteTiles(exitOnly,exit))
        val atExit=exitOnly.copy(hero=exitOnly.hero.copy(
            x=base.exitX,y=base.exitY))
        assertEquals(listOf(base.exitX to base.exitY),
            RelicObjectiveGuide.knownRouteTiles(atExit,exit))
        assertNull(RelicObjectiveGuide.knownRouteTiles(
            atExit.copy(phase=RelicPhase.FAILURE),exit))
    }

    @Test fun liveContractsShowRealBagProgressButDoNotPayBeforeEscape() {
        val base=RelicEscapeWorld.create(817,1,emptyList(),0,1)
        val common=RelicLoot("common-progress","旧王银币",RelicTier.COMMON,80)
        val rare=RelicLoot("rare-progress","月银胸针",RelicTier.RARE,200)
        assertEquals(null,RelicContractBoard.bagProgress(base))
        val holding=base.copy(hero=base.hero.copy(bag=listOf(common,rare)))
        val first=holding.copy(contract=RelicContract.FIRST_HAUL)
        assertEquals(1 to 1,RelicContractBoard.bagProgress(first))
        assertEquals(0,RelicContractBoard.bonus(first))
        val hunter=holding.copy(contract=RelicContract.COLLECTOR)
        assertEquals(2 to 3,RelicContractBoard.bagProgress(hunter))
        assertEquals(0,RelicContractBoard.bonus(hunter))
        val rareRun=holding.copy(contract=RelicContract.RARITY)
        assertEquals(1 to 1,RelicContractBoard.bagProgress(rareRun))
        assertEquals(0,RelicContractBoard.bonus(rareRun))
        assertEquals(0 to 1,RelicContractBoard.bagProgress(
            rareRun.copy(phase=RelicPhase.FAILURE)))
        val secured=rareRun.copy(phase=RelicPhase.SUCCESS,
            hero=rareRun.hero.copy(bag=emptyList()),secured=listOf(common,rare))
        assertEquals(1 to 1,RelicContractBoard.bagProgress(secured))
        assertEquals(160,RelicContractBoard.bonus(secured))
        assertEquals(0,RelicContractBoard.bonus(
            secured.copy(contract=RelicContract.COLLECTOR)))
    }

    @Test fun distinctSignatureJewelsUseOneSharedIllustrationForRevealAndMuseum() {
        val expected=mapOf(
            "月银胸针" to RelicSignatureKind.MOON_BROOCH,
            "星穹冠冕" to RelicSignatureKind.STAR_CROWN,
            "冰魄竖琴" to RelicSignatureKind.FROST_HARP,
        )
        assertEquals(3,expected.values.toSet().size)
        for((name,signature) in expected) {
            assertEquals(signature,RelicSignatureJewels.kind(name))
            assertTrue(RelicTreasureArt.catalogKinds.containsKey(name))
            assertTrue(RelicTreasureGallery.identity(name).isNotBlank())
        }
        assertNull(RelicSignatureJewels.kind("旧王银币"))
        assertNull(RelicSignatureJewels.kind("幽蓝宝石"))
        assertNull(RelicSignatureJewels.kind("还没有发行的宝物"))
        // The specialist never becomes a second loot or collection source:
        // it changes only the illustration chosen by drawTreasure().
        assertEquals(3,expected.keys.size)
    }

    @Test fun permanentTreasureGalleryOnlyDisplaysActuallyOwnedLoot() {
        val ledger=cleared()
        val context=RuntimeEnvironment.getApplication()
        val owned=RelicLoot("museum-owned","月银胸针",RelicTier.RARE,210)
        val other=RelicLoot("museum-other","星穹冠冕",RelicTier.PRECIOUS,490)
        val original=RelicSnapshot(vault=listOf(owned,other),coins=15)
        context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE)
            .edit().putString("ledger",original.json().toString()).commit()
        assertEquals(null,ledger.featureLoot("not-owned").featuredLootId)
        val shown=ledger.featureLoot(owned.id)
        assertEquals(owned.id,shown.featuredLootId)
        assertEquals(listOf(owned,other),shown.vault)
        assertEquals(15,shown.coins)
        assertEquals(owned.id,RelicEscapeLedger(context).read().featuredLootId)
        assertTrue(RelicTreasureGallery.identity(owned.name).contains("月光"))
        assertTrue(RelicTreasureGallery.shareText(owned).contains("月银胸针"))
        assertFalse(RelicTreasureGallery.shareText(owned).contains("星穹冠冕"))
        val sold=ledger.sellLoot(owned.id)
        assertEquals(null,sold.featuredLootId)
        assertEquals(listOf(other),sold.vault)
        assertEquals(225,sold.coins)
        assertEquals(null,RelicEscapeLedger(context).read().featuredLootId)
        val old=JSONObject(original.json().toString()).apply { remove("featuredLootId") }
        assertNull(RelicSnapshot.read(old.toString()).featuredLootId)
        val fraudulent=JSONObject(original.json().toString()).apply {
            put("featuredLootId","not-owned")
        }
        assertNull(RelicSnapshot.read(fraudulent.toString()).featuredLootId)
        val newDisplay=ledger.featureLoot(other.id)
        assertEquals(other.id,newDisplay.featuredLootId)
        assertEquals(other.id,RelicSnapshot.read(newDisplay.json().toString()).featuredLootId)
    }

    @Test fun physicalContractSuccessAddsPermanentCoinsOnlyOnce() {
        val ledger=cleared()
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE)
        val run=RelicEscapeWorld.create(232,1,emptyList(),0,1)
        val loot=RelicLoot("contract-award","旧王银币",RelicTier.COMMON,80)
        val prepared=run.copy(contract=RelicContract.FIRST_HAUL,mobs=emptyList(),
            hero=run.hero.copy(x=run.exitX,y=run.exitY,bag=listOf(loot)))
        val json=JSONObject(prepared.json().toString())
        assertEquals(RelicContract.FIRST_HAUL,RelicRun.read(json)!!.contract)
        json.remove("contract")
        assertEquals(RelicContract.NONE,RelicRun.read(json)!!.contract)
        prefs.edit().putString("ledger",RelicSnapshot(run=prepared).json().toString()).commit()
        assertEquals(0,RelicContractBoard.bonus(prepared))
        ledger.perform("extract")
        repeat(3){ledger.perform("wait")}
        val settled=ledger.read()
        assertEquals(RelicPhase.SUCCESS,settled.run!!.phase)
        assertEquals(80/4+60,settled.coins)
        assertEquals(60,RelicContractBoard.bonus(settled.run!!))
        ledger.perform("extract")
        ledger.perform("wait")
        assertEquals(settled.coins,RelicEscapeLedger(context).read().coins)
        assertEquals(0,RelicContractBoard.bonus(settled.run!!.copy(contract=RelicContract.RARITY)))
        assertEquals(0,RelicContractBoard.bonus(settled.run!!.copy(phase=RelicPhase.FAILURE)))
    }
    private fun cleared():RelicEscapeLedger {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE).edit().clear().commit()
        return RelicEscapeLedger(context)
    }
    @Test fun difficultyChangesRealThreatsSurvivesSaveAndDefaultsOnOldRuns() {
        val easy=RelicEscapeWorld.create(341,1,emptyList(),0,1,
            difficulty=RelicDifficulty.EASY)
        val normal=RelicEscapeWorld.create(341,1,emptyList(),0,1,
            difficulty=RelicDifficulty.NORMAL)
        val hard=RelicEscapeWorld.create(341,1,emptyList(),0,1,
            difficulty=RelicDifficulty.HARD)
        assertEquals(normal.mobs.size-1,easy.mobs.size)
        assertEquals(normal.mobs.size+2,hard.mobs.size)
        assertTrue(hard.mobs.first().hp>normal.mobs.first().hp)
        assertEquals(RelicDifficulty.HARD,
            RelicRun.read(JSONObject(hard.json().toString()))!!.difficulty)
        assertEquals(RelicDifficulty.NORMAL,RelicRun.read(
            JSONObject(normal.json().toString()).apply { remove("difficulty") })!!.difficulty)
        val easyDamage=(1+easy.difficulty.incomingDamage).coerceAtLeast(1)
        val hardDamage=(1+hard.difficulty.incomingDamage).coerceAtLeast(1)
        assertTrue(hardDamage>easyDamage)
    }

    @Test fun campEquipmentPurchasesGiveRealSelectableStartingWeapons() {
        val ledger=cleared()
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE).edit()
            .putString("ledger",RelicSnapshot(coins=1100).json().toString()).commit()
        val upgraded=ledger.upgradeWeapon()
        assertTrue("spear" in upgraded.purchasedWeapons)
        assertEquals(750,upgraded.coins)
        val bought=ledger.buyBow()
        assertEquals(100,bought.coins)
        assertTrue("bow" in bought.purchasedWeapons)
        assertEquals(bought,ledger.buyBow())
        val started=ledger.begin(emptyList(),0,RelicDifficulty.NORMAL,"bow")
        assertEquals("bow",started.run!!.equipped)
        assertTrue("spear" in started.run!!.weapons)
        assertEquals(started,ledger.buyBow())
        val legacy=JSONObject(RelicSnapshot(weapon=2).json().toString()).apply {
            remove("purchasedWeapons")
        }
        assertTrue("spear" in RelicSnapshot.read(legacy.toString()).purchasedWeapons)
        assertTrue("blade" in RelicSnapshot.read(legacy.toString()).purchasedWeapons)
    }

    @Test fun downedPlayerMayAbandonAndStartFreshRunWithoutLosingVault() {
        val ledger=cleared()
        val old=ledger.begin(listOf("friend"),0,RelicDifficulty.HARD)
        val first=old.run!!
        val down=first.copy(phase=RelicPhase.DOWNED,hero=first.hero.copy(hp=0))
        val blocked=RelicEscapeRules.act(down,"attack",down.hero.x,down.hero.y)
        assertEquals(RelicPhase.DOWNED,blocked.phase)
        assertEquals(RelicPhase.FAILURE,RelicEscapeRules.abandon(down).phase)
        val ended=ledger.perform("abandon")
        assertEquals(RelicPhase.FAILURE,ended.run!!.phase)
        val restarted=ledger.begin(listOf("friend"),0,first.difficulty)
        assertNotEquals(first.id,restarted.run!!.id)
        assertEquals(RelicDifficulty.HARD,restarted.run!!.difficulty)
        assertEquals(listOf("friend"),restarted.run!!.allies.map { it.id })
        assertEquals(old.vault,restarted.vault)
    }

    @Test fun satchelPurchaseIsAtomicPermanentAndCannotMintCapacityMidRun() {
        val ledger=cleared()
        val context=RuntimeEnvironment.getApplication()
        val saved=RelicSnapshot(coins=1000,discovered=setOf("旧王银币"))
        context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE).edit()
            .putString("ledger",saved.json().toString()).commit()
        assertEquals(0,ledger.read().satchelLevel)
        val first=ledger.upgradeSatchel()
        assertEquals(1,first.satchelLevel)
        assertEquals(740,first.coins)
        val second=ledger.upgradeSatchel()
        assertEquals(2,second.satchelLevel)
        assertEquals(120,second.coins)
        assertEquals(second,ledger.upgradeSatchel())
        assertTrue("旧王银币" in RelicEscapeLedger(context).read().discovered)
        val begun=ledger.begin(emptyList(),0)
        assertEquals(2,begun.run!!.satchelLevel)
        assertEquals(12,RelicEscapeRules.heroCapacity(begun.run!!))
        assertEquals(begun,ledger.upgradeSatchel())
        val old=JSONObject(saved.json().toString()).apply{remove("satchelLevel")}
        assertEquals(0,RelicSnapshot.read(old.toString()).satchelLevel)
    }

    @Test fun extraSatchelSlotsOnlyApplyToPlayerLootAndSurviveSave() {
        val original=RelicEscapeWorld.create(672,3,emptyList(),0,1,2)
        val chest=original.chests.first()
        val stuffed=(0 until 8).map{idx->
            RelicLoot("cached$idx","旧王银币",RelicTier.COMMON,70)
        }
        val base=RelicEscapeRules.reveal(original.copy(
            hero=original.hero.copy(x=chest.x,y=chest.y,bag=stuffed),
            chests=original.chests.map {
                if(it.id==chest.id)it.copy(tier=RelicTier.RARE)else it
            },mobs=emptyList(),sites=emptyList()))
        val unexpanded=RelicEscapeRules.act(base.copy(satchelLevel=0),"open",chest.x,chest.y)
        assertEquals(8,unexpanded.hero.load)
        assertTrue(unexpanded.drops.any{it.loot.id.endsWith(":chest:"+chest.id)})
        val expanded=RelicEscapeRules.act(base,"open",chest.x,chest.y)
        assertEquals(10,expanded.hero.load)
        assertFalse(expanded.drops.any{it.loot.id.endsWith(":chest:"+chest.id)})
        val restored=RelicRun.read(JSONObject(expanded.json().toString()))!!
        assertEquals(12,RelicEscapeRules.heroCapacity(restored))
        assertEquals(expanded.hero.bag,restored.hero.bag)
    }

    @Test fun openingCeremonyOnlyRevealsLootThatWasPhysicallyCommitted() {
        val generated=RelicEscapeWorld.create(612,2,emptyList(),0,1)
        val chest=generated.chests.first()
        val prepared=RelicEscapeRules.reveal(generated.copy(
            hero=generated.hero.copy(x=chest.x,y=chest.y),
            mobs=emptyList(),sites=emptyList()))
        assertNull(RelicChestPresentation.committed(prepared,prepared,chest.id))
        val opened=RelicEscapeRules.act(prepared,"open",chest.x,chest.y)
        val result=RelicChestPresentation.committed(prepared,opened,chest.id)
        assertNotNull(result)
        assertEquals(prepared.id,result!!.runId)
        assertEquals(RelicEscapeRules.treasure(prepared,chest),result.loot)
        assertTrue(result.inBag)
        assertNull("重复触发不能再次演出",RelicChestPresentation.committed(opened,opened,chest.id))
        assertNull("不存在实物不能假出货",RelicChestPresentation.committed(prepared,
            opened.copy(hero=opened.hero.copy(bag=emptyList()),drops=emptyList()),chest.id))
        assertNull("跨局结果不允许冒领",RelicChestPresentation.committed(prepared,
            opened.copy(id="unrelated-run"),chest.id))

        val fullBag=prepared.copy(hero=prepared.hero.copy(bag=(0 until 8).map {
            RelicLoot("old-item-"+it,"旧王银币",RelicTier.COMMON,70)
        }))
        val dropped=RelicEscapeRules.act(fullBag,"open",chest.x,chest.y)
        val fallen=RelicChestPresentation.committed(fullBag,dropped,chest.id)
        assertNotNull(fallen)
        assertFalse("包满必须说明物品掉在地上",fallen!!.inBag)
        assertTrue(dropped.drops.any { it.loot == fallen.loot })
    }

    @Test fun mapOpeningGlowRequiresSameCommittedChestAndStopsOnDismiss() {
        val initial = RelicEscapeWorld.create(618,2,emptyList(),0,1)
        val chest = initial.chests.first()
        val prepared = RelicEscapeRules.reveal(initial.copy(
            hero=initial.hero.copy(x=chest.x,y=chest.y),
            mobs=emptyList(),sites=emptyList()))
        val opened = RelicEscapeRules.act(prepared,"open",chest.x,chest.y)
        val receipt = RelicChestPresentation.committed(prepared,opened,chest.id)
        assertNotNull(receipt)
        assertTrue(RelicChestPresentation.isCurrentOpening(opened,chest.id,receipt))
        assertFalse("No pre-commit map celebration",
            RelicChestPresentation.isCurrentOpening(prepared,chest.id,receipt))
        assertFalse("Other chest must not light up",
            RelicChestPresentation.isCurrentOpening(opened,chest.id+999,receipt))
        assertFalse("Other run must not reuse a prior opening",
            RelicChestPresentation.isCurrentOpening(opened.copy(id="other"),chest.id,receipt))
        assertFalse("Dismissed overlay ends the map glow, not the actual loot",
            RelicChestPresentation.isCurrentOpening(opened,chest.id,null))
        assertTrue(opened.hero.bag.any { it == receipt!!.loot } ||
            opened.drops.any { it.loot == receipt!!.loot })
    }

    @Test fun mapChestLidSharesCommittedCeremonyTimelineWithoutMintingLoot() {
        val initial = RelicEscapeWorld.create(619, 2, emptyList(), 0, 1)
        val chest = initial.chests.first()
        val before = RelicEscapeRules.reveal(initial.copy(
            hero = initial.hero.copy(x = chest.x, y = chest.y),
            mobs = emptyList(), sites = emptyList()))
        val after = RelicEscapeRules.act(before, "open", chest.x, chest.y)
        val receipt = RelicChestPresentation.committed(before, after, chest.id)
        assertNotNull(receipt)
        assertEquals(0f, relicChestLidLift(before, chest.id, receipt, 1f))
        assertEquals(0f, relicChestLidLift(after, chest.id, receipt, 0f))
        assertEquals(0f, relicChestLidLift(after, chest.id, receipt, .10f))
        assertEquals(1f, relicChestLidLift(after, chest.id, receipt, .57f))
        assertEquals(1f, relicChestLidLift(after, chest.id, receipt, 1f))
        assertEquals(0f, relicChestLidLift(after, chest.id, null, 1f))
        assertEquals(0f, relicChestLidLift(after, chest.id + 999, receipt, 1f))
        assertEquals(0f, relicChestLidLift(after.copy(id = "different"), chest.id, receipt, 1f))
        assertTrue(after.hero.bag.any { it == receipt!!.loot } ||
            after.drops.any { it.loot == receipt!!.loot })
    }

    @Test fun chestCeremonyUsesNewPhysicalCopyNotAnOldIdenticalLoot() {
        val generated=RelicEscapeWorld.create(613,2,emptyList(),0,1)
        val chest=generated.chests.first()
        val expected=RelicEscapeRules.treasure(generated,chest)
        val oldBag=(0 until 7).map {
            RelicLoot("older-"+it,"旧王银币",RelicTier.COMMON,70)
        } + expected
        val prepared=RelicEscapeRules.reveal(generated.copy(
            hero=generated.hero.copy(x=chest.x,y=chest.y,bag=oldBag),
            mobs=emptyList(),sites=emptyList()))
        val opened=RelicEscapeRules.act(prepared,"open",chest.x,chest.y)
        val receipt=RelicChestPresentation.committed(prepared,opened,chest.id)
        assertNotNull(receipt)
        assertFalse("New reward is on ground even if an old identical item is in bag",
            receipt!!.inBag)
        assertEquals(oldBag,opened.hero.bag)
        assertTrue(opened.drops.any { it.loot==expected })
        val forged=opened.copy(drops=prepared.drops)
        assertNull("Old inventory alone must not trigger a ceremony",
            RelicChestPresentation.committed(prepared,forged,chest.id))
    }

    @Test fun everyTreasureHasOriginalDistinctArtSharedByChestAndMuseum() {
        assertEquals("No blank generic icons for new collectibles",
            RelicCollectibles.names.toSet(),RelicTreasureArt.catalogKinds.keys)
        assertEquals(28,RelicTreasureArt.catalogKinds.size)
        assertTrue("Different item categories should look different",
            RelicTreasureArt.catalogKinds.values.toSet().size >= 12)
        assertEquals(RelicArtKind.STAR,
            RelicTreasureArt.kind(RelicCollectibles.CROWN_JEWEL))
        assertEquals(RelicArtKind.CROWN,RelicTreasureArt.kind("星穹冠冕"))
        assertEquals(RelicArtKind.HOURGLASS,RelicTreasureArt.kind("星河沙漏"))
        assertEquals(RelicArtKind.RING,RelicTreasureArt.kind("暮光戒指"))
        assertEquals(RelicArtKind.HARP,RelicTreasureArt.kind("冰魄竖琴"))
        assertEquals("Legacy inventory names still have safe art",
            RelicArtKind.CRYSTAL,RelicTreasureArt.kind("old_version_unknown"))
    }

    @Test fun treasureCatalogIsActuallyObtainableAndSeededWithoutRerolls() {
        assertEquals(RelicCollectibles.names.size,RelicCollectibles.names.distinct().size)
        var legendaryCount=0
        for(seed in -400..400) {
            val run=RelicEscapeWorld.create(seed,4,emptyList(),0,1)
            for(chest in run.chests) {
                val first=RelicEscapeRules.treasure(run,chest)
                assertEquals(first,RelicEscapeRules.treasure(run,chest))
                assertTrue(first.name in RelicCollectibles.names)
                if(RelicCollectibles.isCrownJewel(first.name)) {
                    assertEquals(RelicTier.PRECIOUS,first.tier)
                    legendaryCount++
                }
            }
        }
        assertTrue("稀有物品必须真的能掉落",legendaryCount>0)
    }

    @Test fun successfulLootEntersPermanentAlbumButSalesAndFailedRunsNeverEraseIt() {
        val ledger=cleared()
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("relic_escape_v2",Context.MODE_PRIVATE)
        val base=RelicEscapeWorld.create(831,3,emptyList(),0,1).copy(mobs=emptyList())
        val item=RelicEscapeRules.treasure(base,base.chests.first())
        val prepared=base.copy(hero=base.hero.copy(x=base.exitX,y=base.exitY,bag=listOf(item)))
        prefs.edit().putString("ledger",ledger.read().copy(run=prepared).json().toString()).commit()
        assertEquals(RelicPhase.EXTRACT,ledger.perform("extract").run!!.phase)
        repeat(3){ledger.perform("wait")}
        val earned=ledger.read()
        assertEquals(RelicPhase.SUCCESS,earned.run!!.phase)
        assertTrue(item.name in earned.discovered)
        assertTrue(item.name in earned.newDiscoveries)
        assertEquals(earned.newDiscoveries,RelicSnapshot.read(earned.json().toString()).newDiscoveries)
        assertEquals(1,earned.vault.size)
        val sold=ledger.sellLoot(item.id)
        assertTrue(sold.vault.isEmpty())
        assertTrue(item.name in sold.discovered)
        val restored=RelicEscapeLedger(context).read()
        assertEquals(sold.discovered,restored.discovered)
        assertEquals(earned.newDiscoveries,restored.newDiscoveries)
        val next=ledger.begin(emptyList(),0)
        assertNotEquals(base.id,next.run?.id)
        ledger.perform("abandon")
        assertEquals(restored.discovered,ledger.read().discovered)
        assertTrue(ledger.read().newDiscoveries.isEmpty())
        // Pre-album saves keep a best-effort record of the unsold physical collection.
        val old=JSONObject(earned.json().toString())
        old.remove("discovered")
        old.remove("newDiscoveries")
        assertTrue(item.name in RelicSnapshot.read(old.toString()).discovered)
        assertTrue(RelicSnapshot.read(old.toString()).newDiscoveries.isEmpty())
    }

    @Test fun rareChestGambleConsumesToolAndAwakensRealGuardianWithoutDoubleUpgrade() {
        val base=RelicEscapeWorld.create(992,2,emptyList(),0,1)
        val chest=base.chests.first()
        val rare=chest.copy(tier=RelicTier.RARE)
        val ready=RelicEscapeRules.reveal(base.copy(
            hero=base.hero.copy(x=chest.x,y=chest.y),
            chests=base.chests.map{if(it.id==chest.id)rare else it},
            mobs=emptyList(),sites=emptyList(),tools=1))
        val risked=RelicEscapeRules.act(ready,"channel",chest.x,chest.y)
        assertEquals(ready.turns+1,risked.turns)
        assertEquals(0,risked.tools)
        val upgraded=risked.chests.first{it.id==chest.id}
        assertEquals(RelicTier.PRECIOUS,upgraded.tier)
        assertTrue(risked.mobs.any{it.hp>0 && it.kind==2})
        assertTrue(risked.events.any{it.action=="channel"})
        val again=RelicEscapeRules.act(risked,"channel",chest.x,chest.y)
        assertEquals(risked.turns,again.turns)
        assertEquals(risked.mobs,again.mobs)
        val restored=RelicRun.read(JSONObject(risked.json().toString()))!!
        assertEquals(risked.tools,restored.tools)
        assertEquals(upgraded,restored.chests.first{it.id==chest.id})
        assertEquals(RelicEscapeRules.treasure(risked,upgraded),RelicEscapeRules.treasure(restored,upgraded))
        val opened=RelicEscapeRules.act(restored,"open",chest.x,chest.y)
        assertTrue(opened.hero.bag.any{it.tier==RelicTier.PRECIOUS && it.id.endsWith(":chest:"+chest.id)})
    }

    @Test fun rareChestRiskCannotBeTakenWithoutToolsOrRoomForRealGuardian() {
        val base=RelicEscapeWorld.create(731,1,emptyList(),0,1)
        val chest=base.chests.first()
        val rare=chest.copy(tier=RelicTier.RARE)
        val prepared=RelicEscapeRules.reveal(base.copy(
            chests=base.chests.map{if(it.id==chest.id)rare else it},
            hero=base.hero.copy(x=chest.x,y=chest.y),mobs=emptyList(),sites=emptyList(),tools=0))
        val noTool=RelicEscapeRules.act(prepared,"channel",chest.x,chest.y)
        assertEquals(prepared.turns,noTool.turns)
        assertEquals(prepared.chests,noTool.chests)
        val neighbors=listOf(chest.x+1 to chest.y,chest.x-1 to chest.y,
            chest.x to chest.y+1,chest.x to chest.y-1)
        val blockedTiles=prepared.tiles.mapIndexed{index,kind->
            if((index%prepared.w to index/prepared.w) in neighbors)1 else kind
        }
        val enclosed=prepared.copy(tiles=blockedTiles,tools=1)
        val noGuardian=RelicEscapeRules.act(enclosed,"channel",chest.x,chest.y)
        assertEquals(enclosed.turns,noGuardian.turns)
        assertEquals(enclosed.tools,noGuardian.tools)
        assertEquals(enclosed.chests,noGuardian.chests)
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
        var s=RelicEscapeWorld.create(915,2,emptyList(),2,2).copy(mobs=emptyList(),sites=emptyList())
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
