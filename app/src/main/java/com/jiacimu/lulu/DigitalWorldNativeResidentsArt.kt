package com.jiacimu.lulu

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jiacimu.lulu.data.DigitalWorldResident
import com.jiacimu.lulu.games.WorldVector

internal data class DigitalNativeResidentPose(
    val resident: DigitalWorldResident,
    val position: WorldVector,
    val moving: Boolean,
    val facingX: Float,
)

/**
 * Character-scale native inhabitants: painted 2.5D heads, torsos, arms, shoes,
 * clothing layers, hair and ground shadows. Drawn at actual in-scene coordinates.
 * Gameplay uses the same coordinates for taps and interaction distances.
 */
internal fun DrawScope.drawNativeWorldResidents(poses: List<DigitalNativeResidentPose>, lightPhase: Float) {
    poses.sortedBy { it.position.y }.forEach { pose ->
        val resident = pose.resident
        val seed = resident.id.hashCode() and Int.MAX_VALUE
        val x = pose.position.x
        val y = pose.position.y
        val swing = if (pose.moving) (lightPhase - .5f) * 17f else 0f
        val coat = when (seed % 5) {
            0 -> Color(0xFF75A59B)
            1 -> Color(0xFFB98E9B)
            2 -> Color(0xFF8D9DB9)
            3 -> Color(0xFFBF9B78)
            else -> Color(0xFF7D9B78)
        }
        val skin = when ((seed / 7) % 4) {
            0 -> Color(0xFFF0CDB5)
            1 -> Color(0xFFDBAE91)
            2 -> Color(0xFFBD8C72)
            else -> Color(0xFFE6B89E)
        }
        // Cast shadow under both planted feet, rather than a hovering map pin.
        drawOval(Color(0xFF13241F).copy(alpha = .28f), Offset(x - 37f, y - 7f), Size(74f, 20f))
        // Trouser legs, independently animated while walking.
        drawLine(Color(0xFF43555C), Offset(x - 12f, y - 40f),
            Offset(x - 14f + swing, y - 6f), 15f, StrokeCap.Round)
        drawLine(Color(0xFF43555C), Offset(x + 12f, y - 40f),
            Offset(x + 14f - swing, y - 6f), 15f, StrokeCap.Round)
        drawOval(Color(0xFF323F43), Offset(x - 28f + swing, y - 13f), Size(27f, 15f))
        drawOval(Color(0xFF323F43), Offset(x + 1f - swing, y - 13f), Size(27f, 15f))
        // Upright arms with sleeves and hands.
        drawLine(coat.copy(red = coat.red * .77f, green = coat.green * .77f,
                blue = coat.blue * .77f), Offset(x - 24f, y - 95f),
            Offset(x - 32f - swing * .5f, y - 46f), 17f, StrokeCap.Round)
        drawLine(coat, Offset(x + 24f, y - 95f),
            Offset(x + 32f + swing * .5f, y - 46f), 17f, StrokeCap.Round)
        drawCircle(skin, 8f, Offset(x - 32f - swing * .5f, y - 43f))
        drawCircle(skin, 8f, Offset(x + 32f + swing * .5f, y - 43f))
        // Jacket sides and cloth center emphasize the three-dimensional torso.
        drawRoundRect(Color(0xFF263F3A).copy(alpha = .20f),
            Offset(x - 27f, y - 101f), Size(54f, 68f), CornerRadius(17f))
        drawRoundRect(Brush.verticalGradient(listOf(coat, coat.copy(alpha = .89f))),
            Offset(x - 26f, y - 105f), Size(52f, 67f), CornerRadius(17f))
        drawRoundRect(Color.White.copy(alpha = .14f),
            Offset(x - 17f, y - 98f), Size(12f, 47f), CornerRadius(6f))
        drawLine(Color(0xFF243F40).copy(alpha = .48f),
            Offset(x + 1f, y - 90f), Offset(x + 1f, y - 47f), 2.5f)
        drawCircle(Color(0xFFF4E3D2).copy(alpha = .80f), 2f, Offset(x + 4f, y - 71f))
        // Neck, ears, face lighting and asymmetric hair fringe.
        drawRoundRect(skin, Offset(x - 8f, y - 118f), Size(16f, 18f), CornerRadius(6f))
        drawCircle(Color(0xFF563F36), 29f, Offset(x, y - 145f))
        drawCircle(skin, 25f, Offset(x, y - 139f))
        drawCircle(skin, 7f, Offset(x - 24f, y - 141f))
        drawCircle(skin, 7f, Offset(x + 24f, y - 141f))
        drawOval(Brush.verticalGradient(listOf(Color(0xFF302D2C), Color(0xFF514139))),
            Offset(x - 28f, y - 176f), Size(56f, 25f))
        drawOval(Color(0xFF403531), Offset(x - 26f, y - 162f), Size(24f, 14f))
        val eyeShift = if (pose.facingX >= 0f) 2.5f else -2.5f
        drawCircle(Color(0xFF453E3A), 2.9f, Offset(x - 9f + eyeShift, y - 141f))
        drawCircle(Color(0xFF453E3A), 2.9f, Offset(x + 9f + eyeShift, y - 141f))
        drawLine(Color(0xFFAD746C).copy(alpha = .75f),
            Offset(x - 4f + eyeShift, y - 127f), Offset(x + 5f + eyeShift, y - 127f),
            1.9f, StrokeCap.Round)
        // Tiny wearable accessory is a visual variant, not a fabricated inventory item.
        if (seed % 3 == 0) {
            drawLine(Color(0xFFE6D2AE).copy(alpha = .75f),
                Offset(x - 17f, y - 98f), Offset(x + 17f, y - 53f), 3.2f)
        }
        drawCircle(Color(0xFFF3FCF5).copy(alpha = .35f), 36f,
            Offset(x, y - 104f), style = Stroke(1.5f))
    }
}
