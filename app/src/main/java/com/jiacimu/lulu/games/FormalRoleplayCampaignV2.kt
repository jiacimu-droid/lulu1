package com.jiacimu.lulu.games

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
    CampaignWorld("apocalypse_store", "废土便利店最后营业日", "末日生存 · 公路治愈", "末日第九年，你们经营荒原最后一家便利店，客人用灾难前的崭新车票买走最后一盒草莓糖。", "末日生存与温柔治愈并存。写风沙、废墟、旧商品、经营日常、互相照顾和微小希望；危险真实但不持续压抑。", Color(0xFFFF8A5B)),
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
    val narration: String,
    val log: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
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
        "apocalypse_store" -> "风暴季来临前，你们守着荒原最后一家仍亮灯的便利店。傍晚，一个穿旧校服的孩子用灾难前的崭新车票买走最后一盒草莓糖，随后走向早已断裂的公路。收银机却自动打印出一张地图，标记着二十公里外本不该存在的列车站。夜幕中，远处传来了火车鸣笛。"
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
    "apocalypse_store" -> listOf("追上买糖的孩子", "研究收银机地图", "加固便利店准备风暴", "询问同行者是否听到火车")
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
                .put("luck", value.luck).put("clues", value.clues).put("narration", value.narration)
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
    var saves by remember { mutableStateOf(campaignStore.load()) }
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val active = saves.firstOrNull { save -> save.id == activeId }

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
    onDismiss: () -> Unit,
    onCreate: (String, CampaignWorld, List<CharacterSettings>) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var worldId by remember { mutableStateOf(CAMPAIGN_WORLDS.first().id) }
    var partyIds by remember { mutableStateOf(setOf<String>()) }
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
                item { Text("选择同行角色（1—3人）", fontWeight = FontWeight.Bold) }
                items(characters, key = CharacterSettings::characterId) { character ->
                    FilterChip(
                        selected = character.characterId in partyIds,
                        onClick = {
                            partyIds = when {
                                character.characterId in partyIds -> partyIds - character.characterId
                                partyIds.size < 3 -> partyIds + character.characterId
                                else -> partyIds
                            }
                        },
                        label = { Text(character.displayName) },
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
            rolling = true
            repeat(15) { index ->
                rollingFace = Random.nextInt(1, 21)
                delay(45L + index * 7L)
            }
            val firstRoll = Random.nextInt(1, 21)
            val secondRoll = if (useLuck) Random.nextInt(1, 21) else firstRoll
            val roll = maxOf(firstRoll, secondRoll)
            rollingFace = roll
            lastRoll = roll
            delay(260)
            rolling = false

            val difficulty = (10 + save.scene / 2).coerceIn(10, 17)
            val result = when {
                roll == 20 -> "大成功"
                roll == 1 -> "大失败"
                roll >= difficulty -> "成功"
                else -> "失败"
            }
            lastResult = result
            busy = true
            val partyPrompt = party.joinToString("\n") { member ->
                "- ${member.displayName}：${member.persona.ifBlank { "按其既有角色设定行动" }}"
            }
            val facts = """
                正式跑团：《${save.title}》
                世界：${world.title}
                前提：${world.premise}
                文风：${world.style}
                当前场景：${save.scene}
                状态：生命${save.hp}/10，理智${save.sanity}/10，幸运${save.luck}，线索${save.clues}
                用户行动：$cleanAction
                程序判定：d20=$roll，难度=$difficulty，结果=$result${if (useLuck) "（已消耗幸运，取两次较高值）" else ""}
                同行角色：
                $partyPrompt
                最近记录：
                ${save.log.takeLast(8).joinToString("\n")}
                上一段叙事：
                ${save.narration.takeLast(2600)}
            """.trimIndent()
            val instruction = """
                你是成熟的跑团主持人与小说叙事者。必须服从程序骰点，不得改骰或把失败偷换成成功。
                “你”只代表用户本人；同行角色必须直接写名字，禁止用含混的“我”混淆视角。
                输出约700—1200个汉字的完整沉浸正文。先写行动如何发生，再写骰点结果造成的具体后果；使用环境、五感、空间距离、微动作、心理压力和潜台词。
                同行角色是主角小队，每轮至少发生两次有效互动，每个人必须符合persona，不能变成只会附和的背景板。
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
                )
            }

            generation.onSuccess { reply ->
                if (reply.text.isBlank()) {
                    lastResult = "$result · 叙事暂时中断，原行动已保留，可再次掷骰"
                    busy = false
                    return@onSuccess
                }
                val hpDelta = when { roll == 1 -> -2; result == "失败" && Random.nextBoolean() -> -1; else -> 0 }
                val sanityDelta = when { world.sanityRisk && roll == 1 -> -2; world.sanityRisk && result == "失败" -> -1; else -> 0 }
                val clueDelta = when { roll == 20 -> 2; result == "成功" -> 1; else -> 0 }
                val updated = save.copy(
                    scene = save.scene + 1,
                    hp = (save.hp + hpDelta).coerceIn(0, 10),
                    sanity = (save.sanity + sanityDelta).coerceIn(0, 10),
                    luck = (save.luck - if (useLuck) 1 else 0).coerceAtLeast(0),
                    clues = save.clues + clueDelta,
                    narration = reply.text,
                    log = (save.log + "SCENE ${save.scene}｜$cleanAction｜d20=$roll $result\n${reply.text.take(500)}").takeLast(80),
                    updatedAt = System.currentTimeMillis(),
                )
                val recordId = gameStore.recordExternalGame(
                    LuluGameType.RoleplayAdventure,
                    "跑团 · ${world.title} · 第${save.scene}幕",
                    roll * 5,
                    if (result == "成功" || result == "大成功") 8 else 3,
                    "在${world.title}执行“$cleanAction”，d20=$roll，$result。",
                    JSONObject().put("world", world.title).put("scene", save.scene).put("action", cleanAction)
                        .put("roll", roll).put("difficulty", difficulty).put("result", result)
                        .put("party", JSONArray(save.partyNames)).put("narration", reply.text).toString(),
                )
                gameStore.attachCharacterReply(recordId, reply.text)
                onUpdate(updated)
                action = ""
            }
            generation.onFailure {
                lastResult = "$result · 叙事暂时中断，原行动已保留，可再次尝试"
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
                }
            }
            item {
                Surface(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = CampaignColors.panel,
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
            if (rolling || lastRoll > 0) {
                item {
                    Surface(
                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                        color = CampaignColors.violet.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, CampaignColors.violet.copy(alpha = 0.55f)),
                    ) {
                        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (rolling) "D20 正在滚动" else "D20 · $lastResult", color = CampaignColors.muted)
                            Text((if (rolling) rollingFace else lastRoll).toString(), color = CampaignColors.text, fontSize = 42.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
        Surface(color = CampaignColors.panel, shadowElevation = 8.dp) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = action,
                    onValueChange = { value -> action = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("描述你的行动") },
                    placeholder = { Text("例如：我先检查那条异常信息，再问同行者有没有注意到别的细节") },
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { perform(true) },
                        enabled = !busy && !rolling && action.isNotBlank() && save.luck > 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text("消耗幸运（${save.luck}）", color = Color.White) }
                    Button(
                        onClick = { perform(false) },
                        enabled = !busy && !rolling && action.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = world.accent, contentColor = Color.White),
                    ) { Text(if (busy) "主持中…" else if (rolling) "掷骰中…" else "掷 d20 并行动", color = Color.White) }
                }
            }
        }
    }
}

private fun JSONArray?.stringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}
