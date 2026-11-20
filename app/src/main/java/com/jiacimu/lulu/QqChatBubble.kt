package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.design.JournalChatBubble
import com.jiacimu.lulu.design.LocalLuluAppearance
import com.jiacimu.lulu.design.cssAlignment
import com.jiacimu.lulu.design.cssColor
import com.jiacimu.lulu.design.cssFontFamily
import com.jiacimu.lulu.design.cssFontSize
import com.jiacimu.lulu.design.hasCssSurface
import com.jiacimu.lulu.design.isJournal
import com.jiacimu.lulu.design.luluCssDecor
import com.jiacimu.lulu.design.luluCssPadding
import com.jiacimu.lulu.design.luluCssStyle

@Composable
internal fun qqBubbleStyle(mine: Boolean): Map<String, String> =
    luluCssStyle("bubble", if (mine) "bubble-user" else "bubble-role")

internal fun qqBubbleAlignment(mine: Boolean, style: Map<String, String>): Alignment =
    style.cssAlignment(if (mine) Alignment.BottomEnd else Alignment.BottomStart)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QqTextBubble(
    mine: Boolean,
    style: Map<String, String>,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val profile = LocalLuluAppearance.current
    val journal = profile.isJournal()
    val padded: @Composable () -> Unit = {
        Box(Modifier.luluCssPadding(style, horizontal = 12.dp, vertical = 7.dp)) { content() }
    }
    val click = if (onLongClick == null) Modifier else Modifier.combinedClickable(
        onClick = {}, onLongClick = onLongClick,
    )
    if (journal && !style.hasCssSurface()) {
        JournalChatBubble(mine, modifier.luluCssDecor(style, includePadding = false).then(click), padded)
    } else {
        val radius = profile.corner.coerceIn(6, 34).dp
        val shape = when {
            !journal -> RoundedCornerShape(15.dp)
            mine -> RoundedCornerShape(radius, 6.dp, radius, radius)
            else -> RoundedCornerShape(6.dp, radius, radius, radius)
        }
        val fill = when {
            !journal -> if (mine) QqMine else QqOther
            mine -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        }
        val border = if (journal) MaterialTheme.colorScheme.outline.copy(alpha = .73f) else if (mine) QqMine else QqBorder
        Box(
            modifier.luluCssDecor(
                style, includePadding = false, defaultShape = shape,
                defaultBackground = fill, defaultBorder = BorderStroke(1.dp, border),
            ).then(click),
            propagateMinConstraints = true,
        ) { padded() }
    }
}

@Composable
internal fun QqBubbleText(text: String, mine: Boolean, style: Map<String, String>, voiceNote: Boolean = false) {
    val size = style.cssFontSize()
    Text(
        text,
        color = style.cssColor("color") ?: if (mine && !LocalLuluAppearance.current.isJournal()) QqMineInk else QqInk,
        fontSize = size ?: if (voiceNote) 13.sp else 15.sp,
        lineHeight = size?.let { (it.value + 6).sp } ?: 21.sp,
        fontFamily = style.cssFontFamily(),
    )
}
