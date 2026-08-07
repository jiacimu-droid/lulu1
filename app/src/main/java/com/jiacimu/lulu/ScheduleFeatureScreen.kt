package com.jiacimu.lulu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.design.LuluColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleFeatureScreen(onBack: () -> Unit) {
    val today = LocalDate.now()
    Scaffold(
        containerColor = LuluColors.Paper,
        topBar = {
            TopAppBar(
                title = { Text("日程", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LuluColors.Paper),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
                border = BorderStroke(1.dp, LuluColors.Border),
                shape = RoundedCornerShape(22.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = LuluColors.CardStrong) {
                        Icon(Icons.Outlined.CalendarMonth, null, modifier = Modifier.padding(12.dp).size(28.dp))
                    }
                    Spacer(Modifier.width(13.dp))
                    Column {
                        Text(today.format(DateTimeFormatter.ofPattern("M月d日")), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Text("日程 App 已建立", color = LuluColors.Muted, fontSize = 12.sp)
                    }
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = LuluColors.Card),
                border = BorderStroke(1.dp, LuluColors.Border),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("暂无日程", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("先保留独立入口。之后可以再决定这里放考试、待办、提醒、角色约定或其他时间安排。", color = LuluColors.Muted)
                }
            }
        }
    }
}
