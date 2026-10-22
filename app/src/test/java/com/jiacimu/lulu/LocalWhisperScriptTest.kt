package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalWhisperScriptTest {
    @Test fun convertsTraditionalTextToSimplified() {
        assertEquals("今天我们一起听音乐", LocalWhisperScript.simplifiedChinese("今天我們一起聽音樂"))
        assertEquals("", LocalWhisperScript.simplifiedChinese(""))
    }
}
