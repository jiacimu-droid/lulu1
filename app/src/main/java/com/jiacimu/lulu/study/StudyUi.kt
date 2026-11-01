package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.design.LocalLuluAppearance
import com.jiacimu.lulu.design.isJournal
import com.jiacimu.lulu.design.journalPaper

/** Small visual primitives still shared by 自序专注, 画卷 and 阅读 after retiring 考研. */
internal object StudyDesign {
    val paper = Color(0xFFFFFDF7)
    val card = Color(0xFFFFFBF1)
    val wheat = Color(0xFFFFF2C8)
    val wheatSoft = Color(0xFFFFF2C8)
    val muted = Color(0xFF6D7888)
    val ink = Color(0xFF343434)
    val border = Color(0xFFEAE0CC)
    val dark = Color(0xFF272A30)
    val darkCard = Color(0xFF343840)
    val success = Color(0xFF5E7E65)
    val error = Color(0xFFA55C54)
}

@Composable
internal fun StudyCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(LocalLuluAppearance.current.corner.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().then(
                if (LocalLuluAppearance.current.isJournal())
                    Modifier.journalPaper(LocalLuluAppearance.current)
                else Modifier
            ).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

@Composable
internal fun StudyMessage(text: String, error: Boolean = false) {
    if (text.isBlank()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(15.dp),
    ) {
        Text(
            text,
            Modifier.padding(13.dp),
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
