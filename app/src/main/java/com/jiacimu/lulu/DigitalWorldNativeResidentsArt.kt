package com.jiacimu.lulu

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.jiacimu.lulu.data.DigitalWorldResident

/**
 * Program-backed native residents. A painted person exists only where the stored
 * currentLocationCode says they are; no random ambient ghosts or dialogue puppets.
 */
internal fun DrawScope.drawNativeWorldResidents(
    residents: List<DigitalWorldResident>,
    sceneCode: String,
) {
    residents.forEachIndexed { index, resident ->
        val seed = resident.id.hashCode() and Int.MAX_VALUE
        val ownRoom = sceneCode == resident.homeLocationCode
        val x = if (ownRoom) 930f else 310f + ((seed % 9) * 110f)
        val y = if (ownRoom) 635f else 465f + (((seed / 11) % 4) * 116f)
        // Ground contact shadow, two legs, torso and head viewed from above.
        drawOval(Color(0xFF182C24).copy(alpha = .29f),
            Offset(x - 27f, y + 17f), Size(54f, 16f))
        drawLine(Color(0xFF394D52), Offset(x - 11f, y + 1f),
            Offset(x - 13f, y + 18f), 9f, StrokeCap.Round)
        drawLine(Color(0xFF394D52), Offset(x + 11f, y + 1f),
            Offset(x + 13f, y + 18f), 9f, StrokeCap.Round)
        val coat = when (seed % 4) {
            0 -> Color(0xFF7DA7A0)
            1 -> Color(0xFFB58C93)
            2 -> Color(0xFF8F9CC3)
            else -> Color(0xFFBC9A71)
        }
        drawRoundRect(Brush.verticalGradient(listOf(coat, coat.copy(alpha = .83f))),
            Offset(x - 23f, y - 49f), Size(46f, 52f), CornerRadius(17f))
        drawCircle(Color(0xFFDBB39C), 22f, Offset(x, y - 62f))
        drawOval(Color(0xFF403A37), Offset(x - 23f, y - 89f), Size(46f, 28f))
        drawCircle(Color.White.copy(alpha = .58f), 3.5f, Offset(x - 8f, y - 66f))
        drawCircle(Color.White.copy(alpha = .58f), 3.5f, Offset(x + 8f, y - 66f))
        // Distinct resident halo: a discoverable presence, not an interactive AI character.
        drawCircle(Color(0xFFE7FFF1).copy(alpha = .23f), 43f, Offset(x, y - 40f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.6f))
    }
}
