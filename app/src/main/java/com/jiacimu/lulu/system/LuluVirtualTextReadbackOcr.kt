package com.jiacimu.lulu.system

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Secondary SENSOR for third-party text editors that expose zero A11y nodes.
 * Visual readback is evidence about a draft, not a reason to press Submit.
 * Nothing is sent to OCR servers, and a failed OCR cannot repeat the paste.
 */
internal object LuluVirtualTextReadbackOcr {
    data class TextLine(val text: String, val left: Int, val top: Int,
        val right: Int, val bottom: Int)

    private fun normalized(text: String) = text.filterNot {
        it.isWhitespace() || it == '\u200b' || it == '\ufeff'
    }

    fun exactNearField(
        lines: List<TextLine>, expected: String, width: Int, height: Int,
        inputX: Int, inputY: Int,
    ): Boolean {
        if (width <= 0 || height <= 0 || inputX !in 0..1000 ||
            inputY !in 0..1000 || expected.isBlank()) return false
        val px = inputX * width / 1000
        val py = inputY * height / 1000
        // The OS can move the composer up when opening the IME. Require the
        // actual draft in a nearby, visible text block, never just somewhere
        // in a conversation history or another unrelated app panel.
        val nearby = lines.filter { line ->
            line.right > 0 && line.bottom > 0 && line.top < height &&
                line.left < width &&
                kotlin.math.abs((line.top + line.bottom) / 2 - py) <= height * 35 / 100 &&
                kotlin.math.abs((line.left + line.right) / 2 - px) <= width * 48 / 100
        }.sortedWith(compareBy({ it.top }, { it.left }))
        val wanted = normalized(expected)
        return nearby.any { normalized(it.text) == wanted } ||
            nearby.joinToString("") { normalized(it.text) } == wanted
    }

    suspend fun verify(
        image: Bitmap, expected: String, inputX: Int, inputY: Int,
    ): Boolean = withTimeoutOrNull(7_000L) {
        suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build())
            try {
                recognizer.process(InputImage.fromBitmap(image, 0))
                    .addOnSuccessListener { result ->
                        val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                            line.boundingBox?.let { bounds ->
                                TextLine(line.text, bounds.left, bounds.top,
                                    bounds.right, bounds.bottom)
                            }
                        }
                        if (continuation.isActive) continuation.resume(
                            exactNearField(lines, expected, image.width, image.height,
                                inputX, inputY))
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(false)
                    }
                    .addOnCompleteListener { recognizer.close(); image.recycle() }
            } catch (_: Exception) {
                recognizer.close()
                image.recycle()
                if (continuation.isActive) continuation.resume(false)
            }
        }
    } ?: false
}
