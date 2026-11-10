package com.jiacimu.lulu.games

/** Optional camp contracts, resolved only against physically secured items at extraction. */
internal enum class RelicContract(
    val label:String,val condition:String,val bonus:Int,
) {
    NONE("自由探索","不接委托",0),
    FIRST_HAUL("首件归仓","成功带回至少1件宝物",60),
    COLLECTOR("宝箱猎人","成功带回至少3件宝物",130),
    RARITY("稀有鉴定","成功带回至少1件稀有或珍贵宝物",160),
}

internal object RelicContractBoard {
    fun completed(run:RelicRun):Boolean {
        if(run.phase!=RelicPhase.SUCCESS)return false
        return when(run.contract) {
            RelicContract.NONE->false
            RelicContract.FIRST_HAUL->run.secured.isNotEmpty()
            RelicContract.COLLECTOR->run.secured.size>=3
            RelicContract.RARITY->run.secured.any{it.tier!=RelicTier.COMMON}
        }
    }
    /** Provisional contract progress is based on actual loot in real party bags.
     * It is never treated as success or paid until the party extracts it. */
    fun bagProgress(run:RelicRun):Pair<Int,Int>? {
        val held=when(run.phase) {
            RelicPhase.SUCCESS -> run.secured
            RelicPhase.EXPLORE,RelicPhase.EXTRACT,RelicPhase.DOWNED -> run.carried
            else -> emptyList()
        }
        return when(run.contract) {
            RelicContract.NONE -> null
            RelicContract.FIRST_HAUL -> (if(held.isNotEmpty())1 else 0) to 1
            RelicContract.COLLECTOR -> held.size.coerceAtMost(3) to 3
            RelicContract.RARITY -> (if(held.any { it.tier!=RelicTier.COMMON })1 else 0) to 1
        }
    }
    fun bonus(run:RelicRun):Int=if(completed(run))run.contract.bonus else 0
}
