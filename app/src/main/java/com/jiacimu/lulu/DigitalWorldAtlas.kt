package com.jiacimu.lulu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.DigitalWorldResident
import com.jiacimu.lulu.data.DigitalWorldPublicPlaces
import com.jiacimu.lulu.data.DigitalWorldState
import com.jiacimu.lulu.data.DigitalWorldStore

/**
 * Walkable-world atlas: the drawing and the six hit targets refer to actual committed locations.
 * It is a navigational overview, not a decorative "world entrance" destination.
 */
@Composable
internal fun DigitalWorldAtlas(
    characters: List<CharacterSettings>,
    nativeResidents: List<DigitalWorldResident>,
    world: DigitalWorldState,
    onOpenScene: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.height(294.dp).clip(RoundedCornerShape(26.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(Brush.verticalGradient(listOf(Color(0xFFBBD1CA), Color(0xFFDBE8DC), Color(0xFFAFCABD))))
            // Floating land masses, paths and tree canopies create a legible illustrated atlas.
            drawOval(Color.White.copy(alpha = .27f), Offset(-w * .12f, h * .40f), Size(w * 1.22f, h * .67f))
            drawOval(Color(0xFF6E9D8C).copy(alpha = .24f), Offset(w * .43f, h * .10f), Size(w * .58f, h * .69f))
            drawOval(Color(0xFF8CB6A2).copy(alpha = .50f), Offset(-w * .09f, h * .29f), Size(w * .66f, h * .60f))
            val pathway = Path().apply {
                moveTo(w * .15f, h * .44f)
                cubicTo(w * .49f, h * .29f, w * .49f, h * .65f, w * .78f, h * .51f)
                cubicTo(w * .95f, h * .43f, w * .56f, h * .90f, w * .31f, h * .78f)
            }
            drawPath(pathway, Color(0xFF3C6455).copy(alpha = .17f), style = Stroke(18f, cap = StrokeCap.Round))
            drawPath(pathway, Color(0xFFF4EFE0).copy(alpha = .95f), style = Stroke(12f, cap = StrokeCap.Round))
            drawPath(pathway, Color.White.copy(alpha = .60f), style = Stroke(2f, cap = StrokeCap.Round))
            repeat(17) { i ->
                val x = ((i * 139 + 29) % 100) / 100f * w
                val y = h * (.30f + ((i * 47 + 9) % 65) / 100f)
                val radius = 7f + i % 4 * 3f
                drawOval(Color(0xFF426C5C).copy(alpha = .17f), Offset(x - radius, y), Size(radius * 2, radius * .6f))
                drawCircle(Color(0xFF557F69).copy(alpha = .67f), radius, Offset(x, y - radius * .55f))
                drawCircle(Color(0xFFAFD0AC).copy(alpha = .66f), radius * .54f, Offset(x - radius * .28f, y - radius * .9f))
            }
            // Physical landmarks: cloud, courtyard pond, cafe and arcade building.
            drawOval(Color(0xFFF4F9F5), Offset(w * .045f, h * .36f), Size(w * .32f, h * .09f))
            drawOval(Color(0xFF9BBFBE), Offset(w * .69f, h * .29f), Size(w * .21f, h * .075f))
            drawRoundRect(Color(0xFFF2E4D3), Offset(w * .13f, h * .54f), Size(w * .20f, h * .14f), CornerRadius(8f))
            drawRoundRect(Color(0xFFA87C65), Offset(w * .115f, h * .53f), Size(w * .23f, h * .04f), CornerRadius(4f))
            drawRoundRect(Color(0xFF718795), Offset(w * .65f, h * .56f), Size(w * .22f, h * .13f), CornerRadius(8f))
            drawRoundRect(Color(0xFFAAD4D5), Offset(w * .69f, h * .58f), Size(w * .13f, h * .06f), CornerRadius(3f))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF688D7D).copy(alpha = .12f))))
        }
        Column(Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 14.dp)) {
            Text("云上世界", color = Color(0xFF213E34), fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text("点选地图上的地点，走进去看看", color = Color(0xFF507467), fontSize = 10.sp)
        }
        Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 17.dp, end = 14.dp),
            shape = RoundedCornerShape(99.dp),
            color = Color.White.copy(alpha = .78f),
        ) {
            Text("${characters.size + nativeResidents.size} 座家 · ${world.characterLocations.size + nativeResidents.size} 位居民",
                color = Color(0xFF547263), fontSize = 8.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        }
        AtlasStop("云眠原", Icons.Outlined.Cloud, 0.045f, 94.dp, maxWidth,
            onClick = { onOpenScene(DigitalWorldStore.CLOUD_MEADOW) })
        AtlasStop("庭院", Icons.Outlined.Park, 0.66f, 101.dp, maxWidth,
            onClick = { onOpenScene(DigitalWorldPublicPlaces.COURTYARD) })
        AtlasStop("咖啡角", Icons.Outlined.LocalCafe, 0.05f, 179.dp, maxWidth,
            onClick = { onOpenScene(DigitalWorldPublicPlaces.CAFE) })
        AtlasStop("游戏馆", Icons.Outlined.SportsEsports, 0.66f, 180.dp, maxWidth,
            onClick = { onOpenScene(DigitalWorldPublicPlaces.GAME_HALL) })
        characters.firstOrNull()?.let { character ->
            AtlasStop("${character.displayName}的家", Icons.Outlined.Home, 0.13f, 242.dp, maxWidth,
                onClick = { onOpenScene(DigitalWorldStore.homeLocation(character.characterId)) })
        }
        nativeResidents.firstOrNull()?.let { resident ->
            AtlasStop("${resident.name}的家", Icons.Outlined.HolidayVillage, 0.61f, 242.dp, maxWidth,
                onClick = { onOpenScene(resident.homeLocationCode) })
        }
    }
}

@Composable
private fun AtlasStop(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    left: Float,
    top: Dp,
    width: Dp,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.offset(x = width * left, y = top).widthIn(max = 124.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(99.dp),
        color = Color(0xF8FAFEFA),
        shadowElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBDDD0)),
    ) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, modifier = Modifier.size(14.dp), tint = Color(0xFF4F8770))
            Text(title, color = Color(0xFF254337), fontSize = 9.5.sp,
                maxLines = 1, fontWeight = FontWeight.Bold)
        }
    }
}
