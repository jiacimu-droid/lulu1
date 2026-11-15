package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[33])
class RelicCombatSkillsTest {
    private fun scene(vararg enemies:RelicMob):RelicRun {
        val base=RelicEscapeWorld.create(6164,1,emptyList(),2,1)
        return RelicEscapeRules.reveal(base.copy(
            tiles=List(base.w*base.h){0},
            mobs=enemies.toList(),
            chests=emptyList(),sites=emptyList(),allies=emptyList(),
            seen=(0 until base.w*base.h).toSet(),
            equipped="blade",weapons=setOf("blade","spear","bow"),
            bolts=5,
        ))
    }

    @Test fun bladeAttacksTheDirectionFirstThenAnyAdjacentEnemyWithoutMapSelection() {
        val b=scene()
        val h=b.hero
        val left=RelicMob(101,h.x-1,h.y,5,0)
        val right=RelicMob(102,h.x+1,h.y,5,1,windup=true)
        val s=b.copy(mobs=listOf(left,right))
        assertEquals(left.id,RelicCombatSkills.attackTarget(s,-1 to 0)?.id)
        assertEquals(right.id,RelicCombatSkills.attackTarget(s,1 to 0)?.id)
        // Pointing at empty north is not a reason for the attack button to fail.
        assertEquals(right.id,RelicCombatSkills.attackTarget(s,0 to -1)?.id)
        val next=RelicCombatSkills.attackTarget(s,0 to -1)!!
        val hit=RelicEscapeRules.act(s,"attack",next.x,next.y)
        assertEquals(s.turns+1,hit.turns)
        assertTrue(hit.mobs.first{it.id==right.id}.hp<right.hp)
    }

    @Test fun spearAndBowOnlyShootFacingLaneNotOtherEnemiesOrAcrossWalls() {
        val b=scene()
        val h=b.hero
        val north=RelicMob(110,h.x,h.y-2,7,0)
        val east=RelicMob(111,h.x+1,h.y,7,0)
        val spear=b.copy(mobs=listOf(north,east),equipped="spear")
        assertEquals(north.id,RelicCombatSkills.attackTarget(spear,0 to -1)?.id)
        assertEquals(east.id,RelicCombatSkills.attackTarget(spear,1 to 0)?.id)
        assertNull(RelicCombatSkills.attackTarget(spear,0 to 1))
        // The bow has a longer lane, not auto-aim at a selected map monster.
        val farther=RelicMob(112,h.x,h.y+4,7,0)
        val bow=spear.copy(mobs=listOf(farther,east),equipped="bow")
        assertEquals(farther.id,RelicCombatSkills.attackTarget(bow,0 to 1)?.id)
        assertNull(RelicCombatSkills.attackTarget(bow,0 to -1))
        assertNull(RelicCombatSkills.attackTarget(bow.copy(bolts=0),0 to 1))
        // A stone tile blocks the full ray, including special attacks.
        val blockedTiles=b.tiles.toMutableList().apply{this[b.index(h.x,h.y+2)]=1}
        val blocked=bow.copy(tiles=blockedTiles)
        assertNull(RelicCombatSkills.attackTarget(blocked,0 to 1))
        assertTrue(RelicCombatSkills.weaponSkillTargets(blocked,0 to 1).isEmpty())
    }

    @Test fun spearPierceHitsTwoInLineWithRealTurnCooldownAndWorldPersistence() {
        val b=scene()
        val h=b.hero
        val first=RelicMob(120,h.x+1,h.y,8,0)
        val second=RelicMob(121,h.x+2,h.y,8,1)
        val outside=RelicMob(122,h.x,h.y+1,8,0)
        val spear=b.copy(equipped="spear",mobs=listOf(first,second,outside))
        assertEquals(first.id,RelicCombatSkills.attackTarget(spear,1 to 0)?.id)
        assertEquals(listOf(first.id,second.id),
            RelicCombatSkills.weaponSkillTargets(spear,1 to 0).map{it.id})
        assertTrue(RelicCombatSkills.canWeaponSkill(spear,1 to 0))
        val hit=RelicEscapeRules.act(spear,"weapon_skill",1,0)
        assertEquals(spear.turns+1,hit.turns)
        assertEquals(3,hit.sweepReadyTurn)
        assertTrue(hit.mobs.first{it.id==first.id}.hp<first.hp)
        assertTrue(hit.mobs.first{it.id==second.id}.hp<second.hp)
        assertEquals(outside.hp,hit.mobs.first{it.id==outside.id}.hp)
        val restored=RelicRun.read(hit.json())!!
        val retry=RelicEscapeRules.act(restored,"weapon_skill",1,0)
        assertEquals(restored.turns,retry.turns)
        assertEquals(restored.mobs,retry.mobs)
    }

