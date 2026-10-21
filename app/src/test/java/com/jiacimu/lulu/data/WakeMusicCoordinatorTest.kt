package com.jiacimu.lulu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.content.Context

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WakeMusicCoordinatorTest {
    @Test fun obsoletePendingMusicNeverRestartsWhenOrdinaryCallEnds() {
        val context = RuntimeEnvironment.getApplication()
        val store = context.getSharedPreferences("lulu_wake_music_actions", Context.MODE_PRIVATE)
        store.edit().putString("pending:wake-role", "old-music-task").commit()
        WakeMusicCoordinator.onCallEnded(context, "wake-role")
        assertFalse(store.contains("pending:wake-role"))
    }
}
