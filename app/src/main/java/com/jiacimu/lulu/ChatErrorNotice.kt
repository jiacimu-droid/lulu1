package com.jiacimu.lulu

internal fun chatErrorNotice(raw: String): String {
    val message = raw.replace(Regex("(?i)Bearer\\s+[^\\s\"']+"), "Bearer [隐藏]")
        .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "[密钥隐藏]").take(220)
    if (Regex("未知能力|tool.*(?:缺失|为空)|无效(?:的)?工具|未提供有效的操作类型|action=tool").containsMatchIn(message)) {
        return "这次回复没成功发送，请点右下角「回复」重试"
    }
    val hint = when {
        Regex("(?i)insufficient|balance|quota exceeded|余额|额度不足|402").containsMatchIn(message) -> "账户余额或额度不足"
        Regex("(?i)no available|no channel|无可用|没有可用|渠道不可用").containsMatchIn(message) -> "没有可用模型渠道"
        Regex("401|403|(?i)unauthorized|invalid.*key").containsMatchIn(message) -> "账号密钥或访问权限不可用"
        Regex("429|(?i)rate.limit").containsMatchIn(message) -> "请求频率受限"
        Regex("(?i)timeout|timed out|network|connect|超时|网络").containsMatchIn(message) -> "网络连接或请求超时"
        else -> "回复失败"
    }
    return when (hint) {
        "回复失败" -> "回复暂时失败，点「回复」重试"
        else -> "$hint，请检查连接后重试"
    }
}
