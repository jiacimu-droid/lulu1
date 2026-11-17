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
 * Read an actual rendered chat title when Android accessibility omits that
 * TextView. This is a narrowly scoped, offline, read-only supplement to the
 * existing semantic node path, NOT an alternative authority to click/send.
 *
 * Only screenshot pixels belonging to the role's already verified independent
 * app display are processed. Nothing is persisted or sent to an OCR server.
 */
internal object LuluVirtualChatTitleOcr {
    data class Line(val text: String, val bounds: LuluVirtualChatSendPolicy.ComposerBounds)

    /** Never treat OCR text from a conversation bubble, search result or system
     * status bar as a verified chat title. Header location + exact text required.
     * The composer, app window and the send control are checked independently. */
    fun hasExactToolbarTitle(
        lines: List<Line>, recipient: String, width: Int, height: Int,
        centeredToolbar: Boolean = false,
    ): Boolean {
        if (width <= 0 || height <= 0 ||
            !LuluVirtualChatSendPolicy.validRecipient(recipient)) return false
        val candidates = lines.filter { line ->
            val b = line.bounds
            val usualToolbar = b.centerY >= height * 3 / 100
            val edgeToEdgeTitle = centeredToolbar &&
                b.centerX in (width * 35 / 100)..(width * 65 / 100) && b.top >= 0
            b.width > 0 && b.height > 0 &&
                b.left >= width / 10 && b.centerX <= width * 92 / 100 &&
                b.centerY <= height * 14 / 100 && (usualToolbar || edgeToEdgeTitle) &&
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

    /** Keep clipped names from becoming a falsely exact hit after cropping
     * away the back badge and the overflow button on WeChat's title row. */
    fun interiorToolbarLines(lines: List<Line>, left: Int, right: Int): List<Line> =
        lines.filter { it.bounds.left > 2 && it.bounds.right < right - left - 2 }
            .map { line -> line.copy(bounds = line.bounds.copy(
                left = line.bounds.left + left, right = line.bounds.right + left)) }

    private suspend fun screenshotHasTitle(
        screenshot: Bitmap, packageName: String, recipient: String,
    ): Boolean {
        val width = screenshot.width
        val height = screenshot.height
        val top = height * 18 / 100
        if (width <= 0 || top <= 0) return false
        val wechat = packageName == LuluVirtualChatSendPolicy.WECHAT
        val complete = readLines(Bitmap.createBitmap(screenshot, 0, 0, width, top))
        if (hasExactToolbarTitle(complete, recipient, width, height, centeredToolbar = wechat))
            return true
        if (!wechat) return false
        val left = width / 5
        val right = width - left
        val centered = readLines(Bitmap.createBitmap(screenshot, left, 0, right - left, top))
        return hasExactToolbarTitle(interiorToolbarLines(centered, left, right),
            recipient, width, height, centeredToolbar = true)
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
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (attempt == 1) return null
            }
        }
        return null
    }

    /** Check the verified app window before and after reading new pixels.
     * Fresh evidence is mandatory just before an irreversible send click. */
    suspend fun matches(
        displayId: Int, packageName: String, recipient: String,
        forceFresh: Boolean = false,
        frameSource: suspend () -> Bitmap? = { null },
    ): Boolean {
        if (displayId <= 0 || !LuluVirtualChatSendPolicy.supports(packageName) ||
            !LuluVirtualChatSendPolicy.validRecipient(recipient)) return false
        return withTimeoutOrNull(6_000) {
            screenshotGate.withLock {
                val before = withContext(Dispatchers.Main) {
                    if (LuluAccessibilityService.virtualWindowStatus(displayId, packageName)
                            .optString("kind") != "target") return@withContext ""
                    LuluAccessibilityService.virtualChatWindowIdentity(displayId, packageName)
                }
                if (before.isBlank()) return@withLock false
                if (!forceFresh && proofUsable(latestProof, displayId, packageName,
                        recipient, before, SystemClock.uptimeMillis())) return@withLock true
                latestProof = null
                val screenshot = frameSource() ?: captureRateLimited(displayId)
                    ?: return@withLock false
                val recognized = try {
                    screenshotHasTitle(screenshot, packageName, recipient)
                } finally {
                    screenshot.recycle()
                }
                if (!recognized) return@withLock false
                val stillSame = withContext(Dispatchers.Main) {
                    LuluAccessibilityService.virtualWindowStatus(displayId, packageName)
                        .optString("kind") == "target" &&
                        LuluAccessibilityService.virtualChatWindowIdentity(displayId, packageName) == before
                }
                if (stillSame) latestProof = Proof(
                    displayId, packageName, recipient, before, SystemClock.uptimeMillis())
                stillSame
            }
        } ?: false
    }
}
