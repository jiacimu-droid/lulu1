/* Ported from Mika core/uistate/Accuracy.kt (Apache-2.0).
 * Copyright 2026 wangzan101. Modified: scoped to local verified search
 * fields; no model or third-party dependencies. Original NOTICE/LICENSE
 * in app/src/main/assets/licenses.
 */
package com.jiacimu.lulu.system

internal object LuluMikaTextAccuracy {
    enum class Verification(val wire: String) {
        MATCH("match"), MISMATCH("mismatch"), UNKNOWN("unknown")
    }

    /** 输入框读写。[read] 返回用户可见的实际文字(显示提示语时为空串),读不出(密码框、节点已不在)为 null。 */
    interface TextField {
        fun read(): String?
        fun write(text: String): Boolean
        /** 第一次写入后读回不一致时的换通道重试;没有另一通道的字段默认照原方式再写。 */
        fun retry(text: String): Boolean = write(text)
    }
    
    /** 比较前去掉所有空白:输入框可能自动给号码分组加空格。 */
    private fun normText(s: String) = s.filterNot { it.isWhitespace() }
    
    /**
     * 读回是不是「原提示语 + 空白 + 目标文字」这种有歧义的形式。
     * 它可能是控件把标签和正文拼在了一起,也可能用户正文本来就以标签开头;不能删前缀或判成匹配,调用方应停止重试并报 UNKNOWN。
     */
    fun ambiguousFieldReadback(shown: String, placeholder: String?, typing: String?): Boolean {
        val label = placeholder?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val wanted = typing?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val value = shown.trim()
        if (!value.startsWith(label) || !value.endsWith(wanted)) return false
        val separatorLength = value.length - label.length - wanted.length
        if (separatorLength <= 0) return false
        return value.substring(label.length, label.length + separatorLength).all { it.isWhitespace() }
    }
    
    /**
     * 写入 [text] 并读回核对,不一致重试,最多 [attempts] 次;[settle] 在每次写入后等输入框更新。
     * 只认「写入后读到的文字恰好是目标」;写入前就已经是目标时先清空,清不掉就无法区分写没写进去,返回 UNKNOWN。
     * 空串表示清空,读回为空即 MATCH。
     * 有的输入框对无障碍一直报同一段文字(如提示语),读回永远对不上:每次读回都和写入前一样、
     * 至少有一次写入成功、而 [reacted] 说界面在输入后出现了含所输文字的新文字(联想词等)时,
     * App 其实收到了输入,返回 UNKNOWN 而不是 MISMATCH。
     * [secret](密码框)只写一次、不重试、不读回:密码框读不回原文,重试只会把同一段字又往框里写一遍。
     */
    suspend fun typeAndVerify(
        field: TextField,
        text: String,
        attempts: Int = 2,
        settle: suspend () -> Unit = {},
        reacted: () -> Boolean = { false },
        secret: Boolean = false,
    ): Verification {
        if (secret) {
            return if (field.write(text)) Verification.UNKNOWN else Verification.MISMATCH
        }
        val want = normText(text)
        var baseline = field.read() ?: run {
            // 读不出也照常写一次,但无法核对;写入本身被拒绝不能说成未知
            return if (field.write(text)) Verification.UNKNOWN else Verification.MISMATCH
        }
        if (want.isNotEmpty() && normText(baseline) == want) {
            val cleared = field.write("")
            settle()
            baseline = field.read() ?: return if (cleared) Verification.UNKNOWN else Verification.MISMATCH
            if (normText(baseline) == want) {
                return if (field.write(text)) Verification.UNKNOWN else Verification.MISMATCH
            }
        }
        var stuck = true
        var wrote = false
        repeat(attempts) { attempt ->
            val accepted = if (attempt == 0) field.write(text) else field.retry(text)
            if (!accepted) return@repeat
            wrote = true
            settle()
            val actual = field.read() ?: return Verification.UNKNOWN
            if (normText(actual) == want) return Verification.MATCH
            if (actual != baseline) stuck = false
        }
        return if (wrote && stuck && reacted()) Verification.UNKNOWN else Verification.MISMATCH
    }
}
