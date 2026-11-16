package com.jiacimu.lulu.games

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Original puzzle facts are immutable: neither the host nor a teammate may rewrite the solution. */
internal data class TurtlePuzzle(
    val id: String,
    val title: String,
    val surface: String,
    val truth: String,
    val genre: String,
    val difficulty: String,
    val hints: List<String>,
)

internal object TurtleSoupLibrary {
    val cases = listOf(
        TurtlePuzzle(
            "last_caption", "最后一行字幕",
            "直播快结束时，女主播微笑着说了声“明天见”。粉丝却因为屏幕上的最后一行字幕报了警。直播没有被黑客入侵。",
            "这是预先录好的带货视频。女主播被人胁迫，不能直接出声求救，却在录制时借由一块提词板反复提示工作人员不要把她临时加的一行字幕删除。剪辑师将“如果我说了明天见，请立刻报警，我被锁在录影棚”的求救文字作为最后一行硬字幕保留下来。她的声音和笑容来自同一次录制；字幕不是自动语音识别生成的，而是人为加上的。",
            "都市悬疑 · 媒介误导", "进阶",
            listOf("字幕不一定由声音自动产生。", "直播内容不一定在此刻拍摄。", "真正的求救信息来自字幕制作环节。"),
        ),
        TurtlePuzzle(
            "reverse_clock", "向前走的钟",
            "每晚十二点，旧旅馆前台的钟都会倒着走一分钟。老板不在意。某晚它终于正常向前走，老板却立刻锁了门。",
            "钟实际上接在地下金库的老式电源监测器上。每天午夜，夜间自检程序会将指针倒拨一分钟，代表保险门电子锁仍然与电源相连。一次午夜自检没有发生，表面看是钟恢复正常，实际上表明有人拔掉了金库监测线路准备撬锁。老板知道这条约定才立即封锁出口。钟不是超自然现象。",
            "密室悬疑 · 规则反转", "困难",
            listOf("钟不是普通计时装置。", "倒走是一个安全信号。", "正常走时意味着保护机制被切断。"),
        ),
        TurtlePuzzle(
            "three_umbrellas", "第三把伞",
            "大雨中，女人回家看见门口摆着三把一样的黑伞。她和丈夫只有两把。她没有进屋，而是敲了邻居的门。",
            "夫妻住在需要预约保洁的公寓，门口两把黑伞是他们用来标记已确认安全回家的习惯。第三把伞的柄上沾着一小段黄色警戒胶带，是住宅楼维修人员用来标记施工区的。当天物业通知过检修人员不得进入住户房间；可那把伞已经被雨淋湿、还靠在她家门内侧。有人借检修名义闯入屋内，女人看见门缝里伸出的伞柄，选择去邻居家求助。关键并非伞的数量本身，而是第三把伞的来源和位置。",
            "生活恐怖 · 细节推理", "中等",
            listOf("注意第三把伞来自哪里。", "它的位置意味着有人已经进屋。", "物业通知与施工标记有关。"),
        ),
        TurtlePuzzle(
            "silent_bell", "没有响过的门铃",
            "老人每晚听到门铃响两次，才放心去睡觉。今天门铃没有响，女儿却说一切正常。老人仍然报警了。",
            "老人独居，家里安装了两段式门禁：第一次提示是楼下有访客，第二次提示是有人通过家门前的感应器。女儿每晚下班前会经过门口但并不进去，所以两声提醒已成为她到家附近的正常信号。今天女儿在电话里自称已安全到家，门口感应器却没有记录任何人经过；老人并不能仅凭此确定遇险，但联系不上物业和女儿后选择报警，请警方协助核实。门铃并非暗号，是可验证的客观出入证据。",
            "生活悬疑 · 证据缺失", "中等",
            listOf("门铃由感应器触发，不一定有人按。", "女儿的自述与客观记录冲突。", "未发生的声音本身就是线索。"),
        ),
        TurtlePuzzle(
            "empty_frame", "缺席的第六个人",
            "五个人拍完合照，摄影师却坚持说照片里有六个人。大家数了三遍，屏幕上始终只有五个人。",
            "六个人曾在镜头前站成一排，摄影师按下快门后，其中一人立即跑去厕所。相机开启了“移除路人”的自动修图功能，自动将最边缘移动的第六个人擦掉，并用背景填充。摄影师并非看见鬼，而是回忆起取景器里明明有六个身影；他检查未经处理的原图，证明第六个人确实存在。题面中的“照片”是处理后版本，关键信息是拍摄与展示并非同一图像。",
            "科技诡计 · 视觉误导", "进阶",
            listOf("照片不一定与快门瞬间完全相同。", "摄影师相信的是拍摄时亲眼见到的人。", "自动修图抹去了一个人。"),
        ),
        TurtlePuzzle(
            "soup_with_no_soup", "菜单上的海龟汤",
            "客人点了一碗海龟汤。服务员只给他一张空白菜单，他却立刻知道自己该离开这家店。",
            "这是以“海龟汤”为主题的沉浸式推理店。每位入场客人先领到菜单，上面会用遇水显影的墨水写出本场案件题面。服务员端来空白纸，是因为当晚店内停水、显影用的喷雾没有准备好。客人是店里活动的设计师，知道没有题面就无法公平进行游戏，于是决定退出当场活动。这道元叙事题故意利用了海龟汤既是菜名也是推理游戏的歧义；没有食人或真实危险。",
            "元叙事 · 语义机关", "中等",
            listOf("“海龟汤”不一定是食物。", "纸上的信息需要特定条件才能出现。", "他并非因为害怕离开，而是不想玩不公平的局。"),
        ),
    )
    fun find(id: String): TurtlePuzzle? = cases.firstOrNull { it.id == id }
}

