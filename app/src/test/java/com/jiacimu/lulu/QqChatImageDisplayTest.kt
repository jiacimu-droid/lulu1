package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class QqChatImageDisplayTest {
    @Test fun newSearchImageKeepsCharacterWordsApartFromAttribution() {
        val original = encodeQqChatImage(
            imageUri = "file:///data/user/0/app/files/online-image.jpg",
            caption = "搜来了，先看这个。",
            imageDescription = "联网搜到的图片",
            sourceUrl = "https://example.com/official",
            sourceProvider = "serper",
        )
        val decoded = decodeQqChatImage(original)
        assertNotNull(decoded)
        val display = qqChatImageDisplay(decoded!!)
        assertEquals("搜来了，先看这个。", display.caption)
        assertEquals("https://example.com/official", display.sourceUrl)
        assertEquals("图片来源", display.sourceLabel)
    }

    @Test fun legacyImageAttributionBecomesACompactLinkWithoutRewritingHistory() {
        val image = QqChatImageMessage(
            "file:///old-image.jpg",
            "先看这个～\n图片来源 · https://id5.163.com/news/official/sample.html",
            "旧的识图描述",
        )
        val display = qqChatImageDisplay(image)
        assertEquals("先看这个～", display.caption)
        assertEquals("https://id5.163.com/news/official/sample.html", display.sourceUrl)
        assertEquals("图片来源", display.sourceLabel)
    }

    @Test fun giphyStickerRetainsCaptionAndRequiredCredit() {
        val original = encodeQqChatImage(
            imageUri = "file:///image.gif",
            caption = "给你一个抱抱",
            sticker = true,
            sourceUrl = "https://giphy.com/gifs/example",
            sourceProvider = "giphy",
        )
        val image = decodeQqChatImage(original)!!
        val display = qqChatImageDisplay(image)
        assertEquals("给你一个抱抱", display.caption)
        assertEquals("Powered By GIPHY", display.sourceLabel)
        assertEquals("https://giphy.com/gifs/example", display.sourceUrl)
    }

    @Test fun personalPhotoWithoutSourceStillShowsItsNormalCaption() {
        val display = qqChatImageDisplay(QqChatImageMessage("file:///own.png", "我拍的！", ""))
        assertEquals("我拍的！", display.caption)
        assertEquals("", display.sourceUrl)
    }
}
