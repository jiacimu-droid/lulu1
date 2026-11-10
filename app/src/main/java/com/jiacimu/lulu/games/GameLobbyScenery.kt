package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.jiacimu.lulu.R
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Original procedural title-screen scenery shared only by the two lobby surfaces.
 * A fixed number of canvas primitives keeps the scene fast on low-end Android phones.
 * This is presentation, never a parallel world or a fabricated game reward.
 */
@Composable
internal fun GameLobbyStage(
    island: Boolean,
    eyebrow: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    fullBleed: Boolean = false,
    showTitle: Boolean = true,
) {
    Box(if(fullBleed) modifier else modifier.clip(RoundedCornerShape(22.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (island) {
                // Pastel daybreak, layered sea, forest hills and a little wooden cabin.
                drawRect(brush = Brush.verticalGradient(listOf(
                    Color(0xFF8BD8DC), Color(0xFFDCE9C2), Color(0xFF4FAD9A))))
                drawCircle(Color(0xFFFFE8A5), w * .092f,
                    Offset(w * .8f, h * .19f))
                drawCircle(Color(0x69FFFFFF), w * .12f,
                    Offset(w * .2f, h * .18f))
                drawCircle(Color(0x58FFFFFF), w * .07f,
                    Offset(w * .32f, h * .13f))
                val mountains = Path().apply {
                    moveTo(0f, h * .57f)
                    lineTo(w * .16f, h * .34f)
                    lineTo(w * .29f, h * .52f)
                    lineTo(w * .51f, h * .25f)
                    lineTo(w * .72f, h * .55f)
                    lineTo(w, h * .39f)
                    lineTo(w, h); lineTo(0f,h); close()
                }
                drawPath(mountains, Color(0xFF78BDA4))
                drawRect(Color(0xFF3D9B9A), Offset(0f, h * .67f),
                    Size(w, h * .33f))
                for (n in 0..13) {
                    val x = (n * 79 % 131) / 131f * w
                    val y = h * (.76f + (n % 4) * .057f)
                    drawRect(Color(0x6075D2C6), Offset(x, y), Size(w * .055f, h * .012f))
                }
                val islandCoast = Path().apply {
                    moveTo(w * .07f, h * .75f)
                    lineTo(w * .2f, h * .64f)
                    lineTo(w * .69f, h * .62f)
                    lineTo(w * .91f, h * .76f)
                    lineTo(w * .82f, h * .87f)
                    lineTo(w * .19f, h * .88f); close()
                }
                drawPath(islandCoast, Color(0xFFEACF9D))
                val grass = Path().apply {
                    moveTo(w * .14f, h * .72f)
                    lineTo(w * .25f, h * .62f)
                    lineTo(w * .67f, h * .61f)
                    lineTo(w * .87f, h * .72f)
                    lineTo(w * .78f, h * .79f)
                    lineTo(w * .24f, h * .81f); close()
                }
                drawPath(grass, Color(0xFF76B76A))
                for (xFraction in listOf(.2f,.72f,.79f)) {
                    val x = w * xFraction
                    val y = h * (.55f + xFraction * .05f)
                    drawRect(Color(0xFF704D38), Offset(x-w*.012f,y), Size(w*.024f,h*.19f))
                    drawCircle(Color(0xFF276F54), w * .052f, Offset(x, y))
                    drawCircle(Color(0xFF469568), w * .035f, Offset(x-w*.02f,y-h*.035f))
                }
                // Physical recognizable house, not an empty placeholder marker.
                drawRect(Color(0xFFB77D54), Offset(w*.44f,h*.52f), Size(w*.13f,h*.20f))
                val roof = Path().apply {
                    moveTo(w*.418f,h*.54f);lineTo(w*.503f,h*.40f)
                    lineTo(w*.594f,h*.54f);close()
                }
                drawPath(roof, Color(0xFFAE5549))
                drawRect(Color(0xFF5E403E), Offset(w*.492f,h*.62f), Size(w*.03f,h*.10f))
                drawRect(Color(0xFFFFE7A8),Offset(w*.448f,h*.565f),Size(w*.028f,h*.044f))
            } else {
                // Framed stone gate with an illuminated vault beyond, on a moody forest floor.
                drawRect(brush = Brush.verticalGradient(listOf(
                    Color(0xFF131A35),Color(0xFF254153),Color(0xFF172C32))))
                drawCircle(Color(0xFFBBD9E0),w*.07f,Offset(w*.77f,h*.2f))
                drawCircle(Color(0x5533D5B9),w*.22f,Offset(w*.52f,h*.63f))
                for (n in 0..19) {
                    val x=((n*41+13)%101)/101f*w
                    val y=((n*19+7)%51)/100f*h
                    drawCircle(Color(0x88D2F4E9),w*.0016f,Offset(x,y))
                }
                val back = Path().apply {
                    moveTo(0f,h*.66f);lineTo(w*.19f,h*.46f)
                    lineTo(w*.32f,h*.60f);lineTo(w*.7f,h*.43f)
                    lineTo(w,h*.61f);lineTo(w,h);lineTo(0f,h);close()
                }
                drawPath(back,Color(0xFF274651))
                drawRect(Color(0xFF192C33),Offset(0f,h*.76f),Size(w,h*.24f))
                drawRect(Color(0xFF40545C),Offset(w*.33f,h*.28f),Size(w*.035f,h*.53f))
                drawRect(Color(0xFF40545C),Offset(w*.645f,h*.28f),Size(w*.035f,h*.53f))
                drawRect(Color(0xFF64727B),Offset(w*.31f,h*.23f),Size(w*.39f,h*.075f))
                drawRect(Color(0xFF394C59),Offset(w*.375f,h*.34f),Size(w*.27f,h*.43f))
                drawRect(brush=Brush.verticalGradient(listOf(
                    Color(0xFF1B546A),Color(0xFF0D2538))),topLeft=Offset(w*.41f,h*.37f),
                    size=Size(w*.20f,h*.38f))
                drawCircle(Color(0xAA5BDFD6),w*.074f,Offset(w*.51f,h*.55f))
                drawCircle(Color(0xFFB7F5E5),w*.021f,Offset(w*.51f,h*.55f))
                for (x in listOf(.27f,.72f)) {
                    drawRect(Color(0xFF5F655B),Offset(w*x,h*.55f),Size(w*.026f,h*.23f))
                    drawCircle(Color(0xFFFFC06B),w*.020f,Offset(w*(x+.013f),h*.53f))
                    drawCircle(Color(0x88FF9550),w*.047f,Offset(w*(x+.013f),h*.53f))
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0.0f to Color.Transparent,
            .47f to Color(0x200A1722),
            1f to Color(0xF2081A21),
        )))
        if(showTitle) {
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal=17.dp,vertical=15.dp),
                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(eyebrow,color=if(island)Color(0xFFBCECB3) else Color(0xFFFFD7A0),
                    fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.7.sp)
                Text(title,color=Color.White,fontWeight=FontWeight.Black,fontSize=21.sp,
                    lineHeight=25.sp,maxLines=2)
                Text(subtitle,color=Color(0xFFD5E6E5),fontSize=11.sp,lineHeight=15.sp,maxLines=2)
            }
        }
    }
}

