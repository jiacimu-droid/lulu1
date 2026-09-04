package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TradeNightV5 = Color(0xFF101714)
private val TradePanelV5 = Color(0xFF19231F)
private val TradeAccentV5 = Color(0xFFE7D08B)
private val TradeMutedV5 = Color(0xFFA8B8AF)

@Composable
internal fun ApocalypseTradeSheetV5(
    save: ApocalypseV3Save,
    market: ApocalypseTradeMarketV5,
    onDismiss: () -> Unit,
    onConfirmed: (ApocalypseTradeResolutionV5) -> Unit,
) {
    var requested by remember(market.id) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val quote = remember(save.stats, market, requested) { quoteApocalypseTradeV5(save, market, requested) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = TradeNightV5,
        contentColor = Color.White,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = TradeAccentV5.copy(alpha = .14f), shape = RoundedCornerShape(14.dp)) {
                    Icon(
                        Icons.Outlined.ShoppingCart,
                        null,
                        tint = TradeAccentV5,
                        modifier = Modifier.padding(11.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(market.sellerName, fontSize = 19.sp, fontWeight = FontWeight.Black)
                    Text("${market.location} · ${market.mode.label}", color = TradeMutedV5, fontSize = 10.sp)
                }
            }

            Text(
                market.sellerDetail,
                color = TradeMutedV5,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 390.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(market.offers, key = { it.id }) { offer ->
                    val quantity = requested[offer.id] ?: 0
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = TradePanelV5,
                        shape = RoundedCornerShape(17.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .09f)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(offer.title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(7.dp))
                                    Text("库存 ${offer.stock}${offer.unitLabel}", color = TradeMutedV5, fontSize = 8.5.sp)
                                }
                                Text(offer.detail, color = TradeMutedV5, fontSize = 9.5.sp, lineHeight = 14.sp)
                                Text(
                                    tradeOfferPriceLabelV5(offer),
                                    color = TradeAccentV5,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        requested = requested + (offer.id to (quantity - 1).coerceAtLeast(0))
                                    },
                                    enabled = quantity > 0,
                                ) {
                                    Icon(Icons.Outlined.Remove, "减少", tint = if (quantity > 0) Color.White else TradeMutedV5)
                                }
                                Text("$quantity", fontWeight = FontWeight.Black, fontSize = 14.sp)
                                IconButton(
                                    onClick = {
                                        requested = requested + (offer.id to (quantity + 1).coerceAtMost(offer.stock))
                                    },
                                    enabled = quantity < offer.stock,
                                ) {
                                    Icon(Icons.Outlined.Add, "增加", tint = if (quantity < offer.stock) Color.White else TradeMutedV5)
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF0C1311),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .1f)),
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row {
                        Text("交易结算", fontWeight = FontWeight.Black, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("约 ${quote.minutesPassed} 分钟", color = TradeMutedV5, fontSize = 9.sp)
                    }
                    Text(
                        buildString {
                            if (quote.moneyCost > 0) append("资金 -¥${quote.moneyCost}  ")
                            if (quote.materialsCost > 0) append("材料 -${quote.materialsCost}  ")
                            if (quote.coresCost > 0) append("晶核 -${quote.coresCost}  ")
                            if (quote.foodGain > 0) append("食物 +${quote.foodGain}  ")
                            if (quote.waterGain > 0) append("饮水 +${quote.waterGain}  ")
                            if (quote.medicineGain > 0) append("药品 +${quote.medicineGain}  ")
                            if (quote.materialsGain > 0) append("材料 +${quote.materialsGain}")
                        }.ifBlank { "选择商品后在这里结算" },
                        color = if (quote.valid) TradeAccentV5 else Color(0xFFFF9D98),
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                    if (!quote.valid && quote.reason.isNotBlank()) {
                        Text(quote.reason, color = Color(0xFFFF9D98), fontSize = 9.sp)
                    }
                }
            }

            Button(
                onClick = {
                    resolveApocalypseTradeV5(save, market, requested)?.let(onConfirmed)
                },
                enabled = quote.valid && quote.lines.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TradeAccentV5,
                    contentColor = Color(0xFF17150D),
                ),
                shape = RoundedCornerShape(15.dp),
            ) {
                Text("确认交易", fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun tradeOfferPriceLabelV5(offer: ApocalypseTradeOfferV5): String = buildList {
    if (offer.moneyCost > 0) add("¥${offer.moneyCost}")
    if (offer.materialsCost > 0) add("材料 ${offer.materialsCost}")
    if (offer.coresCost > 0) add("晶核 ${offer.coresCost}")
}.joinToString(" + ").ifBlank { "无需支付" }
