package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class XhsExplicitReadIntentTest {
    @Test fun userSearchIsAnActualToolInstruction() {
        val request = XhsExplicitReadIntent.parse("你现在去小红书搜索一下猫咪晒太阳的笔记")
        assertNotNull(request)
        assertEquals("猫咪晒太阳的笔记", request!!.keyword)
        assertEquals("", request.url)
    }

    @Test fun directNoteUsesExactLinkRatherThanSearchingByTitle() {
        val request = XhsExplicitReadIntent.parse(
            "你帮我读一下小红书这篇：https://www.xiaohongshu.com/explore/6777abcdef1234567890abcd",
        )
        assertNotNull(request)
        assertEquals("https://www.xiaohongshu.com/explore/6777abcdef1234567890abcd", request!!.url)
    }

    @Test fun talkingAboutBrokenCapabilityIsNotACommandToScrape() {
        assertNull(XhsExplicitReadIntent.parse("为什么你的小红书搜索线路找不到？"))
        assertNull(XhsExplicitReadIntent.parse("我今天在小红书看到了猫咪。"))
    }

    @Test fun homeBrowsingIsAllowedWhenNoKeywordSpecified() {
        val request = XhsExplicitReadIntent.parse("你去小红书看看")
        assertNotNull(request)
        assertEquals("", request!!.keyword)
    }

    @Test fun decliningOrCancellingAReadNeverStartsAnotherSearch() {
        listOf("你不要去小红书搜猫咪", "先别去小红书看看", "不用帮我搜索小红书了", "取消去小红书搜索猫咪")
            .forEach { assertNull(it, XhsExplicitReadIntent.parse(it)) }
    }
}
