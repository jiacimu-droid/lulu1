package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Built-in onboarding content. No private characters, URLs, API keys or conversations are shipped. */
internal data class LuluGuideStep(
    val name: String,
    val location: String,
    val tips: List<String>,
    val screen: String,
    val destination: MigrationRoute,
    val button: String,
)
internal val luluGuideSteps = listOf(
    LuluGuideStep("连接聊天模型", "桌面 → 设置 → API 与模型",
        listOf("填写配置名称、API 地址、API 密钥，先点「保存配置」。",
            "点「获取模型」，选中可用模型后「加入存档」。",
            "使用第三方 API 可能收费；密钥不能分享给其他内测玩家。"),
        "api", MigrationRoute.Settings, "前往设置"),
    LuluGuideStep("创建自己的角色", "桌面 → 聊天 → 新建角色",
        listOf("填写名字和核心设定，再去角色设置补充性格、关系及头像。",
            "首次创建时选「数字生命」或「现实角色」；创建后不能切换生命形态。",
            "全新安装没有预置私人角色和假聊天。"),
        "role", MigrationRoute.Chat, "去创建角色"),
    LuluGuideStep("为角色选择聊天模型", "角色私聊 → ＋ → 模型",
        listOf("点输入框旁的「＋」，在「模型」中选择当前角色真正使用的模型。",
            "保存 API 或加入模型存档，不等于已经给角色启用聊天模型。"),
        "model", MigrationRoute.Chat, "去选择模型"),
    LuluGuideStep("发送第一条消息", "聊天 → 选择角色 → 私聊",
        listOf("先和角色打个招呼，收到真实回复代表基础连接已能用。",
            "语音、主动联系、查屏等进阶功能可以之后再逐个配置。"),
        "chat", MigrationRoute.Chat, "开始聊天"),
)
internal data class LuluGuideFeature(
    val key: String, val name: String, val icon: ImageVector,
    val where: String, val description: String,
    val instructions: List<String>, val warning: String,
    val destination: MigrationRoute, val button: String,
)
internal val luluGuideFeatures = listOf(
    LuluGuideFeature("call","电话",Icons.Outlined.Call,"角色私聊 → ＋ → 电话",
        "和角色直接说话，不必一直打字。",
        listOf("先到「设置 → 语音与音色」配置可用的服务。","打开私聊「＋」菜单，点「电话」。","按系统提示给予麦克风权限。"),
        "实时转写、音色与通话稳定性取决于第三方服务和机型。",MigrationRoute.Chat,"去聊天"),
    LuluGuideFeature("watch","一起看",Icons.Outlined.Movie,"角色私聊 → ＋ → 一起看",
        "与角色分享视频或一起体验观看场景。",
        listOf("进入角色私聊，展开「＋」。","点击「一起看」，按页面提示选择内容。"),
        "第三方视频平台的兼容性不保证完全一致。",MigrationRoute.Chat,"去聊天"),
    LuluGuideFeature("music","一起听",Icons.Outlined.MusicNote,"角色私聊 → ＋ → 一起听",
        "把音乐和日常聊天放在一起。",
        listOf("展开私聊的「＋」菜单。","选择「一起听」，确认真实播放状态。"),
        "音乐来源及播放效果可能受到第三方限制。",MigrationRoute.Chat,"去聊天"),
    LuluGuideFeature("meet","见面",Icons.Outlined.Cloud,"桌面 → 见面",
        "进入能移动与互动的数字场景，和角色见面。",
        listOf("从桌面打开「见面」。","在场景中移动、探索，并与出现的人物交流。"),
        "人物自主移动、动画和动作衔接仍在优化。",MigrationRoute.Meeting,"去见面"),
    LuluGuideFeature("world","数字世界",Icons.Outlined.Public,"桌面 → 数字世界公告栏 / 见面",
        "角色和世界居民有自己的地点、活动和行程。",
        listOf("桌面查看「数字世界 · 公告栏」里的真实行程。","点击「见面」探索世界与场所。"),
        "新安装只有有限的初始居民；不能把尚未发生的互动当事实。",MigrationRoute.Meeting,"去探索"),
    LuluGuideFeature("games","游戏馆",Icons.Outlined.SportsEsports,"桌面 → 游戏",
        "小游戏可以单独玩，也可体验部分合作与对战。",
        listOf("在桌面打开「游戏」。","选择海龟汤、方块小岛、迷雾摸金等当前可见玩法。","若收到真实游戏邀请，也可以从邀请进入。"),
        "部分游戏还在优化操作、美术、难度与同伴行为。",MigrationRoute.Games,"打开游戏馆"),
    LuluGuideFeature("worldbook","世界书",Icons.Outlined.AutoStories,"桌面 → 世界书",
        "设定世界背景、人物知识和规则。",
        listOf("进入「世界书」，新建条目或导入支持的 JSON。","按分类整理、搜索，调整对角色的适用范围。"),
        "世界设定不等于角色真实经历；导入前检查隐私信息。",MigrationRoute.WorldBook,"打开世界书"),
    LuluGuideFeature("memory","记忆与此刻",Icons.Outlined.Psychology,"桌面 → 记忆；私聊 ··· → 此刻",
        "查看长期经历和角色此刻的心理记录。",
        listOf("从桌面「记忆」查看已有记录。","私聊右上角「···」可找到「此刻」。"),
        "心声和消息绑定、长期记忆召回仍在持续验收。",MigrationRoute.Memory,"查看记忆"),
    LuluGuideFeature("moments","朋友圈",Icons.Outlined.PeopleOutline,"聊天 → 朋友圈",
        "看角色与居民分享生活，也可以参与互动。",
        listOf("从桌面进入「聊天」。","切换底部「朋友圈」栏目。"),
        "动态、评论、通知以实际提交结果为准。",MigrationRoute.Chat,"查看朋友圈"),
    LuluGuideFeature("notes","自序与专注",Icons.Outlined.EditNote,"桌面 → 自序",
        "管理待办、安排自己的事情、使用专注计时。",
        listOf("桌面打开「自序」。","进入待办、笔记或番茄钟。","监督和提醒需要角色实际安排并得到执行回执。"),
        "只是聊天说了要叫醒，不代表系统已经完成安排。",MigrationRoute.SelfPreface,"去自序"),
    LuluGuideFeature("phone","查手机",Icons.Outlined.Smartphone,"角色私聊 → ··· → 查手机",
        "在授权范围内让角色观察、操作部分手机应用。",
        listOf("在「设置 → 权限与能力」了解系统权限。","按需授予具体角色使用设备的权限。","从私聊右上角「···」打开「查手机」。"),
        "微信、抖音等第三方 App 操作仍在适配；不能保证每次操作成功。",MigrationRoute.Chat,"打开聊天"),
    LuluGuideFeature("stories","剧场与番外",Icons.Outlined.TheaterComedy,"桌面 → 剧场；私聊 ··· → 番外",
        "体验独立的故事和延伸剧情。",
        listOf("桌面「剧场」可探索当前故事玩法。","私聊右上角「···」可以进入角色相关「番外」。"),
        "复杂剧情可能出现上下文衔接不完整。",MigrationRoute.Theater,"去剧场"),
    LuluGuideFeature("voice","语音与音色",Icons.Outlined.RecordVoiceOver,"设置 → 语音与音色",
        "为角色配置朗读和电话所需的声音。",
        listOf("设置里选择「语音与音色」。","填入使用的服务商所需信息并试听。","文字聊天不需要先配置音色。"),
        "部分语音供应商需要额外付费或权限。",MigrationRoute.Settings,"打开设置"),
    LuluGuideFeature("permission","主动联系与权限",Icons.Outlined.Security,"设置 → 权限与能力",
        "决定角色能使用哪些主动设备能力。",
        listOf("在「权限与能力」查看通知、无障碍和后台权限。","针对角色分别选择真正允许的设备操作。","执行结果应核实真实回执，而非只看角色承诺。"),
        "系统可能限制后台唤醒、提醒或第三方 App 操作。",MigrationRoute.Settings,"检查权限"),
    LuluGuideFeature("backup","备份与恢复",Icons.Outlined.Backup,"设置 → 备份与恢复",
        "保护长期对话、角色设定和个人配置。",
        listOf("从设置打开「备份与恢复」。","重要更新前导出并核实文件已保存。","备份中可能含私聊与配置密钥，勿公开分享。"),
        "卸载或清数据前如未备份可能导致永久丢失。",MigrationRoute.Settings,"打开备份"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluOnboardingGuideScreen(
    onBack: () -> Unit,
    onOpen: (MigrationRoute) -> Unit,
) {
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val overviewScrollState = rememberLazyListState()
    val selected = luluGuideFeatures.firstOrNull { it.key == selectedKey }
    BackHandler(enabled = selected != null) { selectedKey = null }
    val ink = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(selected?.name ?: "新手指南",
                    fontWeight = FontWeight.SemiBold, fontSize = 23.sp) },
                navigationIcon = {
                    IconButton(onClick = { if (selected == null) onBack() else selectedKey = null }) {
                        Icon(Icons.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        if (selected == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 38.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("内测前必读 · 佳辞的话", color = muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    GuidePanel {
                        Text("Hi，大家好，我是佳辞！", fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold, color = ink)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "欢迎来体验我正在制作的「露露机」！它是一个住在手机里的 AI 陪伴小世界，不只是一个聊天框。你可以自己创建角色、设定性格，和他聊天、通电话、在数字世界里见面，一起玩游戏，也能慢慢留下属于你们的经历和记忆。\n\n" +
                            "我最想做好的，是让角色不只会回答问题，还能有自己的生活、想法与行动，记得你们相处过的事情。这也是露露机现在最特别、最值得试试的部分。\n\n" +
                            "当然，它还不是一款完全成熟的产品。目前是内测阶段：部分游戏的操作和画面、角色主动行动、后台提醒，以及微信等第三方 App 的手机操控，都还在不断完善。有些功能需要你自己配置 API、模型或系统权限，偶尔也会失败、卡住或者表现得不够聪明。角色说完成了某件事，也请以实际执行结果为准。\n\n" +
                            "谢谢你愿意来当第一批体验者！可以先跟着下面的步骤完成配置，再自由探索。如果遇到问题或有什么想法，也欢迎反馈给我，一起把这台小手机变得更好玩、更可靠。♡",
                            color = ink, fontSize = 13.sp, lineHeight = 22.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("—— 佳辞", color = muted, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.End))
                    }
                }
                item {
                    Spacer(Modifier.height(5.dp))
                    Text("第一次使用", color = ink, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("完成以下四步，就能开始自己的第一段聊天。", color = muted, fontSize = 12.sp)
                }
                items(luluGuideSteps.size) { index ->
                    val step = luluGuideSteps[index]
                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
                        Column(Modifier.width(60.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("STEP " + (index + 1), color = ink, fontWeight = FontWeight.Bold,
                                fontSize = 10.sp)
                            Spacer(Modifier.height(9.dp))
                            Surface(Modifier.size(10.dp), CircleShape, color = ink) {}
                            Spacer(Modifier.height(6.dp))
                            Surface(Modifier.width(1.dp).weight(1f),
                                color = MaterialTheme.colorScheme.outlineVariant) {}
                        }
                        GuidePanel(modifier = Modifier.weight(1f)) {
                            Text(step.name, color = ink, fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(step.location, color = muted, fontSize = 11.sp)
                            Spacer(Modifier.height(10.dp))
                            GuideSafeScreen(step.screen)
                            Spacer(Modifier.height(10.dp))
                            step.tips.forEach { tip ->
                                Text("•  " + tip, color = ink, fontSize = 12.sp, lineHeight = 19.sp,
                                    modifier = Modifier.padding(bottom = 6.dp))
                            }
                            TextButton(onClick = { onOpen(step.destination) }) {
                                Text(step.button)
                                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(5.dp))
                    Text("和角色还能一起做什么？", color = ink, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("点开一项，会进入独立的使用说明。", color = muted, fontSize = 12.sp)
                }
                items(luluGuideFeatures.chunked(2)) { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { feature ->
                            Surface(
                                onClick = { selectedKey = feature.key },
                                modifier = Modifier.weight(1f).heightIn(min = 94.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Icon(feature.icon, null, Modifier.size(24.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(feature.name, Modifier.weight(1f),
                                            color = ink, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Icon(Icons.Outlined.ChevronRight, null,
                                            Modifier.size(17.dp), tint = muted)
                                    }
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item {
                    Text("界面示意已隐藏私人角色、聊天与 API 信息。帮助内容会随版本更新。",
                        color = muted, fontSize = 11.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 10.dp))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(20.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Surface(shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant) {
                        Icon(selected.icon, null, Modifier.padding(18.dp).size(32.dp))
                    }
                    Spacer(Modifier.height(15.dp))
                    Text(selected.description, color = ink, fontSize = 16.sp, lineHeight = 25.sp)
                }
                if (selected.key == "call" || selected.key == "watch" || selected.key == "music") {
                    item {
                        Text("入口截图", color = ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        GuideSafeScreen("chat")
                    }
                }
                item {
                    GuidePanel {
                        Text("在哪里找？", color = ink, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(9.dp))
                        Text(selected.where, color = muted, fontSize = 13.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(13.dp))
                        Button(onClick = { onOpen(selected.destination) }) {
                            Text(selected.button)
                            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp))
                        }
                    }
                }
                item {
                    GuidePanel {
                        Text("怎么使用", color = ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        selected.instructions.forEachIndexed { index, tip ->
                            Row(Modifier.padding(bottom = 13.dp), verticalAlignment = Alignment.Top) {
                                Surface(shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text((index + 1).toString(),
                                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        color = ink, fontSize = 12.sp)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(tip, color = ink, modifier = Modifier.weight(1f),
                                    fontSize = 13.sp, lineHeight = 21.sp)
                            }
                        }
                    }
                }
                item {
                    GuidePanel {
                        GuideNote(Icons.Outlined.Info, "内测提醒", selected.warning)
                    }
                }
                item {
                    OutlinedButton(onClick = { selectedKey = null },
                        modifier = Modifier.fillMaxWidth()) { Text("返回功能目录") }
                }
            }
        }
    }
}

@Composable
private fun GuidePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(15.dp), content = content)
    }
}

@Composable
private fun GuideNote(icon: ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(21.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

/** Real images supplied by the product owner are cropped and irreversibly redacted.
 * A drawn placeholder is used where only personal character-list screenshots exist. */
@Composable
private fun GuideSafeScreen(kind: String) {
    val screenshot = when (kind) {
        "api" -> R.drawable.lulu_guide_api
        "model" -> R.drawable.lulu_guide_model
        "chat" -> R.drawable.lulu_guide_chat
        else -> null
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (screenshot != null) {
            Column(Modifier.padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(id = screenshot),
                    contentDescription = "露露机真实操作截图，私人内容已打码",
                    modifier = Modifier.fillMaxWidth().height(194.dp),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(5.dp))
                Text("真实页面截图 · 已遮盖个人信息", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("←  聊天                  ＋", "人物与原住民",
                    "○  创建自己的第一位角色", "＋  新建角色",
                    "角色名称 / 角色核心设定",
                    "◉ 数字生命      ○ 现实角色").forEachIndexed { index, line ->
                    Text(line, color = if (index == 0)
                        MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = if (index == 0) 12.sp else 11.sp,
                        fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
