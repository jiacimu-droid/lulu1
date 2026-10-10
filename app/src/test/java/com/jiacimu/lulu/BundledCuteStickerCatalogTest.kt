package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class BundledCuteStickerCatalogTest {
    @Test fun everyStickerHasARealVisualDescriptionNotJustAnEmotionLabel() {
        val stickers = BundledCuteStickerCatalog.items
        assertEquals(29, stickers.size)
        assertEquals(29, stickers.map(BuiltInCuteSticker::id).distinct().size)
        assertEquals(setOf("猫猫表情", "软萌动物", "日常心情梗"), stickers.map { it.pack }.toSet())
        stickers.forEach { sticker ->
            assertTrue("Missing pictured content: ${sticker.code}", sticker.description.length >= 20)
            assertTrue("Missing social use: ${sticker.code}", sticker.usage.length >= 2)
            assertNotEquals("Usage is not the picture: ${sticker.code}", sticker.description, sticker.usage)
        }
    }

    @Test fun aCatKissHasDistinguishableVisualFactsAndSocialMeaning() {
        val sticker = BundledCuteStickerCatalog.items.single { it.code == "1F63D" }
        assertTrue(sticker.description.contains("猫"))
        assertTrue(sticker.description.contains("嘴巴"))
        assertTrue(sticker.usage.contains("示好"))
        val modelCaption = StickerLibraryStore.imageDescription(
            LuluSticker(sticker.id, "file:///test/sticker.png", sticker.name,
                pack = sticker.pack, visualDescription = sticker.description,
                usageHint = sticker.usage))
        assertTrue(modelCaption.contains("画面："))
        assertTrue(modelCaption.contains("嘴巴"))
        assertTrue(modelCaption.contains("可表达的语气"))
        assertFalse(modelCaption.startsWith("示好"))
    }

    @Test fun anUnknownPersonalImageIsNotFalselyDescribed() {
        val text = StickerLibraryStore.imageDescription(
            LuluSticker("custom", "file:///unknown.png", "自选表情"))
        assertTrue(text.contains("画面尚无识图描述"))
    }
}
