package com.jiacimu.lulu

/**
 * Names, fits, suggested expression pairings and default face presets for
 * the shipped wolf motion pack (LuluWolf group, indices 00..47).
 *
 * Every fits text first says when the clip fits, then, after
 * “；动作是”, exactly what the body does, so two clips sharing one
 * emotion stay distinguishable by their movement, not just their mood.
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
 * Legacy touch hints are retained for compatibility and documentation only.
 * Local physical contact never auto-plays an authored whole-body Motion3.
 * The character may select one of these clips explicitly during dialogue.
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
        0 to Entry("点头", "赞同、肯定、嗯时；动作是头上下点一次，幅度不大，很快回正"),
        1 to Entry("左歪头", "疑惑、卖萌、歪头看你时；动作是头向左歪过去，停一会儿再回正", touch = "beast-ear:stroke"),
        2 to Entry("右歪头", "疑惑、卖萌、歪头看你时；动作是头向右歪过去，停一会儿再回正", touch = "beast-ear:stroke"),
        3 to Entry("前倾", "好奇、凑近听、认真听时；动作是头和上身一起向前下方探，像凑近来听", face = curiousFace, faceNote = "好奇抬眉、眼睛睁大"),
        4 to Entry("后仰", "大笑、后仰、放松时；动作是头和上身向后仰开，像笑得往后倒", face = amusedFace, faceNote = "笑眯眯、眯起眼"),
        5 to Entry("左转头", "转头看、回避时；动作是头和上身一起向左转过去，停住再回来"),
        6 to Entry("右转头", "转头看、回避时；动作是头和上身一起向右转过去，停住再回来"),
        7 to Entry("连连点头", "连连赞同、嗯嗯时；动作是头连着点两三下，一下比一下轻"),
        8 to Entry("摇头", "否定、不要、不同意时；动作是头左右摇两三下，越摇越慢"),
        9 to Entry(
            "受惊后缩", "受惊、吓一跳、后缩时；动作是头和上身猛地向后一缩，停一下才缓回来（受惊后还回头看你用33）", touch = "cheek:slap",
            face = surprisedFace, faceNote = "眼睛睁大、眉毛抬起",
        ),
        10 to Entry("左探头", "探头看、好奇张望时；动作是头向左侧探出去张望，停住再缩回"),
        11 to Entry("右探头", "探头看、好奇张望时；动作是头向右侧探出去张望，停住再缩回"),
        12 to Entry(
            "害羞低头", "害羞、低头时；动作是头深深低下去，停一会儿才慢慢抬回", "脸红",
            face = shyFace, faceNote = "含笑低眼、视线偏开",
        ),
        13 to Entry("抬下巴", "得意、骄傲、哼时；动作是只把下巴高高抬起停住，身子基本不动（挺胸得意用21）", face = proudFace, faceNote = "嘴角微扬、神态自得"),
        14 to Entry("左摇晃", "摇晃、心情好、晃悠时；动作是身子带着头向左晃过去，慢慢荡回来"),
        15 to Entry("右摇晃", "摇晃、心情好、晃悠时；动作是身子带着头向右晃过去，慢慢荡回来"),
        16 to Entry("好奇点动", "好奇、点动、真的吗时；动作是头向前下方点动两下，还偏着头像在确认", face = curiousFace, faceNote = "好奇抬眉、眼睛睁大"),
        17 to Entry(
            "开心蹦跳", "开心、好耶、蹦跳时；动作是头和身子一起上下蹦跶好几下，幅度大", "星星眼", "chest:tap",
            face = happyFace, faceNote = "大笑容、笑眼",
        ),
        18 to Entry("缓慢重心移动", "放松、换姿势、慢慢晃时；动作是整个人慢慢从一侧晃到另一侧，节奏很慢"),
        19 to Entry("咯咯笑晃动", "忍不住笑、咯咯笑时；动作是头小幅度快速左右晃，像憋不住笑在抖", face = amusedFace, faceNote = "笑眯眯、眯起眼"),
        20 to Entry(
            "难过垂头", "难过、低落、叹气时；动作是头慢慢垂下去停住，半天才抬回来，幅度深", "流泪",
            face = sadFace, faceNote = "眉眼低垂、嘴角下压",
        ),
        21 to Entry("得意挺胸", "得意、骄傲时；动作是胸口挺起来、头只微微扬起停住摆一会儿（只抬下巴用13）", face = proudFace, faceNote = "嘴角微扬、神态自得"),
        22 to Entry(
            "害羞转开头", "害羞转头、不看你时；动作是头转开到一侧停住，半天才转回来", "脸红",
            face = shyFace, faceNote = "含笑低眼、视线偏开",
        ),
        23 to Entry(
            "惊讶一震", "惊讶、诶、真的假的时；动作是整个人猛地一震，头一下抬起来又落下，很快", "星星眼", "nose:tap",
            face = surprisedFace, faceNote = "眼睛睁大、眉毛抬起",
        ),
        24 to Entry(
            "困意摇晃", "困了、迷迷糊糊时；动作是头慢慢左右晃，越晃越低，像要睡着",
            face = mapOf(
                "eyeOpen" to 0.55f, "gazeY" to -0.2f, "smile" to 0.05f, "browY" to 0.0f,
            ),
            faceNote = "半闭眼、没精神",
        ),
        25 to Entry(
            "兴奋小抖动", "兴奋、期待时；动作是头小幅度快速点动好几下，急切的样子", "星星眼",
            face = happyFace, faceNote = "大笑容、笑眼",
        ),
        26 to Entry(
            "思考抬眼", "思考、琢磨、让我想想时；动作是头微微转向一侧、眼睛往上抬，停住想事情", touch = "forehead:stroke",
            face = mapOf(
                "gazeX" to 0.28f, "gazeY" to -0.12f, "eyeOpen" to 0.82f, "browAngle" to -0.12f,
            ),
            faceNote = "视线飘开、若有所思",
        ),
        27 to Entry(
            "害羞歪头低头", "娇羞、不好意思笑时；动作是头歪向一侧又低下去，停住一会儿", "脸红",
            face = shyFace, faceNote = "含笑低眼、视线偏开",
        ),
        28 to Entry(
            "温柔靠近", "温柔、安慰、靠近你时；动作是上身慢慢向你靠近，头微微侧着，停住陪着你", "星星眼", "cheek:stroke",
            face = mapOf(
                "smile" to 0.46f, "eyeSmile" to 0.35f, "eyeOpen" to 0.82f,
                "browY" to 0.16f, "gazeX" to 0.0f,
            ),
            faceNote = "温柔笑眼、注视对方",
        ),
        29 to Entry(
            "调皮闪躲", "调皮、躲开、抓不到我时；动作是头快速向一侧闪一下又回来，连闪两下",
            face = mapOf(
                "smile" to 0.68f, "eyeSmile" to 0.28f, "eyeOpen" to 0.92f,
                "browY" to 0.26f, "gazeX" to 0.3f,
            ),
            faceNote = "俏皮笑容、视线灵动",
        ),
        30 to Entry("侧倾倾听后点头", "专心听完、理解后回应时；动作是头侧倾着听，最后轻轻点一下头（只侧耳听、不点头用34）", face = curiousFace,
            faceNote = "抬眉注视、侧倾后轻点头"),
        31 to Entry("犹豫转开再回望", "迟疑、斟酌措辞、欲言又止时；动作是头转开到一侧，停了停又慢慢转回来看你",
            face = mapOf("eyeOpen" to 0.86f, "browY" to 0.16f, "gazeX" to 0.25f),
            faceNote = "视线略偏、眉毛轻抬"),
        32 to Entry("松口气缓缓放松", "释然、紧张消退、终于放心时；动作是头先微微扬起，又缓缓垂下放松，整个人松下来",
            face = mapOf("eyeOpen" to 0.76f, "smile" to 0.22f, "browY" to 0.08f),
            faceNote = "眼睛稍放松、嘴角轻扬"),
        33 to Entry("后缩侧偏再回望", "受惊后重新看向对方时；动作是头先后缩侧偏开，又慢慢转回来重新看你（只后缩、不回头用09）", face = surprisedFace,
            faceNote = "抬眉、睁眼回望"),
        34 to Entry("认真侧耳倾听", "侧耳细听、温柔地关注对方时；动作是头微微侧过去像在侧耳听，动得很轻、很专注（听完点头回应用30）",
            face = curiousFace, faceNote = "目光专注、轻抬眉"),
        35 to Entry("耳朵欢快点弹", "轻快开心、被逗笑、雀跃回应时；动作是头轻轻快快地小点动，带着雀跃的弹跳感",
            face = happyFace, faceNote = "眼睛发亮、笑意明显"),
        36 to Entry("害羞偷看回眸", "不好意思却忍不住回头看时；动作是头先转开，又忍不住慢慢转回来偷看你", "脸红",
            face = shyFace, faceNote = "视线先移开再偷偷回望"),
        37 to Entry("委屈垂眼再抬头", "委屈、难过后慢慢重新看向对方时；动作是头委屈地垂下去，停一会儿又慢慢抬起来看你",
            face = sadFace, faceNote = "先垂眸，再缓缓恢复目光"),
        38 to Entry("好奇回头二连看", "突然注意到有趣的事情、诶等一下时；动作是头先转向一侧，又快速转回另一侧确认，像看了两眼",
            face = curiousFace, faceNote = "目光来回确认、眉毛轻扬"),
        39 to Entry("困倦轻轻侧靠", "睡意朦胧、靠一会儿、安静休息时；动作是头和身子慢慢向一侧靠下去，越靠越沉，像困得依偎下来",
            face = mapOf("eyeOpen" to 0.52f, "gazeY" to -0.2f, "smile" to 0.04f),
            faceNote = "眼皮半垂、渐渐放松"),
        40 to Entry(
            "不悦嘟嘴", "闹别扭、不服气、嘟嘴赌气时；动作是头微微扭向一侧、下巴收着，幅度小，还偷偷看你（真生气干脆扭头用43）",
            face = mapOf(
                "smile" to -0.3f, "eyeOpen" to 0.78f, "browY" to -0.25f,
                "browAngle" to -0.35f, "pout" to 0.55f,
            ),
            faceNote = "嘟着嘴、眉头微压、斜着眼看你",
        ),
        41 to Entry(
            "担忧前倾", "担心你、关切地问你怎么了时；动作是上身向你探近，头微微低着凑近看你",
            face = mapOf(
                "smile" to -0.2f, "eyeOpen" to 0.95f, "browY" to 0.42f,
                "browAngle" to 0.26f, "gazeY" to -0.12f,
            ),
            faceNote = "眉头抬起收紧、担心地看着你",
        ),
        42 to Entry(
            "撒娇轻晃", "撒娇、想要你陪、黏着你时；动作是头和身子一起轻轻左右晃，像在蹭你撒娇", "脸红",
            face = mapOf(
                "smile" to 0.55f, "eyeSmile" to 0.4f, "eyeOpen" to 0.85f,
                "browY" to 0.18f, "gazeY" to -0.1f,
            ),
            faceNote = "甜甜地笑、轻轻晃着撒娇",
        ),
        43 to Entry(
            "生气扭头", "真生气、哼一声不理你时；动作是头和上身干脆地扭向一侧停住，幅度大、不看你（只是嘟嘴赌气用40）",
            face = mapOf(
                "smile" to -0.35f, "eyeOpen" to 0.8f, "browY" to -0.3f,
                "browAngle" to -0.4f, "pout" to 0.3f,
            ),
            faceNote = "眉头压低、哼地扭头不看你",
        ),
        44 to Entry(
            "发呆放空", "发呆、走神、放空时；动作是头慢慢飘向一侧上方，停住不动，像在想别的",
            face = mapOf(
                "eyeOpen" to 0.7f, "gazeX" to 0.3f, "gazeY" to 0.15f, "browY" to 0.0f,
            ),
            faceNote = "眼神放空、微微出神",
        ),
        45 to Entry(
            "紧张局促", "紧张、心虚、不自在时；动作是小幅度地左右动来动去，眼神飘忽停不住",
            face = mapOf(
                "eyeOpen" to 0.95f, "browY" to 0.3f, "browAngle" to 0.2f,
                "smile" to -0.05f, "gazeY" to -0.15f,
            ),
            faceNote = "眼神飘忽、眉头微紧",
        ),
        46 to Entry(
            "无奈叹气", "无奈、拿你没办法、叹气时；动作是头垂下去，一边慢慢摇头一边叹气，再抬起来（和08的区别：08是抬头干脆拒绝，这个是垂头叹气）",
            face = mapOf(
                "smile" to -0.15f, "eyeOpen" to 0.7f, "browY" to 0.1f,
                "browAngle" to -0.1f, "gazeY" to -0.25f,
            ),
            faceNote = "垂着头、慢慢摇头叹气",
        ),
        47 to Entry(
            "疑惑双歪头", "没听懂、疑惑、满脸问号时；动作是头先歪向一边，没想通又歪向另一边（比01、02的单次歪头更懵）",
            face = curiousFace, faceNote = "先歪一边再歪另一边、满脸问号",
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
