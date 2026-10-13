package com.jiacimu.lulu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeMusicCoordinatorTest {
    @Test fun onlyExplicitAwakeConfirmationCanPlayMusic() {
        assertTrue(WakeMusicCoordinator.explicitlyAwake("我醒了"))
        assertTrue(WakeMusicCoordinator.explicitlyAwake("嗯，我已经醒了！"))
        assertFalse(WakeMusicCoordinator.explicitlyAwake("我还没醒"))
        assertFalse(WakeMusicCoordinator.explicitlyAwake("我没起来"))
        assertFalse(WakeMusicCoordinator.explicitlyAwake("醒了再说"))
        assertFalse(WakeMusicCoordinator.explicitlyAwake("我醒了，但是别放歌"))
    }
}
