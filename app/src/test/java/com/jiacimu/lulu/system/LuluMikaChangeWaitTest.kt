/* Mika upstream regression tests (Apache-2.0, Copyright 2026 wangzan101).
 * Adapted to Lulu module names and available kotlinx.coroutines.runBlocking.
 */
package com.jiacimu.lulu.system

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluMikaChangeWaitTest {
    private val before = IntArray(288) { 200 }
    private val after = IntArray(288) { 20 }
    /** 每 50 ms 画面都不一样的中间态(还在画、在播放)。 */
    private fun moving(t: Long) = IntArray(288) { ((t / 50 * 37 + it * 11) % 256).toInt() }

    /** 假时钟:sleep 推进时间;frameAt/lastEventAt 按时间给出画面与最近一次事件时刻。 */
    private class Fake(
        val frameAt: (Long) -> IntArray?,
        val lastEventAt: (Long) -> Long = { -10_000 },
        val treeChangedAt: Long = Long.MAX_VALUE,
    ) {
        var now = 0L
        suspend fun run(before: IntArray?, fallback: (suspend () -> Boolean)? = null) = LuluMikaChangeWait.awaitStableChange(
            now = { now },
            sleep = { now += it },
            frame = { frameAt(now) },
            quietForMs = { now - lastEventAt(now) },
            treeChanged = { now >= treeChangedAt },
            beforeFrame = before,
            fallback = fallback,
        )
    }

    @Test fun slowPageChangeIsReportedAfterFramesSettle() = runBlocking {
        // 页面 1.2 s 后才开始画,1.6 s 才画完;事件一直很安静,控件树没变(自绘界面)
        val f = Fake(frameAt = { t -> when { t < 1_200 -> before; t < 1_600 -> moving(t); else -> after } })
        assertTrue(f.run(before))
        assertTrue("waited for the frame to settle, at ${f.now}", f.now >= 1_600)
        assertTrue(f.now <= LuluMikaChangeWait.CAP_MS)
    }

    @Test fun fastPathReturnsAsSoonAsChangeIsStable() = runBlocking {
        val f = Fake(frameAt = { t -> if (t < 100) before else after })
        assertTrue(f.run(before))
        assertTrue("returned early, at ${f.now}", f.now <= 2 * LuluMikaChangeWait.POLL_MS)
    }

    @Test fun normalPageChangeReturnsSoonAfterEventsQuiet() = runBlocking {
        // 普通翻页:事件持续到 100 ms,控件树和画面都变了
        val f = Fake(frameAt = { t -> if (t < 100) before else after }, lastEventAt = { t -> minOf(t, 100) }, treeChangedAt = 50)
        assertTrue(f.run(before))
        assertTrue("returned at ${f.now}", f.now in 100 + LuluMikaChangeWait.QUIET_MS..100 + LuluMikaChangeWait.QUIET_MS + LuluMikaChangeWait.POLL_MS)
    }

    @Test fun neverStillPageWithTreeChangeReturnsFast() = runBlocking {
        // 视频、直播:画面一直在动,但事件安静了、控件树变了,不用等画面停
        val f = Fake(frameAt = ::moving, treeChangedAt = 0)
        assertTrue(f.run(before))
        assertTrue("returned at ${f.now}", f.now <= LuluMikaChangeWait.POLL_MS)
    }

    @Test fun nothingChangesReturnsFalseAtCap() = runBlocking {
        val f = Fake(frameAt = { before })
        assertFalse(f.run(before))
        assertTrue(f.now >= LuluMikaChangeWait.CAP_MS)
        assertTrue(f.now < LuluMikaChangeWait.CAP_MS + LuluMikaChangeWait.POLL_MS)
    }

    @Test fun waitsForEventQuietBeforeJudging() = runBlocking {
        // 控件树一开始就变了,但事件持续到 1 s
        val f = Fake(frameAt = { after }, lastEventAt = { t -> minOf(t, 1_000) }, treeChangedAt = 0)
        assertTrue(f.run(before))
        assertTrue("judged at ${f.now}", f.now >= 1_000 + LuluMikaChangeWait.QUIET_MS)
    }

    @Test fun treeChangeWithoutFrameCounts() = runBlocking {
        // 没有画面(预览帧不可用):只看控件树
        val f = Fake(frameAt = { null }, treeChangedAt = 900)
        assertTrue(f.run(null))
        assertTrue(f.now in 900..LuluMikaChangeWait.CAP_MS)
    }

    @Test fun stillAnimatingAtCapButChangedCountsAsChanged() = runBlocking {
        val f = Fake(frameAt = ::moving)
        assertTrue(f.run(before))
        assertTrue(f.now >= LuluMikaChangeWait.CAP_MS)
    }

    @Test fun eventsNeverQuietDecidesAtCapWithCurrentState() = runBlocking {
        // 事件一直不停(例如进度条):到上限按当时的状态下结论
        val changed = Fake(frameAt = { after }, lastEventAt = { it }, treeChangedAt = 0)
        assertTrue(changed.run(before))
        assertTrue(changed.now >= LuluMikaChangeWait.CAP_MS)
        assertFalse(Fake(frameAt = { before }, lastEventAt = { it }).run(before))
    }

    @Test fun fallbackRunsAtProbeWhenPrimaryHasNoEffect() = runBlocking {
        var fallbackAt = -1L
        val f = Fake(
            frameAt = { t -> if (fallbackAt in 0..t) after else before },
            lastEventAt = { t -> if (fallbackAt in 0..t) fallbackAt else -10_000 },
        )
        val changed = f.run(before) {
            fallbackAt = f.now
            true
        }
        assertTrue(changed)
        assertTrue("fallback triggered at $fallbackAt", fallbackAt == LuluMikaChangeWait.PROBE_MS)
        assertTrue("settled at ${f.now}", f.now in (LuluMikaChangeWait.PROBE_MS + LuluMikaChangeWait.QUIET_MS)..(LuluMikaChangeWait.PROBE_MS + LuluMikaChangeWait.QUIET_MS + LuluMikaChangeWait.POLL_MS))
    }

    @Test fun fallbackSkippedWhenPrimaryAlreadyAnimatingOrEmittingEvents() = runBlocking {
        var calls = 0
        // 550 ms 时画面已经开始动了,不能误触发备用点击
        val animating = Fake(frameAt = { t -> when { t < 300 -> before; t < 900 -> moving(t); else -> after } })
        assertTrue(animating.run(before) { calls++; true })
        assertTrue(calls == 0)

        // 550 ms 时无障碍事件还没安静,也不能抢着补点
        val noisy = Fake(
            frameAt = { t -> if (t < 800) before else after },
            lastEventAt = { t -> minOf(t, 700) },
            treeChangedAt = 800,
        )
        assertTrue(noisy.run(before) { calls++; true })
        assertTrue(calls == 0)
    }

    @Test fun fallbackExtendsDeadlineAndSkippedFallbackKeepsOriginalCap() = runBlocking {
        // 备用通道在 550 ms 触发后,页面到 2_700 ms(超过原 2_500 ms 上限)才变,新窗口仍能等到
        var fallbackAt = -1L
        val extended = Fake(frameAt = { t -> if (t < 2_700) before else after })
        assertTrue(extended.run(before) { fallbackAt = extended.now; true })
        assertTrue(fallbackAt == LuluMikaChangeWait.PROBE_MS)
        assertTrue("settled at ${extended.now}", extended.now in 2_700..(LuluMikaChangeWait.PROBE_MS + LuluMikaChangeWait.CAP_MS))

        // 备用通道返回 false(无可用备用目标)时,照原上限 2_500 ms 结束,不白等双倍时间
        val noTarget = Fake(frameAt = { before })
        assertFalse(noTarget.run(before) { false })
        assertTrue("ended at ${noTarget.now}", noTarget.now in LuluMikaChangeWait.CAP_MS..(LuluMikaChangeWait.CAP_MS + LuluMikaChangeWait.POLL_MS))
    }
}
