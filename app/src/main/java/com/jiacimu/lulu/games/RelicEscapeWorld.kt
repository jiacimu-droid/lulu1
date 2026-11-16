package com.jiacimu.lulu.games

import kotlin.math.abs
import kotlin.random.Random
import java.util.UUID

/**
 * Difficulty is about a readable *progression*, not just bigger HP numbers.
 * First floor standard is a learning expedition; higher levels graduate from
 * separated small encounters to denser groups. No existing save is mutated.
 */
internal object RelicThreatProgression {
    fun monsterCount(level:Int, difficulty:RelicDifficulty):Int {
        val stage=level.coerceIn(1,5)
        val standard=if(stage<=2) 2 else stage
        return (standard+difficulty.extraMobs).coerceAtLeast(1)
    }
    fun safeSpawnDistance(difficulty:RelicDifficulty):Int =
        if(difficulty==RelicDifficulty.HARD) 5 else 8
    fun separation(difficulty:RelicDifficulty):Int =
        if(difficulty==RelicDifficulty.HARD) 3 else 5
    fun eliteUnlocked(level:Int,difficulty:RelicDifficulty):Boolean =
        when(difficulty) {
            RelicDifficulty.EASY -> level>=4
            RelicDifficulty.NORMAL -> level>=3
            RelicDifficulty.HARD -> level>=1
        }
}

