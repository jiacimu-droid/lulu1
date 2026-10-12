package com.jiacimu.lulu

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.jiacimu.lulu.games.WorldRectangle

/**
 * A bookcase/cabinet is a VERTICAL object rooted in a floor footprint.
 * Its visible facade projects upwards; its collision still uses that footprint.
 */
internal fun DrawScope.drawDigitalUprightFurniture(
    floor: WorldRectangle,
    baseColor: Color,
    books: Boolean,
    pattern: String,
) {
    val x = floor.left
    val w = floor.width
    val depth = 21f
    val height = if (books) 210f else 165f
    val top = floor.bottom - height
    val foot = floor.bottom - 8f
    val shadow = Color(0xFF202B25)
    drawOval(shadow.copy(alpha = .26f),
        Offset(x + 9f, foot - 2f), Size(w + depth, 30f))
    val right = Path().apply {
        moveTo(x + w, top)
        lineTo(x + w + depth, top - 14f)
        lineTo(x + w + depth, foot - 14f)
        lineTo(x + w, foot)
        close()
    }
    drawPath(right, baseColor.copy(alpha = .89f))
    val topFace = Path().apply {
        moveTo(x, top)
        lineTo(x + depth, top - 14f)
        lineTo(x + w + depth, top - 14f)
        lineTo(x + w, top)
        close()
    }
    drawPath(topFace, Color(0xFFECE7D9))
    drawRoundRect(
        Brush.horizontalGradient(listOf(Color(0xFFC9C6B6), baseColor, Color(0xFF645C4B))),
        Offset(x, top), Size(w, height - 8f), CornerRadius(7f),
    )
    val inner = Offset(x + 12f, top + 13f)
    val iw = w - 24f
    val ih = height - 34f
    drawRoundRect(shadow.copy(alpha = .87f), inner, Size(iw, ih), CornerRadius(4f))
    if (books) {
        val covers = listOf(
            Color(0xFF89ABA1), Color(0xFFD3B693), Color(0xFFB48781),
            Color(0xFF8B9EB6), Color(0xFFD7CCA8), Color(0xFFA5A2B7),
        )
        repeat(3) { row ->
            val slot = ih / 3f
            val baseY = inner.y + slot * (row + 1) - 7f
            repeat(7) { col ->
                val heightOfBook = slot * (.52f + ((row + col * 3) % 4) * .095f)
                val bx = inner.x + 6f + col * (iw - 13f) / 7f
                drawRoundRect(covers[(col + row * 2) % covers.size],
                    Offset(bx, baseY - heightOfBook),
                    Size((iw - 22f) / 7f, heightOfBook), CornerRadius(2f))
                drawLine(Color.White.copy(alpha = .27f),
                    Offset(bx + 3f, baseY - heightOfBook + 4f),
                    Offset(bx + 3f, baseY - 3f), 1.2f)
            }
            drawRect(Color(0xFFC2A788), Offset(inner.x - 2f, baseY),
                Size(iw + 4f, 8f))
        }
    } else {
        repeat(2) { door ->
            val dx = inner.x + door * iw / 2f + 3f
            drawRoundRect(
                if (pattern == "glassdoor") Color(0xFF8CAFA8) else baseColor,
                Offset(dx, inner.y + 4f), Size(iw / 2f - 6f, ih - 8f), CornerRadius(4f))
            drawCircle(Color(0xFFE5D39F), 3f,
                Offset(if (door == 0) dx + iw / 2f - 15f else dx + 12f, inner.y + ih * .52f))
        }
    }
    drawLine(Color.White.copy(alpha = .26f),
        Offset(x + 6f, top + 8f), Offset(x + 6f, foot - 8f), 2f)
}
