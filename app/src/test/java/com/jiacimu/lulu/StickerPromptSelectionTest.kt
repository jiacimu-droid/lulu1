package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class StickerPromptSelectionTest {
    private fun sticker(id: String, pack: String, name: String = id, favorite: Boolean = false) =
        LuluSticker(id, "file:///$id.png", name, favorite, pack, name, name)

    @Test fun laterPacksAndUserImportedImagesAreNotHiddenBehindTheFirstForty() {
        val all = (0 until 45).map { sticker("cat-$it", "猫猫") } +
            listOf(sticker("mood", "心情", "委屈流泪"), sticker("mine", "我的添加", "我的狗狗"))
        val selected = selectPromptStickers(all, 8, "今天好委屈，想流泪")
        assertEquals(8, selected.size)
        assertTrue(selected.any { it.id == "mood" })
        assertTrue(selected.any { it.id == "mine" })
        assertTrue(selected.any { it.pack == "猫猫" })
        assertEquals(selected.size, selected.map { it.id }.toSet().size)
    }

    @Test fun favoritesRemainReachableWithoutFabricatedImagesAndRespectZeroBudget() {
        val all = listOf(sticker("a", "动物"), sticker("b", "动物", favorite = true))
        assertEquals("b", selectPromptStickers(all, 1, "").single().id)
        assertEquals(all.size, selectPromptStickers(all, 20, "").size)
        assertTrue(selectPromptStickers(all, 0, "").isEmpty())
    }
}
