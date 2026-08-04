package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object GameDesign {
    val paper = Color(0xFFF6F7F9)
    val card = Color(0xFFFFFFFF)
    val wheat = Color(0xFF24262B)
    val wheatSoft = Color(0xFFEDEFF3)
    val border = Color(0xFFE1E4E9)
    val muted = Color(0xFF717784)
    val ink = Color(0xFF202226)
    val success = Color(0xFF3E7656)
    val error = Color(0xFFB24F53)
    val board = Color(0xFFD6AD62)
}

@Composable
internal fun GameCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GameDesign.card),
        border = BorderStroke(1.dp, GameDesign.border),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

@Composable
internal fun GamePageList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (success) Color(0xFFE7F2EA) else Color(0xFFF7E7E4),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(text, Modifier.padding(14.dp), fontWeight = FontWeight.SemiBold)
    }
}
