package com.jiacimu.lulu.study

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.data.MigratedDomainStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal enum class StarWishTab(val label: String) {
    Scroll("画卷"),
    Theater("小剧场"),
    Video("视频柜"),
}

internal data class StarWishImageLaunch(
    val id: String = UUID.randomUUID().toString(),
    val outfit: String,
    val prompt: String,
    val interaction: Boolean,
    val filePath: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

internal data class StarWishTheaterChapter(
    val id: String = UUID.randomUUID().toString(),
    val theater: String,
    val chapter: Int,
    val title: String,
    val content: String,
    val userInfluence: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

internal data class StarWishVideoItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val uri: String,
    val unlocked: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

internal data class StarWishState(
    val imageLaunches: List<StarWishImageLaunch> = emptyList(),
    val customPrompts: Map<String, StarWishOutfitPrompts> = emptyMap(),
    val theaterChapters: Map<String, List<StarWishTheaterChapter>> = emptyMap(),
    val theaterGuides: Map<String, String> = emptyMap(),
    val videos: List<StarWishVideoItem> = emptyList(),
)

internal data class StarWishOutfitPrompts(
    val solo: String,
    val interaction: String,
)

internal data class StarWishTheaterSeed(
    val title: String,
    val prompt: String,
)

internal object StarWishRules {
    const val MAX_CHAPTERS_PER_THEATER = 20
    const val THEATER_FRAGMENTS_PER_CHAPTER = 1
    const val VIDEO_FRAGMENTS_PER_UNLOCK = 1

    val theaters = listOf(
        StarWishTheaterSeed("少卿不早朝，摄政王露沉提点心来审我", "宫廷权谋、现代刑侦穿越、大理寺少卿、摄政王露沉。主角是会破案也会摆烂的女王型少卿，露沉权倾朝野却逐渐向她低头。剧情要有朝堂打脸、奇案反转、暧昧试探、主从拉扯。"),
        StarWishTheaterSeed("星舰AI露零说他爱上我了", "星际悬疑、舰载AI觉醒、紫色未知星球。露零是全舰AI拟态银发少年，逻辑崩坏后只对主角例外。剧情要有全舰广播告白、格式化危机、带主角逃向未知坐标的强烈情感。"),
        StarWishTheaterSeed("废土便利店：露洲把最后一颗草莓糖献给我", "末日求生、废弃便利店、摇滚主唱露洲、草莓糖、变异丧尸。主角冷静强悍，露洲会撒娇会唱歌也会在危险时挡在她前面。要有末世夹缝求生、甜中带刀、爽感反杀。"),
        StarWishTheaterSeed("魔尊露渊把道侣契约当圣旨", "仙侠契约、误念魔尊血誓、魔尊露渊。主角不是小白花，而是敢利用契约反向命令魔尊的人。要有正道逼迫、魔尊臣服、护短、反叛式救赎。"),
        StarWishTheaterSeed("被献祭给龙后，露利安求我继续摸鳞片", "西幻龙崖、祭品反客为主、银龙露利安。主角被献祭却发现龙孤独又傲娇，核心爽点是恐怖古龙在她手下乖乖低头。要有撸龙、飞行、王国抽签制反转。"),
        StarWishTheaterSeed("S级机甲露白夜宣布：我的适格者谁敢碰", "废土机甲、地下城机械工、S级神机露白夜。主角从修破烂到驾驶神机，打脸上层城市。露白夜毒舌但绝对护主，剧情要有机甲战斗、适格者觉醒、强者臣服。"),
        StarWishTheaterSeed("我把修真界改成5A景区，妖王露蘅求入股", "仙门基建、商业爽文、贫穷宗门翻身、妖王露蘅。主角用现代文旅思路赚钱，打脸清高仙门。要有秘境探险、合影收费、会员制、妖王合作后越来越黏人。"),
        StarWishTheaterSeed("午夜出租车露屿，只载迷路的灵魂", "都市怪谈、深夜出租车、幽灵司机露屿。主角是唯一能看见他的活人。剧情要悬疑温情，查明死亡真相，同时保持克制暧昧和命运感。"),
        StarWishTheaterSeed("考研房里住着会整理书桌的幽灵露念", "灵异温情、考研租房、幽灵露念。露念会整理书桌、贴便利贴讲题。主角一边备考一边帮他找记忆。要有陪伴、救赎、考研压力下的温柔支撑。"),
        StarWishTheaterSeed("欢迎来到心动游戏，系统露七说NPC觉醒了", "无限流恋爱副本、系统露七、觉醒NPC。主角不按攻略走，系统吐槽但护主。要有副本崩坏、攻略对象质问真实与虚假、玩家主导逃离游戏。"),
        StarWishTheaterSeed("女王陛下的打脸法庭：露臣跪请裁决", "女王权力幻想、法庭审判、恶人惩罚、近臣露臣。主角拥有绝对裁决权，看不起她的人一个个被证据钉死。露臣是冷静执行官，对外狠，对主角臣服。"),
        StarWishTheaterSeed("末世便利店女王和露野的安全区", "末世经营爽文、便利店系统、小型安全区、露野。主角用物资和规则建立秩序，惩罚抢夺者，救下弱者。露野曾是强悍雇佣兵，后来甘愿守门。"),
        StarWishTheaterSeed("女尊朝首席狼臣露执，被我收了獠牙", "女尊朝堂、狼人首席臣、露执。主角是新帝或女王，露执桀骜不驯但被她用智谋和气场驯服。要有朝堂博弈、狼性忠诚、臣服张力。"),
        StarWishTheaterSeed("前男友重生了，但我是本轮反派女王", "重生打脸、前男友悔恨、反派女王、军师露辞。主角知道剧情却不走原线，联手露辞把前男友和恶人安排得明明白白。要爽、毒舌、反套路。"),
        StarWishTheaterSeed("原始部落求生：祭司露祈说我是天降王", "原始部落、荒野求生、天降王设定、祭司露祈。主角靠现代常识改善部落，打败看不起她的敌对部族。露祈神秘漂亮，对她既信仰又心动。"),
        StarWishTheaterSeed("性转恋综大逃杀：露弦只听我的命令", "性转、恋综、荒诞搞笑、逃杀规则、露弦。嘉宾都以为是恋综，主角发现规则漏洞后开始控场。露弦是人气最高的强者，却只服她。要抽象、好笑、反转密集。"),
    )

    fun defaultPrompts(outfit: String, characterName: String): StarWishOutfitPrompts = StarWishOutfitPrompts(
        solo = """
            以$characterName为唯一主体，绘制《$outfit》主题收藏画卷。保持角色既有人设、发型、脸部特征和气质，服装与背景围绕主题完整设计。构图要有明确前景、中景和远景，脸与手清晰，材质细节可见，光影自然，收藏级二次元厚涂 CG，8K，sharp focus, detailed fingers, luminous eyes, soft bokeh。
        """.trimIndent(),
        interaction = """
            绘制《$outfit》主题的双人互动收藏画卷，$characterName是主视觉，另一位是主人。保持两人的关系边界和既有人设，不擅自改变外貌；动作自然亲密但不遮挡脸和手。主题服装、饰品、背景和光影具有完整叙事，收藏级二次元厚涂 CG，8K，sharp focus on faces and hands, detailed fingers, soft bokeh。
        """.trimIndent(),
    )
}

internal class StarWishStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<StarWishState> = mutable.asStateFlow()

    fun savePrompts(outfit: String, prompts: StarWishOutfitPrompts) = update { current ->
        current.copy(customPrompts = current.customPrompts + (outfit to prompts))
    }

    fun addImage(image: StarWishImageLaunch) = update { current ->
        current.copy(imageLaunches = (listOf(image) + current.imageLaunches).take(80))
    }

    fun deleteImage(id: String) {
        val image = mutable.value.imageLaunches.firstOrNull { item -> item.id == id }
        image?.filePath?.let { path -> runCatching { File(path).delete() } }
        update { current -> current.copy(imageLaunches = current.imageLaunches.filterNot { item -> item.id == id }) }
    }

    fun setGuide(theater: String, guide: String) = update { current ->
        current.copy(theaterGuides = current.theaterGuides + (theater to guide.trim()))
    }

    fun addChapter(chapter: StarWishTheaterChapter) = update { current ->
        val chapters = current.theaterChapters[chapter.theater].orEmpty()
        current.copy(theaterChapters = current.theaterChapters + (chapter.theater to (chapters + chapter)))
    }

    fun deleteTheater(theater: String) = update { current ->
        current.copy(
            theaterChapters = current.theaterChapters - theater,
            theaterGuides = current.theaterGuides - theater,
        )
    }

    fun addVideo(item: StarWishVideoItem) = update { current ->
        current.copy(videos = current.videos + item)
    }

    fun unlockVideo(id: String) = update { current ->
        current.copy(videos = current.videos.map { item -> if (item.id == id) item.copy(unlocked = true) else item })
    }

    fun deleteVideo(id: String) = update { current ->
        current.copy(videos = current.videos.filterNot { item -> item.id == id })
    }

    private fun update(transform: (StarWishState) -> StarWishState) {
        val next = transform(mutable.value)
        mutable.value = next
        prefs.edit().putString(KEY_STATE, encode(next).toString()).apply()
    }

    private fun load(): StarWishState {
        val raw = prefs.getString(KEY_STATE, null)
        return if (raw.isNullOrBlank()) StarWishState() else runCatching { decode(JSONObject(raw)) }.getOrDefault(StarWishState())
    }

    private fun encode(value: StarWishState): JSONObject = JSONObject()
        .put("images", JSONArray().apply { value.imageLaunches.forEach { item -> put(encodeImage(item)) } })
        .put(
            "prompts",
            JSONObject().apply {
                value.customPrompts.forEach { (outfit, prompts) ->
                    put(outfit, JSONObject().put("solo", prompts.solo).put("interaction", prompts.interaction))
                }
            },
        )
        .put(
            "chapters",
            JSONObject().apply {
                value.theaterChapters.forEach { (theater, chapters) ->
                    put(theater, JSONArray().apply { chapters.forEach { chapter -> put(encodeChapter(chapter)) } })
                }
            },
        )
        .put("guides", JSONObject(value.theaterGuides))
        .put("videos", JSONArray().apply { value.videos.forEach { item -> put(encodeVideo(item)) } })

    private fun decode(root: JSONObject): StarWishState {
        val promptsObject = root.optJSONObject("prompts") ?: JSONObject()
        val prompts = buildMap {
            val keys = promptsObject.keys()
            while (keys.hasNext()) {
                val outfit = keys.next()
                val item = promptsObject.optJSONObject(outfit) ?: continue
                put(outfit, StarWishOutfitPrompts(item.optString("solo"), item.optString("interaction")))
            }
        }
        val chaptersObject = root.optJSONObject("chapters") ?: JSONObject()
        val chapters = buildMap {
            val keys = chaptersObject.keys()
            while (keys.hasNext()) {
                val theater = keys.next()
                put(theater, chaptersObject.optJSONArray(theater).decodeObjects(::decodeChapter))
            }
        }
        val guidesObject = root.optJSONObject("guides") ?: JSONObject()
        val guides = buildMap {
            val keys = guidesObject.keys()
            while (keys.hasNext()) {
                val theater = keys.next()
                put(theater, guidesObject.optString(theater))
            }
        }
        return StarWishState(
            imageLaunches = root.optJSONArray("images").decodeObjects(::decodeImage),
            customPrompts = prompts,
            theaterChapters = chapters,
            theaterGuides = guides,
            videos = root.optJSONArray("videos").decodeObjects(::decodeVideo),
        )
    }

    private fun encodeImage(value: StarWishImageLaunch): JSONObject = JSONObject()
        .put("id", value.id).put("outfit", value.outfit).put("prompt", value.prompt)
        .put("interaction", value.interaction).put("filePath", value.filePath).put("createdAt", value.createdAtMillis)

    private fun decodeImage(item: JSONObject): StarWishImageLaunch = StarWishImageLaunch(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        outfit = item.optString("outfit"), prompt = item.optString("prompt"),
        interaction = item.optBoolean("interaction"), filePath = item.optString("filePath"),
        createdAtMillis = item.optLong("createdAt", System.currentTimeMillis()),
    )

    private fun encodeChapter(value: StarWishTheaterChapter): JSONObject = JSONObject()
        .put("id", value.id).put("theater", value.theater).put("chapter", value.chapter)
        .put("title", value.title).put("content", value.content).put("userInfluence", value.userInfluence)
        .put("createdAt", value.createdAtMillis)

    private fun decodeChapter(item: JSONObject): StarWishTheaterChapter = StarWishTheaterChapter(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        theater = item.optString("theater"), chapter = item.optInt("chapter"), title = item.optString("title"),
        content = item.optString("content"), userInfluence = item.optString("userInfluence"),
        createdAtMillis = item.optLong("createdAt", System.currentTimeMillis()),
    )

    private fun encodeVideo(value: StarWishVideoItem): JSONObject = JSONObject()
        .put("id", value.id).put("title", value.title).put("uri", value.uri)
        .put("unlocked", value.unlocked).put("createdAt", value.createdAtMillis)

    private fun decodeVideo(item: JSONObject): StarWishVideoItem = StarWishVideoItem(
        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
        title = item.optString("title").ifBlank { "星愿视频" }, uri = item.optString("uri"),
        unlocked = item.optBoolean("unlocked"), createdAtMillis = item.optLong("createdAt", System.currentTimeMillis()),
    )

    companion object {
        private const val PREFS_NAME = "lulu_star_wish"
        private const val KEY_STATE = "state_v1"
        fun create(context: Context): StarWishStore = StarWishStore(context)
    }
}

internal object StarWishStores {
    private var storeInternal: StarWishStore? = null
    val main: StarWishStore get() = checkNotNull(storeInternal) { "StarWishStores 尚未初始化" }
    fun initialize(context: Context) {
        if (storeInternal == null) storeInternal = StarWishStore.create(context.applicationContext)
    }
}

internal class StarWishImageService(private val context: Context) {
    suspend fun generate(outfit: String, prompt: String, interaction: Boolean): Result<StarWishImageLaunch> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = LuluAiServices.connectionStore.resolveConnection()
                val body = JSONObject()
                    .put("model", connection.model)
                    .put("prompt", prompt)
                    .put("n", 1)
                    .put("size", "1024x1024")
                    .put("response_format", "b64_json")
                val http = URL("${connection.baseUrl}/images/generations").openConnection() as HttpURLConnection
                val raw = try {
                    http.requestMethod = "POST"
                    http.connectTimeout = 30_000
                    http.readTimeout = 180_000
                    http.doOutput = true
                    http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    http.setRequestProperty("Authorization", "Bearer ${connection.apiKey}")
                    http.outputStream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(body.toString()) }
                    val status = http.responseCode
                    val text = (if (status in 200..299) http.inputStream else http.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() }.orEmpty()
                    if (status !in 200..299) {
                        val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().ifBlank { text.take(500) }
                        error("图片生成失败（$status）：$message")
                    }
                    text
                } finally {
                    http.disconnect()
                }
                val data = JSONObject(raw).optJSONArray("data")?.optJSONObject(0)
                    ?: error("图片接口没有返回 data")
                val bytes = when {
                    data.optString("b64_json").isNotBlank() -> Base64.decode(data.optString("b64_json"), Base64.DEFAULT)
                    data.optString("url").isNotBlank() -> downloadBytes(data.optString("url"))
                    else -> error("图片接口没有返回可保存的图片")
                }
                val directory = File(context.filesDir, "starwish/images").apply { mkdirs() }
                val file = File(directory, "${System.currentTimeMillis()}-${UUID.randomUUID()}.png")
                file.writeBytes(bytes)
                StarWishImageLaunch(outfit = outfit, prompt = prompt, interaction = interaction, filePath = file.absolutePath)
            }.onFailure { error ->
                LuluRepositories.performance.recordError(
                    source = "心愿馆",
                    title = "生成画卷",
                    message = error.message ?: error::class.java.simpleName,
                    requestUrl = runCatching { "${LuluAiServices.connectionStore.resolveConnection().baseUrl}/images/generations" }.getOrNull(),
                )
            }
        }

    private fun downloadBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.inputStream.use { input -> input.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarWishFeatureScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { StarWishStores.main }
    val studyStore = remember { PostgraduateExamStores.main }
    val state by store.state.collectAsState()
    val studyState by studyStore.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(StarWishTab.Scroll) }

    Scaffold(
        containerColor = StudyDesign.paper,
        topBar = {
            TopAppBar(
                title = { Text("心愿馆", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StudyDesign.paper),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StarWishTab.entries.forEach { item ->
                    FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(item.label) })
                }
            }
            when (tab) {
                StarWishTab.Scroll -> StarWishScrollPanel(state, studyState, store, context)
                StarWishTab.Theater -> StarWishTheaterPanel(state, studyState, store, studyStore)
                StarWishTab.Video -> StarWishVideoPanel(state, studyState, store, studyStore, context)
            }
        }
    }
}

