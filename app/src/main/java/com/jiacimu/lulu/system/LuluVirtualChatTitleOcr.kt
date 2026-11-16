package com.jiacimu.lulu.system

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Read an actual rendered WeChat title when Android accessibility omits that
 * TextView. This is a narrowly scoped, offline, read-only supplement to the
 * existing semantic node path, NOT an alternative authority to click/send.
 *
 * Only screenshot pixels belonging to the role's already verified independent
 * WeChat display are processed. Nothing is persisted or sent to an OCR server.
 */
internal object LuluVirtualChatTitleOcr {
    data class Line(val text: String, val bounds: LuluVirtualChatSendPolicy.ComposerBounds)

    /** Never treat OCR text from a conversation bubble, search result or system
     * status bar as a verified chat title. Header location + exact text required.
     * The composer, app window and the send control are checked independently. */
    fun hasExactToolbarTitle(
        lines: List<Line>, recipient: String, width: Int, height: Int,
    ): Boolean {
        if (width <= 0 || height <= 0 ||
            !LuluVirtualChatSendPolicy.validRecipient(recipient)) return false
        val candidates = lines.filter { line ->
            val b = line.bounds
            b.width > 0 && b.height > 0 &&
                b.centerX in (width * 24 / 100)..(width * 92 / 100) &&
                b.centerY in (height * 3 / 100)..(height * 14 / 100) &&
                b.bottom < height * 18 / 100 &&
                LuluVirtualChatSendPolicy.headerMatches(line.text, null, recipient)
        }.map { it.bounds }
        return LuluVirtualChatSendPolicy.uniqueHeaderTitle(candidates, height)
    }

    private suspend fun readLines(image: Bitmap): List<Line> =
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build())
            // Close and recycle in the task callback, not on coroutine timeout:
            // the recognizer may still be reading these pixels.
            try {
                recognizer.process(InputImage.fromBitmap(image, 0))
                    .addOnSuccessListener { result ->
                        val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                            line.boundingBox?.let { bounds ->
                                Line(line.text,
                                    LuluVirtualChatSendPolicy.ComposerBounds(
                                        bounds.left, bounds.top, bounds.right, bounds.bottom))
                            }
                        }
                        if (continuation.isActive) continuation.resume(lines)
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                    .addOnCompleteListener {
                        recognizer.close()
                        image.recycle()
                    }
            } catch (_: Exception) {
                recognizer.close()
                image.recycle()
                if (continuation.isActive) continuation.resume(emptyList())
            }
        }

    /** Strict fresh-screen check on BOTH sides of the OCR call. In particular,
     * never reuse a header read before a keyboard toggle as send authority. */
    suspend fun matches(displayId: Int, packageName: String, recipient: String): Boolean {
        if (displayId <= 0 || packageName != LuluVirtualChatSendPolicy.WECHAT ||
            !LuluVirtualChatSendPolicy.validRecipient(recipient)) return false
        return withTimeoutOrNull(4_500) {
            val before = withContext(Dispatchers.Main) {
                if (LuluAccessibilityService.virtualWindowStatus(displayId, packageName)
                        .optString("kind") != "target") return@withContext ""
                LuluAccessibilityService.virtualUiSnapshot(displayId, packageName)
                    .optString("fingerprint")
            }
            if (before.isBlank()) return@withTimeoutOrNull false
            val screenshot = try {
                LuluAccessibilityService.captureBitmap(displayId)
            } catch (_: Exception) { return@withTimeoutOrNull false }
            val width = screenshot.width
            val height = screenshot.height
            val top = if (height > 0) (height * 18 / 100).coerceAtLeast(1) else 0
            if (width <= 0 || top <= 0) {
                screenshot.recycle()
                return@withTimeoutOrNull false
            }
            val crop = try {
                Bitmap.createBitmap(screenshot, 0, 0, width, top)
            } finally { screenshot.recycle() }
            val lines = readLines(crop)
            if (!hasExactToolbarTitle(lines, recipient, width, height))
                return@withTimeoutOrNull false
            withContext(Dispatchers.Main) {
                LuluAccessibilityService.virtualWindowStatus(displayId, packageName)
                    .optString("kind") == "target" &&
                    LuluAccessibilityService.virtualUiSnapshot(displayId, packageName)
                        .optString("fingerprint") == before
            }
        } ?: false
    }
}
