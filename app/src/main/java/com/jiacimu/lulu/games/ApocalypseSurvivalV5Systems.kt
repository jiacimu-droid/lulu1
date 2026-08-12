package com.jiacimu.lulu.games

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SystemNight = Color(0xFF07111F)
private val SystemBlue = Color(0xFF2387E8)
private val SystemBlueSoft = Color(0xFFEAF4FF)
private val SystemInk = Color(0xFF0A1726)
private val SystemMuted = Color(0xFF607287)
private val SystemBorder = Color(0xFFD3E3F2)

internal fun apocalypseDayLabelV5(dayIndex: Int): String = when {
    dayIndex < 0 -> "灾变前${-dayIndex}天"
    else -> "灾变第${dayIndex + 1}天"
}

internal fun apocalypseClockLabelV5(clockMinutes: Int): String {
    val safe = ((clockMinutes % 1440) + 1440) % 1440
    val hour = safe / 60
    val minute = safe % 60
    return "%02d:%02d".format(hour, minute)
}

internal fun apocalypseSurvivalConditionLabelV5(stats: ApocalypseV3Stats): String = when {
    stats.health <= 25 -> "重伤"
    stats.infection >= 70 -> "高感染风险"
    stats.stamina <= 20 -> "极度疲劳"
    stats.morale <= 20 -> "精神濒临崩溃"
    stats.health <= 55 -> "受伤"
    stats.stamina <= 45 -> "疲劳"
    stats.infection >= 35 -> "感染暴露"
    else -> "状态稳定"
}

private data class BaseModuleV5(
    val icon: ImageVector,
    val name: String,
    val detail: String,
    val requiredLevel: Int,
)

private fun apocalypseBaseModulesV5(): List<BaseModuleV5> = listOf(
    BaseModuleV5(Icons.Outlined.HomeWork, "安全睡眠区", "遮雨、隔音、夜间轮值与基本防火。", 1),
    BaseModuleV5(Icons.Outlined.WaterDrop, "储水与净水", "封闭储水、基础过滤、雨水/管网应急接入。", 2),
    BaseModuleV5(Icons.Outlined.MedicalServices, "医疗角", "伤口处理、药品分类、隔离与基础观察。", 2),
    BaseModuleV5(Icons.Outlined.Bolt, "独立供电", "电池、发电机或微电网，优先保障照明与通信。", 3),
    BaseModuleV5(Icons.Outlined.Handyman, "维修工坊", "工具、备件、简单加工和车辆维护。", 3),
    BaseModuleV5(Icons.Outlined.Security, "外围防御", "警戒、缓冲区、撤退路线与噪音控制。", 4),
    BaseModuleV5(Icons.Outlined.CellTower, "远距通信", "无线电中继、区域情报与跨据点联络。", 4),
    BaseModuleV5(Icons.Outlined.Agriculture, "持续生产", "温室、种植、食物加工与长期水循环。", 5),
)

@Composable
internal fun ApocalypseSurvivalSnapshotV5(save: ApocalypseV3Save) {
    val director = save.director
    val stats = save.stats
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SystemNight,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, tint = Color(0xFF4EA8FF), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    "${apocalypseDayLabelV5(director.dayIndex)} · ${apocalypseClockLabelV5(director.clockMinutes)}",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                )
                Text("${director.weather} · ${director.temperatureC}℃", color = Color(0xFFAEC4D9), fontSize = 10.sp)
            }
            Text("${director.location} · ${apocalypseSurvivalConditionLabelV5(stats)}", color = Color(0xFFAEC4D9), fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ApocalypseConditionValueV5("生命", stats.health, false)
                ApocalypseConditionValueV5("体力", stats.stamina, false)
                ApocalypseConditionValueV5("感染", stats.infection, true)
                ApocalypseConditionValueV5("士气", stats.morale, false)
            }
        }
    }
}

@Composable
private fun ApocalypseConditionValueV5(label: String, value: Int, dangerHigh: Boolean) {
    val warning = if (dangerHigh) value >= 60 else value <= 30
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", color = if (warning) Color(0xFFFF9F9F) else Color(0xFF8CCAFF), fontSize = 15.sp, fontWeight = FontWeight.Black)
        Text(label, color = Color(0xFFAEC4D9), fontSize = 8.sp)
    }
}

@Composable
internal fun ApocalypseObjectivePanelV5(director: ApocalypseV3Director) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, SystemBorder),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Flag, null, tint = SystemBlue, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(7.dp))
                Text("当前目标", color = SystemInk, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            Text(director.sceneGoal, color = SystemInk, fontSize = 12.sp, lineHeight = 18.sp)
            val visibleThreads = director.storyThreads
                .filter { it.visibility == "main" && it.status == "active" }
                .map { "${it.title}：${it.currentState}" }
                .take(4)
                .ifEmpty { director.activeThreads.take(4) }
            visibleThreads.forEach { thread ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("•", color = SystemBlue, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(7.dp))
                    Text(thread, color = SystemMuted, fontSize = 11.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
internal fun ApocalypseCharacterStatePanelV5(dossiers: List<ApocalypseCharacterDossierV5>) {
    if (dossiers.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, SystemBorder),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Groups, null, tint = SystemBlue, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(7.dp))
                Text("人物状态", color = SystemInk, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            dossiers.sortedByDescending { it.lastSeenScene }.take(12).forEach { dossier ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(apocalypseDossierDisplayNameV5(dossier), color = SystemInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        val statusLabel = when (dossier.status) {
                            "away" -> "离屏"
                            "missing" -> "失联"
                            "dead" -> "死亡"
                            else -> "活动中"
                        }
                        Text(statusLabel, color = SystemBlue, fontSize = 9.sp)
                    }
                    Text(
                        listOf(dossier.currentLocation, dossier.physicalState, dossier.emotionalState)
                            .filter(String::isNotBlank)
                            .joinToString(" · ")
                            .ifBlank { "状态尚未在剧情中确认" },
                        color = SystemMuted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                    dossier.offscreenIntent.takeIf(String::isNotBlank)?.let { intent ->
                        Text("当前打算：$intent", color = SystemMuted, fontSize = 9.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ApocalypseBaseDashboardV5(save: ApocalypseV3Save) {
    val level = save.stats.baseLevel
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SystemBlueSoft,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, SystemBorder),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Domain, null, tint = SystemBlue)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (level <= 0) "尚未建立基地" else "${save.stats.baseName} · Lv.$level", color = SystemInk, fontWeight = FontWeight.Black)
                    Text(
                        if (level <= 0) "目前所有休息、储物和防御都依赖临时地点。" else "基地能力不再只是等级数字；升级会解锁真实生存功能。",
                        color = SystemMuted,
                        fontSize = 10.sp,
                    )
                }
            }
            apocalypseBaseModulesV5().forEach { module ->
                val unlocked = level >= module.requiredLevel
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (unlocked) Icons.Outlined.CheckCircle else Icons.Outlined.Lock,
                        null,
                        tint = if (unlocked) SystemBlue else SystemMuted,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(module.name, color = if (unlocked) SystemInk else SystemMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text(module.detail, color = SystemMuted, fontSize = 9.sp, lineHeight = 14.sp)
                    }
                    Text("Lv.${module.requiredLevel}", color = SystemMuted, fontSize = 8.sp)
                }
            }
        }
    }
}
