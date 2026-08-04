package com.jiacimu.lulu.games

import androidx.activity.compose.BackHandler
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.ModelUsage
import com.jiacimu.lulu.data.CharacterSettings
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

private data class CampaignWorld(
    val id: String,
    val title: String,
    val tag: String,
    val premise: String,
    val style: String,
    val accent: Color,
    val sanityRisk: Boolean = false,
)

private val CAMPAIGN_WORLDS = listOf(
    CampaignWorld("occult_city", "雾港失踪案", "都市怪谈 · 调查恐怖", "终年起雾的港城里，午夜会出现不存在的街道。失踪者都收到过写着自己死亡日期的旧车票。", "硬派调查与都市怪谈。强化潮湿、港口腥味、旧楼回声、路灯死角和现实逐渐失真；线索可追踪，同行者有不同推理与保护反应。", Color(0xFF38D9F1), true),
    CampaignWorld("fantasy_ruin", "失落王庭", "高魔幻想 · 遗迹史诗", "沉入地底三百年的王城重新升起。王座仍在等待继承者，进入者却会逐渐忘记自己的名字。", "史诗奇幻与遗迹冒险。描写宏大尺度、古老魔法、失落礼仪、身份诱惑与阵营选择；关系线和王庭秘密同步推进。", Color(0xFFFFC45B)),
    CampaignWorld("space_derelict", "静默星舰", "太空惊悚 · 生存悬疑", "失联二十年的殖民星舰发来求救信号，登舰后主脑坚持声称船员仍全部存活。", "冷峻科幻惊悚。强化失重、金属回声、维生噪声、终端残片与宇宙孤独；队伍真实讨论资源、风险和是否信任主脑。", Color(0xFF7C8CFF), true),
    CampaignWorld("academy_secret", "第十三间教室", "校园怪谈 · 心理恐怖", "学校平面图只有十二间教室，但雨夜走廊尽头会出现第十三扇门，门后课表写着你们的名字。", "高浓度心理恐怖。强化雨声、灯管嗡鸣、粉笔灰、潮湿气味、脚步错位、被注视感和不可靠记忆；恐怖逐步逼近。", Color(0xFFFF668F), true),
    CampaignWorld("romance_target_me", "全员都在攻略我", "恋爱修罗场 · 被攻略", "你进入无法退出的恋爱实验，同行角色都收到秘密任务：七天内让你主动选择他。", "高张力恋爱修罗场。重点写眼神、距离、试探、偏爱、吃醋、误会与暗流；每位同行者使用不同攻略方式。", Color(0xFFFF6FAE)),
    CampaignWorld("romance_i_target", "心动对象观察日志", "主动攻略 · 都市恋爱", "你获得一本只显示心动波动、不显示数值的日志，只能从行动和共同经历判断谁正在动心。", "克制、暧昧、生活化。重视聊天节奏、微表情、日常陪伴和未说出口的话；不能轻易表白。", Color(0xFFFFB26B)),
    CampaignWorld("system_mission", "系统说今天必须心动", "系统任务 · 轻喜剧", "不太靠谱的系统天天发布交换身份、假装情侣、敌前演戏和真心话等离谱任务。", "快节奏系统轻喜剧。笑点来自性格碰撞、尴尬任务和互相拆台，但不能让人物降智；关键场景保留真情绪。", Color(0xFFFFD84D)),
    CampaignWorld("palace_scheme", "今夜谁在宫门外", "古风宫廷 · 权谋关系", "新帝登基后的雪夜，宫门外出现没有影子的尸体，你们卷入储位、旧案和禁军的角力。", "古风权谋与克制情感。重视礼法、身份、称谓、沉默和言外之意；阴谋可推理，感情通过危险中的选择推进。", Color(0xFFD8A45B)),
    CampaignWorld("cyber_memory", "霓虹雨中的假记忆", "赛博都市 · 身份悬疑", "在可以买卖记忆的城市里，你们发现彼此都拥有同一段童年，但它只可能属于一个人。", "赛博朋克身份悬疑。强化霓虹雨、广告噪声、义体触觉和数据残影；核心是谁的记忆被改写、队友还能否互信。", Color(0xFF37E3B5), true),
    CampaignWorld("apocalypse_store", "末日前七日：异能纪元", "末世群像 · 异能生存 · 长篇", "你提前知道七天后‘赤潮天灾’会让文明崩塌、异能觉醒。没有人知道情报从何而来；你必须在秩序尚存时说服伙伴、囤积资源、选择据点，并在末世后与基地、财团、教团和异能组织共同改写时代。", "宏大、可长期推进的末世异能小说，轻喜剧底色。世界包含灾前倒计时、城市沦陷、基地兴衰、组织战争和文明重建；异能分自然、精神、空间、生命、强化、规则六系，阶位从萤火、星芒、月环、日冕到天灾。资源、金钱、关系、选择和伏笔持续兑现，人物机灵但不降智。", Color(0xFFFF8A5B), true),
    CampaignWorld("cultivation_comedy", "小师弟把魔尊契约当话本", "仙侠轻喜剧 · 契约冒险", "写错名字的上古契约把你们与魔尊绑在一起，解除契约要完成九项离谱试炼。", "仙侠冒险与欢喜冤家。保持东方奇幻、门派规矩和秘境奇观；笑点来自契约限制、嘴硬和身份反差。", Color(0xFF8BE0C5)),
    CampaignWorld("time_loop_date", "约会结束前世界会重启", "时间循环 · 恋爱悬疑", "每天23:59世界都会回到第一次见面的早晨，只有同行小队保留记忆。", "时间循环与恋爱悬疑。重复场景通过细节偏差、记忆累积和关系变化保持新鲜；浪漫来自共同记得。", Color(0xFFA98CFF), true),
)