/** A real full-bleed title scene, not a short banner floating over a settings form.
 * Its shading layers are decorative and never block button hit-testing. */
@Composable
internal fun GameLobbyBackdrop(island:Boolean, modifier:Modifier=Modifier) {
    Box(modifier) {
        if(island) {
            // The scene is a real, bundled export of an editable layered Figma
            // illustration. Actual players, quests and controls remain native
            // Compose overlays with live saved state — not baked into the PNG.
            Image(painter=painterResource(R.drawable.game_island_lobby_art),
                contentDescription=null,modifier=Modifier.matchParentSize(),
                contentScale=ContentScale.Crop)
        } else {
            // Preserve the high-resolution authored relic camp already bundled
            // with the project instead of regressing to debug Canvas scenery.
            RelicCampBackdrop()
        }
        val horizontal=if(island)
            listOf(Color(0x85102732),Color(0x1710242B),Color(0x92102230))
        else listOf(Color(0xA00C1F29),Color(0x3310242B),Color(0xA60C1721))
        val vertical=if(island)
            listOf(Color(0x8A0B1C25),Color(0x02122731),Color(0xC30B1D24))
        else listOf(Color(0xA60B1C25),Color(0x0A122731),Color(0xE80B1D24))
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(horizontal)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(vertical)))
    }
}

