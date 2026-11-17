package com.jiacimu.lulu.system

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.Base64

/** Mika locatedPixel-inspired local recheck, using the chosen target's region
 * so a playing video elsewhere does not disable its unchanged toolbar. */
internal object LuluVisualTargetFrame {
    data class Signature(val hash: Long, val luminance: Int)

    fun signature(samples: IntArray): Signature? {
        if (samples.size != 64 || samples.any { it !in 0..255 }) return null
        val average = samples.sum() / 64
        var bits = 0L
        samples.forEachIndexed { index, value -> if (value >= average) bits = bits or (1L shl index) }
        return Signature(bits, average)
    }

    // Mika Locator.LOCATE_SCREEN_CHANGE uses 8 bits for its 64-bit aHash.
    // Include mean brightness: plain black and plain white have the same aHash.
    fun matches(before: Signature?, after: Signature?): Boolean = before != null && after != null &&
        java.lang.Long.bitCount(before.hash xor after.hash) <= 8 &&
        kotlin.math.abs(before.luminance - after.luminance) <= 24

    private fun sample(bitmap: Bitmap, x: Int, y: Int): Signature? {
        if (x !in 0..1000 || y !in 0..1000) return null
        val cx = (bitmap.width - 1) * x / 1000
        val cy = (bitmap.height - 1) * y / 1000
        val radius = (bitmap.width / 12).coerceAtLeast(8)
        val left = (cx - radius).coerceAtLeast(0)
        val top = (cy - radius).coerceAtLeast(0)
        val right = (cx + radius).coerceAtMost(bitmap.width - 1)
        val bottom = (cy + radius).coerceAtMost(bitmap.height - 1)
        return signature(IntArray(64) { index ->
            val pixel = bitmap.getPixel(left + (right - left) * (index % 8) / 7,
                top + (bottom - top) * (index / 8) / 7)
            (android.graphics.Color.red(pixel) * 299 + android.graphics.Color.green(pixel) * 587 +
                android.graphics.Color.blue(pixel) * 114) / 1000
        })
    }

    fun unchanged(previous: String, current: String, x: Int, y: Int): Boolean = runCatching {
        fun decode(data: String): Bitmap? {
            if (!data.startsWith("data:image/jpeg;base64,")) return null
            val bytes = Base64.getDecoder().decode(data.substringAfter(','))
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        val before = decode(previous) ?: return@runCatching false
        try {
            val after = decode(current) ?: return@runCatching false
            try {
                before.width == after.width && before.height == after.height &&
                    matches(sample(before, x, y), sample(after, x, y))
            } finally { after.recycle() }
        } finally { before.recycle() }
    }.getOrDefault(false)
}
