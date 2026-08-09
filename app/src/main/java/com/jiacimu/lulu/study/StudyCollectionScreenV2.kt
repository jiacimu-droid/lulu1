package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class CompactCollectionTicket(
    val id: String,
    val title: String,
    val amount: Int,
    val use: () -> Unit,
)

@Composable
internal fun StudyCollectionScreenV2(
    state: StudyState,
    store: PostgraduateExamStore,
    onOpenTheater: () -> Unit,
    onOpenProbabilityDesign: () -> Unit,
) {
    var message by remember { mutableStateOf("") }

    val builtInTickets = state.gachaRules.mapNotNull { rule ->
        when (rule.type) {
            StudyGachaRewardType.Douyin -> CompactCollectionTicket(
                id = rule.id,
                title = rule.title,
                amount = state.inventory.douyinTickets,
            ) { message = store.redeemEntertainment(StudyEntertainmentKind.Douyin) }

            StudyGachaRewardType.GameRound -> CompactCollectionTicket(
                id = rule.id,
                title = rule.title,
                amount = state.inventory.gameRoundTickets,
            ) { message = store.redeemEntertainment(StudyEntertainmentKind.GameRound) }

            StudyGachaRewardType.Theater -> CompactCollectionTicket(
                id = rule.id,
                title = rule.title,
                amount = state.inventory.theaterFragments,
            ) {
                message = store.redeemEntertainment(StudyEntertainmentKind.Theater)
                if (!message.contains("不足") && !message.contains("全部")) onOpenTheater()
            }

            StudyGachaRewardType.Movie -> CompactCollectionTicket(
                id = rule.id,
                title = rule.title,
                amount = state.inventory.gameTickets,
            ) { message = store.redeemEntertainment(StudyEntertainmentKind.Game) }

            StudyGachaRewardType.Anime -> CompactCollectionTicket(
                id = rule.id,
                title = rule.title,
                amount = state.inventory.animeTickets,
            ) { message = store.redeemEntertainment(StudyEntertainmentKind.Anime) }

            StudyGachaRewardType.Custom -> null
        }
    }

    val customTickets = state.gachaRules.filter(StudyGachaRule::custom).map { rule ->
        CompactCollectionTicket(
            id = rule.id,
            title = rule.title,
            amount = state.inventory.customRewards[rule.id] ?: 0,
        ) {
            message = store.redeemCustomReward(rule.id)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "probability-entry") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onOpenProbabilityDesign,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("概率设计", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        items(builtInTickets, key = { it.id }) { ticket ->
            CompactCollectionTicketCard(ticket)
        }

        if (customTickets.isNotEmpty()) {
            item(key = "custom-rewards-title") {
                Text("自定义奖励", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
            items(customTickets, key = { it.id }) { ticket ->
                CompactCollectionTicketCard(ticket)
            }
        }

        item(key = "fragments-title") {
            Text("画卷碎片", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
        }

        items(blueFragmentCatalog.chunked(3), key = { it.joinToString("|") }) { titles ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                titles.forEach { title ->
                    CompactFragmentProgressCard(
                        title = title,
                        amount = state.inventory.blueFragments[title] ?: 0,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - titles.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        item { StudyMessage(message, message.contains("不足") || message.contains("失败")) }
    }
}

@Composable
private fun CompactCollectionTicketCard(ticket: CompactCollectionTicket) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = StudyDesign.card,
        border = BorderStroke(1.dp, StudyDesign.border),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ticket.title, fontWeight = FontWeight.Bold)
                Text("拥有 ${ticket.amount}", color = StudyDesign.muted, fontSize = 12.sp)
            }
            Button(
                onClick = ticket.use,
                enabled = ticket.amount > 0,
                colors = ButtonDefaults.buttonColors(containerColor = StudyDesign.wheat, contentColor = StudyDesign.ink),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("使用")
            }
        }
    }
}

@Composable
private fun CompactFragmentProgressCard(
    title: String,
    amount: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = StudyDesign.card,
        border = BorderStroke(1.dp, StudyDesign.border),
    ) {
        Column(
            Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text("$amount/$BLUE_FRAGMENTS_PER_SCROLL", color = StudyDesign.muted, fontSize = 11.sp)
            LinearProgressIndicator(
                progress = { (amount.toFloat() / BLUE_FRAGMENTS_PER_SCROLL).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = StudyDesign.wheatSoft,
                trackColor = StudyDesign.border,
            )
        }
    }
}
