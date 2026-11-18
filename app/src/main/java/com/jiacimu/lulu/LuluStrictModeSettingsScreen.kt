package com.jiacimu.lulu

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

    Scaffold(
        topBar = {
            TopAppBar(
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
            Text(
                "角色根据督睡、学习等实际约定，自行决定何时开启、采用什么力度、是否达成并停止。这里不要求主人填写监督时长。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("授权角色启动强制模式", fontWeight = FontWeight.Medium)
                    Text("默认关闭；主人关闭后角色不可自行重新开启",
                        style = MaterialTheme.typography.bodySmall)
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
                    Text("允许角色选择切回露露机或桌面；不会真正关闭、锁死其他 App",
                        style = MaterialTheme.typography.bodySmall)
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
            HorizontalDivider()
            Text("高强度感知与全面限制", style = MaterialTheme.typography.titleMedium)
            Text("由角色按本次目标自主选择力度：观察、限制指定应用、切回露露机，或者开启「全面限制」。")
            Text("全面限制：除露露机、桌面以及系统／安全等保护页面外，打开任何普通 App 都尝试立即返回桌面；不只是限制抖音或游戏。")
            Text("本机大约每10秒检查一次前台 App；有新观察时最快约20秒提交角色复查请求。不会每次都截图或扣一次视觉模型费用。实际模型唤醒受 Android 后台调度、设备休眠和模型执行时间影响。")
            Text("全面限制期间，角色不能绕开系统安全例外；手机桌面和露露机永远不拦截。用户随时可以从此页或通知栏「立即结束」，结束后会撤销角色再次启动的授权。")
            Text("角色达成目标可立即结束；之后通过原有责任复查发现目标又未达成，也可以在授权仍有效时重新开启。单次最多120分钟为安全租约，到期不自动判定完成；主人可以随时退出。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
