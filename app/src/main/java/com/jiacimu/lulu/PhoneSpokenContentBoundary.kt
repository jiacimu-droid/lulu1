package com.jiacimu.lulu

/**
 * A final publication boundary for telephone speech. The character may privately
 * consider device observations, but these are not dialogue. If the model writes
 * analysis as spoken text, suppress the whole turn rather than surgically
 * deleting phrases and accidentally inventing a different utterance.
 */
internal object PhoneSpokenContentBoundary {
    private val timeStamp = Regex("""\b20\d{2}-\d{2}-\d{2}T\d{2}:\d{2}""")
    private val protocol = Regex("""(?i)(\binnerThought\b|\bappraisal\b|\btoolResult\b|\bcall-silence\b|\bspeechIntent\b|\bcommitmentChanges\b)""")
    private val telemetry = Regex(
        """麦克风.{0,12}(静音|状态|未静音)|语音识别.{0,12}(结果|未返回|无新增)|最近一次.{0,10}(角色发言|语音活动)|角色发言时间|上一轮发言时间|时间线.{0,10}(查看|查询|记录)|(?:查看|读取|检查)时间线"""
    )
    private val subjectAnalysis = Regex(
        """(?:电话里|通话中).{0,14}(她|用户).{0,40}(没回应|没说话|静音)|(?:她|用户).{0,15}(刚才说|没有新增发言).{0,55}(时间|记录|需要确认)|(?:现在时间|本地时间).{0,40}(上一|发言|时间线)"""
    )

    fun leaksInternalAnalysis(speech: String): Boolean {
        val s = speech.trim()
        if (s.isBlank()) return false
        if (s.startsWith("{") || s.startsWith("```") || protocol.containsMatchIn(s))
            return true
        val signals = listOf(
            timeStamp.containsMatchIn(s),
            telemetry.containsMatchIn(s),
            subjectAnalysis.containsMatchIn(s),
            s.contains("我需要看时间线") || s.contains("上一轮角色发言") ||
                s.contains("本轮没有新增用户发言"),
        )
        // Diagnostic telemetry plus a second independent sign of deliberation
        // is an internal observation accidentally being voiced, not a chat line.
        return signals.count { it } >= 2 ||
            s.contains("上一次角色发言时间") ||
            s.contains("我需要看时间线") ||
            s.contains("角色本轮") && s.contains("执行器")
    }
}
