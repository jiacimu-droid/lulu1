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

    /**
     * Emotionally expressive phone models need the full speaking turn as one input.
     * Cutting after each period hides the preceding cause of an emotion from TTS,
     * so [angry] on a later sentence may be heard as a new, unrelated outburst.
     * Legacy voices still use the low-latency incremental queue.
     */
    fun phoneNeedsWholeTurn(context: Context): Boolean = supportsTags(context)

    fun forPlayback(context: Context, text: String): String =
        if (supportsTags(context)) unfinished.replace(text, "").trim() else plain(text)

    val direction = """
        【先理解整段，再安排声音】先判断这一次完整发言的核心情绪、对象、原因，以及从开头到结尾真实发生了哪些情绪变化，再在实际转折处插入英文方括号音频标签。
        不要逐句重新猜一种情绪，也不要一句一句随机切换标签。相邻句子没有真实转折，就保持相同的情感底色；通常开头一个准确的标签足够，真正转折时才添加新的标签。不以标签数量作为表现丰富的标准。
        尤其分清对象和语义：后悔、自责、羞愧、难过、想求原谅，不等于 [angry] 或 [shouting]。例如角色怪自己做错了事，多半可以轻声、迟疑、压抑地反省；只有确实愤怒、且情节支持情绪骤变，才允许突然提高音量。也不能把对自己的愧疚演成对对方发火。
        可以有层次：欲言又止、吸气、声音发紧、轻声认错、勉强平静；不要永远哭、永远低语或固定模板。留意上文角色的说话方式、关系状态和本轮真实心情，保持同一个人的声音。
        可用语气如 [warmly]、[regretful]、[softly]、[hesitant]、[excited]、[sarcastic]、[angry]（仅真愤怒时）；真实发声如 [sighs]、[inhales]、[exhales]、[laughs]、[gasps]、[coughs]。
        标签只是表演指示，不是新增情节；不要用音效凭空新增打人、亲密接触或环境事件。声音方向只写在音频轨，正文不念标签和旁白。
    """.trimIndent()

    fun phoneInstruction(context: Context, sleepMode: Boolean = false): String {
        val regular = if (supportsTags(context)) """
            电话的 text 是这一整轮角色真实说出口的完整台词，随后会作为一段连贯文本送往语音服务；允许嵌入英文音频标签，这是 text 不放内部指令规则的唯一例外。
            请先确定这一轮完整发言的情绪弧线。不要一句一句独立添加 [angry]、[sad] 等相互冲突的标签，也不要对每个句子重复一个相同标签；声音变化必须跟随角色真实心理转折。
            $direction
            电话只能表现当前通话实际可听见的声音，不编造与用户同处一室或隔着电话触碰用户。
        """.trimIndent() else ""
        if (!sleepMode) return regular
        val whisper = if (supportsTags(context)) """
            当前是哄睡通话，低语优先于上述普通电话的情绪标签规则。整轮用 [whispers] 气声般地轻轻说话，不能突然切换成高声、兴奋、喊叫或者戏剧化表演；不需要插入 [laughs]、[gasps] 等响亮的声音。可在自然换气处少量加入 [inhales]、[exhales]，在完整意群之间用 [pause] 或 [long pause] 拉开节奏；不要每隔两三个字就喘一次。
            内容仍要真实、有情感和个性，不是单调机械地朗读指令。可以慢一点，给听者留出自然停顿。
            语音播放层会去掉本轮其他情绪标签，并在每个自然语段补入 [whispers]，确保持续低语；正文不朗读标签。
        """.trimIndent() else """
            当前是哄睡通话：用短而自然的口语、放缓叙述和温柔的停顿来营造贴近的轻声陪伴。
            此语音引擎不支持英文表演标签，不要把 [whispers] 之类标记写进真实台词，也不宣称设备音色已经切换成功。
        """.trimIndent()
        return listOf(regular, whisper).filter(String::isNotBlank).joinToString("\n")
    }

    /**
     * Voice models may omit or lose a performance label between clauses.
     * During opt-in bedtime calls add consistent low-volume directions to the
     * AUDIO track only. Spoken characters and subtitles remain unchanged.
     */
    fun sleepAudio(context: Context, text: String, sleepMode: Boolean): String {
        if (!sleepMode) return text
        // Bedtime has its own performance track. Generated excitement, gasps,
        // laughs or shouting must not undo a continuous whisper instruction.
        // Keep only spoken words; subtitles and stored transcripts stay as-is.
        val words = plain(text)
        if (!supportsTags(context) || words.isBlank()) return words
        val result = StringBuilder("[whispers] ")
        var sinceWhisper = 0
        var sentenceCount = 0
        var pauseCount = 0
        words.forEachIndexed { index, character ->
            result.append(character)
            if (!character.isWhitespace()) sinceWhisper++
            val majorBreak = character in "。！？!?；;"
            val naturalBreak = majorBreak || character in "，,、：:"
            val moreSpeech = words.substring(index + 1).any { it.isLetterOrDigit() }
            if (naturalBreak && sinceWhisper >= 2 && moreSpeech) {
                // Reassert whisper on every natural clause. Audible breath
                // cues are occasional, not a repetitive gasp between words.
                if (majorBreak) sentenceCount++
                pauseCount++
                when {
                    majorBreak && sentenceCount % 3 == 0 ->
                        result.append(" [long pause] [exhales] ")
                    majorBreak && sentenceCount % 2 == 0 ->
                        result.append(" [pause] ")
                    pauseCount % 5 == 0 ->
                        result.append(" [inhales] ")
                }
                result.append(" [whispers] ")
                sinceWhisper = 0
            }
        }
        return result.toString().trim()
    }

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
