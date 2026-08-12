package com.jiacimu.lulu.data

import android.content.Context

/**
 * Keyword-only memory extraction is intentionally disabled.
 *
 * Long-term facts, preferences, boundaries and corrections now come from the configured batch
 * memory model, which sees the surrounding conversation and can decide whether a sentence really
 * represents a durable correction instead of reacting to isolated words such as “不是” or “其实”.
 * This object remains as a compatibility initializer so existing startup wiring does not change.
 */
object DeterministicMemoryAutomation {
    @Synchronized
    fun initialize(context: Context) {
        // No-op by design. Do not create durable memories from lexical trigger words alone.
        context.applicationContext
    }
}
