package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CallMediaUserIntentTest {
    @Test fun explicitStopOutranksOldPlay() {
        assertTrue(CallMediaUserIntent.stopped("我刚才把音乐停了，想好好和你说话"))
        assertTrue(CallMediaUserIntent.stopped("别放歌了"))
        assertTrue(CallMediaUserIntent.stopped("把播放器暂停"))
        assertFalse(CallMediaUserIntent.restarted("我把音乐停了"))
    }

    @Test fun talkingAboutOldPlaybackIsNotANewPlayCommand() {
        assertFalse(CallMediaUserIntent.restarted("刚才你给我播放了音乐，结果又自己放起来"))
        assertFalse(CallMediaUserIntent.restarted("为什么挂电话音乐又播放了"))
        assertFalse(CallMediaUserIntent.restarted("那我们继续说话"))
    }

    @Test fun explicitNewMusicRequestsCanReauthorizePlayback() {
        assertTrue(CallMediaUserIntent.restarted("再放音乐"))
        assertTrue(CallMediaUserIntent.restarted("宝宝给我放音乐"))
        assertTrue(CallMediaUserIntent.restarted("放音乐吧"))
        assertFalse(CallMediaUserIntent.stopped("宝宝给我放音乐"))
    }
}
