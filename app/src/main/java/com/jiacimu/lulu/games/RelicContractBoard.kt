package com.jiacimu.lulu.games

/** Optional camp contracts, resolved only against physically secured items at extraction. */
internal enum class RelicContract(
    val label:String,val condition:String,val bonus:Int,
) {
    NONE("自由探索","不接委托",0),
    FIRST_HAUL("首件归仓","成功带回至少1件宝物",60),
    COLLECTOR("宝箱猎人","成功带回至少3件宝物",130),
    RARITY("稀有鉴定","成功带回至少1件稀有或珍贵宝物",160),
    MASTER("双珍寻踪","带回2件珍贵或更高宝物，并击败至少2只守卫",420),
    ABYSS("绝境秘藏","险境难度：带回2件珍贵或更高宝物（含封印之心），击败至少3只守卫",980),
}

internal object RelicContractBoard {
    fun completed(run:RelicRun):Boolean {
        if(run.phase!=RelicPhase.SUCCESS)return false
        return when(run.contract) {
            RelicContract.NONE->false
            RelicContract.FIRST_HAUL->run.secured.isNotEmpty()
            RelicContract.COLLECTOR->run.secured.size>=3
            RelicContract.RARITY->run.secured.any{it.tier!=RelicTier.COMMON}
            RelicContract.MASTER->run.secured.count { it.tier==RelicTier.PRECIOUS }>=2 &&
                run.mobs.count { it.hp<=0 }>=2
            RelicContract.ABYSS->run.difficulty==RelicDifficulty.HARD &&
                run.secured.count { it.tier==RelicTier.PRECIOUS }>=2 &&
                run.secured.any { it.name=="封印之心" } &&
                run.mobs.count { it.hp<=0 }>=3
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
            RelicContract.MASTER -> held.count { it.tier==RelicTier.PRECIOUS }.coerceAtMost(2) to 2
            RelicContract.ABYSS -> held.count { it.tier==RelicTier.PRECIOUS }.coerceAtMost(2) to 2
        }
    }
    /** All counters are observed from persisted world facts, not narrated victory text. */
    fun status(run:RelicRun):String {
        val held=if(run.phase==RelicPhase.SUCCESS)run.secured else run.carried
        val kills=run.mobs.count { it.hp<=0 }
        val precious=held.count { it.tier==RelicTier.PRECIOUS }
        return when(run.contract) {
            RelicContract.MASTER -> "珍贵 ${precious.coerceAtMost(2)}/2 · 击败 ${kills.coerceAtMost(2)}/2"
            RelicContract.ABYSS -> "珍贵 ${precious.coerceAtMost(2)}/2 · 击败 ${kills.coerceAtMost(3)}/3 · 封印之心 "+
                (if(held.any { it.name=="封印之心" })"✓" else "未取得")+
                (if(run.difficulty==RelicDifficulty.HARD)"" else " · 需险境难度")
            else -> bagProgress(run)?.let { "${it.first}/${it.second}" } ?: "自由探索"
        }
    }
    fun bonus(run:RelicRun):Int=if(completed(run))run.contract.bonus else 0
}
