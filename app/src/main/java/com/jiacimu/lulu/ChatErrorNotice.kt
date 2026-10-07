package com.jiacimu.lulu

internal fun chatErrorNotice(raw: String): String {
    val message = raw.replace(Regex("(?i)Bearer\\s+[^\\s\"']+"), "Bearer [隐藏]")
        .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "[密钥隐藏]").take(220)
    val hint = when {
        Regex("(?i)insufficient|balance|quota exceeded|余额|额度不足|402").containsMatchIn(message) -> "账户余额或额度不足"
        Regex("(?i)no available|no channel|无可用|没有可用|渠道不可用").containsMatchIn(message) -> "没有可用模型渠道"
        Regex("401|403|(?i)unauthorized|invalid.*key").containsMatchIn(message) -> "账号密钥或访问权限不可用"
        Regex("429|(?i)rate.limit").containsMatchIn(message) -> "请求频率受限"
        Regex("(?i)timeout|timed out|network|connect|超时|网络").containsMatchIn(message) -> "网络连接或请求超时"
        else -> "回复失败"
    }
    return "$hint：$message"
}