private data class CampaignSave(
    val id: String,
    val title: String,
    val worldId: String,
    val partyIds: List<String>,
    val partyNames: List<String>,
    val scene: Int = 1,
    val hp: Int = 10,
    val sanity: Int = 10,
    val luck: Int = 3,
    val clues: Int = 0,
    val money: Int = 3_000,
    val narration: String,
    val log: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

private data class CampaignAdjudication(
    val needsRoll: Boolean,
    val roller: String,
    val rollerCharacterId: String,
    val checkName: String,
    val difficulty: Int,
    val attitude: String,
    val ruling: String,
)

private object CampaignColors {
    val top = Color(0xFF070A16)
    val bottom = Color(0xFF17102D)
    val panel = Color(0xFF13182D)
    val border = Color(0xFF425079)
    val text = Color(0xFFF7F8FF)
    val muted = Color(0xFFAAB2D2)
    val cyan = Color(0xFF38D9F1)
    val violet = Color(0xFFA88BFF)
    val amber = Color(0xFFFFC45B)
    val rose = Color(0xFFFF668F)
    val mint = Color(0xFF61E6B3)
}

private const val LEGACY_OPENING = "你们抵达了故事的入口。空气里已有某种东西先一步注意到了你们。"

private fun campaignOpening(world: CampaignWorld, partyNames: List<String>): String {
    val party = partyNames.joinToString("、").ifBlank { "同行者" }
    val scene = when (world.id) {
        "occult_city" -> "凌晨十一点四十七分，雾港旧车站的末班钟响了三次。售票厅早已停用，湿漉漉的地砖上却摆着一只刚送来的牛皮纸信封。信封里有一张写着你名字的旧车票，终点站一栏被刮掉，只剩明天的日期。站外，一名失踪者的家属正在雨棚下等你们；而封闭多年的三号站台，刚刚亮起了灯。"
        "fantasy_ruin" -> "失落王庭在黎明前从裂谷中升起，整座城市仍滴着三百年前地底河的黑水。你们站在断桥这一端，城门上十二尊无首雕像同时转向了你。守门石碑要求每位来客报出真名，可${party}中的一个人忽然发现，自己已经想不起母亲给他的名字。"
        "space_derelict" -> "接驳舱与静默星舰完成锁定时，外部摄像头拍到舷窗后有人挥手。可生命扫描仍显示全舰零存活。气闸开启后，走廊灯依次亮起，主脑用温柔得近乎僵硬的声音欢迎你们回家，并要求立刻前往休眠区参加二十年前尚未结束的点名。"
        "academy_secret" -> "晚自习结束后暴雨封住了校门。你们返回教学楼取东西，却发现四楼走廊比白天多出一截。尽头那扇没有编号的门里亮着灯，黑板上写着你和${party}的名字，课表第一节是“缺席者说明会”。身后的楼梯间传来班主任的脚步声，但班主任今天明明请了假。"
        "romance_target_me" -> "实验开始于一栋看似普通的合租公寓。你醒来时，客厅屏幕宣布：七天内，只有被你主动选择的人能离开。${party}都声称自己刚刚才知道规则，可茶几上分别放着只属于他们的秘密任务卡。第一顿早餐尚未开始，门锁便提示：今天必须完成一次双人约会。"
        "romance_i_target" -> "周六午后，你和${party}在旧城区的独立书店集合。你口袋里的《心动对象观察日志》第一次自行翻页，只写下一句：‘心动不会告诉你答案，只会改变细节。’窗外忽然下雨，店员说最后一把伞只能借给两个人。与此同时，其中一人的手机亮起，屏幕上是与你有关、却被迅速按灭的消息。"
        "system_mission" -> "早上八点整，只有你们能看见的系统在餐桌上方弹出今日任务：‘在不暴露任务的前提下，与指定对象扮演情侣四小时。失败惩罚：互换手机通讯录备注。’指定对象的名字被故意打了码，而${party}每个人收到的提示似乎都不一样。门铃恰在这时响起，来客自称是你们共同的前任。"
        "palace_scheme" -> "新帝登基后的第一场雪封住宫门。你们被秘密召到午门外，一具没有影子的尸体伏在雪中，袖口藏着先帝禁用的朱砂印。禁军要求天亮前给出解释，否则所有在场者都会以谋逆论处。远处宫墙后传来一声钟响，而今夜本不该鸣钟。"
        "cyber_memory" -> "霓虹雨沿着记忆交易所的玻璃幕墙往下流。你与${party}刚完成一次非法记忆鉴定，结果显示你们脑中都存在同一段童年：红色秋千、停电的医院和一句‘别让他们找到原件’。鉴定师在说出结果后被远程清除了身份，而追踪警报已经锁定这间诊室。"
        "apocalypse_store" -> "距离赤潮天灾还有七天。下午四点十七分，城市仍在为晚高峰堵车，商场广播还在推销第二件半价。只有你知道：七天后第一轮红雨会让电网瘫痪，幸存者觉醒异能，三个月内东区会变成吞噬生命的雾巢。你把${party}约进一家火锅店的包间，桌上摊着三千元现金、仓库租赁广告和一张被你标满的城市地图。你必须先让他们相信这不是整蛊，再决定有限的钱该买药、食物、武器，还是一辆怎么看都快报废的面包车。窗外，一个未来会建立‘白塔’组织的人刚好撑伞走过。"
        "cultivation_comedy" -> "上古契约在你们面前烧成金字：因抄写者把名字写错，你、${party}与被封印的魔尊共享痛觉，距离超过十丈就会同时打喷嚏。解除契约的第一项试炼写着‘取得天下第一正道门派的掌门信物’，而掌门此刻正在山门外搜查偷走契约的人。"
        "time_loop_date" -> "上午九点，你再次在同一家咖啡馆见到${party}。窗外同一辆公交车溅起同一片水，服务员打碎同一只杯子。你们都记得昨晚23:59世界归零，也记得重启前有人在天台说：‘只要这次约会以同样方式结束，明天就不会再有人醒来。’今天必须找出循环中唯一改变的细节。"
        else -> "你与${party}抵达了${world.title}的起点。${world.premise}眼前已经出现第一个异常，它既是危险，也是进入真相的入口。"
    }
    return """
        $scene

        你们当前的首要目标，是在局势彻底失控前弄清眼前异常与核心事件的联系。现场至少有三条可追查方向，同行者也会依据各自性格提出意见、保护你或与你争执。你可以自由描述任何行动，不必局限于建议；行动越具体，判定和后果越明确。
    """.trimIndent()
}

private fun suggestedActions(worldId: String): List<String> = when (worldId) {
    "occult_city" -> listOf("检查旧车票和信封", "询问失踪者家属", "前往亮灯的三号站台", "观察周围是否有人跟踪")
    "fantasy_ruin" -> listOf("研究守门石碑", "帮助同行者回忆名字", "寻找绕过断桥的路", "观察城门雕像")
    "space_derelict" -> listOf("扫描走廊生命迹象", "质问主脑点名内容", "检查舷窗后的身影", "先封锁接驳舱退路")
    "academy_secret" -> listOf("查看黑板上的课表", "推开第十三间教室", "躲起来观察脚步声", "联系真正的班主任")
    "romance_target_me" -> listOf("检查秘密任务卡", "观察谁在说谎", "选择一人完成约会", "尝试破解公寓门锁")
    "romance_i_target" -> listOf("邀请一人共撑雨伞", "追问被按灭的消息", "翻看日志的新变化", "提议大家一起去附近咖啡馆")
    "system_mission" -> listOf("试探每个人收到的任务", "主动认领情侣身份", "询问门外来客", "寻找系统规则漏洞")
    "palace_scheme" -> listOf("检查尸体与朱砂印", "询问值守禁军", "追查异常钟声", "保护现场避免被栽赃")
    "cyber_memory" -> listOf("导出共同记忆片段", "切断诊室网络", "追查记忆原件", "从追踪警报中找出口")
    "apocalypse_store" -> listOf("告诉伙伴末世情报", "核对灾前采购清单", "寻找第一处安全据点", "接触未来的白塔领袖")
    "cultivation_comedy" -> listOf("研究契约漏洞", "伪装通过山门搜查", "与魔尊谈条件", "寻找掌门信物线索")
    "time_loop_date" -> listOf("记录今天所有异常", "询问每个人重启前经历", "重走昨天的约会路线", "提前前往天台")
    else -> listOf("调查眼前异常", "与同行者商量", "寻找安全退路", "追踪最明显的线索")
}

private class CampaignStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("formal_roleplay_campaigns_v2", Context.MODE_PRIVATE)

    fun load(): List<CampaignSave> = runCatching {
        val array = JSONArray(prefs.getString("saves", "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val worldId = item.optString("worldId").ifBlank { CAMPAIGN_WORLDS.first().id }
                val partyNames = item.optJSONArray("partyNames").stringList()
                val world = CAMPAIGN_WORLDS.firstOrNull { it.id == worldId } ?: CAMPAIGN_WORLDS.first()
                val storedNarration = item.optString("narration")
                add(CampaignSave(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = item.optString("title").ifBlank { "未命名战役" },
                    worldId = worldId,
                    partyIds = item.optJSONArray("partyIds").stringList(),
                    partyNames = partyNames,
                    scene = item.optInt("scene", 1), hp = item.optInt("hp", 10),
                    sanity = item.optInt("sanity", 10), luck = item.optInt("luck", 3), clues = item.optInt("clues", 0),
                    money = item.optInt("money", 3_000),
                    narration = storedNarration.takeUnless { it.isBlank() || it == LEGACY_OPENING }
                        ?: campaignOpening(world, partyNames),
                    log = item.optJSONArray("log").stringList(),
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun save(values: List<CampaignSave>) {
        val array = JSONArray()
        values.forEach { value ->
            array.put(JSONObject()
                .put("id", value.id).put("title", value.title).put("worldId", value.worldId)
                .put("partyIds", JSONArray(value.partyIds)).put("partyNames", JSONArray(value.partyNames))
                .put("scene", value.scene).put("hp", value.hp).put("sanity", value.sanity)
                .put("luck", value.luck).put("clues", value.clues).put("money", value.money).put("narration", value.narration)
                .put("log", JSONArray(value.log)).put("updatedAt", value.updatedAt))
        }
        prefs.edit().putString("saves", array.toString()).apply()
    }
}

@Composable
internal fun FormalRoleplayCampaignScreen(store: LuluGameStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val campaignStore = remember(context) { CampaignStore(context) }
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    val gameState by store.state.collectAsState()
    var saves by remember { mutableStateOf(campaignStore.load()) }
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val active = saves.firstOrNull { save -> save.id == activeId }

    BackHandler(enabled = activeId != null || creating) {
        if (creating) creating = false else activeId = null
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CampaignColors.top, CampaignColors.bottom)))) {
        if (active == null) {
            CampaignArchive(
                saves = saves,
                onBack = onBack,
                onOpen = { selectedId -> activeId = selectedId },
                onCreate = { creating = true },
                onDelete = { selectedId ->
                    saves = saves.filterNot { save -> save.id == selectedId }
                    campaignStore.save(saves)
                },
            )
        } else {
            CampaignPlay(
                save = active,
                characters = characters,
                gameStore = store,
                onBack = { activeId = null },
                onUpdate = { updated ->
                    saves = saves.map { old -> if (old.id == updated.id) updated else old }
                    campaignStore.save(saves)
                },
            )
        }
    }

    if (creating) {
        CreateCampaignDialog(
            characters = characters.values.toList(),
            initialPartyIds = gameState.selectedCharacterIds,
            onDismiss = { creating = false },
            onCreate = { title, world, party ->
                val partyNames = party.map(CharacterSettings::displayName)
                val save = CampaignSave(
                    id = UUID.randomUUID().toString(),
                    title = title.ifBlank { world.title },
                    worldId = world.id,
                    partyIds = party.map(CharacterSettings::characterId),
                    partyNames = partyNames,
                    narration = campaignOpening(world, partyNames),
                )
                saves = listOf(save) + saves
                campaignStore.save(saves)
                activeId = save.id
                creating = false
            },
        )
    }
}

@Composable
private fun CampaignArchive(
    saves: List<CampaignSave>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = CampaignColors.text) }
            Column(Modifier.weight(1f)) {
                Text("战役档案", color = CampaignColors.text, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("TRPG CAMPAIGNS", color = CampaignColors.cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            FilledTonalButton(onClick = onCreate) { Icon(Icons.Outlined.Add, null); Text("新建") }
        }
        if (saves.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = onCreate) { Text("建立第一份战役档案") }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(14.dp, 10.dp, 14.dp, 30.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(saves, key = CampaignSave::id) { save ->
                    val world = CAMPAIGN_WORLDS.firstOrNull { item -> item.id == save.worldId } ?: CAMPAIGN_WORLDS.first()
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(save.id) },
                        shape = RoundedCornerShape(22.dp), color = CampaignColors.panel,
                        contentColor = CampaignColors.text,
                        border = BorderStroke(1.dp, world.accent.copy(alpha = 0.72f)),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(save.title, color = CampaignColors.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                    Text(world.title, color = world.accent, fontWeight = FontWeight.SemiBold)
                                }
                                IconButton(onClick = { onDelete(save.id) }) {
                                    Icon(Icons.Outlined.DeleteOutline, "删除", tint = CampaignColors.muted)
                                }
                            }
                            Text(save.partyNames.joinToString(" · ").ifBlank { "独自冒险" }, color = CampaignColors.muted)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CampaignStat("幕", save.scene.toString(), world.accent)
                                CampaignStat("生命", save.hp.toString(), CampaignColors.rose)
                                CampaignStat("理智", save.sanity.toString(), CampaignColors.violet)
                                CampaignStat("幸运", save.luck.toString(), CampaignColors.amber)
                                CampaignStat("线索", save.clues.toString(), CampaignColors.mint)
                                CampaignStat("金钱", "¥${save.money}", CampaignColors.cyan)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.CampaignStat(label: String, value: String, accent: Color) {
    Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.12f)) {
        Column(Modifier.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = CampaignColors.text, fontWeight = FontWeight.Bold)
            Text(label, color = accent, fontSize = 9.sp)
        }
    }
}