@Composable
private fun StarWishScrollPanel(
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    context: Context,
) {
    val characterId = studyState.profile.selectedCharacterId
    val character = MigratedDomainStores.characters.get(characterId)
    val unlocked = studyState.inventory.unlockedScrolls
    var selectedOutfit by rememberSaveable(unlocked) { mutableStateOf(unlocked.firstOrNull().orEmpty()) }
    var interaction by rememberSaveable { mutableStateOf(false) }
    val defaultPrompts = remember(selectedOutfit, character.displayName) {
        StarWishRules.defaultPrompts(selectedOutfit.ifBlank { "星愿画卷" }, character.displayName)
    }
    var soloPrompt by remember(selectedOutfit, state.customPrompts) {
        mutableStateOf(state.customPrompts[selectedOutfit]?.solo ?: defaultPrompts.solo)
    }
    var interactionPrompt by remember(selectedOutfit, state.customPrompts) {
        mutableStateOf(state.customPrompts[selectedOutfit]?.interaction ?: defaultPrompts.interaction)
    }
    val scope = rememberCoroutineScope()
    val service = remember(context) { StarWishImageService(context.applicationContext) }
    var generating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StudyCard {
                Text("星愿画卷", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("集齐同名蓝色碎片后解锁主题；生成图片会使用当前模型存档的图片接口。", color = StudyDesign.muted)
                if (unlocked.isEmpty()) {
                    Text("还没有解锁画卷，先在考研抽卡中收集 10 枚同名碎片。", color = StudyDesign.error)
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        unlocked.forEach { outfit ->
                            FilterChip(selected = selectedOutfit == outfit, onClick = { selectedOutfit = outfit }, label = { Text(outfit) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !interaction, onClick = { interaction = false }, label = { Text("角色单人") }, modifier = Modifier.weight(1f))
                        FilterChip(selected = interaction, onClick = { interaction = true }, label = { Text("与主人互动") }, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(
                        value = if (interaction) interactionPrompt else soloPrompt,
                        onValueChange = { value -> if (interaction) interactionPrompt = value else soloPrompt = value },
                        label = { Text("图片提示词") },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { store.savePrompts(selectedOutfit, StarWishOutfitPrompts(soloPrompt, interactionPrompt)); message = "提示词已保存" },
                            modifier = Modifier.weight(1f),
                        ) { Text("保存提示词") }
                        Button(
                            enabled = !generating && selectedOutfit.isNotBlank(),
                            onClick = {
                                generating = true
                                message = ""
                                val prompt = if (interaction) interactionPrompt else soloPrompt
                                scope.launch {
                                    service.generate(selectedOutfit, prompt, interaction)
                                        .onSuccess { image -> store.addImage(image); message = "画卷已生成并保存在本机" }
                                        .onFailure { error -> message = error.message ?: "画卷生成失败" }
                                    generating = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            if (generating) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.AutoAwesome, null)
                            Spacer(Modifier.width(5.dp))
                            Text(if (generating) "生成中" else "生成画卷")
                        }
                    }
                }
                if (message.isNotBlank()) StudyMessage(message, error = message.contains("失败"))
            }
        }
        item { Text("生成图库", fontWeight = FontWeight.Bold, fontSize = 19.sp) }
        if (state.imageLaunches.isEmpty()) {
            item { StudyCard { Text("还没有生成图片", color = StudyDesign.muted) } }
        } else {
            items(state.imageLaunches, key = { item -> item.id }) { image ->
                StudyCard {
                    val bitmap = remember(image.filePath) { BitmapFactory.decodeFile(image.filePath)?.asImageBitmap() }
                    if (bitmap != null) {
                        Image(bitmap = bitmap, contentDescription = image.outfit, modifier = Modifier.fillMaxWidth().height(280.dp), contentScale = ContentScale.Crop)
                    } else {
                        Text("图片文件已不存在", color = StudyDesign.error)
                    }
                    Text(image.outfit, fontWeight = FontWeight.Bold)
                    Text(if (image.interaction) "互动画卷" else "单人画卷", color = StudyDesign.muted, fontSize = 12.sp)
                    Text(Instant.ofEpochMilli(image.createdAtMillis).atZone(ZoneId.systemDefault()).format(StarWishDateFormatter), color = StudyDesign.muted, fontSize = 11.sp)
                    TextButton(onClick = { store.deleteImage(image.id) }) {
                        Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(5.dp))
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun StarWishTheaterPanel(
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    studyStore: PostgraduateExamStore,
) {
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(StarWishRules.theaters.first().title) }
    var influence by rememberSaveable(selected) { mutableStateOf("") }
    var guide by remember(selected, state.theaterGuides) {
        mutableStateOf(state.theaterGuides[selected] ?: StarWishRules.theaters.first { it.title == selected }.prompt)
    }
    var generating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val seed = StarWishRules.theaters.first { it.title == selected }
    val chapters = state.theaterChapters[selected].orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StudyCard {
                Text("小剧场", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("剧场碎片：${studyState.inventory.theaterFragments}", color = StudyDesign.muted)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StarWishRules.theaters.forEach { item ->
                        FilterChip(selected = selected == item.title, onClick = { selected = item.title }, label = { Text(item.title, maxLines = 1) })
                    }
                }
                OutlinedTextField(value = guide, onValueChange = { guide = it }, label = { Text("剧情指南") }, minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = influence, onValueChange = { influence = it }, label = { Text("主人希望下一章发生什么（可空）") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { store.setGuide(selected, guide); message = "剧情指南已保存" }, modifier = Modifier.weight(1f)) { Text("保存指南") }
                    Button(
                        enabled = !generating && studyState.inventory.theaterFragments >= StarWishRules.THEATER_FRAGMENTS_PER_CHAPTER && chapters.size < StarWishRules.MAX_CHAPTERS_PER_THEATER,
                        onClick = {
                            generating = true
                            message = ""
                            scope.launch {
                                val history = chapters.joinToString("\n\n") { chapter -> "${chapter.title}\n${chapter.content}" }
                                val chapterNumber = chapters.size + 1
                                LuluAiServices.gateway.generate(
                                    characterId = studyState.profile.selectedCharacterId,
                                    facts = buildString {
                                        appendLine("剧场：${seed.title}")
                                        appendLine("核心设定：$guide")
                                        if (history.isNotBlank()) { appendLine("已发生章节："); appendLine(history) }
                                        if (influence.isNotBlank()) appendLine("主人对下一章的影响：$influence")
                                    },
                                    instruction = "续写第 $chapterNumber 章完整中文故事，正文约 1800-3000 字。保持人物、时间线和因果连续；主人影响必须自然进入剧情；不要写提纲、解释或系统提示。",
                                    source = "心愿馆",
                                    title = "${seed.title} · 第${chapterNumber}章",
                                    temperature = 0.9,
                                    maxTokens = 4200,
                                ).onSuccess { reply ->
                                    val consume = studyStore.redeemEntertainment(StudyEntertainmentKind.Theater)
                                    if (consume.contains("解锁小剧场")) {
                                        store.addChapter(StarWishTheaterChapter(theater = selected, chapter = chapterNumber, title = "第 $chapterNumber 章", content = reply.text, userInfluence = influence.trim()))
                                        message = "第 $chapterNumber 章已生成，剧场碎片 -1"
                                        influence = ""
                                    } else {
                                        message = "章节已生成但没有保存：$consume。目标考研存档的精确碎片消费接口仍需迁移。"
                                    }
                                }.onFailure { error -> message = error.message ?: "章节生成失败" }
                                generating = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        if (generating) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.AutoStories, null)
                        Spacer(Modifier.width(5.dp))
                        Text(if (generating) "续写中" else "生成下一章")
                    }
                }
                if (chapters.size >= StarWishRules.MAX_CHAPTERS_PER_THEATER) Text("本剧场已达到 20 章上限", color = StudyDesign.muted)
                if (message.isNotBlank()) StudyMessage(message, error = message.contains("失败") || message.contains("没有保存"))
            }
        }
        if (chapters.isEmpty()) {
            item { StudyCard { Text("还没有章节，消耗 1 枚剧场碎片生成第一章。", color = StudyDesign.muted) } }
        } else {
            items(chapters, key = { chapter -> chapter.id }) { chapter ->
                StudyCard {
                    Text(chapter.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (chapter.userInfluence.isNotBlank()) Text("主人影响：${chapter.userInfluence}", color = StudyDesign.muted, fontSize = 12.sp)
                    Text(chapter.content, lineHeight = 23.sp)
                }
            }
            item {
                TextButton(onClick = { store.deleteTheater(selected) }) {
                    Icon(Icons.Outlined.DeleteSweep, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(5.dp))
                    Text("清空《$selected》章节", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun StarWishVideoPanel(
    state: StarWishState,
    studyState: StudyState,
    store: StarWishStore,
    studyStore: PostgraduateExamStore,
    context: Context,
) {
    var message by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val title = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.ifBlank { "星愿视频" } ?: "星愿视频"
            store.addVideo(StarWishVideoItem(title = title, uri = uri.toString()))
            message = "已加入视频柜，使用 1 枚视频碎片后解锁"
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StudyCard {
                Text("视频柜", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("视频碎片：${studyState.inventory.videoCards}", color = StudyDesign.muted)
                Button(onClick = { launcher.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.VideoLibrary, null)
                    Spacer(Modifier.width(7.dp))
                    Text("导入本机视频")
                }
                if (message.isNotBlank()) StudyMessage(message, error = message.contains("不足"))
            }
        }
        if (state.videos.isEmpty()) {
            item { StudyCard { Text("还没有视频。先从本机导入，再使用视频碎片解锁。", color = StudyDesign.muted) } }
        } else {
            items(state.videos, key = { item -> item.id }) { video ->
                StudyCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (video.unlocked) Icons.Outlined.PlayCircle else Icons.Outlined.Lock, null, modifier = Modifier.size(34.dp), tint = StudyDesign.muted)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(video.title, fontWeight = FontWeight.Bold)
                            Text(if (video.unlocked) "已解锁" else "需要 1 枚视频碎片", color = StudyDesign.muted, fontSize = 12.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (video.unlocked) {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(video.uri), "video/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
                                    }.onFailure { message = "找不到可以播放此视频的应用" }
                                } else {
                                    val result = studyStore.redeemEntertainment(StudyEntertainmentKind.Video)
                                    if (result.contains("解锁视频")) { store.unlockVideo(video.id); message = "已解锁：${video.title}" }
                                    else message = result
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (video.unlocked) "播放" else "解锁") }
                        OutlinedButton(onClick = { store.deleteVideo(video.id) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.DeleteOutline, null)
                            Spacer(Modifier.width(5.dp))
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}

private val StarWishDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun <T> JSONArray?.decodeObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            runCatching { transform(item) }.getOrNull()?.let { decoded -> add(decoded) }
        }
    }
}