internal enum class TurtleHost { AI, CHARACTER, OWNER }
internal enum class TurtleKind { QUESTION, ANSWER, DISCUSS, HINT }

internal data class TurtleMessage(
    val speakerId: String,
    val speaker: String,
    val kind: TurtleKind,
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("speakerId", speakerId).put("speaker", speaker).put("kind", kind.name)
        .put("text", text).put("createdAt", createdAt)

    companion object {
        fun fromJson(json: JSONObject): TurtleMessage? = runCatching {
            TurtleMessage(
                json.getString("speakerId"), json.getString("speaker"),
                TurtleKind.valueOf(json.getString("kind")), json.getString("text"),
                json.optLong("createdAt", 0),
            )
        }.getOrNull()
    }
}

/** The durable round is separate from the companion's 35-message private-chat recall window. */
internal data class TurtleRoom(
    val id: String = UUID.randomUUID().toString(),
    val caseId: String,
    val host: TurtleHost,
    val hostCharacterId: String = "",
    val companions: List<String> = emptyList(),
    val customSurface: String = "",
    val customTruth: String = "",
    val messages: List<TurtleMessage> = emptyList(),
    val hintsUsed: Int = 0,
    val revealed: Boolean = false,
) {
    val puzzle: TurtlePuzzle? get() = TurtleSoupLibrary.find(caseId)
    val title: String get() = puzzle?.title ?: "我的原创谜题"
    val surface: String get() = puzzle?.surface ?: customSurface
    val truth: String get() = puzzle?.truth ?: customTruth
    val pendingQuestion: TurtleMessage?
        get() = messages.lastOrNull()?.takeIf { it.kind == TurtleKind.QUESTION }

    /**
     * Full public transcript, not the chat-window tail; explicitly never send the
     * solution to a guesser. All old entries remain persisted and included.
     * Token limits are handled as provider errors, not by quietly forgetting clues.
     */
    fun publicContext(): String = buildString {
        append("固定汤面：").append(surface).append("\n")
        append("推理房间公开记录（按先后顺序，不得更改旧回答）：\n")
        messages.forEachIndexed { i, event ->
            append(i + 1).append(". ").append(event.speaker).append(" [")
                .append(event.kind.name).append("] ").append(event.text).append("\n")
        }
    }

    fun nextGuessingCharacter(): String? {
        val available = companions.filter { it.isNotBlank() && it != hostCharacterId }
        return available.minByOrNull { id ->
            messages.count { it.speakerId == id }
        }
    }

    fun toJson(): String = JSONObject()
        .put("id", id).put("caseId", caseId).put("host", host.name)
        .put("hostCharacterId", hostCharacterId)
        .put("companions", JSONArray(companions))
        .put("customSurface", customSurface).put("customTruth", customTruth)
        .put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        .put("hintsUsed", hintsUsed).put("revealed", revealed)
        .toString()

    companion object {
        fun fromJson(raw: String?): TurtleRoom? = runCatching {
            val json = JSONObject(raw ?: return null)
            val array = json.optJSONArray("messages") ?: JSONArray()
            val roster = json.optJSONArray("companions") ?: JSONArray()
            val result = TurtleRoom(
                id = json.getString("id"),
                caseId = json.getString("caseId"),
                host = TurtleHost.valueOf(json.getString("host")),
                hostCharacterId = json.optString("hostCharacterId"),
                companions = (0 until roster.length()).map { roster.optString(it) }.filter(String::isNotBlank),
                customSurface = json.optString("customSurface"),
                customTruth = json.optString("customTruth"),
                messages = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(TurtleMessage::fromJson) },
                hintsUsed = json.optInt("hintsUsed").coerceAtLeast(0),
                revealed = json.optBoolean("revealed"),
            )
            require(result.surface.isNotBlank() && result.truth.isNotBlank())
            result
        }.getOrNull()
    }
}

/** The game host's answer is a verdict, not a creative rewrite of the puzzle. */
internal fun turtleVerdict(raw: String): String? = when (raw.trim().removeSuffix("。")) {
    "是", "否", "无关", "是也不是" -> raw.trim().removeSuffix("。")
    else -> null
}

/** A companion decides whether to ask, discuss or pause; program does not choose its line. */
internal data class TurtleMove(val action: String, val text: String) {
    companion object {
        fun parse(raw: String): TurtleMove? = runCatching {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end < start) return null
            val json = JSONObject(raw.substring(start, end + 1))
            val action = json.getString("action")
            val text = json.optString("text").trim()
            if (action !in setOf("ask", "discuss", "wait")) return null
            if (action != "wait" && text.isBlank()) return null
            TurtleMove(action, text)
        }.getOrNull()
    }
}
