package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StickerImportManifestTest {
    @Test fun importsAnEntireJsonPackWithDescriptionsAndUsage() {
        val input = """
            {
                "pack": "猫猫的日常",
                "items": [
                    {"url":"https://example.org/1.png","name":"抱抱猫","description":"一只猫张开两只前爪","usage":"安慰、求抱抱"},
                    {"url":"https://example.org/2.gif","name":"蹦跶猫","description":"一只猫跃起","usage":"开心"}
                ]
            }
        """.trimIndent()
        val imported = StickerImportManifest.parse(input)
        assertEquals(2, imported.size)
        assertEquals("猫猫的日常", imported[0].pack)
        assertEquals("一只猫张开两只前爪", imported[0].visualDescription)
        assertEquals("安慰、求抱抱", imported[0].usageHint)
        assertEquals("蹦跶猫", imported[1].name)
    }

    @Test fun pastingMultipleUrlsUsesSelectedPackAndDeduplicates() {
        val items = StickerImportManifest.parse(
            "https://example.org/a.png\nhttps://example.org/a.png\nhttps://example.org/b.gif",
            "朋友发来的整套猫猫",
        )
        assertEquals(2, items.size)
        assertTrue(items.all { it.pack == "朋友发来的整套猫猫" })
        assertTrue(items.all { it.visualDescription.isBlank() })
    }

    @Test fun importedCsvAndMarkdownKeepDescriptiveFields() {
        val rows = StickerImportManifest.parse(
            "图片地址,名称,画面描述,使用语气,分组\n" +
                "https://example.org/a.png,亲亲,猫咪嘟嘴,示好,自制组\n" +
                "![图](https://example.org/b.jpg) | 害羞 | 猫咪藏脸 | 害羞 | 自制组",
        )
        assertEquals(2, rows.size)
        assertEquals("亲亲", rows[0].name)
        assertEquals("猫咪藏脸", rows[1].visualDescription)
        assertEquals("自制组", rows[1].pack)
    }

    @Test fun rejectsNonHttpsAndLocalImageReferences() {
        for (source in listOf(
            "http://example.org/x.png",
            "file:///sdcard/private.png",
            "https://user:password@example.org/x.png",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                StickerImportManifest.parse(source)
            }
        }
    }
}