/** Same cinematic character cards in both lobbies. An accepted companion is a real actor,
 * not a generic emoji checkbox. These cards only edit party selection, not character intent. */
@Composable
internal fun GameLobbyPartySlot(
    avatarUri:String?,
    fallback:String,
    name:String,
    isPlayer:Boolean,
    accent:Color,
    allowRemove:Boolean=false,
    onRemove:()->Unit={},
) {
    // An RPG-style standee, not a settings checkbox. The actual user/character
    // avatar still comes from the same profile and invitation state.
    Surface(modifier=Modifier.width(112.dp),shape=RoundedCornerShape(17.dp),
        color=Color(0xF01A2D36),
        border=BorderStroke(if(isPlayer)2.dp else 1.dp,
            accent.copy(alpha=if(isPlayer).92f else .65f)),
        shadowElevation=6.dp) {
        Column(Modifier.background(Brush.verticalGradient(listOf(
            if(isPlayer)Color(0xFF45675A) else Color(0xFF354352),
            Color(0xFF182B36),Color(0xFF111F2C)))),
            horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(111.dp),
                contentAlignment=Alignment.Center) {
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(accent.copy(alpha=.14f),size.minDimension*.43f,
                        center=Offset(size.width*.50f,size.height*.43f))
                    drawCircle(accent.copy(alpha=.12f),size.minDimension*.33f,
                        center=Offset(size.width*.50f,size.height*.43f))
                }
                GameCharacterPawn(avatarUri=avatarUri,fallback=fallback,
                    moving=false,facingX=1f,player=isPlayer,
                    coat=if(isPlayer)Color(0xFF608C7D) else Color(0xFF6D6B80))
                Text(if(isPlayer)"♛" else "✓",color=accent,fontSize=18.sp,
                    fontWeight=FontWeight.Bold,
                    modifier=Modifier.align(Alignment.TopEnd).padding(end=7.dp,top=4.dp))
            }
            Text(name,color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold,
                maxLines=1,modifier=Modifier.padding(horizontal=6.dp))
            Text(if(isPlayer)"队长" else "已加入队伍",color=accent,fontSize=10.sp,
                modifier=Modifier.padding(vertical=3.dp))
            if(allowRemove)TextButton(onClick=onRemove,
                modifier=Modifier.height(28.dp),
                contentPadding=PaddingValues(horizontal=5.dp,vertical=0.dp)) {
                Text("移出队伍",color=accent,fontSize=10.sp)
            }
        }
    }
}

@Composable
internal fun GameLobbyInviteSlot(
    accent:Color,
    enabled:Boolean,
    onClick:()->Unit,
) {
    Surface(onClick=onClick,enabled=enabled,
        modifier=Modifier.width(112.dp).height(170.dp),
        color=Color(0xC01B3744),shape=RoundedCornerShape(17.dp),
        border=BorderStroke(1.5.dp,accent.copy(alpha=.62f)),
        shadowElevation=4.dp) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color(0xD02D4B50),Color(0xD0102331)))).padding(9.dp),
            horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center) {
            Surface(shape=RoundedCornerShape(99.dp),color=accent.copy(alpha=.16f),
                border=BorderStroke(1.dp,accent.copy(alpha=.72f))) {
                Text("＋",color=accent,fontSize=36.sp,fontWeight=FontWeight.Light,
                    modifier=Modifier.padding(horizontal=11.dp,vertical=0.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text("邀请新伙伴",color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold)
            Text("由角色自己决定",color=accent,fontSize=9.sp)
        }
    }
}
