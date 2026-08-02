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
    CampaignWorld("occult_city", "雾港失踪案", "都市怪谈 · 调查恐怖", "终年起雾的港城里，每逢午夜都会出现一条不存在的街道。最近失踪的人，都收到过写着自己死亡日期的旧车票。", "硬派调查与都市怪谈。强化潮湿雾气、港口腥味、旧楼回声、路灯死角和逐渐失真的现实。线索必须可追踪，同行者要有不同推理与保护反应。", Color(0xFF38D9F1), true),
    CampaignWorld("fantasy_ruin", "失落王庭", "高魔幻想 · 遗迹史诗", "沉入地底三百年的王城突然重新升起。王座仍在等待继承者，而所有进入王城的人都会逐渐忘记自己的名字。", "史诗奇幻与遗迹冒险。描写宏大尺度、古老魔法、失落礼仪、身份诱惑和阵营选择；关系线与王庭秘密同步推进。", Color(0xFFFFC45B)),
    CampaignWorld("space_derelict", "静默星舰", "太空惊悚 · 生存悬疑", "一艘失联二十年的殖民星舰向你们发送求救信号。登舰后，主脑坚持声称船员仍全部存活。", "冷峻科幻惊悚。强化失重、金属回声、维生系统噪声、终端残片与宇宙孤独；队伍必须真实讨论资源、风险和是否信任主脑。", Color(0xFF7C8CFF), true),
    CampaignWorld("academy_secret", "第十三间教室", "校园怪谈 · 心理恐怖", "学校平面图上只有十二间教室，但每个雨夜，走廊尽头都会出现第十三扇门。门后的课表写着你们所有人的名字。", "高浓度心理恐怖。强化雨声、灯管嗡鸣、粉笔灰、潮湿气味、冷空气、脚步错位、被注视感、不可靠记忆和熟悉场景的细微异常。恐怖逐步逼近，不只依赖血腥。", Color(0xFFFF668F), true),
    CampaignWorld("romance_target_me", "全员都在攻略我", "恋爱修罗场 · 被攻略", "你进入一档无法退出的沉浸式恋爱实验。同行角色都收到秘密任务：七天内让你主动选择他，但每个人隐藏的真实目的不同。", "高张力恋爱修罗场。重点写眼神、距离、试探、偏爱、吃醋、误会与公开场合下的暗流；每位同行者必须主动使用不同攻略方式。", Color(0xFFFF6FAE)),
    CampaignWorld("romance_i_target", "心动对象观察日志", "主动攻略 · 都市恋爱", "你获得一本只显示心动波动、不显示具体数值的观察日志。你只能通过行动和共同经历判断谁正在对你动心。", "克制、暧昧、生活化的都市恋爱。重视聊天节奏、微表情、日常陪伴和未说出口的话；不能轻易表白，要保留未知探索感。", Color(0xFFFFB26B)),
    CampaignWorld("system_mission", "系统说今天必须心动", "系统任务 · 轻喜剧", "一个不太靠谱的系统绑定了你们，天天发布交换身份、假装情侣、敌前演戏和真心话等离谱任务。", "快节奏系统轻喜剧。笑点来自角色性格碰撞、尴尬任务和互相拆台，但不能为了搞笑让人物降智；关键场景仍要有真情绪。", Color(0xFFFFD84D)),
    CampaignWorld("palace_scheme", "今夜谁在宫门外", "古风宫廷 · 权谋关系", "新帝登基后的第一个雪夜，宫门外出现一具没有影子的尸体。你们被卷入储位、旧案与禁军之间的秘密角力。", "古风权谋与克制情感。重视礼法、身份、称谓、沉默和言外之意；阴谋必须可推理，感情通过危险中的选择与信任变化展开。", Color(0xFFD8A45B)),
    CampaignWorld("cyber_memory", "霓虹雨中的假记忆", "赛博都市 · 身份悬疑", "在可以买卖记忆的城市里，你们发现彼此都拥有同一段童年，但那段童年只可能属于一个人。", "赛博朋克身份悬疑。强化霓虹雨、广告噪声、义体触觉和数据残影；核心是谁的记忆被改写，以及队友还能否互相信任。", Color(0xFF37E3B5), true),
    CampaignWorld("apocalypse_store", "废土便利店最后营业日", "末日生存 · 公路治愈", "世界毁灭后的第九年，你们经营荒原上最后一家便利店。某位客人用灾难前的崭新车票买走最后一盒草莓糖。", "末日生存与温柔治愈并存。写风沙、废墟、旧商品、经营日常、互相照顾和微小希望；危险真实但不能持续压抑。", Color(0xFFFF8A5B)),
    CampaignWorld("cultivation_comedy", "小师弟把魔尊契约当话本", "仙侠轻喜剧 · 契约冒险", "一纸写错名字的上古契约，把你们与刚苏醒的魔尊绑在一起。解除契约需要完成九项离谱试炼。", "仙侠冒险与欢喜冤家。保持东方奇幻意象、门派规矩和秘境奇观；笑点来自契约限制、嘴硬与身份反差，战斗和关系变化相互服务。", Color(0xFF8BE0C5)),
    CampaignWorld("time_loop_date", "约会结束前世界会重启", "时间循环 · 恋爱悬疑", "每天晚上十一点五十九分，世界都会回到你们第一次见面的早晨。只有同行小队保留记忆。", "时间循环与恋爱悬疑。重复场景必须通过细节偏差、记忆累积和关系变化产生新鲜感；浪漫来自共同记得，而不是无条件甜宠。", Color(0xFFA98CFF), true),
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

