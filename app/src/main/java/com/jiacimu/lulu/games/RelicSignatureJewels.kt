package com.jiacimu.lulu.games

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Three deliberately different, quiet museum-grade illustrations.
 *
 * Shared by the *real* treasure reveal and the owned-vault exhibit through
 * RelicTreasureArt.drawTreasure. No bitmap loading, random decoration, reward
 * changes or extra model calls. Every piece has its own silhouette, gem
 * cut and material instead of tinting one generic icon.
 */
internal enum class RelicSignatureKind { MOON_BROOCH, STAR_CROWN, FROST_HARP }

internal object RelicSignatureJewels {
    fun kind(name: String): RelicSignatureKind? = when (name) {
        "月银胸针" -> RelicSignatureKind.MOON_BROOCH
        "星穹冠冕" -> RelicSignatureKind.STAR_CROWN
        "冰魄竖琴" -> RelicSignatureKind.FROST_HARP
        else -> null
    }

    fun DrawScope.drawSignature(
        name: String, center: Offset, diameter: Float, alpha: Float,
    ): Boolean {
        val signature = kind(name) ?: return false
        val s = diameter
        val x = center.x
        val y = center.y
        val opacity = alpha.coerceIn(0f, 1f)
        fun p(dx: Float, dy: Float) = Offset(x + s * dx, y + s * dy)
        fun color(rgb: Long, opacityScale: Float = 1f) =
            Color(rgb.toInt()).copy(alpha = opacity * opacityScale.coerceIn(0f, 1f))
        fun stroke(path: Path, tone: Color, thickness: Float) =
            drawPath(path, tone, style = Stroke(width = s * thickness))
        fun polygon(vararg points: Pair<Float, Float>) = Path().apply {
            points.forEachIndexed { index, pt ->
                if (index == 0) moveTo(x + s * pt.first, y + s * pt.second)
                else lineTo(x + s * pt.first, y + s * pt.second)
            }
            close()
        }

        when (signature) {
            RelicSignatureKind.MOON_BROOCH -> {
                val silver = color(0xFFE4E6E5)
                val shadowSilver = color(0xFF819CA6)
                val moon = color(0xFFC7D6DC)
                val sapphire = color(0xFF72C7D8)
                // A curved sprig of silver that embraces a suspended moonstone.
                val outer = Path().apply {
                    moveTo(p(-.32f, .17f).x, p(-.32f, .17f).y)
                    cubicTo(p(-.38f, -.16f).x, p(-.38f, -.16f).y,
                        p(-.05f, -.39f).x, p(-.05f, -.39f).y,
                        p(.26f, -.20f).x, p(.26f, -.20f).y)
                    quadraticBezierTo(p(.37f, -.09f).x, p(.37f, -.09f).y,
                        p(.32f, .21f).x, p(.32f, .21f).y)
                }
                stroke(outer, shadowSilver, .069f)
                stroke(outer, silver, .034f)
                val leftLeaf = polygon(
                    -.32f to -.10f, -.46f to -.27f, -.20f to -.26f, -.13f to -.11f)
                drawPath(leftLeaf, color(0xFFC9D8D7))
                stroke(leftLeaf, silver, .019f)
                val rightLeaf = polygon(
                    .26f to -.22f, .43f to -.33f, .40f to -.08f, .24f to -.03f)
                drawPath(rightLeaf, color(0xFF9DB8B7))
                stroke(rightLeaf, silver, .018f)
                val crescent = Path().apply {
                    moveTo(p(-.18f, .04f).x, p(-.18f, .04f).y)
                    cubicTo(p(-.23f, .27f).x, p(-.23f, .27f).y,
                        p(.01f, .43f).x, p(.01f, .43f).y,
                        p(.20f, .26f).x, p(.20f, .26f).y)
                    quadraticBezierTo(p(.04f, .32f).x, p(.04f, .32f).y,
                        p(-.04f, .18f).x, p(-.04f, .18f).y)
                    close()
                }
                drawPath(crescent, moon)
                stroke(crescent, color(0xFFF7F3EA), .014f)
                // A properly faceted gem, rather than a blue circle.
                val jewel = polygon(
                    -.03f to -.24f, .17f to -.16f, .25f to .05f,
                    .11f to .25f, -.13f to .24f, -.23f to .03f)
                drawPath(jewel, color(0xFF356F94))
                drawPath(polygon(-.03f to -.24f, .17f to -.16f,
                    .04f to .06f, -.23f to .03f), color(0xFF9CDCE2))
                drawPath(polygon(.17f to -.16f, .25f to .05f,
                    .11f to .25f, .04f to .06f), sapphire)
                drawPath(polygon(.04f to .06f, .11f to .25f,
                    -.13f to .24f, -.23f to .03f), color(0xFF498FA8))
                stroke(jewel, silver, .026f)
                drawLine(color(0xFFF7FAF5), p(-.12f, -.01f),
                    p(-.03f, -.15f), strokeWidth = s * .018f)
                drawCircle(color(0xFFF7F3E6), s * .038f, p(-.37f, -.23f))
                drawCircle(color(0xFFE6F4EF), s * .029f, p(.42f, -.31f))
            }
            RelicSignatureKind.STAR_CROWN -> {
                val gold = color(0xFFD6B983)
                val paleGold = color(0xFFF1DEB3)
                val darkGold = color(0xFF806D5C)
                val crown = polygon(
                    -.44f to -.15f, -.30f to .02f, -.33f to -.34f,
                    -.13f to -.13f, 0f to -.43f, .14f to -.13f,
                    .33f to -.34f, .30f to .02f, .44f to -.15f,
                    .34f to .30f, -.34f to .30f)
                drawPath(crown, Brush.verticalGradient(listOf(
                    paleGold, gold, darkGold),
                    startY = y - s * .42f, endY = y + s * .32f))
                stroke(crown, color(0xFFF7E7C4), .018f)
                drawPath(polygon(-.39f to .14f, .39f to .14f,
                    .34f to .30f, -.34f to .30f), darkGold)
                drawLine(paleGold, p(-.36f, .16f), p(.36f, .16f),
                    strokeWidth = s * .018f)
                for (j in -2..2) {
                    val px = j * .17f
                    val centerGem = p(px, if (j == 0) .16f else .22f)
                    drawCircle(color(0xFFF2E3C5), s * .039f, centerGem)
                    if (j != 0) drawCircle(color(0xFF8AB9C5), s * .024f, centerGem)
                }
                val gem = polygon(
                    0f to -.31f, .135f to -.12f, .095f to .06f,
                    0f to .13f, -.095f to .06f, -.135f to -.12f)
                drawPath(gem, color(0xFF376B8F))
                drawPath(polygon(0f to -.31f, .135f to -.12f,
                    0f to -.04f, -.135f to -.12f), color(0xFFB8DFE1))
                drawPath(polygon(0f to -.04f, .135f to -.12f,
                    .095f to .06f, 0f to .13f), color(0xFF6EA5BE))
                stroke(gem, paleGold, .026f)
                // Restrained engraved star-lines instead of particles everywhere.
                for (j in listOf(-.28f, .28f)) {
                    drawLine(paleGold, p(j, -.06f), p(j, .10f),
                        strokeWidth = s * .012f)
                }
                for (j in listOf(-.33f, 0f, .33f)) {
                    drawCircle(paleGold, s * .033f,
                        p(j, if (j == 0f) -.42f else -.33f))
                }
            }
            RelicSignatureKind.FROST_HARP -> {
                val pearl = color(0xFFE4EFF1)
                val coldSilver = color(0xFF9FBEC8)
                val ice = color(0xFF76C3D3)
                val frame = Path().apply {
                    moveTo(p(-.27f, .34f).x, p(-.27f, .34f).y)
                    cubicTo(p(-.36f, -.20f).x, p(-.36f, -.20f).y,
                        p(-.19f, -.43f).x, p(-.19f, -.43f).y,
                        p(.26f, -.38f).x, p(.26f, -.38f).y)
                    quadraticBezierTo(p(.35f, -.16f).x, p(.35f, -.16f).y,
                        p(.22f, .28f).x, p(.22f, .28f).y)
                }
                stroke(frame, coldSilver, .092f)
                stroke(frame, pearl, .045f)
                val lower = polygon(
                    -.35f to .28f, .26f to .22f, .38f to .32f,
                    .28f to .39f, -.35f to .39f)
                drawPath(lower, color(0xFF7899AE))
                stroke(lower, pearl, .018f)
                drawPath(polygon(-.25f to -.36f, -.12f to -.42f,
                    .29f to -.39f, .31f to -.28f), color(0xFFC7E1E2))
                for (j in 0..6) {
                    val gx = -.16f + j * .065f
                    val top = -.29f - .052f * kotlin.math.sin(
                        j * .52f)
                    drawLine(color(0xFFD9F8FA, .87f), p(gx, top),
                        p(gx + .04f, .25f), strokeWidth = s * .009f)
                }
                // A unique icy fleur beneath the neck; still readable at 60dp.
                val flower = polygon(
                    0f to -.38f, .065f to -.27f, .16f to -.25f,
                    .064f to -.20f, .01f to -.11f, -.05f to -.21f,
                    -.16f to -.24f, -.07f to -.28f)
                drawPath(flower, ice)
                stroke(flower, pearl, .012f)
                drawCircle(color(0xFFF8F8EE), s * .028f, p(0f, -.25f))
                drawLine(pearl, p(-.28f, .34f), p(.33f, .34f),
                    strokeWidth = s * .026f)
            }
        }
        return true
    }
}
