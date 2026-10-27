package com.jiacimu.lulu

/**
 * Names, fits and suggested expression pairings for the shipped wolf motion
 * pack (LuluWolf group, indices 00..29). Selection only: the role picks an
 * existing group#index from this list; curves are never generated, and a
 * suggested expression pairing never forces the role to use that expression.
 * Touch triggers are advisory data for the touch-to-motion wiring: a touch
 * reflex stays deterministic and is never a model decision.
 * New pack files must keep using numbers after the existing maximum so the
 * group#index identity of every older clip stays stable.
 */
internal object Live2DMotionCatalog {
    data class Entry(
        val name: String,
        val fits: String,
        val expression: String? = null,
        val touch: String? = null,
    )

    internal val entries: Map<Int, Entry> = mapOf(
        0 to Entry("点头", "赞同、肯定、嗯时"),
        1 to Entry("左歪头", "疑惑、卖萌、歪头看你时", touch = "beast-ear:stroke"),
        2 to Entry("右歪头", "疑惑、卖萌、歪头看你时", touch = "beast-ear:stroke"),
        3 to Entry("前倾", "好奇、凑近听、认真听时"),
        4 to Entry("后仰", "大笑、后仰、放松时"),
        5 to Entry("左转头", "转头看、回避时"),
        6 to Entry("右转头", "转头看、回避时"),
        7 to Entry("连连点头", "连连赞同、嗯嗯时"),
        8 to Entry("摇头", "否定、不要、不同意时"),
        9 to Entry("受惊后缩", "受惊、吓一跳、后缩时", touch = "cheek:slap"),
        10 to Entry("左探头", "探头看、好奇张望时"),
        11 to Entry("右探头", "探头看、好奇张望时"),
        12 to Entry("害羞低头", "害羞、低头时", "脸红"),
        13 to Entry("抬下巴", "得意、骄傲、哼时"),
        14 to Entry("左摇晃", "摇晃、心情好、晃悠时"),
        15 to Entry("右摇晃", "摇晃、心情好、晃悠时"),
        16 to Entry("好奇点动", "好奇、点动、真的吗时"),
        17 to Entry("开心蹦跳", "开心、好耶、蹦跳时", "爱心眼", "chest:tap"),
        18 to Entry("缓慢重心移动", "放松、换姿势、慢慢晃时"),
        19 to Entry("咯咯笑晃动", "忍不住笑、咯咯笑时"),
        20 to Entry("难过垂头", "难过、低落、叹气时", "流泪"),
        21 to Entry("得意挺胸", "得意、骄傲时"),
        22 to Entry("害羞转开头", "害羞转头、不看你时", "脸红"),
        23 to Entry("惊讶一震", "惊讶、诶、真的假的时", "星星眼", "nose:tap"),
        24 to Entry("困意摇晃", "困了、迷迷糊糊时"),
        25 to Entry("兴奋小抖动", "兴奋、期待时", "爱心眼"),
        26 to Entry("思考抬眼", "思考、琢磨、让我想想时", touch = "forehead:stroke"),
        27 to Entry("害羞歪头低头", "娇羞、不好意思笑时", "脸红"),
        28 to Entry("温柔靠近", "温柔、安慰、靠近你时", "爱心眼", "cheek:stroke"),
        29 to Entry("调皮闪躲", "调皮、躲开、抓不到我时"),
    )

    fun describe(group: String, index: Int): String? {
        if (group != "LuluWolf") return null
        val entry = entries[index] ?: return null
        return buildString {
            append("$group#$index=${entry.name}（${entry.fits}）")
            entry.expression?.let { append("；配$it") }
        }
    }
}
