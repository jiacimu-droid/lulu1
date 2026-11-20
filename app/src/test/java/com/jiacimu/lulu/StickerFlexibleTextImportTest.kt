package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class StickerFlexibleTextImportTest {
    @Test fun titleThenLinkThenDescriptionForMultipleStickers() {
        val actual = StickerFlexibleTextImport.parse(
            """
            名称：摸摸头
            图片链接：https://example.org/hello.png
            画面描述：白猫伸出爪子摸头
            使用场景：安慰
            标题：怒怒猫
            地址：https://example.org/angry.webp
            描述：橘猫皱眉，嘴巴下弯
            语气：生气
            """.trimIndent(), "整组测试",
        )
        assertEquals(2, actual.size)
        assertEquals("摸摸头", actual[0].name)
        assertEquals("白猫伸出爪子摸头", actual[0].visualDescription)
        assertEquals("怒怒猫", actual[1].name)
        assertEquals("橘猫皱眉，嘴巴下弯", actual[1].visualDescription)
        assertEquals("整组测试", actual[1].pack)
    }

    @Test fun linkThenTitleThenDescriptionWorks() {
        val actual = StickerFlexibleTextImport.parse(
            """
            https://example.org/1.png
            名称：求抱抱
            描述：小狗张开双手
            https://example.org/2.png
            标题：偷笑
            内容：猫咪捂嘴
            """.trimIndent(),
        )
        assertEquals(2, actual.size)
        assertEquals("求抱抱", actual[0].name)
        assertEquals("小狗张开双手", actual[0].visualDescription)
        assertEquals("偷笑", actual[1].name)
        assertEquals("猫咪捂嘴", actual[1].visualDescription)
    }

    @Test fun tableSupportsUrlInDifferentColumns() {
        val actual = StickerFlexibleTextImport.parse(
            """
            名称 | 图片链接 | 描述 | 用途 | 分组
            撒娇 | https://example.org/cat.png | 猫咪靠近镜头 | 亲近 | 日常猫
            https://example.org/dog.webp | 小狗 | 小狗歪头 | 疑惑 | 日常狗
            """.trimIndent(),
        )
        assertEquals(2, actual.size)
        assertEquals("撒娇", actual[0].name)
        assertEquals("猫咪靠近镜头", actual[0].visualDescription)
        assertEquals("日常猫", actual[0].pack)
        assertEquals("小狗", actual[1].name)
        assertEquals("日常狗", actual[1].pack)
    }

    @Test fun supportsQuotedCsvWithDescriptionAndUrlInDifferentColumns() {
        val entries = StickerFlexibleTextImport.parse(
            "名称,图片链接,描述,用途,分组\n" +
                "\"撒娇\",https://example.org/a.png,\"猫咪伸手,靠近镜头\",求抱抱,萌猫",
        )
        assertEquals(1, entries.size)
        assertEquals("撒娇", entries[0].name)
        assertEquals("猫咪伸手,靠近镜头", entries[0].visualDescription)
        assertEquals("萌猫", entries[0].pack)
    }

    @Test fun absentDescriptionRemainsUnknown() {
        val actual = StickerFlexibleTextImport.parse(
            "名称：神秘猫\n图片：https://example.org/unknown.gif",
        )
        assertEquals(1, actual.size)
        assertEquals("", actual[0].visualDescription)
    }
}
