package com.wangzan101.mika.cap.phone.shell

/**
 * One latest rendered buffer, owned by the helper's serial command loop.
 * Reading is non-destructive: a static Surface does not produce a new image
 * for every observer/OCR request. Keep its producer timestamp, never invent
 * a new timestamp for a repeated read. ImageReader has three slots so the
 * retained image still leaves two slots for acquireLatestImage to drain.
 */
internal class LuluDisplayFrameBuffer<T : AutoCloseable> : AutoCloseable {
    private var current: T? = null

    fun latest(acquire: () -> T?): T? {
        val next = acquire()
        if (next != null && next !== current) {
            val previous = current
            current = next
            previous?.close()
        }
        return current
    }

    override fun close() {
        val previous = current
        current = null
        previous?.close()
    }
}
