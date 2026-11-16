package com.jiacimu.lulu.system

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private data class Proof(
        val displayId: Int, val packageName: String, val recipient: String,
        val fingerprint: String, val capturedAtMs: Long,
    )
    private val screenshotGate = Mutex()
    private var latestProof: Proof? = null
    private var lastScreenshotAtMs = 0L

    /** Cached evidence is only a short-lived convenience between adjacent
     * read-only checks of the SAME accessibility fingerprint. A send always
     * requests new pixels after text has been filled. */
    private fun proofUsable(
        proof: Proof?, displayId: Int, packageName: String, recipient: String,
        fingerprint: String, now: Long,
    ): Boolean = proof != null && now - proof.capturedAtMs in 0..1_000 &&
        proof.displayId == displayId && proof.packageName == packageName &&
        proof.recipient == recipient && proof.fingerprint == fingerprint

    /** Android may throttle rapid AccessibilityService.takeScreenshot calls.
     * A second READ-ONLY capture can be attempted after an interval; there
     * is never an implicit retry of any tap, input, or sending action. */
    private suspend fun captureRateLimited(displayId: Int): Bitmap? {
        repeat(2) { attempt ->
            val gap = 460 - (SystemClock.uptimeMillis() - lastScreenshotAtMs)
            if (gap > 0) delay(gap)
            lastScreenshotAtMs = SystemClock.uptimeMillis()
            try {
                return LuluAccessibilityService.captureBitmap(displayId)
            } catch (_: Exception) {
                if (attempt == 1) return null
            }
        }
        return null
    }

    /** Check the verified WeChat window before and after reading new pixels.
     * Fresh evidence is mandatory just before an irreversible send click. */
    suspend fun matches(
        displayId: Int, packageName: String, recipient: String,
        forceFresh: Boolean = false,
    ): Boolean {
        if (displayId <= 0 || packageName != LuluVirtualChatSendPolicy.WECHAT ||
            !LuluVirtualChatSendPolicy.validRecipient(recipient)) return false
        return withTimeoutOrNull(6_000) {
            screenshotGate.withLock {
                val before = withContext(Dispatchers.Main) {
                    if (LuluAccessibilityService.virtualWindowStatus(displayId, packageName)
                            .optString("kind") != "target") return@withContext ""
                    LuluAccessibilityService.virtualUiSnapshot(displayId, packageName)
                        .optString("fingerprint")
                }
                if (before.isBlank()) return@withLock false
                if (!forceFresh && proofUsable(latestProof, displayId, packageName,
                        recipient, before, SystemClock.uptimeMillis())) return@withLock true
                latestProof = null
                val screenshot = captureRateLimited(displayId)
                    ?: return@withLock false
                val width = screenshot.width
                val height = screenshot.height
                val top = height * 18 / 100
                if (width <= 0 || top <= 0) {
                    screenshot.recycle()
                    return@withLock false
                }
                val crop = try {
                    Bitmap.createBitmap(screenshot, 0, 0, width, top)
                } catch (_: Exception) {
                    return@withLock false
                } finally { screenshot.recycle() }
                val lines = readLines(crop)
                if (!hasExactToolbarTitle(lines, recipient, width, height))
                    return@withLock false
                val stillSame = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.virtualWindowStatus(displayId, packageName)
                        .optString("kind") == "target" &&
                        LuluAccessibilityService.virtualUiSnapshot(displayId, packageName)
                            .optString("fingerprint") == before
                }
                if (stillSame) latestProof = Proof(
                    displayId, packageName, recipient, before, SystemClock.uptimeMillis())
                stillSame
            }
        } ?: false
    }
}
