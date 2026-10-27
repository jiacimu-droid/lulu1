package com.jiacimu.lulu

/**
 * Names, fits, suggested expression pairings and default face presets for
 * the shipped wolf motion pack (LuluWolf group, indices 00..29).
 *
 * Selection only: the role picks an existing group#index from this list;
 * curves are never generated, and a suggested expression pairing never
 * forces the role to use that expression. A motion's face preset is its
 * combined performance's default face: the bus applies it only when the
 * role did not specify a face, and explicit values always win. Face values
 * mirror the feeling poses in Live2DConversationPerformance and only use
 * real faceChannels keys, so a motion never invents a new parameter.
 * Positional motions (nod, turn, peek, sway) intentionally carry no face:
 * forcing a smile onto a plain nod would be a second decision, not a
 * combination.
 *
 * Touch triggers are advisory data for the touch-to-motion wiring: a touch
 * reflex stays deterministic and is never a model decision.
 *
 * New pack files must keep using numbers after the existing maximum so the
 * group#index identity of every older clip stays stable.
 */
internal object Live2DMotionCatalog {
    data class Entry(
        val name: String,
        val fits: String,
        val expression: String? = null,
        val touch: String? = null,
        val face: Map<String, Float> = emptyMap(),
        val faceNote: String? = null,
    )

    private val curiousFace = mapOf(
        "gazeY" to 0.06f, "eyeOpen" to 1.0f, "browY" to 0.28f,
        "browAngle" to 0.08f, "smile" to 0.05f,
    )
    private val amusedFace = mapOf(
        "smile" to 0.72f, "eyeSmile" to 0.68f, "eyeOpen" to 0.68f,
        "browY" to 0.12f, "squint" to 0.16f,
    )
    private val happyFace = mapOf(
        "smile" to 0.8f, "eyeSmile" to 0.55f, "eyeOpen" to 0.86f, "browY" to 0.22f,
    )
    private val shyFace = mapOf(
        "smile" to 0.22f, "eyeSmile" to 0.12f, "eyeOpen" to 0.8f,
        "gazeX" to 0.32f, "gazeY" to -0.2f, "browY" to 0.12f,
    )
    private val surprisedFace = mapOf(
        "eyeOpen" to 1.0f, "browY" to 0.55f, "browAngle" to 0.1f,
    )
    private val sadFace = mapOf(
        "smile" to -0.3f, "eyeOpen" to 0.82f, "browY" to 0.2f,
        "browForm" to -0.25f, "browAngle" to -0.35f, "gazeY" to -0.2f,
    )
    private val proudFace = mapOf(
        "smile" to 0.35f, "eyeSmile" to 0.1f, "eyeOpen" to 0.9f, "browY" to 0.05f,
    )

