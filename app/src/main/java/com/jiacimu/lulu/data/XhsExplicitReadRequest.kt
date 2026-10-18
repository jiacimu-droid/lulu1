package com.jiacimu.lulu.data

import com.jiacimu.lulu.xhsUrlInText

/**
 * High-confidence USER instructions, not generic mentions of Xiaohongshu.
 * If a planner mistakenly says "I don't have that feature", execution may
 * still be attempted against the actual registered tool. Failure stays real.
 */
internal data class XhsExplicitReadRequest(val keyword: String = "", val url: String = "")

internal object XhsExplicitReadIntent {
    private val platform = Regex("""小红书|红薯|(?i:xhs)|xiaohongshu\.com|xhslink\.com""")
    private val command = Regex("""(?:你|请|麻烦|帮我|给我|去|现在|试试|想让你|让你|替我).{0,52}(?:搜|搜索|查找|阅读|读|看看|看下|看一看|浏览|刷|打开)""")
    private val metaDiscussion = Regex("""(?:为什么|怎么回事|找不到.{0,8}(?:线路|功能)|(?:线路|功能).{0,6}(?:不通|缺失|有问题))""")
    private val declined = Regex("""(?:不要|不用|别|先别|暂时别|不必|停止|取消).{0,22}(?:搜|查找|阅读|读|看看|看下|看一看|浏览|刷|打开)""")
    private val query = Regex("""(?:搜索|查找|搜)(?:一下|下|一搜|一查)?(?:小红书)?(?:上|里|里的|上面|的)?\s*(?:关于)?\s*(.{1,64})""")

    fun parse(userText: String): XhsExplicitReadRequest? {
        val text = userText.replace('\n', ' ').trim().take(2_000)
        if (!platform.containsMatchIn(text) || !command.containsMatchIn(text) ||
            metaDiscussion.containsMatchIn(text) || declined.containsMatchIn(text)) return null
        val url = xhsUrlInText(text)
        if (url != null) return XhsExplicitReadRequest(url = url)
        val topic = query.find(text)?.groupValues?.getOrNull(1).orEmpty()
            .trim('。', '？', '！', '，', ',', '：', ':', ' ', '吧', '呢')
            .removePrefix("一下").trim().take(64)
        return XhsExplicitReadRequest(keyword = topic)
    }
}
