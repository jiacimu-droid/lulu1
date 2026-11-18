package com.jiacimu.lulu

import com.jiacimu.lulu.design.LuluCssScaffold
import com.jiacimu.lulu.design.LuluCssTopAppBar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.system.LuluScreenAwareness
import com.jiacimu.lulu.system.LuluStrictMode
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The owner controls authorization here; goals and active session decisions
 * remain with the existing character tool, not another UI-driven planner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LuluStrictModeSettingsScreen(
    onBack: () -> Unit,
    onOpenCapabilities: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { LuluScreenAwareness.preferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            revision++
        }
    }
    @Suppress("UNUSED_VARIABLE") val updateKey = revision
    val consent = LuluScreenAwareness.consented(context)
    val track = LuluScreenAwareness.appTracking(context)
    val grant = LuluStrictMode.ownerGranted(context)
    val directReturn = LuluScreenAwareness.directReturnAllowed(context)
    val active = LuluStrictMode.active(context)
    val mode = when (active?.optString("mode")) {
        "home" -> "指定应用切回桌面"
        "lockdown" -> "全面限制 · 普通应用一律切回桌面"
        "return" -> "切回露露机"
        "observe" -> "仅观察"
        else -> "未启用"
    }
    val roleId = active?.optString("characterId").orEmpty()
    val roleName = if (roleId.isBlank()) "未指定" else runCatching {
        MigratedDomainStores.characters.get(roleId).displayName
    }.getOrNull()?.takeIf(String::isNotBlank) ?: roleId
    val endsAt = active?.optLong("endsAt")?.takeIf { it > 0L }?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    } ?: "—"
    val blocked = active?.optJSONArray("blockedPackages")
    val blockedLabels = blocked?.let { array ->
        (0 until array.length()).map { array.optString(it) }.joinToString("、")
    }.orEmpty()

    LuluCssScaffold(
        cssPage = "StrictMode",
        topBar = {
            LuluCssTopAppBar(
                title = { Text("强制模式", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("角色目标监督", style = MaterialTheme.typography.titleLarge)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("授权角色启动强制模式", fontWeight = FontWeight.Medium)
                }
                Switch(
                    checked = grant,
                    enabled = consent,
                    onCheckedChange = { enabled ->
                        if (!enabled) LuluStrictMode.ownerStop(context, "主人在设置中关闭")
                        else prefs.edit().putBoolean(LuluStrictMode.GRANT, true).commit()
                        revision++
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("允许直接切回", fontWeight = FontWeight.Medium)
                }
                Switch(
                    checked = directReturn,
                    enabled = consent,
                    onCheckedChange = { enabled ->
                        if (!enabled && active?.optString("mode") != "observe")
                            LuluStrictMode.stop(context, reason = "主人关闭切回权限")
                        prefs.edit().putBoolean(LuluScreenAwareness.DIRECT_RETURN, enabled).commit()
                        revision++
                    },
                )
            }
            if (!consent || !track) {
                Text("还需要屏幕视觉授权、应用使用感知与无障碍服务。权限不齐时无法开启监督。",
                    color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = onOpenCapabilities) { Text("检查屏幕感知与权限") }
            HorizontalDivider()
            Text("当前会话", style = MaterialTheme.typography.titleMedium)
            Text("监督角色：$roleName")
            Text("状态：$mode")
            Text("目标：" + (active?.optString("goal")?.ifBlank { "旧版会话，未提供目标" } ?: "目前没有正在进行的监督"))
            Text("安全租约到期：$endsAt（并不代表监督目标完成）")
            if (active != null) {
                Text("限制应用：" + if (blockedLabels.isEmpty()) "除例外外的普通应用" else blockedLabels)
                Button(onClick = {
                    LuluStrictMode.ownerStop(context, "主人主动结束并撤销自动重启授权")
                    revision++
                }) { Text("立即结束并禁止自动重启") }
            }
        }
    }
}
