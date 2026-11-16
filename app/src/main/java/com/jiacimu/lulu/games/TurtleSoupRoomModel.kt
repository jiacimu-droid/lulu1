package com.jiacimu.lulu.games

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The truth is never passed to the companion's guessing request. */
internal data class SoupCase(
    val id: String,
    val title: String,
    val category: String,
    val difficulty: String,
    val surface: String,
    val truth: String,
    val hints: List<String>,
    val boundaries: String = "",
)

internal object SoupLibrary {
    // Original cases with a fixed, testable explanation. No scraped copyrighted puzzle texts.
    val cases = listOf(
        SoupCase(
            "upward_rain", "向上落的雨", "现实悬疑", "中等",
            "深夜，孩子指着门口的监控说：‘雨在往天上落。’父亲看了一眼，立刻带着孩子从后门离开。",
            "那不是实时监控，而是有人把先前录下的雨夜视频倒放后送入了监控画面。雨滴向上移动暴露了视频正在倒放；父亲意识到门口的实时画面被人为替换，无法确定门外是否有人，便选择从后门撤离并报警。这里不能凭汤面确定入侵者身份。",
            listOf("雨不是超自然现象。", "孩子看到的是屏幕，不是天空。", "关键不是谁站在门口，而是画面是不是实时的。"),
            "画面被倒放；门外是否有凶手未知。",
        ),
        SoupCase(
            "flower_receipt", "第七束花", "细节观察", "中等",
            "花店连续七天收到‘已送达’的照片。第七天，店主盯着花瓣看了很久，突然报警。明明照片里的花一朵都没少。",
            "店主每天都委托配送员送一束不同的新鲜花，每天也应收到当日签收照片。第七天发来的却是第一天的旧照片：每朵花的折痕、包装皱褶甚至花瓣上的水滴都完全相同。店主识别出有人重复使用签收证明，担心收件人出了事而报警；不代表收件人必然遇害。",
            listOf("照片的真假比花的数量重要。", "每天应该拍新的照片。", "一滴水的位置完全一样。"),
        ),
        SoupCase(
            "mute_key", "安静的钢琴", "人物误导", "较难",
            "钢琴比赛结束，评委一致说她没有弹错任何一个音。她却因为最后一个‘没听见的音’，失去了冠军。",
            "这是一场特殊的无声演奏比赛：选手使用连接 MIDI 的电子琴，评委核对按键与力度数据，而不听真实声音。她的所有可见指法都正确，但最后一下琴键没有触发 MIDI 信号，因此数据里少了一个音符。评委口中的‘没有弹错’不等于‘没有漏弹’；比赛冠军按完整数据评分。主持人不应把这题说成普通钢琴赛。",
            listOf("评委并不是凭耳朵判断。", "‘没弹错’和‘没有漏掉’不是同一回事。", "琴键动作有记录。"),
            "设定为虚构的 MIDI 技术赛，不是常规钢琴比赛。",
        ),
        SoupCase(
            "closed_shop", "永远打烊的店", "温情反转", "简单",
            "一家小店的门上连续挂了三个月‘今日打烊’。老板每天仍准时开灯，门外的人反而越来越多，还会低声说‘谢谢’。",
            "老板关闭了原本营业的修表店，改成免费给附近老人修理旧钟表的预约窗口。门上的‘今日打烊’是指不再进行商业营业；每天开灯表示仍有人在做免费修理。门外等待的都是来取修好的旧物或表达感谢的人。",
            listOf("打烊不等于里面没人工作。", "门口的人不是来购物的。", "修好的是有纪念意义的旧物。"),
        ),
        SoupCase(
            "last_message", "没发出去的晚安", "叙述陷阱", "较难",
            "女孩看见父亲手机里一条‘晚安’，显示发送失败。她没有重发，却确信父亲那晚按时回到了家。",
            "父亲早已在家。女孩查看的是家里一台只连接局域网的旧手机，父亲每晚睡前用它向已经停用的号码发送‘晚安’，借此纪念去世的家人。发送失败本就是每天的结果；关键是旧手机只有在家里充电才能正常使用。她还通过家中监控的独立时间戳确认父亲那晚确实在家，不能只凭‘发送失败’直接推断。",
            listOf("发送对象不一定还在使用那个号码。", "那台手机不是父亲随身携带的。", "确认回家还需要另一项证据。"),
            "父亲在家的事实必须依赖独立的家中监控时间戳。",
        ),
        SoupCase(
            "wrong_count", "多出来的脚步", "恐怖氛围", "中等",
            "独居女生每晚听见走廊传来四次脚步声，随后门锁响一下。某晚只听见三次脚步声，她立刻躲到卫生间报警。",
            "她住在公寓尽头。平时保安巡逻会经过门前，再走到尽头防火门，一共能听到四组脚步；最后门锁的一响是保安按流程检查防火门。那晚脚步少了一组，但防火门锁照样响，表明巡逻者在不该停的地方停下过，或者有人假扮巡逻。她此前收到过楼管的提醒：保安本周巡逻取消，而当晚却仍有脚步与开锁声，因此警觉。这里无法确定门外的人是谁。",
            listOf("脚步和门锁声来自公共走廊。", "她知道正常巡逻的路径。", "那晚本不该有人巡逻。"),
            "少一组脚步本身不是充分证据，还要结合巡逻取消通知。",
        ),
        SoupCase(
            "silent_subtitle", "错误的字幕", "科技惊悚", "中等",
            "她独自戴耳机看熟悉的电影。某天，画面里的演员明明没开口，字幕却写着：‘别回头。’她立刻摘下耳机，关掉了电视。",
            "她用的并不是影片自带字幕，而是电视的实时语音转文字功能。屏幕里的人正在沉默，‘别回头’来自电视麦克风捕获的房间内另一个人的低语；她意识到自己可能并非独处。因为电视机也可能误识别，题目不保证有超自然事物或必有入侵者。",
            listOf("字幕不是电影制作方写的。", "电视能听见房间里的声音。", "画面中的演员没有说话。"),
            "实时语音识别会误识别，不能以字幕为无误证据。",
        ),
        SoupCase(
            "borrowed_clock", "不存在的第十三下", "规则谜题", "较难",
            "午夜，钟敲了十三下。所有住客都觉得正常，只有新来的修钟师把旅馆的灯全部打开，要求大家先别睡。",
            "旅馆有两座钟：大厅整点敲十二下，厨房值夜钟在整点后另敲一下提醒值班人员巡查。老住客习惯在午夜听到总共十三下。修钟师当天已经亲手拆下厨房的钟，知道它不可能敲响；所谓第十三下来自别处，他怀疑有人在模仿值夜信号，于是亮灯确认安全。这不是超自然的第十三次钟响。",
            listOf("十三下不一定来自同一座钟。", "新来的人知道一件住客不知道的事。", "有一座钟已经被拆下。"),
        ),
        SoupCase(
            "edited_sixth", "缺席的第六个人", "视觉误导 · 科技怪谈", "中等",
            "六个人拍完合照，摄影师却坚持说照片里有六个人。大家数了三遍，屏幕上始终只有五个人。",
            "摄影师按下快门时，取景器里确实有六个人。相机开启了自动移除路人功能，把靠边移动的第六个人当作背景干扰，修图时将其擦掉。大家数的是修图后的预览而不是原始图像。摄影师后来打开原片找到了第六个人。题目中的摄影师没有超自然视力。",
            listOf("取景器和显示出来的照片未必是同一版本。", "第六个人不需要从现场消失。", "自动修图有时会误删真实人物。"),
            "没有证据表明此人失踪或遇险；原始照片里能找到六个人。",
        ),
        SoupCase(
            "caption_sos", "最后一行字幕", "直播疑案 · 信息层", "进阶",
            "直播快结束时，女主播微笑着说了声‘明天见’。粉丝却因为屏幕上的最后一行字幕报了警。直播没有被黑客入侵。",
            "这场所谓的直播实际播的是预录视频。女主播录制时受胁迫，不敢直接用声音求救，但设法把一行求救文字交给了制作字幕的工作人员。剪辑师保留了那一行后来被放到画面里的硬字幕：‘如果看到我说完明天见，请报警，我被困在这里。’粉丝看到与笑容形成反差的字幕报了警。字幕不是音频自动识别结果。",
            listOf("最后一行字幕不一定是语音识别结果。", "所谓直播不一定是实时拍摄。", "真正的求救信息来自字幕制作流程。"),
            "题目并不保证主播后来是否获救，不要凭空补造结局。",
        ),
        SoupCase(
            "backward_test", "向前走的钟", "规则怪谈 · 反向警报", "较难",
            "每晚十二点，旧旅馆前台的钟都会倒着走一分钟。老板从不在意。某晚它终于正常向前走，老板却立刻锁了门。",
            "这只钟被改装为地下金库供电与门禁巡检的展示器。正常夜间自检会把指针短暂倒拨一分钟，说明监控线路完好。那天自检没有发生，表面上钟恢复正常，其实意味着有人断开了金库的线路。老板知道报警逻辑，因此立即锁住出口并寻求协助。倒走是特定的人工设置，不是所有旅馆的通用规则。",
            listOf("倒走是设计好的功能，不是故障。", "时钟和另一套设备共用线路。", "没有倒走意味着安全检查没有完成。"),
            "单凭钟正常走不能证明一定有贼；老板是根据监控线路异常采取防范。",
        ),
        SoupCase(
            "blank_menu", "菜单上的海龟汤", "元叙事 · 文字机关", "中等",
            "客人点了一碗海龟汤。服务员只递给他一张空白菜单，他却立刻知道自己该离开这家店。",
            "这不是餐厅用来吃的汤，而是一家以海龟汤推理为主题的沉浸式体验店。空白菜单本来应当用遇水显影的墨水显示当场的谜题汤面；当天店里停水，连展示题面的喷雾都无法使用。客人是推理游戏爱好者，知道看不到公平的谜题就没法玩，主动离开了游戏场。主人公没有受到威胁。",
            listOf("海龟汤不一定是菜。", "这张纸本来应该显示文字。", "离开不是为了逃命，只是游戏无法正常开始。"),
        ),
    ) + SoupExpansionCases.cases
    fun find(id: String): SoupCase? = cases.firstOrNull { it.id == id }
}

