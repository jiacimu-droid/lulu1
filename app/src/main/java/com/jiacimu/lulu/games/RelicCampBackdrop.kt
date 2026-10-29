package com.jiacimu.lulu.games

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.jiacimu.lulu.R
import kotlin.math.sin

/** Bundled original artwork. Actual game terrain is always drawn from the run separately. */
@Composable
internal fun RelicCampBackdrop() {
    val phase by rememberInfiniteTransition(label="camp").animateFloat(0f,1f,
        infiniteRepeatable(tween(6000,easing=LinearEasing)),label="embers")
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.game_relic_camp),contentDescription=null,
            modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        Canvas(Modifier.fillMaxSize()) {
            repeat(18) { i ->
                val drift=(phase+i/18f)%1f
                val p=Offset(size.width*(.44f+.06f*sin(i*1.7f+drift*2)),
                    size.height*(.80f-drift*.30f))
                drawCircle(Color(0xFFFFD890).copy(alpha=(1f-drift)*.5f),
                    size.minDimension*.0018f,p)
            }
        }
    }
}
