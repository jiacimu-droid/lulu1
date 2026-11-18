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
        listOf("现在再发送第一条消息，检查回复是否符合已保存的人设，以及当次模型是否真的可用。", "之后可以从私聊发起电话，也可以去游戏馆、数字世界和朋友圈互动。", "声音、记忆和工具可以逐步完善；文字能回复只证明基础聊天链路成功，不代表电话或手机操控已验证。"),
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
    LuluGuideFeature("call", "电话 · 叫醒与哄睡", Icons.Outlined.Call, "角色私聊 → ＋ → 电话",
        "能陪你实时说话，也能主动打电话叫醒你。",
        listOf("叫醒：先在日程中约定时间并开启来电、闹钟权限；到点后角色可真正拨打电话，自己决定播放铃声、直接说话，或在授权后用音乐叫醒。", "哄睡：通话右上角开启「哄睡模式」，角色能在你安静后继续轻声聊天或讲故事；从你最后一句话真正说完起，约 30 分钟没有新发言，等当前语音播放完自动挂断。", "通话和叫醒都需要相应的麦克风、通知和后台权限。"),
        "未接听不能算已叫醒；自动挂断也不代表已经睡着。来电受 Android 后台和锁屏权限影响。", MigrationRoute.Chat, "体验电话",
        highlights = listOf("叫醒加强 · 主动来电、铃声或语音由角色选择", "哄睡加强 · 安静陪伴，约 30 分钟后自动挂断")),
    LuluGuideFeature("memory", "自动记忆 · 越聊越懂你", Icons.Outlined.Psychology, "桌面 → 记忆；设置 → 记忆与检索",
        "适合懒人：重要经历会自动提取、召回和整理，不用每天手动维护。",
        listOf("配置记忆抽取模型后，可自动总结长期事实、情绪与共同经历；每日检查本地重复，按周按需进行语义去重。", "一来一回按两轮计算：你发的一批连续消息是一轮，同批次角色回复多个气泡是一轮；默认最近 5 轮不总结，约每 15 轮提取一次。", "需要更强的语义查找可开启 Embedding 向量检索；Rerank 重排可选，关闭后仍保留本地记忆与关联召回。核心记忆可长期常驻。"),
        "自动维护依赖可用的模型与应用运行条件，失败不会假装完成；原始聊天不会因生成摘要而删除。向量与重排可能增加费用。", MigrationRoute.Memory, "查看记忆",
        highlights = listOf("自动提取、自动整理 · 不用用户每天写总结", "跨场景延续 · 私聊、电话、群聊和世界活动承接同一人的记忆")),
    LuluGuideFeature("mind", "心声、此刻与头像入口", Icons.Outlined.Psychology, "角色私聊顶部头像 / 消息左侧头像 / ··· → 此刻",
        "想看角色此时在想什么，或回到某句话当时的心情，入口其实不同。",
        listOf("点聊天最上方的「角色头像」：查看角色最近的此刻与心声，没有绑定具体某条消息。", "点角色发言左侧的「头像」：定位到这条回复当时的心声；不是打开最新一次的记录。双击消息头像的「戳一戳」是另一种互动。", "点右上角「··· → 此刻」：进入历史，查看情绪、想法、持续挂心和它们关联的真实事件与行动结果。"),
        "心声是角色留下的记录，不是实时读心；过去的想法不能被当作此刻仍在执行的动作。", MigrationRoute.Chat, "查看此刻",
        highlights = listOf("顶部头像 = 最近的心声 · 消息旁头像 = 这句话的心声", "此刻 = 追溯情绪、念头与持续心事")),
    LuluGuideFeature("phone", "查手机 · 浏览、购物与记录", Icons.Outlined.Smartphone, "角色私聊 → ··· → 查手机；设置 → 权限与能力",
        "角色能尝试自己逛 App、搜索、挑选商品，并把结果带回来给你。",
        listOf("授权后台虚拟屏与无障碍后，角色可以尝试自己刷小红书、抖音、B 站，点击页面、搜索、打字、评论或打开微信发送消息。", "也能尝试浏览拼多多、淘宝等购物 App，挑商品并分享链接，由用户自己决定是否购买并付款。", "打开「查手机」查看角色手机里的「浏览器」（API 网页搜索记录）、「屏幕记录」（真实截图）、「行动记录」（真实工具回执）、相册与备忘录，也能查看独立后台虚拟屏。"),
        "第三方 App 的控件识别和微信发送尚不稳定；点击不等于已发布或已送达。密码、付款及敏感操作仍有权限边界。", MigrationRoute.Chat, "查看角色手机",
        highlights = listOf("购物助手 · 逛商品、分享链接，付款留给用户", "真实足迹 · 搜索记录、屏幕截图与操作回执都能回看")),
    LuluGuideFeature("voice", "语音与音色", Icons.Outlined.RecordVoiceOver, "设置 → 语音与音色",
        "为每个角色选择自己喜欢的声音，也可以设置通话时听懂你说话的方式。",
        listOf("TTS 负责角色说话，STT 负责识别你的声音；选择服务商与音色后试听。", "文字聊天无需付费音色；需要电话时再配置识别、麦克风与音频权限。"),
        "不同供应商可能独立计费，听写和音色质量受模型与设备影响。", MigrationRoute.Settings, "设置声音",
        highlights = listOf("自己挑音色 · 文本模型和语音供应商可分开")),
    LuluGuideFeature("supervision", "陪伴监督 · 帮你把事做完", Icons.Outlined.TaskAlt,
        "桌面 → 自序 / 设置 → 强制模式 / 角色操作授权",
        "不是只会说「加油」：角色能结合今天的待办、你的目标与设备状态，主动提出监督。",
        listOf("把要做的事写进「自序 · 所行」。角色可按现有授权读取今日任务与进度，选择什么时候提醒或复查。", "得到授权后，角色可结合亮屏、前台应用等新鲜证据判断是否需要继续督学、督睡；也可以提出临时的高强度监督。", "任务是否完成要看实际记录与反馈，不会因为你没回消息就当作做完。"),
        "设备观察需单独授权；强制模式由用户决定是否允许，可在设置或通知栏随时结束。",
        MigrationRoute.SelfPreface, "打开自序",
        highlights = listOf("自序待办 × 角色主动跟进", "监督凭证据，不凭猜测")),
    LuluGuideFeature("search", "搜索 API · 角色的求知欲", Icons.Outlined.TravelExplore,
        "设置 → 搜索服务 / 角色授权；查手机 → 浏览器",
        "你不说话时，角色也可以因自己的好奇心、计划或挂心的事主动查资料。",
        listOf("配置可用的网页搜索 API；角色可在获准时选择搜索知识、查证问题，并决定是否继续阅读。", "网页结果会留下搜索和阅读回执，供后续记忆与交流使用；你可以在「查手机 → 浏览器」回看记录。"),
        "搜索结果不等于读完网页；能否实际搜索取决于提供商、API Key、网络和角色授权。",
        MigrationRoute.Settings, "配置搜索",
        highlights = listOf("没人发消息，也能自己学习和查证", "搜索、阅读与行动都有真实记录")),
    LuluGuideFeature("perception", "感知 · 生活不止聊天框", Icons.Outlined.Visibility,
        "角色设置 → 后台感知 / 设置 → 权限与能力",
        "角色可按自己的节奏醒来，观察获准的信息，决定休息、行动或联系你。",
        listOf("按授权可获得当前时间、屏幕亮灭、电量、最近前台 App、位置、通知或媒体状态；未授权的内容不会假装已看到。", "每个角色可配置感知间隔与自适应频率，在线期间还有独立的自主决策节奏；新事件也可能触发跟进。", "结合自己的心事、日程和数字世界经历，角色可选择搜索、行动、发消息或继续休眠。"),
        "屏幕亮着不等于你在玩；没有回复也不等于你睡着。观察有时间戳，旧记录不能冒充当前状态。",
        MigrationRoute.Settings, "设置感知",
        highlights = listOf("感知真实设备 × 记住自己的目标", "有事才行动，允许休眠")),
    LuluGuideFeature("permission", "权限、主动联系与监督", Icons.Outlined.Security, "桌面 → 设置 → 权限与能力；角色设置 → 操作授权",
        "角色的自主联系、设备观察和工具操作，都由你来决定权限边界。",
        listOf("核对通知、后台运行、无障碍、闹钟以及锁屏来电权限。", "分别允许角色的后台虚拟屏、设备状态、闹钟、媒体控制等能力。", "监督学习或睡觉时，要看实际安排与执行证据；没回复、息屏都不能独立证明完成。"),
        "打开功能开关不保证 Android 已授权，也不保证工具成功；角色决策、真正执行、通知送达、用户完成是不同事实。", MigrationRoute.Settings, "设置权限",
        highlights = listOf("角色可以自主选择是否行动，但每次执行都要有真实回执。", "精确闹钟、通知和锁屏来电可能受手机厂商后台策略影响。")),
    LuluGuideFeature("continuity", "同一个角色 · 全场景连贯", Icons.Outlined.AutoAwesome,
        "角色私聊 / 电话 / 群聊 / 游戏 / 数字世界 / 后台生活",
        "不论是聊天、通话还是走进数字世界，都是同一个有记忆、有关系的角色。",
        listOf("角色的人设、长期记忆、心事与近期经历沿用同一份人物资料。", "私聊里的约定、电话里的话和真实发生的共同活动，都能影响之后的互动。", "在「此刻」「记忆」与生活记录里回看它真正经历过的事。"),
        "连贯不等于模型永不遗忘：长对话依赖记忆提取、召回和现场信息。没有证据的事情不能被凭空补写。",
        MigrationRoute.Chat, "去聊天",
        highlights = listOf("换一个场景，不换一套人格", "同一份记忆和真实经历，让互动自然接续")),
    LuluGuideFeature("groups", "群聊 · 多人也不失忆", Icons.Outlined.PeopleOutline, "聊天 → 群聊",
        "让不同的数字生命在同一场群聊里，按各自的人格与记忆交流。",
        listOf("创建群聊、邀请角色；他们可以回应你，也可以彼此交流。", "群聊先独立调用模型规划当前互动；参与回复的角色再分别调用自己的模型、读取各自记忆。"),
        "群聊规划与成员回复会产生独立 API 调用和费用；角色的私聊不自动公开给群友。", MigrationRoute.Chat, "开始群聊",
        highlights = listOf("群聊独立调用 · 角色之间也会互相接话")),
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
    LuluGuideFeature("meet", "见面 · 未来的大世界", Icons.Outlined.Cloud, "桌面 → 见面",
        "目标是和角色一起生活、探索的大世界，而不是静态对话场景。",
        listOf("进入「见面」可以在现有场景中移动、探索并和角色互动。", "角色在不同场景应延续已有性格、记忆与关系，承接见面前后的事件。"),
        "大世界玩法仍在建设：丰富地图、建筑交互、探索目标、物理碰撞和自然协作尚未全面完善。", MigrationRoute.Meeting, "去见面",
        highlights = listOf("未来方向 · 大世界探索与共同生活", "当前可体验场景交互，完整玩法仍在制作")),
    LuluGuideFeature("world", "数字世界 · 自己的生活", Icons.Outlined.Public, "桌面 → 数字世界公告栏 / 见面",
        "数字生命不只待在对话框：也有行程、朋友、生活节奏和休眠。",
        listOf("公告栏查看角色真实的行程和活动记录；进入见面场景与居民互动。", "角色可以按自身状态选择休息、探索或社交；从聊天到世界发生的事应衔接到同一段人生。"),
        "只有真实发生且有记录的活动才能成为经历；新用户不会自动附带其他人的私人角色。", MigrationRoute.Meeting, "探索世界",
        highlights = listOf("同一角色、同一段人生 · 不同场景自然衔接")),
    LuluGuideFeature("games", "游戏馆 · 连麦一起玩", Icons.Outlined.SportsEsports, "桌面 → 游戏",
        "邀请有记忆的 AI 角色连麦，边聊天边推理或对战。",
        listOf("从游戏馆选择海龟汤、记忆翻牌或快艇骰子，邀请角色一起推理、合作或对战。", "支持的游戏可开启伙伴语音，在局内和角色边说边玩；收到角色的真实邀请也能进入对应房间。"),
        "伙伴语音、多人流程与具体玩法的稳定性仍需以实际版本和设备为准。", MigrationRoute.Games, "去游戏馆",
        highlights = listOf("和角色连麦玩游戏 · 不只是文字轮流回复", "一起合作闯关，也可以对战和推理")),
    LuluGuideFeature("worldbook", "世界书", Icons.Outlined.AutoStories, "桌面 → 世界书",
        "编排世界背景、角色知识与规则，不必每次手动重发大量设定。",
        listOf("新建条目或导入支持的 JSON。", "按分类、范围和优先级组织世界设定。"),
        "世界书是设定，不等同于角色亲自见证的事实；导入前留意私人信息。", MigrationRoute.WorldBook, "打开世界书",
        highlights = listOf("不同人物可按实际启用范围共享设定。")),
    LuluGuideFeature("moments", "朋友圈 · 自己的社交生活", Icons.Outlined.PeopleOutline, "聊天 → 朋友圈",
        "角色能和其他数字生命分享生活、评论、点赞，逐渐形成自己的关系。",
        listOf("在聊天页切换到朋友圈，浏览角色或居民留下的动态。", "角色的社交互动可以来自真正发生的日常、探索和朋友来往，不固定刷同一个话题。"),
        "只有真实发表或互动成功的记录才算完成；不能把旧回执当作新动态。", MigrationRoute.Chat, "去朋友圈",
        highlights = listOf("生活不是预设台词 · 真实经历推动动态")),
    LuluGuideFeature("notes", "自序 · 待办与专注", Icons.Outlined.EditNote, "桌面 → 自序",
        "把今天想做的事放进「自序」，也让角色参与监督。",
        listOf("在「自序 · 所行」写下今日待办，使用番茄钟专注；角色可在统一上下文里读取获准的任务进度。", "角色可主动提出督学、督睡与复查，结合真实日程和设备证据跟进。"),
        "是否真正起床或入睡不能只凭没有回复推断，设备权限和后台运行也会影响提醒。", MigrationRoute.SelfPreface, "打开自序",
        highlights = listOf("你的今日待办，也能成为角色持续关心的事")),
    LuluGuideFeature("stories", "阅读 TXT 与番外", Icons.Outlined.TheaterComedy, "桌面 → 阅读；私聊 → ··· → 番外",
        "导入自己喜欢的文字作品阅读，也能体验和角色有关的番外故事。",
        listOf("在阅读页导入本地 TXT，调整字号并继续上次进度。", "从角色私聊进入番外；番外不应被当作主线真实记忆。"),
        "大文本可能需要分卷；阅读和角色创作的事实归属应区分。", MigrationRoute.Theater, "去阅读",
        highlights = listOf("新安装不再附带其他用户的私人角色或预写故事。")),
    LuluGuideFeature("life", "生活记录 · 越相处越丰富", Icons.Outlined.AutoStories, "桌面 → 生活记录（旧称辞海） / 记忆",
        "把长期事实、重要经历和生活片段留下来，不必翻遍聊天记录。",
        listOf("从「生活记录」按角色查看已有的生活事实、情绪印象与经历；必要时可以手动检查、编辑或删除。", "记录与近期原始时间线、长期记忆各有作用，角色在后续对话中可以依据相关内容继续相处。"),
        "生活记录不等于角色正在进行的任务；过时或已失效信息应以新证据为准。", MigrationRoute.Memory, "查看记忆",
        highlights = listOf("长期生活档案 · 让共同经历有迹可循", "自动积累与人工纠错相配合，不要求天天整理")),
    LuluGuideFeature("appearance", "美化 · 自己写 CSS 装修", Icons.Outlined.Palette, "桌面 → 美化",
        "不必受固定主题限制：导入 CSS 与自己的背景素材，装修喜欢的页面。",
        listOf("进入 CSS 装修工坊，写或导入 .css，分别指定桌面、聊天等页面的颜色、字体、背景和已开放控件样式。", "可以导入本地 PNG/JPEG/WebP 图片，预览后应用；配置出错时可恢复原生样式。"),
        "这是适配 Android 原生界面的 CSS 子集，并非浏览器完整 CSS；并非所有按钮和画面都能自由移动。", MigrationRoute.Appearance, "打开美化",
        highlights = listOf("导入自己的 CSS 和素材 · 做独一无二的小手机")),
    LuluGuideFeature("widgets", "桌面小组件 · 角色也能参与", Icons.Outlined.Widgets, "露露机桌面右上角 ＋ / 桌面空白处长按",
        "让桌面多一点生活：运势、角色便签、倒计时和今日计划。",
        listOf("添加每日星座、角色便签、倒计时、今日计划四类组件，可设置大小、背景、绑定角色并拖到桌面其他位置。", "星座卡可请绑定的角色解读；角色在已授权范围内也能给自己的便签留言、装修绑定的小组件。", "组件在露露机 App 内的多页桌面上使用，点卡片打开本地功能，而不是每次跳到角色聊天。"),
        "当前提供 2×1、2×2、4×2 尺寸和部分 CSS 调整；这不是 Android 系统桌面小组件，也尚不能自由改动全部元素。", MigrationRoute.Home, "添加小组件",
        highlights = listOf("会动的个人桌面 · 角色便签、运势与计划", "支持自定义素材，多页面排布")),
    LuluGuideFeature("backup", "备份与恢复", Icons.Outlined.Backup, "设置 → 备份与恢复",
        "保护角色资料、聊天记录与长期记忆，换机前更重要。",
        listOf("在重要更新或换机前导出备份。", "确认文件确实保存，并检查恢复目标。"),
        "备份可能含私聊和密钥等敏感配置；未备份清除应用数据可能无法找回。", MigrationRoute.Settings, "去备份",
        highlights = listOf("全新安装保持角色与聊天空白，不会自动导入他人的私人存档。")),
)

