package com.jiacimu.lulu.games

/**
 * Rendering-only atlas selection. Large, coherent moss patches look like a
 * living coastline; unrelated per-tile noise looks like an unpolished
 * checkerboard. The same saved physical tiles still control every collision,
 * gathering and building action.
 */
internal object IslandTerrainPalette {
    fun atlasFrame(kind:Int,x:Int,y:Int,seed:Int):Int = when(kind) {
        8 -> 2 // water
        4 -> 4 // wooden construction ground
        3 -> 3 // stone paths
        7 -> 5 // the sandy starter camp
        else -> {
            // A restrained, reproducible 5x5 biome patch. Most of the
            // landscape remains quiet grass, with isolated cooler moss zones.
            val regionX=x/5
            val regionY=y/5
            val regionHash=((regionX*73856093) xor
                (regionY*19349663) xor seed) and Int.MAX_VALUE
            if(regionHash%7==0)1 else 0
        }
    }
}
