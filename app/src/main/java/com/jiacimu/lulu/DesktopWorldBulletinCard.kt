package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FiberNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.data.DigitalWorldBulletin
import com.jiacimu.lulu.data.DigitalWorldExpansionStore
import com.jiacimu.lulu.data.DigitalWorldNotice
import com.jiacimu.lulu.data.DigitalWorldStore
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * Home's centre area is a real world bulletin, not a decorative quote.
 * The badge is per-device acknowledgement; never marks a fictional event as real.
 */
@Composable
internal fun DesktopWorldBulletinCard() {
    val context = LocalContext.current
    val world by DigitalWorldStore.state.collectAsState()
    val prefs = remember(context) {
        context.applicationContext.getSharedPreferences("lulu_world_bulletin_read_v1", android.content.Context.MODE_PRIVATE)
    }
    var refresh by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(prefs.getStringSet("seen", emptySet()).orEmpty().toSet()) }
    var opened by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        DigitalWorldStore.initialize(context)
        DigitalWorldExpansionStore.initialize(context)
        while (true) {
            refresh++
            delay(20_000L)
        }
    }
    val notices = remember(world, refresh) {
        DigitalWorldBulletin.collect(
            world, DigitalWorldExpansionStore.residents(), DigitalWorldExpansionStore.places(),
        )
    }
    val unread = notices.count { it.id !in seen }
    val latest = notices.firstOrNull()
    Surface(
        modifier = Modifier.fillMaxWidth().height(110.dp),
        color = Color(0xFFFCFCFC),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFE7E7E7)),
        shadowElevation = 1.dp,
        onClick = {
            opened = true
            val updated = (seen + notices.map(DigitalWorldNotice::id)).toList().takeLast(500).toSet()
            seen = updated
            prefs.edit().putStringSet("seen", updated).apply()
        },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Campaign, null, Modifier.size(27.dp), tint = Color(0xFF343436))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("数字世界 · 公告栏", color = Color(0xFF1D1D1F),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (unread > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(color = Color(0xFF3B3B3E), shape = RoundedCornerShape(99.dp)) {
                            Text("${unread.coerceAtMost(99)} 新动态",
                                color = Color.White, fontSize = 9.sp,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
                        }
                    }
                }
                Text(latest?.title ?: "世界暂时没有新公告",
                    color = Color(0xFF303033), fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(latest?.detail ?: "新居民、地点和重要建设会在这里出现。",
                    color = Color(0xFF77777B), fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.ChevronRight, "查看世界公告", Modifier.size(20.dp),
                tint = Color(0xFF89898E))
        }
    }

    if (opened) {
        AlertDialog(
            onDismissRequest = { opened = false },
            title = { Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Campaign, null, tint = Color(0xFF303033))
                Spacer(Modifier.width(9.dp))
                Text("数字世界公告", fontSize = 20.sp)
            } },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 490.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("以下动态均来自已保存的世界状态，尚未发生的提案不会被公布。",
                        fontSize = 11.sp, color = Color(0xFF77777B))
                    if (notices.isEmpty()) {
                        Text("世界仍在慢慢生长，还没有值得公告的大事。",
                            fontSize = 13.sp, color = Color(0xFF77777B))
                    }
                    notices.forEach { notice ->
                        Surface(
                            color = Color(0xFFF5F5F5), shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFE8E8E8)),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(notice.title, Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(notice.occurredAt.atZone(ZoneId.systemDefault())
                                        .format(DateTimeFormatter.ofPattern("M/d HH:mm")),
                                        color = Color(0xFF89898E), fontSize = 10.sp)
                                }
                                Text(notice.detail, color = Color(0xFF4C4C50),
                                    fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { opened = false }) { Text("知道啦") }
            },
            containerColor = Color.White,
        )
    }
}
