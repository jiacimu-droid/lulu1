package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.MeetingSegment
import com.jiacimu.lulu.data.MeetingSegmentType

/** Audio directions are a performance track, never dialogue or memory facts. */
internal object VoicePerformance {
    private val tags = Regex("\\[[A-Za-z][A-Za-z0-9 ,.'’:_/!()?+\\-]{0,119}\\]")
    private val unfinished = Regex("\\[[A-Za-z][^\\]\\r\\n]*$")

    fun plain(text: String): String = tags.replace(unfinished.replace(text, ""), "").trim()

    fun supportsTags(context: Context): Boolean {
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        return prefs.getString("tts_provider", "system") == "elevenlabs" &&
            ElevenLabsModels.dialogue(prefs.getString("eleven_tts_model", ElevenLabsModels.DEFAULT).orEmpty())
    }

    fun forPlayback(context: Context, text: String): String =
        if (supportsTags(context)) unfinished.replace(text, "").trim() else plain(text)

    val direction = """
        发声表现要结合上下文、人设、上一刻情绪和当前动作，不能把每句都读成同一种平直语气。
        在实际发生的位置插入英文方括号音频标签，可以丰富、连续变化，不设固定数量或每句上限。
        情绪与语气例如 [warmly]、[sarcastic]、[excited]、[whispering]、[shouting]、[hesitant]；
        身体发声例如 [laughs]、[sighs]、[inhales]、[exhales]、[gasps]、[sneezes]、[coughs]、[swallows]；
        现场音效可用清楚的英文声音描述，例如 [slap sound]、[gentle footsteps]、[door closes]。
        可以按真实情境组合标签和改变力度、速度、停顿，不为了凑标签编造动作，也不把所有角色统一成轻笑或低语。
        标签必须跟随已经描写/发生的行为；不借音效新增打人、亲密接触、环境事件或替用户决定行为。
        台词每个完整句子的发声可以承接上一句的情绪；持续的语气可在后一句继续标注，独立音效不要重复。
        声音方向只写在音频轨；正文中不写标签解释，不念“他说”“她叹了口气”等舞台说明。
    """.trimIndent()

    fun phoneInstruction(context: Context): String = if (supportsTags(context)) """
        电话的 text 是直接送往语音服务的发声文本，允许在原话中插入音频标签；这是 text 不放内部指令规则的唯一例外。
        $direction
        电话只能表现当前通话实际可听见的声音，不编造与用户同处一室或隔着电话触碰用户。
    """.trimIndent() else ""

    fun meetingSegment(type: MeetingSegmentType, text: String, speechText: String, user: Boolean = false): MeetingSegment {
        val clean = plain(text)
        if (user) return MeetingSegment(type, clean)
        val candidate = speechText.trim().ifBlank { if (type == MeetingSegmentType.DIALOGUE) text.trim() else "" }
        val audio = if (type == MeetingSegmentType.DIALOGUE) {
            if (plain(candidate).filterNot(Char::isWhitespace) != clean.filterNot(Char::isWhitespace)) clean
            else if (plain(candidate) == clean) candidate
            else alignToText(clean, candidate)
        } else {
            // An action page may sound its events, but must never narrate its prose aloud.
            candidate.takeIf { it.isNotBlank() && plain(it).isBlank() && tags.containsMatchIn(it) }.orEmpty()
        }
        return MeetingSegment(type, clean, audio)
    }

    private fun alignToText(text: String, performance: String): String {
        val directions = linkedMapOf<Int, MutableList<String>>()
        var spoken = 0
        var cursor = 0
        tags.findAll(performance).forEach { tag ->
            spoken += performance.substring(cursor, tag.range.first).count { !it.isWhitespace() }
            directions.getOrPut(spoken) { mutableListOf() }.add(tag.value)
            cursor = tag.range.last + 1
        }
        return buildString {
            var position = 0
            text.forEach { char ->
                if (!char.isWhitespace()) {
                    directions.remove(position)?.forEach { append(it) }
                    position++
                }
                append(char)
            }
            directions.values.flatten().forEach { append(it) }
        }
    }

    /** Map a displayed text range onto the audio track without repeating action sounds on every page. */
    fun slice(text: String, start: Int, end: Int): String {
        val untrimmed = tags.replace(text, "")
        val left = untrimmed.length - untrimmed.trimStart().length
        val from = start + left
        val through = end + left
        val lastPage = end == untrimmed.trim().length
        val out = StringBuilder()
        var index = 0
        var visible = 0
        while (index < text.length) {
            val tag = tags.find(text, index)?.takeIf { it.range.first == index }
            if (tag != null) {
                if ((visible >= from && visible < through) || (start == 0 && visible < from) ||
                    (lastPage && visible >= through)) out.append(tag.value)
                index = tag.range.last + 1
            } else {
                if (visible in from until through) out.append(text[index])
                visible++
                index++
            }
        }
        return out.toString().trim()
    }
}
