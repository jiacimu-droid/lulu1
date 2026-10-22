package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class StickerPromptSelectionTest {
    private fun sticker(id: String, pack: String, name: String = id, favorite: Boolean = false,
                        visual: String = name, usage: String = name) =
        LuluSticker(id, "file:///$id.png", name, favorite, pack, visual, usage)

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

    @Test fun teasingUsesSocialIntentEvenWithoutLiteralNameOverlap() {
        val stickers = listOf(
            sticker("sad", "猫", "哭唧唧", favorite = true, usage = "掉泪，委屈"),
            sticker("smirk", "猫", "眼睛半眯的猫", visual = "猫咪捂嘴笑", usage = "偷偷得意，故意逗人"),
        )
        assertEquals("smirk", selectPromptStickers(
            stickers, 1, StickerRetrievalContext(userMessage = "你是不是在偷笑我？"),
        ).single().id)
    }

    @Test fun seriousHurtDisfavorsMockeryDespiteMentioningLaughter() {
        val stickers = listOf(
            sticker("smirk", "猫", "坏笑猫", favorite = true, usage = "偷偷嘲笑捉弄人"),
            sticker("comfort", "动物", "小熊", usage = "温暖陪伴，安慰抱抱"),
        )
        assertEquals("comfort", selectPromptStickers(
            stickers, 1, StickerRetrievalContext(userMessage = "我很难过，别再笑我了"),
        ).single().id)
    }

    @Test fun celebratingRanksPositiveReactionAboveFavoriteSadSticker() {
        val stickers = listOf(
            sticker("sad", "动物", "委屈猫", favorite = true, usage = "流泪难过"),
            sticker("yay", "日常", "派对纸屑", usage = "庆祝，成功，开心"),
        )
        assertEquals("yay", selectPromptStickers(
            stickers, 1, StickerRetrievalContext(userMessage = "今天考试全对啦！"),
        ).single().id)
    }

    @Test fun actorMoodIsFallbackNotAReplacementForWhatUserSaid() {
        val stickers = listOf(
            sticker("smirk", "猫", "得意坏笑", usage = "狡黠，捉弄"),
            sticker("hug", "动物", "温柔小熊", usage = "抱抱，安慰，陪伴"),
        )
        assertEquals("hug", selectPromptStickers(
            stickers, 1, StickerRetrievalContext(userMessage = "我好难过", actorMood = "得意坏笑"),
        ).single().id)
    }

    @Test fun noMatchedSocialCueStillRespectsPackDiversityAndDeduplication() {
        val stickers = (0 until 20).map { sticker("cat-$it", "猫猫") } +
            listOf(sticker("mine", "我的添加", "自选表情"), sticker("other", "情绪", "发呆"))
        val retrieved = selectPromptStickers(stickers, 8, StickerRetrievalContext(userMessage = "在吗"))
        assertEquals(8, retrieved.size)
        assertTrue(retrieved.any { it.id == "mine" })
        assertTrue(retrieved.any { it.id == "other" })
        assertEquals(retrieved.size, retrieved.distinctBy { it.id }.size)
    }
}