    @Test fun bowPiercingCostsTwoRealArrowsAndCannotPierceUnknownFog() {
        val b=scene()
        val h=b.hero
        val first=RelicMob(130,h.x+1,h.y,2,0)
        val second=RelicMob(131,h.x+3,h.y,2,0)
        val bow=b.copy(equipped="bow",mobs=listOf(first,second),bolts=2)
        assertTrue(RelicCombatSkills.canWeaponSkill(bow,1 to 0))
        val hit=RelicEscapeRules.act(bow,"weapon_skill",1,0)
        assertEquals(0,hit.bolts)
        assertEquals(2,hit.drops.count{it.loot.id.startsWith(hit.id+":mob:")})
        assertFalse(RelicCombatSkills.canWeaponSkill(hit,1 to 0))
        val fog=bow.copy(seen=bow.seen-setOf(b.index(h.x+2,h.y)))
        assertEquals(1,RelicCombatSkills.weaponSkillTargets(fog,1 to 0).size)
        val noAmmo=bow.copy(bolts=1)
        assertFalse(RelicCombatSkills.canWeaponSkill(noAmmo,1 to 0))
        assertEquals(noAmmo.turns,RelicEscapeRules.act(noAmmo,"weapon_skill",1,0).turns)
    }

    @Test fun professionsProvideDistinctSkillsWithRealCooldownAndOldSaveCompatibility() {
        val b=scene()
        val h=b.hero
        val guard=b.copy(profession=RelicProfession.GUARDIAN,
            hero=h.copy(hp=5))
        val guarded=RelicEscapeRules.act(guard,"profession_skill")
        assertEquals(guard.turns+1,guarded.turns)
        assertEquals(7,guarded.hero.hp)
        assertEquals(4,guarded.professionReadyTurn)
        assertEquals(2,guarded.professionCharges)
        assertFalse(RelicCombatSkills.professionAvailable(guarded))
        val restored=RelicRun.read(guarded.json())!!
        assertEquals(RelicProfession.GUARDIAN,restored.profession)
        assertEquals(4,restored.professionReadyTurn)
        assertEquals(2,restored.professionCharges)
        assertEquals(restored.turns,RelicEscapeRules.act(restored,"profession_skill").turns)

        val medic=b.copy(profession=RelicProfession.MEDIC,
            hero=h.copy(hp=4,kits=0))
        val healed=RelicEscapeRules.act(medic,"profession_skill")
        assertEquals(8,healed.hero.hp)
        assertEquals(0,healed.hero.kits)
        assertEquals(2,healed.professionCharges)
        assertFalse(RelicCombatSkills.professionAvailable(
            medic.copy(hero=h.copy(hp=10))))

        val scout=b.copy(profession=RelicProfession.SCOUT,
            seen=setOf(b.index(h.x,h.y)))
        val explored=RelicEscapeRules.act(scout,"profession_skill")
        assertTrue(explored.seen.size>scout.seen.size)
        assertEquals(4,explored.professionReadyTurn)
        assertEquals(2,explored.professionCharges)
        assertEquals(0,scout.professionReadyTurn)
        val exhausted=medic.copy(professionCharges=0)
        assertFalse(RelicCombatSkills.professionAvailable(exhausted))
        assertTrue(RelicCombatSkills.professionHint(exhausted).contains("用完"))
        assertEquals(exhausted.turns,RelicEscapeRules.act(exhausted,"profession_skill").turns)

        val legacy=org.json.JSONObject(b.json().toString())
            .apply{remove("profession");remove("professionReadyTurn")}
        assertEquals(RelicProfession.SCOUT,RelicRun.read(legacy)!!.profession)
    }
}