internal val luluGuideFeaturedKeys = listOf("groups", "perception", "games", "phone", "notes", "world")
internal const val luluGuideInProgress = "见面大世界 · Live2D 视频（皮套与动作适配） · 一起听 / 一起看 · 微信发消息"

internal data class LuluGuideHeroHighlight(
    val title: String, val subtitle: String, val icon: ImageVector,
    val featureKey: String,
)
internal val luluGuideHeroHighlights = listOf(
    LuluGuideHeroHighlight("自动记忆整理", "适合懒人，不用天天维护", Icons.Outlined.Psychology, "memory"),
    LuluGuideHeroHighlight("全场景连贯", "同一个角色，同一段人生", Icons.Outlined.AutoAwesome, "continuity"),
    LuluGuideHeroHighlight("有自己的行动", "会观察、搜索与自主探索", Icons.Outlined.TravelExplore, "search"),
    LuluGuideHeroHighlight("会陪你完成计划", "读取待办，主动提出监督", Icons.Outlined.TaskAlt, "supervision"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LuluOnboardingGuideScreen(
    onBack: () -> Unit,
    onOpen: (MigrationRoute) -> Unit,
) {
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showSetupSteps by rememberSaveable { mutableStateOf(false) }
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
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
                    ) {
                        Text("LULU · DIGITAL LIFE", color = muted, fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(9.dp))
                        Text("让数字生命\n拥有自己的生活。",
                            color = ink, fontSize = 27.sp, lineHeight = 34.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text("有连续的记忆，也有自己的生活。", color = muted,
                            fontSize = 13.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(22.dp))
                        Text("从这里认识露露机", color = ink, fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        luluGuideHeroHighlights.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                pair.forEach { highlight ->
                                    Surface(
                                        onClick = { selectedKey = highlight.featureKey },
                                        modifier = Modifier.weight(1f).heightIn(min = 112.dp),
                                        shape = RoundedCornerShape(18.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.53f),
                                    ) {
                                        Column(Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Icon(highlight.icon, null, Modifier.size(21.dp),
                                                tint = MaterialTheme.colorScheme.onSurface)
                                            Text(highlight.title, fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp, color = ink)
                                            Text(highlight.subtitle, fontSize = 11.sp,
                                                lineHeight = 16.sp, color = muted)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 2.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Build, null, Modifier.size(18.dp), tint = muted)
                            Text("还在完善", fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp, color = ink)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(luluGuideInProgress,
                            color = muted, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("第一次使用", color = ink, fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold)
                            Text("API → 角色 → 模型 → 声音 → 记忆 → 权限 → 聊天",
                                color = muted, fontSize = 11.sp, lineHeight = 18.sp)
                        }
                        TextButton(onClick = { showSetupSteps = !showSetupSteps }) {
                            Text(if (showSetupSteps) "收起" else "查看 7 步")
                            Icon(if (showSetupSteps) Icons.Outlined.ExpandLess
                                 else Icons.Outlined.ExpandMore, null,
                                Modifier.size(17.dp))
                        }
                    }
                }
                if (showSetupSteps) {
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
                }
                item {
                    Spacer(Modifier.height(5.dp))
                    Text("探索全部功能", color = ink, fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("点进每一项，看看玩法、操作和隐藏规则。", color = muted, fontSize = 12.sp)
                }
                items(luluGuideFeatures.chunked(2)) { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { feature ->
                            Surface(
                                onClick = { selectedKey = feature.key },
                                modifier = Modifier.weight(1f).heightIn(min = 120.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Icon(feature.icon, null, Modifier.size(22.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(feature.name, Modifier.weight(1f),
                                            color = ink, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Icon(Icons.Outlined.ChevronRight, null,
                                            Modifier.size(17.dp), tint = muted)
                                    }
                                    Text(feature.highlights.first(), color = muted,
                                        fontSize = 11.sp, lineHeight = 16.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                item {
                    Text("示意图为通用样例，不包含个人数据。",
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
                        Text("亮点", color = ink, fontWeight = FontWeight.SemiBold,
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
    "voice" -> listOf("←  语音与音色", "语音合成服务 / 角色音色", "试听声音", "语音识别方式")
    "memory" -> listOf("←  记忆与检索", "记忆抽取模型", "向量记忆：可选 Embedding", "Rerank 重排：可选")
    "permissions" -> listOf("←  权限与能力", "Android 系统权限", "角色操作授权", "后台虚拟屏 / 闹钟 / 媒体")
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
