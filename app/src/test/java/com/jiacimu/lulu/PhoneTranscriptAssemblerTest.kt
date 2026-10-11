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

    @Test fun actualAudioOverlapStitchesWordsWithoutDroppingRealRepetition() {
        assertEquals("今天讲到这里，明天再说", PhoneTranscriptAssembler.combineOverlapped(
            "今天讲到这里，", "讲到这里，明天再说"))
        assertEquals("哈哈哈哈哈哈", PhoneTranscriptAssembler.combineOverlapped(
            "哈哈哈", "哈哈哈"))
        assertEquals("前一段后一段", PhoneTranscriptAssembler.combineOverlapped(
            "前一段", "后一段"))
        assertEquals("hello world", PhoneTranscriptAssembler.combineOverlapped(
            "hello wor", "lo world"))
        assertEquals("一整个句子", PhoneTranscriptAssembler.combineOverlapped(
            "一整个句子", "一整个句子"))
    }

    @Test fun joinEnglishAtWordBoundaryAndRespectEmptyChunks() {
        assertEquals("hello world", PhoneTranscriptAssembler.combine("hello", "world"))
        assertEquals("我说话", PhoneTranscriptAssembler.combine("我说", "话"))
        assertEquals("刚才", PhoneTranscriptAssembler.combine("", "刚才"))
        assertEquals("刚才", PhoneTranscriptAssembler.combine("刚才", ""))
    }
}
