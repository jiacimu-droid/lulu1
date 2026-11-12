package com.wangzan101.mika.cap.phone.shell

/**
 * Mika-style frame provenance for Lulu's single owned virtual display.
 * Image.timestamp is an opaque monotonic frame sequence from the SAME
 * ImageReader; do not compare it with wall-clock time or another display.
 *
 * A frame acquired after a command need not have been rendered after that
 * command: the reader can still contain frames queued before input dispatch.
 */
internal object LuluDisplayFrameFreshness {
    fun accepts(timestampNanos: Long, afterActionBarrierNanos: Long): Boolean =
        timestampNanos > 0L &&
            (afterActionBarrierNanos <= 0L || timestampNanos > afterActionBarrierNanos)

    fun isNewer(timestampNanos: Long, lastDeliveredNanos: Long): Boolean =
        timestampNanos > 0L && timestampNanos > lastDeliveredNanos

    fun barrier(lastDeliveredNanos: Long, drainedNanos: Long): Long =
        maxOf(0L, lastDeliveredNanos, drainedNanos)
}
