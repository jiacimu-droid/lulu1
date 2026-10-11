package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class PhoneTranscriptAssemblerTest {
    @Test fun combineContinuousChineseSpeechWithoutLosingRepeatedWords() {
        assertEquals("哈哈哈哈哈哈，我今天还想讲", PhoneTranscriptAssembler.combine(
            "哈哈哈哈", "哈哈，我今天还想讲"))
        assertEquals("前面这一大段，后面依然连续。", PhoneTranscriptAssembler.combine(
            "前面这一大段，", "后面依然连续。"))
    }

    @Test fun joinEnglishAtWordBoundaryAndRespectEmptyChunks() {
        assertEquals("hello world", PhoneTranscriptAssembler.combine("hello", "world"))
        assertEquals("我说话", PhoneTranscriptAssembler.combine("我说", "话"))
        assertEquals("刚才", PhoneTranscriptAssembler.combine("", "刚才"))
        assertEquals("刚才", PhoneTranscriptAssembler.combine("刚才", ""))
    }
}
