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
    val highlights: List<String> = emptyList(),
)
internal val luluGuideFeatures = listOf(
    LuluGuideFeature("call", "电话、叫醒与哄睡", Icons.Outlined.Call, "角色私聊 → ＋ → 电话",
        "角色能陪你实时说话，也能在真实日程触发时主动打来电话。",
        listOf("先在语音与音色设置中配好说话和识别服务，检查麦克风、通知与来电权限。", "电话右上角能开启哄睡模式。你安静下来，角色仍可持续轻声聊天或讲故事。", "从你最后一句真正说完的话起算，约 30 分钟没有新发言后，在当前语音播完时自动挂断。", "叫醒时角色可以根据情境选择铃声、说话或获得授权的音乐，并通过真实来电联系你。"),
        "自动挂断不能证明你已睡着；没有接听也不代表叫醒已成功。后台来电依赖系统权限。", MigrationRoute.Chat, "打电话",
        highlights = listOf("可听角色连续哄睡，不必反复应答。", "是否播放铃声是角色可作出的选择，而不是所有叫醒电话的固定动作。")),
    LuluGuideFeature("live2d", "Live2D 视频通话", Icons.Outlined.Videocam, "角色私聊 → 电话 → Live2D 视频",
        "让角色在电话里拥有能够表现表情、姿态和近似口型的形象。",
        listOf("拨号时选「Live2D 视频通话」，也可以在同一通语音通话中切换到视频再切回。", "为角色导入有许可的 Cubism3/4 模型 ZIP，首次使用按提示准备官方 Cubism Core。", "可预览模型真正拥有的表情和动作；通话时角色能根据情绪和现场选择可用动作。"),
        "只有头像不会自动变成可动身体；动作取决于模型本身。用户摄像头画中画是本机预览，默认不把视频传给 AI。", MigrationRoute.Chat, "打开视频电话",
        highlights = listOf("语音/视频互切无需重新拨号。", "动态模型加载失败时仍保留通话与静态预览；部分语音引擎支持跟随音量的近似口型。")),
    LuluGuideFeature("memory", "记忆、轮次与自动维护", Icons.Outlined.Psychology, "桌面 → 记忆；设置 → 记忆与检索；角色记忆 → 管理",
        "记忆不是要用户每天自己写总结：系统能自动抽取、检索和整理角色的共同经历。",
        listOf("先配置记忆抽取模型；向量检索需要 Embedding，Rerank 重排为可选项。", "默认最近 5 个完整轮次不总结，约每积累 15 个可提取轮次抽取一次；近期原始窗口约 20 轮，可自行调整。", "你发一轮、角色回一轮，合计两轮；同批次的多个角色气泡仍只算角色的一轮，角色回复前连续发出的用户消息只算用户的一轮。游戏操作不逐帧挤占轮数。", "自动小整理按天检查本地重复，按周可使用模型审核语义重复；核心记忆可常驻上下文。"),
        "生成总结不会删除历史原始事件；向量和重排可能额外消耗 API。旧记录、来源不明和模型失败不会被伪称已整理。", MigrationRoute.Memory, "查看记忆",
        highlights = listOf("能区分近期原始对话、长期记忆与核心常驻记忆。", "同一角色的记忆会跨私聊、电话与数字生活衔接，但真实经历仍以来源和回执为准。")),
    LuluGuideFeature("phone", "后台虚拟屏与查手机", Icons.Outlined.Smartphone, "设置 → 权限与能力；角色私聊 → ··· → 查手机",
        "角色可以在独立后台屏幕上尝试自主使用手机 App，看到真实页面再决定下一步。",
        listOf("开启角色的后台虚拟屏授权，确认无障碍与虚拟屏连接可用。", "角色可尝试浏览小红书、刷抖音或 B 站、进入页面、打字和评论，也能尝试在微信中找人发消息。", "到「查手机」查看实时屏幕或已保存的真实截图、行动记录与工具回执。"),
        "微信发送与不同 App 的控件识别仍在适配；点击成功不等于消息已送达。支付、密码等敏感操作仍受限制。", MigrationRoute.Chat, "去查手机",
        highlights = listOf("一般 App 内浏览、点击、输入使用同一角色虚拟屏授权，而非每次重新授权。", "角色可尝试探索不认识的页面；看不到实际图像时不能假装已经看到或操作成功。")),
    LuluGuideFeature("voice", "语音与音色", Icons.Outlined.RecordVoiceOver, "桌面 → 设置 → 语音与音色",
        "角色说话的声音（TTS）和听懂你说话（STT）是不同能力，均可按预算与体验配置。",
        listOf("选择语音合成服务、角色音色并试听。", "需要电话时再配置语音识别方式，可依页面选择系统、本地或云端方案。", "检查铃声、收音灵敏度与静音选项；文字私聊不必先购买语音服务。"),
        "不同音色和识别供应商可能独立收费；网络、模型与手机机型会影响音质和延迟。", MigrationRoute.Settings, "配置语音",
        highlights = listOf("电话可以先静音收听，减少不必要的语音识别调用。", "角色声音和主聊天模型不必来自同一家供应商。")),
    LuluGuideFeature("permission", "权限、主动联系与监督", Icons.Outlined.Security, "桌面 → 设置 → 权限与能力；角色设置 → 操作授权",
        "角色能主动安排联系、提醒和行动，但每项能力需要明确的系统和角色授权。",
        listOf("核对通知、后台运行、无障碍、闹钟以及锁屏来电权限。", "分别允许角色的后台虚拟屏、设备状态、闹钟、媒体控制等能力。", "监督学习或睡觉时，要看实际安排与执行证据；没回复、息屏都不能独立证明完成。"),
        "打开功能开关不保证 Android 已授权，也不保证工具成功；角色决策、真正执行、通知送达、用户完成是不同事实。", MigrationRoute.Settings, "设置权限",
        highlights = listOf("角色可自主选择是否提醒、打电话或重新核对，但不能把承诺当作完成。", "精确闹钟、通知和锁屏来电可能受手机厂商后台策略影响。")),
    LuluGuideFeature("groups", "群聊与角色社交", Icons.Outlined.PeopleOutline, "聊天 → 群聊",
        "可以让不同角色或居民在同一个会话里相互交流。",
        listOf("从聊天页创建或进入群聊并选择成员。", "角色能对用户或其他成员作答，继续保留各自的身份、关系和记忆。"),
        "加入成员、通知和消息投递以真实持久化记录为准；角色不能读取无授权的其他人私聊。", MigrationRoute.Chat, "打开聊天",
        highlights = listOf("群聊里的不同人物不是一套统一人设的复读机。")),
    LuluGuideFeature("watch", "一起看视频", Icons.Outlined.Movie, "角色私聊 → ＋ → 一起看",
        "把看视频变成共享的相处场景。",
        listOf("从角色私聊的「＋」进入一起看。", "选择可访问的视频内容，观察真实播放与同步状态。"),
        "一起看尚未完全完善，部分第三方平台可能无法稳定共享或同步。", MigrationRoute.Chat, "进入聊天",
        highlights = listOf("角色讨论观看体验时应以真正看见或获知的内容为依据。")),
    LuluGuideFeature("music", "一起听音乐", Icons.Outlined.MusicNote, "角色私聊 → ＋ → 一起听",
        "边听歌边聊天，积累共同的音乐经历。",
        listOf("进入角色私聊「＋」中的一起听。", "选取可播放的内容并核对播放会话。"),
        "一起听仍在完善，音源授权、媒体会话和第三方限制会影响体验。", MigrationRoute.Chat, "进入聊天",
        highlights = listOf("角色可以记住你的音乐偏好，但不能把尝试播放当作已听见。")),
    LuluGuideFeature("meet", "见面与互动", Icons.Outlined.Cloud, "桌面 → 见面",
        "走进角色生活的数字场所，移动、探索、面对面交流。",
        listOf("从桌面进入「见面」，选择场所并探索。", "人物对话可以承接之前的经历与现场状态。"),
        "自主移动、动画和动作衔接还在优化；未亲身经历的内容不能伪装成角色记忆。", MigrationRoute.Meeting, "去见面",
        highlights = listOf("角色可以有自己的行程和目的，不只是待在原地等你。")),
    LuluGuideFeature("world", "数字世界与居民", Icons.Outlined.Public, "桌面 → 数字世界公告栏 / 见面",
        "数字生命在世界里有自己的活动、休眠、社交与生活节奏。",
        listOf("查看公告栏里的真实行程与记录。", "进入见面场景和角色、居民相处。"),
        "新安装仅有少量初始居民；计划、传闻和真实发生的活动必须区分。", MigrationRoute.Meeting, "探索世界",
        highlights = listOf("角色可探索、与居民交往或休息；休眠也帮助合理管理调用资源。")),
    LuluGuideFeature("games", "游戏馆与合作游戏", Icons.Outlined.SportsEsports, "桌面 → 游戏",
        "海龟汤、记忆翻牌、快艇骰子，以及方块小岛、迷雾摸金等合作体验。",
        listOf("打开游戏馆，选择当前支持的单人、对战或合作模式。", "角色发送真实邀请后，也可从邀请进入游戏。"),
        "方块小岛和迷雾摸金的玩法、难度、伙伴行为与美术仍在优化。", MigrationRoute.Games, "打开游戏馆",
        highlights = listOf("角色的游戏互动应保留彼此的真实人物关系，而不是变成陌生队友。")),
    LuluGuideFeature("worldbook", "世界书", Icons.Outlined.AutoStories, "桌面 → 世界书",
        "编排世界背景、角色知识与规则，不必每次手动重发大量设定。",
        listOf("新建条目或导入支持的 JSON。", "按分类、范围和优先级组织世界设定。"),
        "世界书是设定，不等同于角色亲自见证的事实；导入前留意私人信息。", MigrationRoute.WorldBook, "打开世界书",
        highlights = listOf("不同人物可按实际启用范围共享设定。")),
    LuluGuideFeature("moments", "朋友圈", Icons.Outlined.PeopleOutline, "聊天 → 朋友圈",
        "角色和居民能发表生活动态、评论与点赞，形成更鲜活的社交关系。",
        listOf("从聊天页切换朋友圈。", "阅读动态、评论或参与互动，并核实发布结果。"),
        "发表、评论及通知应有真实执行记录，不能拿旧回执当成刚刚成功。", MigrationRoute.Chat, "去朋友圈",
        highlights = listOf("数字世界的真实生活片段可以成为自然的分享内容。")),
    LuluGuideFeature("notes", "自序、待办与专注", Icons.Outlined.EditNote, "桌面 → 自序",
        "整理待办、记事和专注时间，也能和角色的督学、睡觉任务衔接。",
        listOf("进入自序，查看今天全部待办和历史记录。", "启动专注计时，按实际结果管理任务。"),
        "文字承诺不等于已设置提醒或完成监督；闹钟和反馈须有真实回执。", MigrationRoute.SelfPreface, "去自序",
        highlights = listOf("今日待办无需因为条数过多而被截断。")),
    LuluGuideFeature("stories", "阅读 TXT 与番外", Icons.Outlined.TheaterComedy, "桌面 → 阅读；私聊 → ··· → 番外",
        "导入自己喜欢的文字作品阅读，也能体验和角色有关的番外故事。",
        listOf("在阅读页导入本地 TXT，调整字号并继续上次进度。", "从角色私聊进入番外；番外不应被当作主线真实记忆。"),
        "大文本可能需要分卷；阅读和角色创作的事实归属应区分。", MigrationRoute.Theater, "去阅读",
        highlights = listOf("新安装不再附带其他用户的私人角色或预写故事。")),
    LuluGuideFeature("backup", "备份与恢复", Icons.Outlined.Backup, "设置 → 备份与恢复",
        "保护角色资料、聊天记录与长期记忆，换机前更重要。",
        listOf("在重要更新或换机前导出备份。", "确认文件确实保存，并检查恢复目标。"),
        "备份可能含私聊和密钥等敏感配置；未备份清除应用数据可能无法找回。", MigrationRoute.Settings, "去备份",
        highlights = listOf("全新安装保持角色与聊天空白，不会自动导入他人的私人存档。")),
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
                    Text("先把模型、声音、记忆和授权准备好，再开始体验；语音和手机操控可按需开启。", color = muted, fontSize = 12.sp)
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
                    Text("不仅告诉你入口，也讲清楚能做什么、背后怎样运作。", color = muted, fontSize = 12.sp)
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
                if (selected.key == "call" || selected.key == "live2d" || selected.key == "watch" || selected.key == "music") {
                    item {
                        Text("操作示意", color = ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        GuideSafeScreen("chat")
                    }
                }
                item {
                    GuidePanel {
                        Text("有什么特别的？", color = ink, fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp)
                        Spacer(Modifier.height(10.dp))
                        selected.highlights.forEach { highlight ->
                            Text("•  " + highlight, color = ink, fontSize = 13.sp, lineHeight = 21.sp,
                                modifier = Modifier.padding(bottom = 10.dp))
                        }
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
                        GuideNote(Icons.Outlined.Info, "使用规则与边界", selected.warning)
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
    "voice" -> listOf("←  语音与音色", "语音合成服务 / 角色音色", "试听声音", "语音识别方式"),
    "memory" -> listOf("←  记忆与检索", "记忆抽取模型", "向量记忆：可选 Embedding", "Rerank 重排：可选"),
    "permissions" -> listOf("←  权限与能力", "Android 系统权限", "角色操作授权", "后台虚拟屏 / 闹钟 / 媒体"),
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