private class CampaignStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("formal_roleplay_campaigns_v2", Context.MODE_PRIVATE)

    fun load(): List<CampaignSave> = runCatching {
        val array = JSONArray(prefs.getString("saves", "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(CampaignSave(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = item.optString("title").ifBlank { "未命名战役" },
                    worldId = item.optString("worldId").ifBlank { CAMPAIGN_WORLDS.first().id },
                    partyIds = item.optJSONArray("partyIds").stringList(),
                    partyNames = item.optJSONArray("partyNames").stringList(),
                    scene = item.optInt("scene", 1), hp = item.optInt("hp", 10),
                    sanity = item.optInt("sanity", 10), luck = item.optInt("luck", 3), clues = item.optInt("clues", 0),
                    narration = item.optString("narration"), log = item.optJSONArray("log").stringList(),
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
    val saveStore = remember { CampaignStore(context) }
    val characters by MigratedDomainStores.characters.settings.collectAsState()
    var saves by remember { mutableStateOf(saveStore.load()) }
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val active = saves.firstOrNull { it.id == activeId }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CampaignColors.top, CampaignColors.bottom))),
    ) {
        if (active == null) {
            CampaignArchive(
                saves = saves,
                onBack = onBack,
                onOpen = { activeId = it },
                onCreate = { creating = true },
                onDelete = { id -> saves = saves.filterNot { it.id == id }.also(saveStore::save) },
            )
        } else {
            CampaignPlay(
                save = active,
                characters = characters,
                gameStore = store,
                onBack = { activeId = null },
                onUpdate = { updated ->
                    saves = saves.map { if (it.id == updated.id) updated else it }
                    saveStore.save(saves)
                },
            )
        }
    }

    if (creating) {
        CreateCampaignDialog(
            characters = characters.values.toList(),
            onDismiss = { creating = false },
            onCreate = { title, world, party ->
                val save = CampaignSave(
                    id = UUID.randomUUID().toString(),
                    title = title.ifBlank { world.title },
                    worldId = world.id,
                    partyIds = party.map { it.characterId },
                    partyNames = party.map { it.displayName },
                    narration = "你们抵达了故事的入口。空气里已经有某种东西先一步注意到了你们。",
                )
                saves = listOf(save) + saves
                saveStore.save(saves)
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = CampaignColors.text) }
            Column(Modifier.weight(1f)) {
                Text("战役档案", color = CampaignColors.text, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("TRPG CAMPAIGNS", color = CampaignColors.cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            FilledTonalButton(onClick = onCreate) {
                Icon(Icons.Outlined.Add, null)
                Text("新建")
            }
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
                items(saves, key = { it.id }) { save ->
                    val world = CAMPAIGN_WORLDS.firstOrNull { it.id == save.worldId } ?: CAMPAIGN_WORLDS.first()
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
                                MiniStat("SCENE", save.scene.toString(), world.accent)
                                MiniStat("生命", save.hp.toString(), CampaignColors.rose)
                                MiniStat("理智", save.sanity.toString(), CampaignColors.violet)
                                MiniStat("幸运", save.luck.toString(), CampaignColors.amber)
                                MiniStat("线索", save.clues.toString(), CampaignColors.mint)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.MiniStat(label: String, value: String, accent: Color) {
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
    val world = CAMPAIGN_WORLDS.first { it.id == worldId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("建立战役档案") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedTextField(title, { title = it }, label = { Text("战役名称") }, modifier = Modifier.fillMaxWidth()) }
                item { Text("选择世界", fontWeight = FontWeight.Bold) }
                items(CAMPAIGN_WORLDS, key = { it.id }) { item ->
                    Surface(
                        Modifier.fillMaxWidth().clickable { worldId = item.id },
                        shape = RoundedCornerShape(15.dp),
                        color = if (worldId == item.id) item.accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, if (worldId == item.id) item.accent else Color.Transparent),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(item.title, fontWeight = FontWeight.Bold)
                            Text(item.tag, color = item.accent, fontSize = 12.sp)
                            Text(item.premise, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Text("选择同行角色（1—3人）", fontWeight = FontWeight.Bold) }
                items(characters, key = { it.characterId }) { character ->
                    FilterChip(
                        selected = character.characterId in partyIds,
                        onClick = {
                            partyIds = if (character.characterId in partyIds) partyIds - character.characterId
                            else if (partyIds.size < 3) partyIds + character.characterId else partyIds
                        },
                        label = { Text(character.displayName) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = partyIds.isNotEmpty(),
                onClick = { onCreate(title.trim(), world, characters.filter { it.characterId in partyIds }) },
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
    val world = CAMPAIGN_WORLDS.firstOrNull { it.id == save.worldId } ?: CAMPAIGN_WORLDS.first()
    val party = save.partyIds.map { characters[it] ?: MigratedDomainStores.characters.get(it) }
    val scope = rememberCoroutineScope()
    var action by rememberSaveable(save.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var rolling by remember { mutableStateOf(false) }
    var rollingFace by remember { mutableIntStateOf(1) }
    var lastRoll by remember { mutableIntStateOf(0) }
    var lastResult by remember { mutableStateOf("") }

    fun perform(useLuck: Boolean) {
        val clean = action.trim()
        if (clean.isBlank() || busy || rolling || (useLuck && save.luck <= 0)) return
        scope.launch {
            rolling = true
            repeat(15) { index ->
                rollingFace = Random.nextInt(1, 21)
                delay(45L + index * 7L)
            }
            val first = Random.nextInt(1, 21)
            val second = if (useLuck) Random.nextInt(1, 21) else first
            val roll = maxOf(first, second)
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
            val partyPrompt = party.joinToString("\n") { "- ${it.displayName}：${it.persona.ifBlank { "按其既有角色设定行动" }}" }
            val history = save.log.takeLast(8).joinToString("\n")
            val facts = """
                正式跑团战役：《${save.title}》
                世界：${world.title}
                世界前提：${world.premise}
                文风规则：${world.style}
                当前场景：${save.scene}
                状态：生命${save.hp}/10，理智${save.sanity}/10，幸运${save.luck}，线索${save.clues}
                用户本轮行动：$clean
                程序锁定判定：d20=$roll，难度=$difficulty，结果=$result${if (useLuck) "（已消耗幸运，取两次较高值）" else ""}
                同行角色：
                $partyPrompt
                最近记录：
                $history
                上一段叙事：
                ${save.narration.takeLast(1800)}
            """.trimIndent()
            LuluAiServices.gateway.generate(
                characterId = party.firstOrNull()?.characterId ?: "lulu",
                facts = facts,
                instruction = """
                    你是成熟的跑团主持人与小说叙事者。必须服从程序骰点，不得改骰或把失败偷换成成功。
                    “你”只代表用户本人；同行角色必须直接写名字，禁止用含混的“我”混淆视角。
                    输出约800—1400个汉字的沉浸式正文，即使动作很小，也要用环境、五感、空间距离、微动作、心理压力、潜台词和后果写出过程。
                    同行角色是主角小队，不是背景挂件。每轮至少出现两次有效互动：主动发现、提醒、保护、争执、分工、试探、情绪反应或关系变化；每个人必须符合自己的persona。
                    严格执行当前世界的文风。恐怖世界强化未知、寂静、错位和心理压迫；恋爱世界强化距离、试探、偏爱与未说出口的话；轻喜剧不能让角色降智；权谋必须讲身份与言外之意。
                    失败必须产生真实后果，但仍给出新的信息、危险或可行动方向。结尾自然留下2—4个可尝试方向，不要写系统解释、数值面板或“主持人说”。
                """.trimIndent(),
                source = "游戏",
                title = "跑团 · ${world.title}",
                temperature = 0.86,
                maxTokens = 1900,
            ).onSuccess { reply ->
                val hpDelta = when { roll == 1 -> -2; result == "失败" && Random.nextBoolean() -> -1; else -> 0 }
                val sanityDelta = when { world.sanityRisk && roll == 1 -> -2; world.sanityRisk && result == "失败" -> -1; else -> 0 }
                val clueDelta = when { roll == 20 -> 2; result == "成功" -> 1; else -> 0 }
                val entry = "SCENE ${save.scene}｜行动：$clean｜d20=$roll $result"
                val updated = save.copy(
                    scene = save.scene + 1,
                    hp = (save.hp + hpDelta).coerceIn(0, 10),
                    sanity = (save.sanity + sanityDelta).coerceIn(0, 10),
                    luck = (save.luck - if (useLuck) 1 else 0).coerceAtLeast(0),
                    clues = save.clues + clueDelta,
                    narration = reply.text,
                    log = (save.log + entry + reply.text.take(500)).takeLast(80),
                    updatedAt = System.currentTimeMillis(),
                )
                val recordId = gameStore.recordExternalGame(
                    LuluGameType.RoleplayAdventure,
                    "跑团 · ${world.title} · 第${save.scene}幕",
                    roll * 5,
                    if (result == "成功" || result == "大成功") 8 else 3,
                    "在${world.title}执行“$clean”，d20=$roll，$result。",
                    JSONObject().put("world", world.title).put("scene", save.scene).put("action", clean)
                        .put("roll", roll).put("difficulty", difficulty).put("result", result)
                        .put("party", JSONArray(save.partyNames)).put("narration", reply.text).toString(),
                )
                gameStore.attachCharacterReply(recordId, reply.text)
                onUpdate(updated)
                action = ""
            }.onFailure { error -> lastResult = error.message ?: "本轮生成失败，请重试" }
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
                    MiniStat("生命", save.hp.toString(), CampaignColors.rose)
                    MiniStat("理智", save.sanity.toString(), CampaignColors.violet)
                    MiniStat("幸运", save.luck.toString(), CampaignColors.amber)
                    MiniStat("线索", save.clues.toString(), CampaignColors.mint)
                }
            }
            item {
                Surface(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = CampaignColors.panel,
                    border = BorderStroke(1.dp, world.accent.copy(alpha = 0.52f)),
                ) {
                    Text(
                        save.narration,
                        Modifier.padding(18.dp),
                        color = CampaignColors.text,
                        fontSize = 16.sp,
                        lineHeight = 27.sp,
                    )
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
                    onValueChange = { action = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("你要做什么？") },
                    minLines = 2,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = CampaignColors.cyan,
                        focusedBorderColor = world.accent, unfocusedBorderColor = CampaignColors.border,
                        focusedLabelColor = world.accent, unfocusedLabelColor = CampaignColors.muted,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { perform(true) }, enabled = !busy && !rolling && action.isNotBlank() && save.luck > 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text("消耗幸运（${save.luck}）", color = Color.White) }
                    Button(
                        onClick = { perform(false) }, enabled = !busy && !rolling && action.isNotBlank(),
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
    return buildList { for (i in 0 until length()) optString(i).takeIf(String::isNotBlank)?.let(::add) }
}