internal enum class SoupHost { AI, CHARACTER, USER }
internal enum class SoupKind { QUESTION, ANSWER, DISCUSSION }

internal data class SoupLine(
    val speaker: String,
    val kind: SoupKind,
    val text: String,
    /** Index into lines, for the host's answer to an exact question. */
    val questionIndex: Int = -1,
)

internal data class SoupRoom(
    val id: String = UUID.randomUUID().toString(),
    val caseId: String = SoupLibrary.cases.first().id,
    val host: SoupHost = SoupHost.AI,
    val companions: List<String> = emptyList(),
    val lines: List<SoupLine> = emptyList(),
    val started: Boolean = false,
    val revealed: Boolean = false,
) {
    val puzzle: SoupCase get() = requireNotNull(SoupLibrary.find(caseId))
    val hostId: String? get() = if (host == SoupHost.CHARACTER) companions.firstOrNull() else null
    val guessers: List<String> get() = if (host == SoupHost.CHARACTER) companions.drop(1) else companions
    val pendingQuestion: Int get() = lines.indices.lastOrNull {
        lines[it].kind == SoupKind.QUESTION &&
            lines.indices.none { j -> lines[j].kind == SoupKind.ANSWER && lines[j].questionIndex == it }
    } ?: -1
    val questionCount: Int get() = lines.count { it.kind == SoupKind.QUESTION }

    /** Every event, including early discussions and negative evidence, remains in this authoritative log. */
    fun publicContext(): String = buildString {
        append("题面：").append(puzzle.surface).append('\n')
        append("类型：").append(puzzle.category).append('\n')
        append("主持规则：只能回答是、否、部分是、无关；不得虚构已确认事实。\n")
        lines.forEachIndexed { index, line ->
            append('#').append(index + 1).append(' ')
            append(line.speaker).append(' ')
            append(when (line.kind) {
                SoupKind.QUESTION -> "问"
                SoupKind.ANSWER -> "答"
                SoupKind.DISCUSSION -> "讨论"
            })
            if (line.questionIndex >= 0) append("(回应#").append(line.questionIndex + 1).append(')')
            append('：').append(line.text).append('\n')
        }
    }

    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("id", id)
        .put("case_id", caseId)
        .put("host", host.name)
        .put("companions", JSONArray(companions))
        .put("started", started)
        .put("revealed", revealed)
        .put("lines", JSONArray().apply {
            lines.forEach { line ->
                put(JSONObject().put("speaker", line.speaker)
                    .put("kind", line.kind.name)
                    .put("text", line.text)
                    .put("question_index", line.questionIndex))
            }
        }).toString()

    companion object {
        fun fromJson(value: String?): SoupRoom? = runCatching {
            val raw = JSONObject(value ?: return null)
            val caseId = raw.getString("case_id")
            require(SoupLibrary.find(caseId) != null)
            val roster = raw.optJSONArray("companions") ?: JSONArray()
            val entries = raw.optJSONArray("lines") ?: JSONArray()
            SoupRoom(
                id = raw.getString("id"),
                caseId = caseId,
                host = SoupHost.valueOf(raw.getString("host")),
                companions = (0 until roster.length()).map { roster.getString(it) }.distinct(),
                revealed = raw.optBoolean("revealed"),
                started = raw.optBoolean("started", entries.length() > 0),
                lines = (0 until entries.length()).map { index ->
                    val line = entries.getJSONObject(index)
                    SoupLine(line.getString("speaker"),
                        SoupKind.valueOf(line.getString("kind")),
                        line.getString("text"), line.optInt("question_index", -1))
                },
            )
        }.getOrNull()
    }
}

