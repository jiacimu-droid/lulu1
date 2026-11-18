package com.jiacimu.lulu.games

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// Review layouts feed the same production state and action rules as the regular map.
internal object ArcadeMaps {
    fun coast(seed: Int, partners: List<String>): BlockIslandState {
        val width = 35
        val height = 34
        val rng = Random(seed)
        val board = MutableList(width * height) { i ->
            val x = i % width
            val y = i / width
            val coast = ((x - 16) / 16.0).pow(2) + ((y - 15) / 15.0).pow(2)
            val bay = ((x - 5) / 5.0).pow(2) + ((y - 16) / 4.5).pow(2)
            when {
                x < 2 || x > 32 || y < 2 || y > 31 || coast > 1.1 + .08 * sin(x * .8 + y * .37) || bay < .93 -> 8
                x in 7..13 && y in 11..17 -> 0
                x in 17..28 && y in 6..16 -> if (rng.nextInt(100) < 35) 1 else 0
                x in 19..29 && y in 19..27 -> if (rng.nextInt(100) < 29) 2 else 0
                else -> when (rng.nextInt(100)) { in 0..11 -> 1; in 12..18 -> 2; else -> 0 }
            }
        }
        fun tile(x: Int, y: Int, value: Int) { board[y * width + x] = value }
        // Short loop around the bay and through the forest. Three routes lead to the cave and beacon.
        for (x in 6..27) for (y in 10..11) tile(x, y, 0)
        for (y in 6..24) for (x in 13..14) tile(x, y, 0)
        for (x in 12..27) for (y in 20..21) tile(x, y, 0)
        for (y in 12..22) tile(24, y, 0)
        for (y in 24..27) for (x in 22..25) tile(x, y, 0)
        for ((x,y) in listOf(10 to 8, 9 to 9, 11 to 9, 12 to 8, 8 to 7, 10 to 7, 14 to 9, 15 to 8)) tile(x,y,1)
        for ((x,y) in listOf(12 to 11, 12 to 12, 15 to 12, 15 to 7, 15 to 10, 21 to 25, 25 to 25)) tile(x,y,2)
        tile(10, 11, 7)
        tile(7, 15, 8)
        tile(8, 15, 0)
        tile(24, 23, 0)
        tile(27, 8, 3)
        val w = ExpeditionState(ExpeditionMode.HOMESTEAD,seed,width,height,board,10,11,
            friends=partners.take(3).mapIndexed { index, id -> ExpeditionFriend(id,8+index,13) },
            mobs=listOf(ExpeditionMob(1,20,21,4),ExpeditionMob(2,24,25,5)),
            notice="到岸了。可以沿沙径采木石，或走到灯塔与洞口。")
        val initial = BlockIslandState(w, fruit=2)
        val caches = IslandSupplyCaches.generate(initial).filter { index ->
            val x=index%width; val y=index/width
            x !in 7..15 || y !in 11..17
        }
        val prepared = initial.copy(supplyCaches=caches)
        return prepared.copy(animals=IslandWildlife.generate(prepared).filter {
            abs(it.x-10)+abs(it.y-11)>6
        })
    }

    fun cloister(base: RelicRun, ambush: Boolean): RelicRun {
        val w=23; val h=17
        val cells=MutableList(w*h){1}
        fun room(x1:Int,y1:Int,x2:Int,y2:Int){ for(y in y1..y2)for(x in x1..x2)cells[y*w+x]=0 }
        room(1,2,6,6)     // approach and offering room
        room(3,6,4,14)    // outer cloister
        room(4,12,13,14)  // low-risk extraction route
        room(6,3,12,4)    // bell gallery
        room(10,3,15,8)   // atrium
        room(11,7,12,12)  // lift corridor
        room(14,7,20,8)   // causeway
        room(17,4,21,12)  // vault
        room(15,11,18,13) // side passage to the paired gate
        for ((x,y) in listOf(15 to 7, 16 to 7, 17 to 7, 17 to 10)) cells[y*w+x]=3
        cells[13*w+14]=1
        val chests=listOf(RelicChest(0,4,4,RelicTier.COMMON,grade=1),
            RelicChest(1,13,5,RelicTier.RARE,grade=3),RelicChest(2,20,6,RelicTier.PRECIOUS,grade=4),
            RelicChest(3,20,11,RelicTier.PRECIOUS,grade=5))
        val mobs=buildList {
            if (ambush) add(RelicMob(90,3,7,5,0,alert=4))
            add(RelicMob(1,14,6,6+base.difficulty.monsterHp,1))
            if (base.difficulty!=RelicDifficulty.EASY) add(RelicMob(2,19,7,8+base.difficulty.monsterHp,2))
            if (base.difficulty==RelicDifficulty.HARD) add(RelicMob(3,18,11,9+base.difficulty.monsterHp,2))
        }
        val sites=listOf(RelicSite(0,9,3,"alarm"),RelicSite(1,12,9,"echo"),
            RelicSite(2,11,6,"lens"),RelicSite(3,13,13,"seal_switch",linkId=4),
            RelicSite(4,14,13,"seal_gate",linkId=3),RelicSite(5,20,9,"altar"),
            RelicSite(6,9,13,"supply"),RelicSite(7,19,8,"trap"))
        val run=base.copy(w=w,h=h,tiles=cells,hero=base.hero.copy(x=3,y=4),
            allies=base.allies.mapIndexed { index, a -> a.copy(x=2+index,y=3) },
            chests=chests,mobs=mobs,exitX=6,exitY=13,seen=emptySet(),sites=sites,
            notice="进了回声遗迹。近处有一只箱子，外廊通向撤离门。",
            events=emptyList())
        return RelicEscapeRules.reveal(run)
    }
}
