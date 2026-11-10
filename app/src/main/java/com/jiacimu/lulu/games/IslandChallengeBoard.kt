package com.jiacimu.lulu.games

/**
 * Optional physical milestones. They are paid only after valid player turns, never when
 * a menu recomposes or a character merely promises to do something.
 */
internal data class IslandChallenge(
    val id:String,
    val title:String,
    val detail:String,
    val rewardText:String,
    val wood:Int=0,
    val stone:Int=0,
    val fruit:Int=0,
    val fish:Int=0,
    val completed:(BlockIslandState)->Boolean,
)

internal object IslandChallengeBoard {
    val challenges=listOf(
        IslandChallenge("craft","第一件冒险装备","建好工作台并制作任意工具",
            "野果 +2",fruit=2) { s ->
            s.tools.isNotEmpty() && s.structures.values.any{it=="workbench"}
        },
        IslandChallenge("supply","海岸探宝人","打开第一只野外补给箱",
            "木材 +2、石料 +2",wood=2,stone=2) { s ->
            s.openedSupplies.isNotEmpty()
        },
        IslandChallenge("friends","动物的朋友","结识两种不同的小动物",
            "野果 +3、小鱼 +2",fruit=3,fish=2) { s ->
            s.animals.filter{it.befriended}.map{it.species}.distinct().size>=2
        },
        IslandChallenge("home","有灯光的家","建好宠物小屋与夜灯",
            "木材 +4、石料 +2",wood=4,stone=2) { s ->
            s.structures.values.containsAll(listOf("pet_house","lantern"))
        },
    ) + IslandExpeditionTrail.landmarks.map { place ->
        IslandChallenge(
            id=place.id,
            title=place.title,
            detail=place.description,
            rewardText=place.rewardText,
            wood=place.wood,stone=place.stone,
            fruit=place.fruit,fish=place.fish,
        ) { s -> IslandExpeditionTrail.reached(s,place) }
    }
    val ids=challenges.map{it.id}.toSet()

    fun awardCompleted(s:BlockIslandState):BlockIslandState {
        val earned=challenges.filter{it.id !in s.awardedChallenges && it.completed(s)}
        if(earned.isEmpty())return s
        val messages=earned.map{"★ 挑战「${it.title}」完成！${it.rewardText}"}
        return s.copy(
            awardedChallenges=s.awardedChallenges+earned.map{it.id},
            fruit=s.fruit+earned.sumOf{it.fruit},
            fish=s.fish+earned.sumOf{it.fish},
            world=s.world.copy(
                wood=s.world.wood+earned.sumOf{it.wood},
                stone=s.world.stone+earned.sumOf{it.stone},
                notice=s.world.notice+" · "+messages.joinToString(" · "),
                events=(s.world.events+messages).takeLast(150),
            ),
        )
    }
}