    internal val entries: Map<Int, Entry> = mapOf(
        0 to Entry("点头", "赞同、肯定、嗯时"),
        1 to Entry("左歪头", "疑惑、卖萌、歪头看你时", touch = "beast-ear:stroke"),
        2 to Entry("右歪头", "疑惑、卖萌、歪头看你时", touch = "beast-ear:stroke"),
        3 to Entry("前倾", "好奇、凑近听、认真听时", face = curiousFace, faceNote = "好奇抬眉、眼睛睁大"),
        4 to Entry("后仰", "大笑、后仰、放松时", face = amusedFace, faceNote = "笑眯眯、眯起眼"),
        5 to Entry("左转头", "转头看、回避时"),
        6 to Entry("右转头", "转头看、回避时"),
        7 to Entry("连连点头", "连连赞同、嗯嗯时"),
        8 to Entry("摇头", "否定、不要、不同意时"),
        9 to Entry(
            "受惊后缩", "受惊、吓一跳、后缩时", touch = "cheek:slap",
            face = surprisedFace, faceNote = "眼睛睁大、眉毛抬起",
        ),
        10 to Entry("左探头", "探头看、好奇张望时"),
        11 to Entry("右探头", "探头看、好奇张望时"),
        12 to Entry(
            "害羞低头", "害羞、低头时", "脸红",
            face = shyFace, faceNote = "含笑低眼、视线偏开",
        ),
        13 to Entry("抬下巴", "得意、骄傲、哼时", face = proudFace, faceNote = "嘴角微扬、神态自得"),
        14 to Entry("左摇晃", "摇晃、心情好、晃悠时"),
        15 to Entry("右摇晃", "摇晃、心情好、晃悠时"),
        16 to Entry("好奇点动", "好奇、点动、真的吗时", face = curiousFace, faceNote = "好奇抬眉、眼睛睁大"),
        17 to Entry(
            "开心蹦跳", "开心、好耶、蹦跳时", "星星眼", "chest:tap",
            face = happyFace, faceNote = "大笑容、笑眼",
        ),
        18 to Entry("缓慢重心移动", "放松、换姿势、慢慢晃时"),
        19 to Entry("咯咯笑晃动", "忍不住笑、咯咯笑时", face = amusedFace, faceNote = "笑眯眯、眯起眼"),
        20 to Entry(
            "难过垂头", "难过、低落、叹气时", "流泪",
            face = sadFace, faceNote = "眉眼低垂、嘴角下压",
        ),
        21 to Entry("得意挺胸", "得意、骄傲时", face = proudFace, faceNote = "嘴角微扬、神态自得"),
        22 to Entry(
            "害羞转开头", "害羞转头、不看你时", "脸红",
            face = shyFace, faceNote = "含笑低眼、视线偏开",
        ),
        23 to Entry(
            "惊讶一震", "惊讶、诶、真的假的时", "星星眼", "nose:tap",
            face = surprisedFace, faceNote = "眼睛睁大、眉毛抬起",
        ),
        24 to Entry(
            "困意摇晃", "困了、迷迷糊糊时",
            face = mapOf(
                "eyeOpen" to 0.55f, "gazeY" to -0.2f, "smile" to 0.05f, "browY" to 0.0f,
            ),
            faceNote = "半闭眼、没精神",
        ),
        25 to Entry(
            "兴奋小抖动", "兴奋、期待时", "星星眼",
            face = happyFace, faceNote = "大笑容、笑眼",
        ),
        26 to Entry(
            "思考抬眼", "思考、琢磨、让我想想时", touch = "forehead:stroke",
            face = mapOf(
                "gazeX" to 0.28f, "gazeY" to -0.12f, "eyeOpen" to 0.82f, "browAngle" to -0.12f,
            ),
            faceNote = "视线飘开、若有所思",
        ),
        27 to Entry(
            "害羞歪头低头", "娇羞、不好意思笑时", "脸红",
            face = shyFace, faceNote = "含笑低眼、视线偏开",
        ),
        28 to Entry(
            "温柔靠近", "温柔、安慰、靠近你时", "星星眼", "cheek:stroke",
            face = mapOf(
                "smile" to 0.46f, "eyeSmile" to 0.35f, "eyeOpen" to 0.82f,
                "browY" to 0.16f, "gazeX" to 0.0f,
            ),
            faceNote = "温柔笑眼、注视对方",
        ),
        29 to Entry(
            "调皮闪躲", "调皮、躲开、抓不到我时",
            face = mapOf(
                "smile" to 0.68f, "eyeSmile" to 0.28f, "eyeOpen" to 0.92f,
                "browY" to 0.26f, "gazeX" to 0.3f,
            ),
            faceNote = "俏皮笑容、视线灵动",
        ),
    )

    fun describe(group: String, index: Int): String? {
        if (group != "LuluWolf") return null
        val entry = entries[index] ?: return null
        return buildString {
            append("$group#$index=${entry.name}（${entry.fits}")
            entry.expression?.let { append("；配$it") }
            entry.faceNote?.let { append("；神态：$it") }
            append("）")
        }
    }
}
