package com.jiacimu.lulu

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A one-time, local consent receipt. Bump VERSION only for material agreement
 * changes; no account, government ID, birth date or server is needed to store it.
 */
internal object LuluFirstRunAgreement {
    const val VERSION = 1
    private const val PREFS = "lulu_first_run_agreement"
    private const val ACCEPTED_VERSION = "accepted_version"

    internal fun acceptsCurrentVersion(version: Int): Boolean = version == VERSION

    fun accepted(context: Context): Boolean =
        acceptsCurrentVersion(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(ACCEPTED_VERSION, 0),
        )

    /** commit() ensures the receipt is saved before runtime initialization. */
    fun accept(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(ACCEPTED_VERSION, VERSION).commit()
}

// Shown in full before consent. Do not claim that model requests stay on device.
internal val luluAgreementSections = listOf(
    "服务说明" to
        "露露机是免费提供的 Android AI 数字生命客户端。角色由人工智能生成，并非真人；回应可能不准确，不应替代医疗、法律、财务或紧急救助。",
    "模型与费用" to
        "用户自行选择第三方模型、语音等服务并配置所需密钥。露露机本身不代收模型调用费；第三方服务可能依据其条款独立计费，使用前请自行确认。",
    "数据与第三方服务" to
        "角色资料、对话和记忆主要保存在设备本地。调用用户选择的模型、语音、图片等服务时，为完成请求会向相应服务商发送必要内容；由该服务商按其隐私规则处理。露露机不保证这些请求只在手机上处理。",
    "备份与个人信息" to
        "用户可自行导出包含聊天、设置和密钥等信息的备份；可选 WebDAV 云备份默认关闭，由用户自行设置。Android 系统备份也可能保存应用数据。请妥善保管备份文件，并在第三方服务与设备设置中管理相应数据。",
    "设备权限" to
        "麦克风、相机、定位、通知、无障碍及手机操控等能力取决于实际授权。可在系统设置或露露机相关设置中撤销授权；开启监督功能也不代表放弃自行退出的权利。",
    "使用边界" to
        "本应用的虚拟伴侣功能面向成年人。请确保自己有权使用导入的角色、美术及其他素材，不利用应用侵犯他人权益或从事违法活动。停止使用可退出应用；卸载或清除应用数据会影响本地内容，已导出的备份需另外删除。",
)

@Composable
internal fun LuluFirstRunAgreementScreen(
    onProceed: () -> Boolean,
    onExit: () -> Unit,
) {
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    BackHandler { onExit() }

    AlertDialog(
        onDismissRequest = onExit,
        title = { Text("欢迎来到露露机", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "使用前请阅读用户协议与数据说明。只在首次确认或协议重要更新后显示。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(
                    modifier = Modifier.heightIn(max = 370.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    luluAgreementSections.forEach { (heading, body) ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(heading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(body, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { confirmed = !confirmed },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text("我确认已满 18 周岁，并阅读同意以上协议", fontSize = 13.sp)
                }
                if (saveFailed) {
                    Text("保存同意记录失败，请检查设备存储后重试。", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = confirmed,
                onClick = { if (!onProceed()) saveFailed = true },
            ) { Text("同意并进入") }
        },
        dismissButton = {
            TextButton(onClick = onExit, modifier = Modifier.padding(end = 4.dp)) {
                Text("不同意并退出")
            }
        },
    )
}
