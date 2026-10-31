package com.jiacimu.lulu.games

import kotlin.math.abs
import kotlin.random.Random
import java.util.UUID

/** Every run builds a new, connected physical board, not a narrated map. */
internal object RelicEscapeWorld {
    fun create(seed:Int,level:Int,companions:List<String>,kits:Int,weapon:Int):RelicRun {
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
        val chestPositions=targets.dropLast(1)
        val chests=chestPositions.mapIndexed { index,(x,y)->
            val tier=when {
                index==count-1 && level>=3->RelicTier.PRECIOUS
                rng.nextInt(100)<(8+level*8)->RelicTier.PRECIOUS
                rng.nextInt(100)<(35+level*8)->RelicTier.RARE
                else->RelicTier.COMMON
            }
            RelicChest(index,x,y,tier)
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
        for(index in 0 until (2+level.coerceIn(1,5))) {
            val candidates=(1 until w-1).flatMap{x->(1 until h-1).map{y->x to y}}
                .filter{(x,y)->x to y in reachable && x to y !in occupied &&
                    abs(x-2)+abs(y-2)>4}
            if(candidates.isEmpty())break
            val (x,y)=candidates[rng.nextInt(candidates.size)]
            occupied+=x to y
            val kind=if(level>=2 && index==(2+level.coerceIn(1,5))-1)3
                else if(level<=1)index%2 else rng.nextInt(3)
            mobs+=RelicMob(index,x,y,if(kind==3)9+level else 2+kind*2+level/2,kind)
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
        val allies=companions.distinct().filter{it.isNotBlank()}.take(3).mapIndexed{index,id->
            val positions=listOf(3 to 2,2 to 3,3 to 3)
            val (x,y)=positions[index]
            RelicActor(id,x,y,kits=1)
        }
        val run=RelicRun(UUID.randomUUID().toString(),seed,level,w,h,cells,RelicPhase.EXPLORE,
            RelicActor("player",2,2,kits=kits),allies,chests,mobs,emptyList(),
            exit.first,exit.second,weapon=weapon,sites=sites,
            notice="深入遗迹。远处有一处绿色撤离信标。")
        return RelicEscapeRules.reveal(run).log("player","enter","进入第"+level+"层遗迹，同行"+allies.size+"人")
    }
}
