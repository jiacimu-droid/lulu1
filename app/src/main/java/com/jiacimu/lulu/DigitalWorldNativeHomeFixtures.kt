package com.jiacimu.lulu

import com.jiacimu.lulu.data.DigitalWorldItem
import com.jiacimu.lulu.data.DigitalWorldResident

/**
 * Every registered NPC home has a fixed, reproducible base interior.
 * Items are derived from the persisted resident id/creation time, never from random narration.
 * Native residents own these fixtures; they are not player-editable furniture.
 */
internal fun nativeResidentHomeProps(resident: DigitalWorldResident): List<DigitalRoomProp> {
    val owner = "npc:${resident.id}"
    val at = resident.createdAt
    val fixtures = listOf(
        DigitalWorldItem("native-shelf-${resident.id}", owner, "shelf", "原木书架",
            "立在房间左侧墙边的书架", "房间左侧靠墙", at, at),
        DigitalWorldItem("native-desk-${resident.id}", owner, "desk", "书桌",
            "靠窗的木质书桌", "靠窗", at, at),
        DigitalWorldItem("native-chair-${resident.id}", owner, "chair", "靠背椅",
            "和书桌配套的木椅", "窗边", at, at),
        DigitalWorldItem("native-plant-${resident.id}", owner, "plant", "绿植",
            "角落里的一盆绿植", "房间角落", at, at),
    )
    return buildDigitalRoomProps(fixtures)
}
