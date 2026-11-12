package com.jiacimu.lulu

import kotlin.math.max
import kotlin.math.min

internal data class QqPhotoTransform(
    val scale: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
)

/** Pinch around the fingers and bound dragging to the fitted image's visible edges. */
internal fun nextQqPhotoTransform(
    previous: QqPhotoTransform,
    zoomFactor: Float,
    focalX: Float,
    focalY: Float,
    dragX: Float,
    dragY: Float,
    viewportWidth: Int,
    viewportHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
): QqPhotoTransform {
    val nextScale = (previous.scale * zoomFactor).coerceIn(1f, 5f)
    if (viewportWidth <= 0 || viewportHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
        return QqPhotoTransform(scale = nextScale)
    }
    val factor = nextScale / previous.scale
    val fit = min(viewportWidth.toFloat() / imageWidth, viewportHeight.toFloat() / imageHeight)
    val limitX = max(0f, (imageWidth * fit * nextScale - viewportWidth) / 2f)
    val limitY = max(0f, (imageHeight * fit * nextScale - viewportHeight) / 2f)
    val x = previous.x * factor + (focalX - viewportWidth / 2f) * (1f - factor) + dragX
    val y = previous.y * factor + (focalY - viewportHeight / 2f) * (1f - factor) + dragY
    // Zero travel has one canonical representation: +0.0f. A clamp over
    // [-0.0f, +0.0f] can otherwise preserve signed negative zero, which is
    // visually identical but breaks stable data-class equality/replay tests.
    val boundedX = if (limitX == 0f) 0f else x.coerceIn(-limitX, limitX)
    val boundedY = if (limitY == 0f) 0f else y.coerceIn(-limitY, limitY)
    return QqPhotoTransform(nextScale, boundedX, boundedY)
}
