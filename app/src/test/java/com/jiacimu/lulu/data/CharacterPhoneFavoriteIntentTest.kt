package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CharacterPhoneFavoriteIntentTest {
    @Test fun naturalImmediateWishCanSaveWithoutStrictPhoneFavoriteJson() {
        assertTrue(CharacterPhoneFavoriteIntent.decided("我想把你刚刚说的那句收藏起来。"))
        assertTrue(CharacterPhoneFavoriteIntent.decided("我想收藏你刚才那句话。"))
        assertTrue(CharacterPhoneFavoriteIntent.decided("我现在把这条消息收藏进辞海。"))
    }

    @Test fun deferredHypotheticalToolOrUserQuestionNeverAutoSaves() {
        assertFalse(CharacterPhoneFavoriteIntent.decided("等挂断电话后我再收藏。"))
        assertFalse(CharacterPhoneFavoriteIntent.decided("我暂时不想收藏。"))
        assertFalse(CharacterPhoneFavoriteIntent.decided("我想检查收藏工具的权限。"))
        assertFalse(CharacterPhoneFavoriteIntent.decided("你现在可以收藏我的消息吗？"))
        assertFalse(CharacterPhoneFavoriteIntent.decided("或许以后想收藏"))
    }
}
