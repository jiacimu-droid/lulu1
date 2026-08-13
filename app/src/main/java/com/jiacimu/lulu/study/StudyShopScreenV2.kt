package com.jiacimu.lulu.study

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun StudyShopScreenV2(state: StudyState, store: PostgraduateExamStore) {
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current
    val refreshPrefs = remember {
        context.getSharedPreferences("study_shop_refresh_guard", android.content.Context.MODE_PRIVATE)
    }
    val persistedRefreshDate = refreshPrefs.getString("date", "").orEmpty()
    val canRefresh = state.manualShopRefreshDate != state.activeDate && persistedRefreshDate != state.activeDate

    LaunchedEffect(state.activeDate, state.manualShopRefreshDate) {
        if (state.manualShopRefreshDate == state.activeDate) {
            refreshPrefs.edit().putString("date", state.activeDate).apply()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item {
            StudyCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("神秘商店", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = StudyDesign.ink)
                        Text("夸夸值：${state.profile.praisePoints}", color = StudyDesign.muted)
                    }
                    IconButton(
                        onClick = {
                            if (refreshPrefs.getString("date", "").orEmpty() == state.activeDate) {
                                message = "今天已经手动刷新过了"
                            } else {
                                val result = store.refreshShop()
                                if (result == "商店已刷新") {
                                    refreshPrefs.edit().putString("date", state.activeDate).apply()
                                }
                                message = result
                            }
                        },
                        enabled = canRefresh,
                    ) {
                        Icon(Icons.Outlined.Refresh, "刷新", tint = if (canRefresh) StudyDesign.ink else StudyDesign.muted)
                    }
                }
                Text("每天自动刷新3件随机商品；抽卡券出现权重更高，手动刷新每天最多一次。", color = StudyDesign.muted, fontSize = 12.sp)
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFFFAE9),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.5.dp, StudyDesign.wheat),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = StudyDesign.wheatSoft, shape = RoundedCornerShape(13.dp)) {
                            Icon(Icons.Outlined.SwapHoriz, null, tint = StudyDesign.ink, modifier = Modifier.padding(9.dp))
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text("抽卡券兑换", color = StudyDesign.ink, fontSize = 18.sp, fontWeight = FontWeight.Black)
                            Text("10 张单抽券 → 1 张十连券 · 永久兑换，不占每日商品位", color = StudyDesign.muted, fontSize = 12.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TicketBalance("单抽券", state.inventory.singleTickets, Modifier.weight(1f))
                        TicketBalance("十连券", state.inventory.tenTickets, Modifier.weight(1f))
                    }
                    Button(
                        onClick = { message = store.exchangeSingleTicketsForTen() },
                        enabled = state.inventory.singleTickets >= 10,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StudyDesign.wheat,
                            contentColor = StudyDesign.ink,
                            disabledContainerColor = Color(0xFFF0EEE8),
                            disabledContentColor = StudyDesign.muted,
                        ),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Text(if (state.inventory.singleTickets >= 10) "兑换十连券" else "还差 ${10 - state.inventory.singleTickets} 张单抽券", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        items(state.shopItems, key = { it.id }) { item ->
            StudyCard {
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = StudyDesign.ink)
                        Text(item.subtitle, color = StudyDesign.muted)
                    }
                    Text("${item.cost} 夸夸值", fontWeight = FontWeight.SemiBold, color = StudyDesign.ink)
                }
                Button(
                    onClick = { message = store.buyShopItem(item.id) },
                    enabled = !item.purchased && state.profile.praisePoints >= item.cost,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StudyDesign.wheat,
                        contentColor = StudyDesign.ink,
                        disabledContainerColor = Color(0xFFF0EEE8),
                        disabledContentColor = StudyDesign.muted,
                    ),
                ) { Text(if (item.purchased) "已购买" else "购买", fontWeight = FontWeight.Bold) }
            }
        }
        item { StudyMessage(message, message.contains("不足") || message.contains("失败") || message.contains("还差")) }
    }
}

@Composable
private fun TicketBalance(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Color.White, shape = RoundedCornerShape(15.dp), border = BorderStroke(1.dp, StudyDesign.border)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value.toString(), color = StudyDesign.ink, fontSize = 21.sp, fontWeight = FontWeight.Black)
            Text(label, color = StudyDesign.muted, fontSize = 11.sp)
        }
    }
}