internal class TurtleSoupRoomArchive(context: Context) {
    private val prefs = context.getSharedPreferences("lulu_turtle_soup_room_v1", Context.MODE_PRIVATE)
    fun read(): SoupRoom? = SoupRoom.fromJson(prefs.getString("active_room", null))
    /** Commit before updating the composable state; never claim an unpersisted message was sent. */
    fun save(room: SoupRoom) {
        check(prefs.edit().putString("active_room", room.toJson()).commit()) {
            "海龟汤对局保存失败，请重试"
        }
    }
}

internal fun normalizeSoupVerdict(raw: String): String? {
    val leading = raw.trim().removeSurrounding("\u0060").trim().trimStart('：', ':', '“', '"')
    return when {
        leading.startsWith("是也不是") || leading.startsWith("部分是") -> "部分是"
        leading.startsWith("与此无关") || leading.startsWith("无关") -> "无关"
        leading.startsWith("不是") || leading.startsWith("否") -> "否"
        leading.startsWith("是") -> "是"
        else -> null
    }
}

internal data class SoupCompanionDecision(val type: String, val text: String)
internal fun parseSoupCompanionDecision(raw: String): SoupCompanionDecision? = runCatching {
    val open = raw.indexOf('{')
    val close = raw.lastIndexOf('}')
    require(open >= 0 && close > open)
    val json = JSONObject(raw.substring(open, close + 1))
    val type = json.getString("type").lowercase()
    require(type in setOf("question", "discussion", "wait"))
    val message = json.optString("text").trim()
    require(type == "wait" || message.isNotBlank())
    SoupCompanionDecision(type, message.take(400))
}.getOrNull()
