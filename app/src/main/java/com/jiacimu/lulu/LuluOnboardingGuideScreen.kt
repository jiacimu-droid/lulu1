package com.jiacimu.lulu

import androidx.activity.compose.BackHandler
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
    LuluGuideStep("连接聊天 API", "桌面 → 设置 → API 与模型",
        listOf("新建 API 配置，填写名称、地址和密钥并保存；从上游获取模型列表，选择模型加入存档。", "API 配好只是接通服务，下一步还要把模型指定给具体角色。"),
        "api", MigrationRoute.Settings, "配置 API"),
    LuluGuideStep("创建自己的数字生命", "聊天 → 角色 → ＋ 新建角色",
        listOf("新安装没有预置的私人伴侣，也不会自动发送假聊天记录。", "打开完整的角色设置页，在「资料」「人格」「陪伴」填写姓名、背景、个性和相处方式。", "最后点击「保存并创建角色」；仅退出页面不会创建。"),
        "role", MigrationRoute.CharacterCreate, "创建角色"),
    LuluGuideStep("选定角色的聊天模型", "角色私聊 → ＋ → 模型",
        listOf("在当前角色的私聊「＋」菜单里明确选定聊天模型；仅保存 API 配置不等于已启用角色模型。", "每位角色可以有自己的模型选择；先确认文本回复正常，其他能力稍后逐项测试。"),
        "model", MigrationRoute.Chat, "选择模型"),
    LuluGuideStep("让角色拥有自己的声音", "桌面 → 设置 → 语音与音色",
        listOf("选择语音合成服务与音色，按页面试听，并区分「角色说话的 TTS」与「听懂你说话的语音识别」。", "电话还需要可用的麦克风/识别设置；文字私聊不依赖语音服务。", "声音暂时不想配置也能继续文字聊天，但电话与语音体验可能不可用。"),
        "voice", MigrationRoute.Settings, "设置语音"),
    LuluGuideStep("配置记忆模型与检索", "桌面 → 设置 → 记忆与检索",
        listOf("先选择记忆抽取模型。系统可按完整轮次自动提取重要经历、长期事实与情绪，而不是要求用户每次手动写总结。", "向量记忆需单独配置 Embedding；Rerank 重排是可选增强，可能增加 API 用量。关闭后仍能保留本地记忆和关联召回。", "默认最近 5 轮暂不总结、每 15 轮提取一次；具体轮次和自动维护规则在下方「记忆与轮次」详解。"),
        "memory", MigrationRoute.Settings, "配置记忆"),
    LuluGuideStep("给角色合理的权限与能力", "桌面 → 设置 → 权限与能力；角色设置 → 操作授权",
        listOf("先按需要开启通知、无障碍、后台运行和闹钟等 Android 系统权限，再针对每位角色勾选允许的操作。", "后台虚拟屏幕授权涵盖角色在独立屏内的一般 App 浏览、点击和输入；设备状态、闹钟、媒体等原生能力另外授权。", "开关已打开不代表系统权限、实际屏幕连接及工具结果一定成功；查看真实执行回执。"),
        "permissions", MigrationRoute.Settings, "检查权限"),
    LuluGuideStep("开始真实聊天与互动", "聊天 → 选择自己创建的角色 → 私聊",
        listOf("现在再发送第一条消息，检查回复是否符合已保存的人设，以及当次模型是否真的可用。", "之后可以在私聊「＋」中打电话、选择 Live2D 视频，也可以去游戏馆、数字世界和朋友圈互动。", "声音、记忆和工具可以逐步完善；文字能回复只证明基础聊天链路成功，不代表电话或手机操控已验证。"),
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
    LuluGuideFeature("stories","阅读与番外",Icons.Outlined.TheaterComedy,"桌面 → 阅读；私聊 ··· → 番外",
        "导入手机里的 TXT，安静读书，也能与角色分享故事。",
        listOf("桌面打开「阅读」，点击「导入 TXT」选择本地文本。","阅读支持调整字号和记住上次阅读位置。","原有故事创作存档仍可从阅读书架进入，角色相关番外在私聊右上角「···」。"),
        "超长文本需要分卷导入；角色阅读与用户阅读分别记录进度。",MigrationRoute.Theater,"去阅读"),
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
                    GuidePanel {
                        Text("欢迎来到露露机！", fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold, color = ink)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "露露机是一款以数字生命为核心的 AI 陪伴小手机。\n\n" +
                            "在这里，角色不只是一个聊天机器人。",
                            color = ink, fontSize = 13.sp, lineHeight = 22.sp,
                        )
                        Spacer(Modifier.height(14.dp))
                        listOf(
                            "全场景记忆互通：从私聊到群聊，从朋友圈到数字世界，角色的记忆与经历能够自然衔接。",
                            "自主生活与社交：数字生命拥有感知与休眠机制，可以自主探索世界、结交朋友、安排生活。",
                            "长期记忆：支持向量记忆、记忆召回、自动总结与维护，让角色逐渐记住与你相处的点滴。",
                            "手机感知与操作：授权后可以通过后台虚拟屏查看和操作手机。",
                            "真实的内心世界：角色有自己的心声、想法与情绪，你可以了解 TA 正在想什么。",
                            "共同探索与游戏：与角色在数字世界见面、互动、合作游戏，创造属于你们的共同经历。",
                        ).forEach { feature ->
                            Text("•  $feature", color = ink, fontSize = 13.sp,
                                lineHeight = 22.sp, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        Spacer(Modifier.height(7.dp))
                        Text("目前尚未完善的功能", fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, color = ink)
                        Spacer(Modifier.height(8.dp))
                        listOf(
                            "一起听音乐、一起看视频。",
                            "《方块小岛》《迷雾摸金》的玩法与游戏体验。",
                            "后台虚拟屏中的微信消息发送功能。",
                        ).forEach { feature ->
                            Text("•  $feature", color = ink, fontSize = 13.sp,
                                lineHeight = 22.sp, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("以上功能仍在持续开发与优化中。",
                            color = muted, fontSize = 12.sp, lineHeight = 20.sp)
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
                    Text("操作位置示意不包含私人角色、聊天与 API 信息。帮助内容会随版本更新。",
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

/** Built-in, privacy-safe explanation of the real UI controls.
 * Never decode binary files in the LazyColumn: broken JPEGs previously crashed
 * the whole app as soon as the user scrolled to their first item. */
internal fun guideScreenRows(kind: String): List<String> = when (kind) {
    "api" -> listOf("←  API 设置", "新配置         ＋ 新建",
        "配置名称", "API 地址", "API 密钥  ••••••••",
        "▣  保存配置", "☁  获取模型", "已配置模型")
    "role" -> listOf("←  新建数字生命       保存角色",
        "资料    人格    陪伴", "角色头像", "角色名称", "身份与背景事实",
        "保存并创建角色")
    "model" -> listOf("当前角色聊天模型", "○  模型 A", "◉  模型 B",
        "提示：这里只修改当前角色")
    "chat" -> listOf("←  角色昵称               ···",
        "你好，很高兴认识你。", "               我也很高兴！",
        "🎙   发消息…           ☺    ＋",
        "图片     表情     模型     电话")
    else -> emptyList()
}

@Composable
private fun GuideSafeScreen(kind: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            guideScreenRows(kind).forEachIndexed { index, line ->
                Text(
                    line,
                    fontSize = if (index == 0) 12.sp else 11.sp,
                    fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index == 0) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("操作位置示意 · 不包含私人数据",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        }
    }
}
