package com.jiacimu.lulu.data

import android.database.sqlite.SQLiteOpenHelper
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices

/** Robolectric replaces the app per test but retains Kotlin objects in its SDK sandbox. */
internal object MemoryTestEnvironment {
    fun release() {
        synchronized(SharedExperienceTimeline) {
            val field = SharedExperienceTimeline::class.java.getDeclaredField("helper").apply { isAccessible = true }
            (field.get(SharedExperienceTimeline) as? SQLiteOpenHelper)?.close()
            field.set(SharedExperienceTimeline, null)
        }
        clear(LuluRepositories, "performanceInternal")
        clear(LuluRepositories.memory, "prefs", "advancedPrefs")
        clear(LuluRepositories.lexicon, "prefs")
        clear(MemoryExtractionJobStore, "prefs")
        clear(LuluAiServices, "connectionStoreInternal", "gatewayInternal")
    }

    private fun clear(target: Any, vararg names: String) {
        names.forEach { name -> target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, null) }
    }
}