@Composable
private fun CreateCampaignDialog(
    characters: List<CharacterSettings>,
    initialPartyIds: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, CampaignWorld, List<CharacterSettings>) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var worldId by remember { mutableStateOf(CAMPAIGN_WORLDS.first().id) }
    val partyIds = remember(initialPartyIds, characters) {
        initialPartyIds.filter { id -> characters.any { it.characterId == id } }.take(4).toSet()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("建立战役档案") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedTextField(title, { value -> title = value }, label = { Text("战役名称") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("选择世界", fontWeight = FontWeight.Bold) }
                items(CAMPAIGN_WORLDS, key = CampaignWorld::id) { world ->
                    Surface(
                        Modifier.fillMaxWidth().clickable { worldId = world.id },
                        shape = RoundedCornerShape(15.dp),
                        color = if (worldId == world.id) world.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, if (worldId == world.id) world.accent else Color.Transparent),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(world.title, fontWeight = FontWeight.Bold)
                            Text(world.tag, color = world.accent, fontSize = 12.sp)
                            Text(world.premise, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Text("本战役同行角色", fontWeight = FontWeight.Bold)
                    Text(
                        characters.filter { it.characterId in partyIds }.joinToString("、") { it.displayName }.ifBlank { "请返回游戏入口选择角色" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = partyIds.isNotEmpty(),
                onClick = {
                    val world = CAMPAIGN_WORLDS.first { item -> item.id == worldId }
                    onCreate(title.trim(), world, characters.filter { character -> character.characterId in partyIds })
                },
            ) { Text("进入战役") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CampaignPlay(
    save: CampaignSave,
    characters: Map<String, CharacterSettings>,
    gameStore: LuluGameStore,
    onBack: () -> Unit,
    onUpdate: (CampaignSave) -> Unit,
) {
    val world = CAMPAIGN_WORLDS.firstOrNull { item -> item.id == save.worldId } ?: CAMPAIGN_WORLDS.first()
    val party = save.partyIds.map { id -> characters[id] ?: MigratedDomainStores.characters.get(id) }
    val scope = rememberCoroutineScope()
    var action by rememberSaveable(save.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var rolling by remember { mutableStateOf(false) }
    var rollingFace by remember { mutableIntStateOf(1) }
    var lastRoll by remember { mutableIntStateOf(0) }
    var lastResult by remember { mutableStateOf("") }

    fun perform(useLuck: Boolean) {
        val cleanAction = action.trim()
        if (cleanAction.isBlank() || busy || rolling || (useLuck && save.luck <= 0)) return
        scope.launch {
            busy = true
            lastRoll = 0
            lastResult = "主持人正在判断是否需要检定…"

            val partyPrompt = party.joinToString("\n") { member ->
                "- id=${member.characterId}；姓名=${member.displayName}；persona=${member.persona.ifBlank { "按其既有角色设定行动" }}"
            }
            val adjudicationFacts = """
                正式跑团：《${save.title}》
                世界：${world.title}
                前提：${world.premise}
                文风：${world.style}
                当前场景：${save.scene}
                状态：生命${save.hp}/10，理智${save.sanity}/10，幸运${save.luck}，线索${save.clues}，可用金钱¥${save.money}
                用户声明的行动：$cleanAction
                同行角色：
                $partyPrompt
                最近记录：
                ${save.log.takeLast(8).joinToString("\n")}
                上一段叙事：
                ${save.narration.takeLast(2600)}
            """.trimIndent()
            val adjudicationInstruction = """
                你是严格但尊重人物设定的跑团主持人。先裁定用户声明的行动是否真的需要检定，不要因为玩家输入了行动、选择了选项，或使用了“劝说、请求、帮我”等词就机械掷骰。

                只返回一个 JSON 对象，不要代码块：
                {"needsRoll":true,"roller":"user|character","rollerCharacterId":"同行角色ID或空字符串","checkName":"检定名称","difficulty":12,"attitude":"willing|hesitant|unwilling|not_applicable","ruling":"一句简短中文裁定"}

                裁定规则：
                1. 只有结果存在真实不确定性，而且失败会产生有意义的不同后果时，needsRoll 才为 true。普通交谈、自然移动、接受显而易见的信息、没有压力的简单动作通常无需检定。
                2. 涉及同行角色时，必须先读取该角色 persona，判断他对这个具体请求是 willing、hesitant 还是 unwilling。骰子不能决定角色爱不爱用户、忠不忠诚、是否突然改变性格。
                3. 若角色按 persona 本来就愿意做这件事，不能进行说服检定。若后续执行存在风险，则 needsRoll=true，roller=character，并填写该角色ID；检定的是潜行、观察、撬锁、战斗等执行结果，不是他是否答应。
                4. 只有角色确实犹豫，且用户正试图改变其具体决定时，才可进行说服、欺骗或威吓检定，roller=user，attitude=hesitant。
                5. 若请求触碰角色明确核心底线，attitude=unwilling 且 needsRoll=false。高点数不能精神控制角色；他可以拒绝、解释、妥协或提出替代方案。
                6. 若行动由用户亲自完成，roller=user；由同行角色实际执行，roller=character。不要把同伴的技能检定错误地算成用户的说服检定。
                7. 只依据明确 persona 推断角色倾向与明显擅长项，不得凭空编造职业、技能或数值。没有明确信息时按普通人处理。
                8. difficulty 使用 6—20：容易但有压力约8，普通约10—12，中等约13—15，困难约16—18，极端约19—20。
                9. ruling 要明确说明“为何无需检定”或“由谁进行什么检定”，控制在60个汉字内。
            """.trimIndent()

            var adjudicationGeneration = LuluAiServices.gateway.generate(
                characterId = party.firstOrNull()?.characterId ?: "lulu",
                facts = adjudicationFacts,
                instruction = adjudicationInstruction,
                source = "游戏裁定",
                title = "跑团主持裁定 · ${world.title}",
                temperature = 0.22,
                maxTokens = 500,
                usage = ModelUsage.Game,
                contextMode = CompanionContextMode.PersonaAndScenario,
            )
            var adjudication = adjudicationGeneration.getOrNull()?.text
                ?.let { raw -> parseCampaignAdjudication(raw, party) }
            if (adjudication == null) {
                adjudicationGeneration = LuluAiServices.gateway.generate(
                    characterId = party.firstOrNull()?.characterId ?: "lulu",
                    facts = adjudicationFacts,
                    instruction = adjudicationInstruction + "\n上一次格式无效。本次必须只返回可解析的单个JSON对象，不要添加任何解释。",
                    source = "游戏裁定重试",
                    title = "跑团主持裁定 · ${world.title}",
                    temperature = 0.10,
                    maxTokens = 400,
                    usage = ModelUsage.Game,
                    contextMode = CompanionContextMode.PersonaAndScenario,
                )
                adjudication = adjudicationGeneration.getOrNull()?.text
                    ?.let { raw -> parseCampaignAdjudication(raw, party) }
            }
            val ruling = adjudication ?: CampaignAdjudication(
                needsRoll = true,
                roller = "user",
                rollerCharacterId = "",
                checkName = "行动检定",
                difficulty = (10 + save.scene / 2).coerceIn(10, 17),
                attitude = "not_applicable",
                ruling = "主持裁定暂时无法解析，按普通行动检定处理",
            )

            val rollerCharacter = party.firstOrNull { member -> member.characterId == ruling.rollerCharacterId }
            val rollerLabel = if (ruling.roller == "character" && rollerCharacter != null) {
                rollerCharacter.displayName
            } else {
                "你"
            }

            var roll = 0
            var result = "无需检定"
            val luckSpent: Int
            if (ruling.needsRoll) {
                rolling = true
                repeat(15) { index ->
                    rollingFace = Random.nextInt(1, 21)
                    delay(45L + index * 7L)
                }
                val firstRoll = Random.nextInt(1, 21)
                val secondRoll = if (useLuck) Random.nextInt(1, 21) else firstRoll
                roll = maxOf(firstRoll, secondRoll)
                rollingFace = roll
                lastRoll = roll
                delay(260)
                rolling = false
                result = when {
                    roll == 20 -> "大成功"
                    roll == 1 -> "大失败"
                    roll >= ruling.difficulty -> "成功"
                    else -> "失败"
                }
                luckSpent = if (useLuck) 1 else 0
                lastResult = "$rollerLabel · ${ruling.checkName} · $result"
            } else {
                rolling = false
                luckSpent = 0
                lastResult = "无需检定 · ${ruling.ruling}"
            }

            val resolutionText = if (ruling.needsRoll) {
                "程序判定：执行者=$rollerLabel；检定=${ruling.checkName}；d20=$roll；难度=${ruling.difficulty}；结果=$result${if (luckSpent > 0) "（已消耗幸运，取两次较高值）" else ""}"
            } else {
                "主持裁定：无需检定；角色态度=${ruling.attitude}；原因=${ruling.ruling}。不得擅自补掷骰或制造随机失败。"
            }
            val facts = """
                $adjudicationFacts
                主持裁定：needsRoll=${ruling.needsRoll}；roller=${ruling.roller}；rollerCharacterId=${ruling.rollerCharacterId}；check=${ruling.checkName}；attitude=${ruling.attitude}；ruling=${ruling.ruling}
                $resolutionText
            """.trimIndent()
            val instruction = """
                你是成熟的跑团主持人与小说叙事者。必须服从上面的主持裁定；需要检定时必须服从程序骰点，不得改骰，也不得把失败偷换成成功。无需检定时不得擅自补骰或为了制造刺激强行写失败。
                “你”只代表用户本人；同行角色必须直接写名字，禁止用含混的“我”混淆视角。
                输出约700—1200个汉字的完整沉浸正文。先写行动如何发生，再写裁定或骰点造成的具体后果；使用环境、五感、空间距离、微动作、心理压力和潜台词。
                同行角色是主角小队，每轮至少发生两次有效互动，每个人必须符合 persona，不能变成只会附和的背景板。
                人设、关系倾向与底线优先于骰子。骰子只能决定具体方案是否奏效、行动执行得如何，以及外部世界怎样回应，不能决定角色是否突然不爱用户、背叛既定关系或性格崩坏。
                若同行角色本来 willing，他应自然答应。若其执行检定失败，失败应落在执行偏差、环境阻碍、误解、代价、暴露、时机不对或新的麻烦上，不能偷换成“他突然不愿意”。
                若 attitude=hesitant 且进行了社交检定，成功表示角色接受这个具体方案，失败表示该方案没有说服他；失败时仍须按 persona 表现关心、顾虑、妥协或提出替代方案。
                若 attitude=unwilling 且无需检定，必须保留角色核心底线；角色可以拒绝、解释、保护用户或提出别的办法，不能被高魅力式叙事强行控制。
                若 roller=character，骰点结果属于该同行角色的实际行动；由该角色依据 persona 作出反应。不得把他的行动失败写成用户说服失败。
                这是独立的长篇剧情，不得引用角色与用户在普通聊天、电话、群聊、辞海或日常记忆中发生的事。只延续本战役的开场、存档、日志与角色 persona。
                若世界是《末日前七日：异能纪元》，必须维持灾前倒计时、组织势力、异能体系、资源与金钱的长期连续性；正文像轻喜剧末世小说，不写机械跑团播报。
                失败必须产生真实代价，但也必须推进剧情，给出新信息、危险或可追踪线索，绝不让故事卡死。
                结尾必须自然留下2—4个明确可行动方向，但不要写数值面板、系统解释、“选项A/B”或“主持人说”。
                只输出游戏正文，不要返回空内容，不要输出JSON。
            """.trimIndent()

            var generation = LuluAiServices.gateway.generate(
                characterId = party.firstOrNull()?.characterId ?: "lulu",
                facts = facts,
                instruction = instruction,
                source = "游戏",
                title = "跑团 · ${world.title}",
                temperature = 0.82,
                maxTokens = 1800,
                usage = ModelUsage.Game,
                contextMode = CompanionContextMode.PersonaAndScenario,
            )
            if (generation.getOrNull()?.text.isNullOrBlank()) {
                generation = LuluAiServices.gateway.generate(
                    characterId = party.firstOrNull()?.characterId ?: "lulu",
                    facts = facts,
                    instruction = instruction + "\n上一次返回为空。本次请立刻从用户行动发生的瞬间开始写正文，至少600个汉字。",
                    source = "游戏重试",
                    title = "跑团 · ${world.title}",
                    temperature = 0.72,
                    maxTokens = 1600,
                    usage = ModelUsage.Game,
                    contextMode = CompanionContextMode.PersonaAndScenario,
                )
            }

            generation.onSuccess { reply ->
                if (reply.text.isBlank()) {
                    lastResult = if (ruling.needsRoll) {
                        "$result · 叙事暂时中断，原行动已保留，可再次提交"
                    } else {
                        "无需检定 · 叙事暂时中断，原行动已保留，可再次提交"
                    }
                    busy = false
                    return@onSuccess
                }
                val hpDelta = when {
                    !ruling.needsRoll -> 0
                    roll == 1 -> -2
                    result == "失败" && Random.nextBoolean() -> -1
                    else -> 0
                }
                val sanityDelta = when {
                    !ruling.needsRoll -> 0
                    world.sanityRisk && roll == 1 -> -2
                    world.sanityRisk && result == "失败" -> -1
                    else -> 0
                }
                val clueDelta = when {
                    !ruling.needsRoll -> 0
                    roll == 20 -> 2
                    result == "成功" -> 1
                    else -> 0
                }
                val moneyDelta = when {
                    world.id != "apocalypse_store" || !ruling.needsRoll -> 0
                    roll == 20 -> 120
                    result == "成功" -> 40
                    roll == 1 -> -120
                    else -> -30
                }
                val resolutionLog = if (ruling.needsRoll) {
                    "$rollerLabel｜${ruling.checkName}｜d20=$roll $result"
                } else {
                    "无需检定｜${ruling.ruling}"
                }
                val updated = save.copy(
                    scene = save.scene + 1,
                    hp = (save.hp + hpDelta).coerceIn(0, 10),
                    sanity = (save.sanity + sanityDelta).coerceIn(0, 10),
                    luck = (save.luck - luckSpent).coerceAtLeast(0),
                    clues = save.clues + clueDelta,
                    money = (save.money + moneyDelta).coerceAtLeast(0),
                    narration = reply.text,
                    log = (save.log + "SCENE ${save.scene}｜$cleanAction｜$resolutionLog\n${reply.text.take(500)}").takeLast(80),
                    updatedAt = System.currentTimeMillis(),
                )
                val summary = if (ruling.needsRoll) {
                    "在${world.title}执行“$cleanAction”，由${rollerLabel}进行${ruling.checkName}，d20=$roll，$result。"
                } else {
                    "在${world.title}执行“$cleanAction”，主持判定无需检定：${ruling.ruling}。"
                }
                val recordId = gameStore.recordExternalGame(
                    LuluGameType.RoleplayAdventure,
                    "跑团 · ${world.title} · 第${save.scene}幕",
                    if (ruling.needsRoll) roll * 5 else 60,
                    if (!ruling.needsRoll) 5 else if (result == "成功" || result == "大成功") 8 else 3,
                    summary,
                    JSONObject()
                        .put("world", world.title)
                        .put("scene", save.scene)
                        .put("action", cleanAction)
                        .put("needsRoll", ruling.needsRoll)
                        .put("roller", ruling.roller)
                        .put("rollerCharacterId", ruling.rollerCharacterId)
                        .put("checkName", ruling.checkName)
                        .put("difficulty", ruling.difficulty)
                        .put("attitude", ruling.attitude)
                        .put("ruling", ruling.ruling)
                        .put("roll", if (ruling.needsRoll) roll else JSONObject.NULL)
                        .put("result", result)
                        .put("party", JSONArray(save.partyNames))
                        .put("narration", reply.text)
                        .toString(),
                )
                gameStore.attachCharacterReply(recordId, reply.text)
                onUpdate(updated)
                action = ""
            }
            generation.onFailure {
                lastResult = if (ruling.needsRoll) {
                    "$result · 叙事暂时中断，原行动已保留，可再次尝试"
                } else {
                    "无需检定 · 叙事暂时中断，原行动已保留，可再次尝试"
                }
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回档案", tint = CampaignColors.text) }
            Column(Modifier.weight(1f)) {
                Text(save.title, color = CampaignColors.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("${world.title} · SCENE ${save.scene}", color = world.accent, fontSize = 12.sp)
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CampaignStat("生命", save.hp.toString(), CampaignColors.rose)
                    CampaignStat("理智", save.sanity.toString(), CampaignColors.violet)
                    CampaignStat("幸运", save.luck.toString(), CampaignColors.amber)
                    CampaignStat("线索", save.clues.toString(), CampaignColors.mint)
                    CampaignStat("金钱", "¥${save.money}", CampaignColors.cyan)
                }
            }
            item {
                Surface(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = CampaignColors.panel,
                    contentColor = CampaignColors.text,
                    border = BorderStroke(1.dp, world.accent.copy(alpha = 0.52f)),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Text(save.narration, color = CampaignColors.text, fontSize = 16.sp, lineHeight = 27.sp)
                        HorizontalDivider(color = CampaignColors.border.copy(alpha = 0.7f))
                        Text("你可以这样开始", color = world.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        suggestedActions(world.id).chunked(2).forEach { rowActions ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowActions.forEach { suggestion ->
                                    AssistChip(
                                        onClick = { action = suggestion },
                                        label = { Text(suggestion, maxLines = 2) },
                                        modifier = Modifier.weight(1f),
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = world.accent.copy(alpha = 0.10f),
                                            labelColor = CampaignColors.text,
                                        ),
                                        border = BorderStroke(1.dp, world.accent.copy(alpha = 0.35f)),
                                    )
                                }
                                if (rowActions.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (rolling || lastResult.isNotBlank()) {
                item {
                    Surface(
                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                        color = CampaignColors.violet.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, CampaignColors.violet.copy(alpha = 0.55f)),
                    ) {
                        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            when {
                                rolling -> {
                                    Text("D20 正在滚动", color = CampaignColors.muted)
                                    Text(rollingFace.toString(), color = CampaignColors.text, fontSize = 42.sp, fontWeight = FontWeight.Black)
                                }
                                lastRoll > 0 -> {
                                    Text("D20 · $lastResult", color = CampaignColors.muted)
                                    Text(lastRoll.toString(), color = CampaignColors.text, fontSize = 42.sp, fontWeight = FontWeight.Black)
                                }
                                else -> {
                                    Text("主持裁定", color = CampaignColors.muted)
                                    Text(lastResult, color = CampaignColors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(color = CampaignColors.panel, contentColor = CampaignColors.text, shadowElevation = 8.dp) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = action,
                    onValueChange = { value -> action = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("描述你的行动") },
                    placeholder = { Text("例如：我先检查异常信息，再请同行者帮我留意门外动静") },
                    minLines = 2,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = CampaignColors.cyan,
                        focusedBorderColor = world.accent,
                        unfocusedBorderColor = CampaignColors.border,
                        focusedLabelColor = world.accent,
                        unfocusedLabelColor = CampaignColors.muted,
                        focusedPlaceholderColor = CampaignColors.muted,
                        unfocusedPlaceholderColor = CampaignColors.muted,
                    ),
                )
                Text(
                    "主持人会先判断是否需要检定；无需检定时不会掷骰，也不会消耗幸运。",
                    color = CampaignColors.muted,
                    fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { perform(true) },
                        enabled = !busy && !rolling && action.isNotBlank() && save.luck > 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text("带幸运提交（${save.luck}）", color = Color.White) }
                    Button(
                        onClick = { perform(false) },
                        enabled = !busy && !rolling && action.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = world.accent, contentColor = Color.White),
                    ) {
                        Text(
                            when {
                                rolling -> "掷骰中…"
                                busy -> "主持判断中…"
                                else -> "提交行动"
                            },
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

private fun parseCampaignAdjudication(
    raw: String,
    party: List<CharacterSettings>,
): CampaignAdjudication? = runCatching {
    val cleaned = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
        .let { value ->
            val start = value.indexOf('{')
            val end = value.lastIndexOf('}')
            if (start >= 0 && end > start) value.substring(start, end + 1) else value
        }
    val json = JSONObject(cleaned)
    val needsRoll = json.optBoolean("needsRoll", false)
    val requestedRoller = json.optString("roller").trim().lowercase()
    val requestedCharacterId = json.optString("rollerCharacterId").trim()
    val validCharacter = party.firstOrNull { member -> member.characterId == requestedCharacterId }
    val roller = if (needsRoll && requestedRoller == "character" && validCharacter != null) "character" else "user"
    val attitude = json.optString("attitude").trim().lowercase().let { value ->
        if (value in setOf("willing", "hesitant", "unwilling", "not_applicable")) value else "not_applicable"
    }
    CampaignAdjudication(
        needsRoll = needsRoll,
        roller = roller,
        rollerCharacterId = if (roller == "character") validCharacter?.characterId.orEmpty() else "",
        checkName = json.optString("checkName").trim().ifBlank { if (needsRoll) "行动检定" else "无需检定" }.take(30),
        difficulty = json.optInt("difficulty", 12).coerceIn(6, 20),
        attitude = attitude,
        ruling = json.optString("ruling").trim().ifBlank {
            if (needsRoll) "行动存在有意义的不确定性" else "行动可按当前情境自然完成"
        }.take(100),
    )
}.getOrNull()

private fun JSONArray?.stringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}
