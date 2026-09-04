package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object GameDesign {
    val paper = Color(0xFFE9EDF2)
    val card = Color(0xFFF8FAFC)
    val wheat = Color(0xFF24262B)
    val wheatSoft = Color(0xFFDCE2E9)
    val border = Color(0xFFC9D1DB)
    val muted = Color(0xFF69727F)
    val ink = Color(0xFF18212A)
    val onDark = Color(0xFFFFFFFF)
    val success = Color(0xFF3E7656)
    val error = Color(0xFFB24F53)
    val board = Color(0xFFC99D57)
}

@Composable
internal fun GameCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = GameDesign.card,
            contentColor = GameDesign.ink,
        ),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .82f)),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 9.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = .96f),
                            Color(0xFFF5F7FA),
                            Color(0xFFE7EBF0),
                        ),
                    ),
                ),
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = .76f), Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(160f, 8f),
                            radius = 560f,
                        ),
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = .95f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                content = content,
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xFF707B88).copy(alpha = .12f)),
                        ),
                    ),
            )
        }
    }
}

@Composable
internal fun GamePageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFF2F4F7),
                        GameDesign.paper,
                        Color(0xFFDDE3E9),
                    ),
                ),
            ),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
        content = content,
    )
}

@Composable
internal fun GameRolePanel(characterName: String, response: GameRoleResponse) {
    GameCard {
        Text("$characterName 的回应", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        when {
            response.loading -> Text("正在生成角色回应…", color = GameDesign.muted)
            response.error.isNotBlank() -> Text(response.error, color = GameDesign.error)
            response.text.isNotBlank() -> Text(response.text)
            else -> Text("完成一轮后，角色会根据真实结果和自身人设回应。", color = GameDesign.muted)
        }
    }
}

@Composable
internal fun GameResultBanner(text: String, success: Boolean = true) {
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = .94f),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (success) Color(0xFFEAF4ED) else Color(0xFFF7E9E7),
            contentColor = GameDesign.ink,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(
                1.dp,
                if (success) GameDesign.success.copy(alpha = .28f) else GameDesign.error.copy(alpha = .28f),
            ),
            shadowElevation = 7.dp,
        ) {
            Box(
                Modifier.background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = .52f), Color.Transparent),
                    ),
                ),
            ) {
                Text(text, Modifier.padding(15.dp), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
