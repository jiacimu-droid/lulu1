package com.jiacimu.lulu.games

/**
 * Permanent, deterministic museum sets grounded in the same collectible
 * catalogue and the same successfully extracted discovered-name set.
 * Zero daily chores, background calls or shadow achievements.
 */
internal data class RelicCollectionGoal(
    val id:String,val title:String,val story:String,val names:List<String>,
) {
    fun owned(discovered:Set<String>):Int=names.count { it in discovered }
    fun complete(discovered:Set<String>):Boolean=owned(discovered)==names.size
    fun missing(discovered:Set<String>):List<String> = names.filterNot { it in discovered }
}
internal object RelicCollectionGoals {
    val all=listOf(
        RelicCollectionGoal("moon","月色私藏","找齐属于月亮的首饰与流光",
            listOf("月银胸针","月蚀吊坠","暮光戒指","猫眼胸章")),
        RelicCollectionGoal("stars","星穹遗产","寻回失落王室的星象器物",
            listOf("破损星图","星穹冠冕","黄金天球仪","星河沙漏","黎明权杖")),
        RelicCollectionGoal("sound","旧梦余音","留住遗迹里尚未散去的声音",
            listOf("旅人哨笛","琥珀音匣","冰魄竖琴")),
        RelicCollectionGoal("guardian","守卫战利品","四类守卫都有各自留下的实物",
            listOf("暗影碎片","锻铁徽片","祭司秘纹","哨兵之核")),
        RelicCollectionGoal("forbidden","禁地双心","为珍稀祭坛与隐藏秘宝冒一次险",
            listOf("封印之心",RelicCollectibles.CROWN_JEWEL)),
    )
    fun completed(discovered:Set<String>)=all.count { it.complete(discovered) }
}
