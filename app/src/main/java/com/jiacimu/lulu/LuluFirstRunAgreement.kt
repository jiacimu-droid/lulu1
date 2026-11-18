package com.jiacimu.lulu

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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


// Full-screen consent is available even before the user's appearance store loads.
// Its independent neutral palette respects system dark mode and Lulu's desktop style.
@Composable
internal fun LuluFirstRunAgreementScreen(
    onProceed: () -> Boolean,
    onExit: () -> Unit,
) {
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var saveFailed by rememberSaveable { mutableStateOf(false) }
    BackHandler { onExit() }

    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF121315) else Color.White
    val ink = if (dark) Color(0xFFF4F4F5) else Color(0xFF1D1D1F)
    val muted = if (dark) Color(0xFFAAAAB0) else Color(0xFF73747A)
    val panel = if (dark) Color(0xFF202124) else Color(0xFFF7F7F8)
    val hairline = if (dark) Color(0xFF393A3F) else Color(0xFFE8E8EA)
    val action = if (dark) Color(0xFFF4F4F5) else Color(0xFF29292D)
    val actionText = if (dark) Color(0xFF18191B) else Color.White

    Surface(color = bg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = panel, shape = CircleShape) {
                    Icon(Icons.Outlined.AutoAwesome, null,
                        modifier = Modifier.padding(10.dp).size(20.dp), tint = ink)
                }
                Spacer(Modifier.width(10.dp))
                Text("LULU / DIGITAL LIFE", color = muted,
                    fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(24.dp))
            Text("欢迎来到露露机", color = ink, fontSize = 30.sp,
                lineHeight = 39.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text("阅读并确认使用约定，开启你的数字生命空间。",
                color = muted, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(ink, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text("用户协议与数据说明", color = ink,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text("首次使用", color = muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(11.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(22.dp), color = panel,
                border = BorderStroke(1.dp, hairline),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 17.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    luluAgreementSections.forEachIndexed { index, (heading, body) ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text((index + 1).toString().padStart(2, '0'),
                                color = muted, fontSize = 11.sp)
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(heading, color = ink, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold)
                                Text(body, color = muted, fontSize = 13.sp, lineHeight = 20.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(11.dp))
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clickable { confirmed = !confirmed },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = confirmed, onCheckedChange = { confirmed = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = ink, uncheckedColor = muted, checkmarkColor = bg),
                )
                Spacer(Modifier.width(6.dp))
                Text("我已年满 18 周岁，并阅读同意以上协议",
                    color = ink, fontSize = 13.sp, lineHeight = 19.sp)
            }
            if (saveFailed) {
                Text("保存同意记录失败，请检查设备存储后重试。",
                    color = Color(0xFFC84242), fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 7.dp))
            }
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                enabled = confirmed,
                onClick = { if (!onProceed()) saveFailed = true },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = action, contentColor = actionText,
                    disabledContainerColor = panel, disabledContentColor = muted,
                ),
            ) {
                Text("同意并进入", fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(9.dp))
                Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp))
            }
            TextButton(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) { Text("不同意，退出应用", color = muted, fontSize = 12.sp) }
        }
    }
}
