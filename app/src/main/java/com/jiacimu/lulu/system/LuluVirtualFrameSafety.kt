package com.jiacimu.lulu.system

/** Mika FrameMath guard for image handoff. A frame captured after a gesture
 * can still be completely black: it is not evidence of readable app content.
 * Never replace it with owner's main-screen pixels. */
internal object LuluVirtualFrameSafety {
    fun canSendToRole(validated: Boolean, timestampNanos: Long,
                      imageDataUrl: String, blackFrame: Boolean): Boolean =
        validated && !blackFrame && timestampNanos > 0L &&
            imageDataUrl.startsWith("data:image/jpeg;base64,")
}