/** Every run builds a new, connected physical board, not a narrated map. */
internal object RelicEscapeWorld {
    fun create(seed:Int,level:Int,companions:List<String>,kits:Int,weapon:Int,satchelLevel:Int=0,
        difficulty:RelicDifficulty=RelicDifficulty.NORMAL,
        purchasedWeapons:Set<String> = setOf("blade"),startingWeapon:String="blade"):RelicRun {
        val rng=Random(seed)
        val w=18;val h=15
        val cells=MutableList(w*h){i->
            val x=i%w;val y=i/w
            if(x==0 || y==0 || x==w-1 || y==h-1)1
            else when(rng.nextInt(100)){in 0..19->1;in 20..26->2;in 27..33->3;else->0}
        }
        fun carve(x:Int,y:Int){if(x in 1 until w-1 && y in 1 until h-1)cells[y*w+x]=0}
        for(y in 1..4)for(x in 1..5)carve(x,y)
        val targets=mutableListOf<Pair<Int,Int>>()
        val count=5+(level/2).coerceAtMost(2)
        repeat(count+1) {
            var p:Pair<Int,Int>
            var tries=0
            do {
                p=rng.nextInt(3,w-2) to rng.nextInt(2,h-2)
                tries++
            } while(tries<250 && (targets.any{abs(it.first-p.first)+abs(it.second-p.second)<4}
                || abs(p.first-2)+abs(p.second-2)<6))
            targets+=p
        }
        val exit=targets.last()
        // Guaranteed routes from spawn to each landmark, while intervening rooms remain random.
        for((tx,ty) in targets) {
            var x=2;var y=2
            val horizontalFirst=rng.nextBoolean()
            if(horizontalFirst) {
                while(x!=tx){x+=if(tx>x)1 else -1;carve(x,y)}
                while(y!=ty){y+=if(ty>y)1 else -1;carve(x,y)}
            } else {
                while(y!=ty){y+=if(ty>y)1 else -1;carve(x,y)}
                while(x!=tx){x+=if(tx>x)1 else -1;carve(x,y)}
            }
            carve(tx,ty)
            for((dx,dy) in listOf(1 to 0,-1 to 0,0 to 1,0 to -1))
                if(rng.nextBoolean())carve(tx+dx,ty+dy)
        }
        // Three seeded, connected silhouettes rather than a uniform shuffle of
        // isolated floor tiles. These are playable rooms, not just recolours.
        // Existing guaranteed passages remain open in every layout.
        when(((seed xor (level*127)) and Int.MAX_VALUE)%3) {
            0 -> { // broad central atrium, with open sight lines
                for(yy in 5..9)for(xx in 7..11)carve(xx,yy)
                for(xx in 5..13)carve(xx,7)
            }
            1 -> { // two gallery wings joined by a transversal
                for(xx in 3..14) {
                    carve(xx,4)
                    carve(xx,10)
                }
                for(yy in 4..10)carve(8,yy)
            }
            else -> { // clustered vault chambers and offset connecting corridors
                for(yy in 3..5)for(xx in 10..13)carve(xx,yy)
                for(yy in 9..11)for(xx in 4..7)carve(xx,yy)
                for(xx in 7..10)carve(xx,5)
                for(yy in 5..9)carve(7,yy)
            }
        }
        val chestPositions=targets.dropLast(1)
        val chests=chestPositions.mapIndexed { index,(x,y)->
            // Real seven-colour chest catalogue: common to prismatic, with
            // higher floors and danger improving the odds, never a fixed payout.
            val roll=rng.nextInt(100)
            val baseline=when {
                roll<23->0
                roll<46->1
                roll<67->2
                roll<83->3
                roll<94->4
                roll<99->5
                else->6
            }
            val boost=(if(level>=3 && index==count-1)1 else 0)+
                (if(difficulty==RelicDifficulty.HARD && rng.nextInt(100)<20)1 else 0)
            val grade=(baseline+boost).coerceAtMost(6)
            RelicChest(index,x,y,RelicQuality.at(grade).tier,grade=grade)
        }
        // Only populate connected walkable cells, otherwise enemies/side objectives can
        // spawn in sealed-off pockets and become impossible to reach.
        val reachable=mutableSetOf(2 to 2)
        val frontier=java.util.ArrayDeque<Pair<Int,Int>>()
        frontier.add(2 to 2)
        while(frontier.isNotEmpty()) {
            val (cx,cy)=frontier.removeFirst()
            for((nx,ny) in listOf(cx+1 to cy,cx-1 to cy,cx to cy+1,cx to cy-1)) {
                if(nx !in 1 until w-1 || ny !in 1 until h-1 ||
                    cells[ny*w+nx]==1 || !reachable.add(nx to ny))continue
                frontier.add(nx to ny)
            }
        }
        val occupied=(chestPositions+exit+(2 to 2)).toMutableSet()
        val mobs=mutableListOf<RelicMob>()
        val monsterCount=RelicThreatProgression.monsterCount(level,difficulty)
        val entrySafety=RelicThreatProgression.safeSpawnDistance(difficulty)
        val preferredSpacing=RelicThreatProgression.separation(difficulty)
        val spawnCells=(1 until w-1).flatMap{x->(1 until h-1).map{y->x to y}}
            .filter{(x,y)->x to y in reachable && x to y !in occupied &&
                abs(x-2)+abs(y-2)>entrySafety}
        for(index in 0 until monsterCount) {
            // Distribute early encounters. When the late floors are crowded,
            // relax inter-enemy spacing but never breach the safe entry radius.
            val spaced=spawnCells.filter { (x,y) -> x to y !in occupied &&
                mobs.all { abs(it.x-x)+abs(it.y-y)>preferredSpacing } }
            val options=if(spaced.isNotEmpty())spaced
                else spawnCells.filter { it !in occupied }
            if(options.isEmpty())break
            val (x,y)=options[rng.nextInt(options.size)]
            occupied+=x to y
            val kind=if(RelicThreatProgression.eliteUnlocked(level,difficulty) &&
                index==monsterCount-1)3
                else if(level<=1)index%2 else rng.nextInt(3)
            mobs+=RelicMob(index,x,y,
                (if(kind==3)12+level*2 else 7+kind*3+level)+difficulty.monsterHp,kind)
        }
        // Optional danger rooms and one-use traps are world entities, not narrative rewards.
        val possibleSites=(2 until w-2).flatMap { x->(2 until h-2).map{y->x to y} }
            .filter{(x,y)->cells[y*w+x]==0 && x to y in reachable && x to y !in occupied &&
                abs(x-2)+abs(y-2)>=5}
            .shuffled(rng)
        val sites=mutableListOf<RelicSite>()
        val altar=possibleSites.firstOrNull()
        if(altar!=null) {
            occupied+=altar
            sites+=RelicSite(0,altar.first,altar.second,"altar")
        }
        val traps=possibleSites.filter{it !in occupied}.take((1+level/2).coerceAtMost(4))
        traps.forEachIndexed { index,(x,y)->sites+=RelicSite(index+1,x,y,"trap") }
        occupied+=traps
        possibleSites.firstOrNull { it !in occupied }?.let { (x,y)->
            occupied+=x to y
            sites+=RelicSite(sites.size,x,y,"supply")
        }
        possibleSites.firstOrNull { it !in occupied }?.let { (x,y)->
            occupied+=x to y
            sites+=RelicSite(sites.size,x,y,"fountain")
        }
        // Optional route-choice landmark, never a fake map annotation:
        // taking it down costs a tool but prevents extra pursuit after repeat looting.
        possibleSites.firstOrNull { it !in occupied }?.let { (x,y)->
            occupied+=x to y
            sites+=RelicSite(sites.size,x,y,"alarm")
        }
        // Exploration landmarks have complementary rules, not decorative loot:
        // a lens reveals real unknown tiles, an echo plate punishes rushing but
        // can be crossed silently, and a reachable switch opens a wall shortcut.
        val lens=possibleSites.filter { it !in occupied &&
            abs(it.first-2)+abs(it.second-2) in 5..10 }
            .minByOrNull { abs(abs(it.first-2)+abs(it.second-2)-7) }
        if(lens!=null) {
            occupied+=lens
            sites+=RelicSite(sites.size,lens.first,lens.second,"lens")
        }
        val echo=possibleSites.filter { it !in occupied &&
            abs(it.first-2)+abs(it.second-2)>=9 &&
            chests.any { chest ->
                abs(chest.x-it.first)+abs(chest.y-it.second) in 2..6
            } }.firstOrNull()
        if(echo!=null) {
            occupied+=echo
            sites+=RelicSite(sites.size,echo.first,echo.second,"echo")
        }
        // A sealed gate is an *existing wall* between two already reachable
        // passages. It never closes a guaranteed route or traps a chest.
        // The switch is on the walkable side and has its own persistent ID.
        val gatedWalls=(3 until w-3).flatMap { x ->
            (3 until h-3).map { y -> x to y }
        }.filter { (gx,gy) ->
            cells[gy*w+gx]==1 && abs(gx-2)+abs(gy-2)>7 &&
                (gx-1 to gy in reachable && gx+1 to gy in reachable ||
                    gx to gy-1 in reachable && gx to gy+1 in reachable) &&
                chests.any { chest -> abs(chest.x-gx)+abs(chest.y-gy)<=7 }
        }.shuffled(rng)
        val pairedGate=gatedWalls.firstNotNullOfOrNull { (gx,gy) ->
            val sides=listOf(gx-1 to gy,gx+1 to gy,gx to gy-1,gx to gy+1)
            sides.firstOrNull { it in reachable && it !in occupied &&
                sites.none { site -> !site.spent && site.x==it.first && site.y==it.second } }
                ?.let { (gx to gy) to it }
        }
        if(pairedGate!=null) {
            val gate=pairedGate.first
            val switch=pairedGate.second
            val gateId=sites.size
            sites+=RelicSite(gateId,gate.first,gate.second,"seal_gate",linkId=gateId+1)
            sites+=RelicSite(gateId+1,switch.first,switch.second,
                "seal_switch",linkId=gateId)
            occupied+=gate
            occupied+=switch
        }
        val allies=companions.distinct().filter{it.isNotBlank()}.take(3).mapIndexed{index,id->
            val positions=listOf(3 to 2,2 to 3,3 to 3)
            val (x,y)=positions[index]
            RelicActor(id,x,y,kits=1)
        }
        val loadout=(purchasedWeapons+"blade"+if(weapon>=2)setOf("spear") else emptySet())
            .filter{it in RelicArsenal.weapons}.toSet()
        val run=RelicRun(UUID.randomUUID().toString(),seed,level,w,h,cells,RelicPhase.EXPLORE,
            RelicActor("player",2,2,kits=kits),allies,chests,mobs,emptyList(),
            exit.first,exit.second,weapon=weapon,sites=sites,satchelLevel=satchelLevel.coerceIn(0,2),
            weapons=loadout,equipped=startingWeapon.takeIf{it in loadout} ?: "blade",
            notice="深入遗迹。远处有一处绿色撤离信标。",difficulty=difficulty)
        return RelicEscapeRules.reveal(run).log("player","enter","进入第"+level+"层遗迹，同行"+allies.size+"人")
    }
}
